package prj.salmon.toropassicsystem.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import fi.iki.elonen.NanoHTTPD;
import prj.salmon.toropassicsystem.DatabaseManager;
import prj.salmon.toropassicsystem.types.PaymentHistory;

import java.io.IOException;
import java.sql.SQLException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Wallet などが使う HTTP API。
 *
 * パスワードの確認はメインスレッドに入る前に HTTP のスレッドで行う。
 * プレイヤーのオンライン状態やインベントリを見るのは認証に成功した後だけなので、
 * 認証していない要求でメインスレッドにタスクが積まれることはなく、在席状況も応答から分からない。
 */
public class ApiServer extends NanoHTTPD {
    private static final Set<String> POST_ROUTES = Set.of("/api/webcharge", "/api/writecard", "/api/getcardinfo", "/api/delcardinfo", "/api/pay");
    private static final String BALANCE_ROUTE = "/api/balance/";
    private static final String HISTORY_ROUTE = "/api/history/";
    private static final String FULL_HISTORY_ROUTE = "/api/fullhistory/";
    // NanoHTTPD のヘッダー名は小文字になっている
    private static final String PASSWORD_HEADER = "x-toropass-password";

    private static final int MAX_PLAYER_NAME_LENGTH = 64;
    private static final int MAX_DESCRIPTION_LENGTH = 256;
    private static final int RECENT_HISTORY_LIMIT = 100;
    private static final int FULL_HISTORY_PAGE_LIMIT = 500;
    private static final int MAX_DISCARDED_BODY_BYTES = 64 * 1024;

    /**
     * API から使うゲーム側の処理。Bukkit への依存をここに閉じ込める。
     * callSync 以外のメソッドは callSync の中 (メインスレッド) で呼ぶ。
     */
    public interface GameBridge {
        <T> Future<T> callSync(Callable<T> task);

        boolean isOnline(UUID uuid);

        boolean hasICCard(UUID uuid);

        // 定期券コードを書き込む。拒否した場合は false (理由はプレイヤーのチャットに表示する)
        boolean writeCard(UUID uuid, String cardData);

        void notifyPlayer(UUID uuid, String message);
    }

    private final DatabaseManager db;
    private final ApiSecurity security;
    private final GameBridge game;
    private final Logger logger;
    private final ObjectMapper mapper = new ObjectMapper();

    public ApiServer(int port, ApiSecurity.Settings settings, ApiSecurity security, DatabaseManager db, GameBridge game, Logger logger) throws IOException {
        super(settings.bindAddress == null || settings.bindAddress.isBlank() ? null : settings.bindAddress.trim(), port);
        this.security = security;
        this.db = db;
        this.game = game;
        this.logger = logger;
        setAsyncRunner(new BoundedAsyncRunner(settings.maxWorkers, settings.maxQueuedConnections));
        start(SOCKET_READ_TIMEOUT, true);
    }

    @Override
    public Response serve(IHTTPSession session) {
        Response response;
        try {
            response = route(session);
        } catch (Exception e) {
            logger.warning("HTTPサーバー例外: " + e.getClass().getSimpleName() + ": " + sanitizeForLog(e.getMessage()));
            response = jsonError(Response.Status.INTERNAL_ERROR, "Internal server error");
            response.closeConnection(true);
        }
        if (hasUnreadBody(session)) closeWithoutReadingBody(session, response);
        // パスワードで保護された内容を途中のキャッシュに残さない
        response.addHeader("Cache-Control", "no-store");
        response.addHeader("X-Content-Type-Options", "nosniff");
        response.addHeader("Vary", "Origin");
        String origin = session.getHeaders().get("origin");
        if (security.isCorsOriginAllowed(origin)) response.addHeader("Access-Control-Allow-Origin", origin);
        return response;
    }

    // NanoHTTPD は圧縮するとき本文をチャンク形式で書く。204 には本文を付けられないので圧縮しない
    @Override
    protected boolean useGzipWhenAccepted(Response response) {
        return response.getStatus() != Response.Status.NO_CONTENT && super.useGzipWhenAccepted(response);
    }

    private Response route(IHTTPSession session) throws Exception {
        String uri = session.getUri();
        Method method = session.getMethod();
        if (method == Method.OPTIONS) return preflight();
        if (uri.equals("/")) return json(Response.Status.OK, "{\"status\": \"OK\"}");

        String clientIp = security.clientIp(session.getHeaders(), session.getRemoteIpAddress());
        if (uri.startsWith(BALANCE_ROUTE) || uri.startsWith(HISTORY_ROUTE) || uri.startsWith(FULL_HISTORY_ROUTE)) {
            if (method != Method.GET && method != Method.HEAD) return jsonError(Response.Status.METHOD_NOT_ALLOWED, "Method not allowed");
            return handleRead(session, uri, clientIp);
        }
        if (method == Method.POST && POST_ROUTES.contains(uri)) return handlePost(session, uri, clientIp);

        return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "404 Not Found (URI Error)");
    }

    // POST API 以外はボディを読まない。POST API も Content-Length のあるボディだけを読む
    private static boolean hasUnreadBody(IHTTPSession session) {
        Map<String, String> headers = session.getHeaders();
        if (headers.containsKey("transfer-encoding")) return true;
        String length = headers.get("content-length");
        if (length == null || length.trim().equals("0")) return false;
        return !(session.getMethod() == Method.POST && POST_ROUTES.contains(session.getUri()));
    }

    // 読まなかったボディが次のリクエストとして解釈されないよう、応答の後で接続を閉じる
    private static Response closeWithoutReadingBody(IHTTPSession session, Response response) {
        RequestBodies.discard(session.getInputStream(), session.getHeaders().get("content-length"), MAX_DISCARDED_BODY_BYTES);
        response.closeConnection(true);
        return response;
    }

    // ブラウザの事前確認には認証なしで答える。Access-Control-Allow-Origin は serve で許可したオリジンにだけ付ける
    private static Response preflight() {
        Response r = newFixedLengthResponse(Response.Status.NO_CONTENT, "text/plain", "");
        r.addHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        r.addHeader("Access-Control-Allow-Headers", "Content-Type, X-TOROpass-Password");
        r.addHeader("Access-Control-Max-Age", "600");
        return r;
    }

    private Response handleRead(IHTTPSession session, String uri, String clientIp) {
        long wait = security.tryAcquireRead(clientIp);
        if (wait > 0) return tooManyRequests("Too many requests", wait);

        boolean balanceRoute = uri.startsWith(BALANCE_ROUTE);
        String playerName = uri.substring(uri.indexOf('/', 5) + 1);
        if (playerName.length() > MAX_PLAYER_NAME_LENGTH) return jsonError(Response.Status.BAD_REQUEST, "Invalid player name");

        try {
            DatabaseManager.UserData user;
            if (security.isRequireAuthForRead()) {
                Response locked = checkAuthLock(playerName, clientIp);
                if (locked != null) return locked;
                String password = session.getHeaders().get(PASSWORD_HEADER);
                if (password == null) return jsonError(Response.Status.UNAUTHORIZED, "Password required");
                Auth auth = authenticate(playerName, password, clientIp);
                if (auth.denied() != null) return auth.denied();
                user = auth.user();
            } else {
                UUID uuid = db.getUUIDByPlayerName(playerName);
                user = uuid == null ? null : db.getUser(uuid);
                if (user == null) return jsonError(Response.Status.NOT_FOUND, "Player Not Found");
            }

            if (balanceRoute) return json(Response.Status.OK, "{\"balance\":" + user.balance + "}");

            List<PaymentHistory> history;
            if (uri.startsWith(HISTORY_ROUTE)) {
                history = db.getHistory(user.uuid, RECENT_HISTORY_LIMIT);
            } else {
                // 全履歴はページに分けて返す (?limit=件数&offset=先頭から何件飛ばすか)
                Integer limit = pageParam(session, "limit", FULL_HISTORY_PAGE_LIMIT);
                Integer offset = pageParam(session, "offset", 0);
                if (limit == null || offset == null || limit < 1) return jsonError(Response.Status.BAD_REQUEST, "Invalid paging parameter");
                history = db.getHistory(user.uuid, Math.min(limit, FULL_HISTORY_PAGE_LIMIT), offset);
            }
            return json(Response.Status.OK, mapper.writeValueAsString(history));
        } catch (Exception e) {
            logApiError(uri, e);
            return jsonError(Response.Status.INTERNAL_ERROR, "DB Error");
        }
    }

    // 指定がなければ defaultValue、負の値や数値でないものは null
    private static Integer pageParam(IHTTPSession session, String name, int defaultValue) {
        List<String> values = session.getParameters().get(name);
        if (values == null || values.isEmpty()) return defaultValue;
        try {
            int value = Integer.parseInt(values.get(0));
            return value < 0 ? null : value;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Response handlePost(IHTTPSession session, String uri, String clientIp) throws IOException {
        long wait = security.tryAcquireWrite(clientIp);
        if (wait > 0) return closeWithoutReadingBody(session, tooManyRequests("Too many requests", wait));

        Map<String, Object> request;
        try {
            request = readJsonBody(session);
        } catch (RequestBodies.BodyException e) {
            Response.IStatus status = e.getStatus() == 413 ? Response.Status.PAYLOAD_TOO_LARGE : Response.Status.BAD_REQUEST;
            return closeWithoutReadingBody(session, jsonError(status, e.getMessage()));
        }
        if (request == null) return jsonError(Response.Status.BAD_REQUEST, "Invalid JSON");

        String playerName = stringField(request, "playername");
        String password = stringField(request, "password");
        if (playerName == null || password == null) return missingFields();
        if (playerName.length() > MAX_PLAYER_NAME_LENGTH) return jsonError(Response.Status.BAD_REQUEST, "Invalid player name");

        try {
            Response locked = checkAuthLock(playerName, clientIp);
            if (locked != null) return locked;
            Auth auth = authenticate(playerName, password, clientIp);
            if (auth.denied() != null) return auth.denied();

            return switch (uri) {
                case "/api/webcharge" -> webCharge(request, auth.user());
                case "/api/writecard" -> writeCard(request, auth.user(), uri);
                case "/api/getcardinfo" -> getCardInfo(auth.user(), uri);
                case "/api/delcardinfo" -> deleteCardInfo(auth.user(), uri);
                case "/api/pay" -> pay(request, auth.user());
                default -> jsonError(Response.Status.NOT_FOUND, "Not found");
            };
        } catch (Exception e) {
            logApiError(uri, e);
            return jsonError(Response.Status.INTERNAL_ERROR, "Internal server error");
        }
    }

    private Response webCharge(Map<String, Object> request, DatabaseManager.UserData user) throws SQLException {
        Object chargeBalanceObj = request.get("chargebalance");
        if (chargeBalanceObj == null) return missingFields();
        int chargeBalance;
        try {
            chargeBalance = Integer.parseInt(chargeBalanceObj.toString());
        } catch (NumberFormatException e) {
            return jsonError(Response.Status.BAD_REQUEST, "Invalid charge amount");
        }
        // Wallet はログインの確認に chargebalance: 0 を送り、この応答で認証の成功を判定する
        if (chargeBalance <= 0) return jsonError(Response.Status.BAD_REQUEST, "Invalid charge amount");
        if (!security.isWebChargeEnabled()) return jsonError(Response.Status.FORBIDDEN, "Web charge is disabled");

        PaymentHistory h = PaymentHistory.build("Special::webcharge", "", chargeBalance, 0, System.currentTimeMillis() / 1000L, "Walletからチャージ");
        OptionalInt balance = db.credit(user.uuid, chargeBalance, DatabaseManager.MAX_BALANCE, h);
        if (balance.isEmpty()) return jsonError(Response.Status.BAD_REQUEST, "Maximum balance is 20000 Tropo");
        return okBalance(balance.getAsInt());
    }

    private Response writeCard(Map<String, Object> request, DatabaseManager.UserData user, String uri) throws Exception {
        String cardData = stringField(request, "carddata");
        if (cardData == null) return missingFields();
        // プレイヤー・インベントリの操作はメインスレッドで行う
        return callSync(uri, () -> {
            if (!game.isOnline(user.uuid)) return jsonError(Response.Status.BAD_REQUEST, "Player must be online to write card");
            if (!game.hasICCard(user.uuid)) return noICCard();
            if (!game.writeCard(user.uuid, cardData)) return jsonError(Response.Status.BAD_REQUEST, "Card data rejected");
            return okBalance(db.getUser(user.uuid).balance);
        });
    }

    private Response getCardInfo(DatabaseManager.UserData user, String uri) throws Exception {
        // プレイヤー・インベントリの参照はメインスレッドで行う
        return callSync(uri, () -> {
            if (!game.isOnline(user.uuid)) return jsonError(Response.Status.BAD_REQUEST, "Player must be online");
            if (!game.hasICCard(user.uuid)) return noICCard();
            Map<String, Object> cardInfo = cardInfoOf(db.getUser(user.uuid));
            if (cardInfo.isEmpty()) return json(Response.Status.OK, "{\"status\": \"OK\", \"message\": \"No ticket information registered\"}");
            return json(Response.Status.OK, mapper.writeValueAsString(Map.of("status", "OK", "cardInfo", cardInfo)));
        });
    }

    private Response deleteCardInfo(DatabaseManager.UserData user, String uri) throws Exception {
        // プレイヤー・インベントリの操作はメインスレッドで行う
        return callSync(uri, () -> {
            if (!game.isOnline(user.uuid)) return jsonError(Response.Status.BAD_REQUEST, "Player must be online");
            if (!game.hasICCard(user.uuid)) return noICCard();
            if (db.getUser(user.uuid).ticketType == null) {
                return json(Response.Status.OK, "{\"status\": \"OK\", \"message\": \"No ticket information to delete\"}");
            }
            db.clearTicket(user.uuid);
            game.notifyPlayer(user.uuid, "Webから定期券情報を削除しました。");
            return json(Response.Status.OK, "{\"status\": \"OK\", \"message\": \"Ticket information deleted\"}");
        });
    }

    private Response pay(Map<String, Object> request, DatabaseManager.UserData user) throws SQLException {
        Object amountObj = request.get("amount");
        if (amountObj == null) return missingFields();
        int amount;
        try {
            amount = Integer.parseInt(amountObj.toString());
        } catch (NumberFormatException e) {
            return jsonError(Response.Status.BAD_REQUEST, "Invalid amount");
        }
        Object descriptionObj = request.get("description");
        if (descriptionObj != null && !(descriptionObj instanceof String)) return jsonError(Response.Status.BAD_REQUEST, "Invalid description");
        String description = descriptionObj == null ? "" : (String) descriptionObj;
        if (description.length() > MAX_DESCRIPTION_LENGTH) return jsonError(Response.Status.BAD_REQUEST, "Description too long");

        if (amount <= 0) return insufficientBalance();
        PaymentHistory h = PaymentHistory.build("web::pay", "", -amount, 0, System.currentTimeMillis() / 1000L, description);
        OptionalInt balance = db.debit(user.uuid, amount, h);
        if (balance.isEmpty()) return insufficientBalance();
        return okBalance(balance.getAsInt());
    }

    private static Map<String, Object> cardInfoOf(DatabaseManager.UserData user) {
        Map<String, Object> cardInfo = new HashMap<>();
        if (user.ticketType == null) return cardInfo;
        cardInfo.put("ticketType", user.ticketType);
        if (user.companyCode != null) cardInfo.put("companyCode", String.format("%02d", user.companyCode));
        if (user.purchaseAmount != null) cardInfo.put("purchaseAmount", user.purchaseAmount);
        if (user.expiryDate != null) cardInfo.put("expiryDate", new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date(user.expiryDate)));
        if (user.checkDigit != null) cardInfo.put("checkDigit", user.checkDigit);
        if (user.routeStart != null) cardInfo.put("routeStart", user.routeStart);
        if (user.routeEnd != null) cardInfo.put("routeEnd", user.routeEnd);
        return cardInfo;
    }

    // ボディをサイズ上限付きで読み込み、JSONオブジェクトとして解析する (JSONオブジェクトでなければ null)
    @SuppressWarnings("unchecked")
    private Map<String, Object> readJsonBody(IHTTPSession session) throws IOException {
        String body = RequestBodies.read(session.getInputStream(), session.getHeaders().get("content-length"), security.getMaxBodyBytes());
        try {
            Object parsed = mapper.readValue(body, Object.class);
            return parsed instanceof Map ? (Map<String, Object>) parsed : null;
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    private static String stringField(Map<String, Object> request, String key) {
        return request.get(key) instanceof String value ? value : null;
    }

    private record Auth(DatabaseManager.UserData user, Response denied) {}

    // パスワードを確認する。存在しないプレイヤーも、パスワードの誤りや期限切れと同じ応答にする
    private Auth authenticate(String playerName, String password, String clientIp) throws SQLException {
        long delay = security.authDelayMillis(playerName);
        if (delay > 0) {
            try {
                Thread.sleep(delay);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        UUID uuid = db.getUUIDByPlayerName(playerName);
        DatabaseManager.UserData user = uuid == null ? null : db.getUser(uuid);
        if (!passwordAccepted(user, password)) {
            if (security.recordFailure(playerName, clientIp, password)) {
                logger.warning("Web API: 認証失敗が続いたため一時的にロックしました (player=" + sanitizeForLog(playerName) + ", ip=" + sanitizeForLog(clientIp) + ")");
            }
            return new Auth(null, jsonError(Response.Status.UNAUTHORIZED, "Invalid password"));
        }
        security.recordSuccess(playerName, clientIp);
        return new Auth(user, null);
    }

    private static boolean passwordAccepted(DatabaseManager.UserData user, String password) {
        if (user == null || user.webPasswordHash == null) return false;
        if (user.webPasswordExpiresAt != null && System.currentTimeMillis() / 1000L >= user.webPasswordExpiresAt) return false;
        return WebPasswords.matches(user.webPasswordHash, password);
    }

    // 認証失敗が続いてロック中のプレイヤー名とIPの組、またはIPからのリクエストを拒否する
    private Response checkAuthLock(String playerName, String clientIp) {
        long remaining = security.lockRemainingMillis(playerName, clientIp);
        if (remaining <= 0) return null;
        return tooManyRequests("Too many failed attempts. Try again later.", remaining);
    }

    // Bukkit API を使う処理をメインスレッドで実行し、結果を待つ
    private Response callSync(String uri, Callable<Response> task) throws Exception {
        Future<Response> future = game.callSync(task);
        try {
            return future.get(security.getSyncTimeoutMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            if (!future.cancel(false)) {
                // 既に実行が始まっている場合は、処理済みなのに失敗を返さないよう完了を待つ
                return future.get(security.getSyncTimeoutMillis(), TimeUnit.MILLISECONDS);
            }
            logger.warning("Web API: メインスレッドでの処理がタイムアウトしました (" + sanitizeForLog(uri) + ")");
            return jsonError(Response.Status.SERVICE_UNAVAILABLE, "Server busy. Try again later.");
        } catch (ExecutionException e) {
            throw e.getCause() instanceof Exception cause ? cause : e;
        }
    }

    private void logApiError(String uri, Exception e) {
        logger.log(Level.WARNING, "Web API エラー (" + sanitizeForLog(uri) + ")", e);
    }

    private static Response json(Response.IStatus status, String body) {
        return newFixedLengthResponse(status, "application/json", body);
    }

    // message には固定の文字列のみを渡すこと
    private static Response jsonError(Response.IStatus status, String message) {
        return json(status, "{\"status\": \"ERROR\", \"message\": \"" + message + "\"}");
    }

    private static Response okBalance(int balance) {
        return json(Response.Status.OK, "{\"status\": \"OK\", \"balance\": " + balance + "}");
    }

    private static Response missingFields() {
        return jsonError(Response.Status.BAD_REQUEST, "Missing required fields");
    }

    private static Response noICCard() {
        return jsonError(Response.Status.BAD_REQUEST, "No valid IC card found in player's inventory");
    }

    private static Response insufficientBalance() {
        return jsonError(Response.Status.BAD_REQUEST, "Insufficient balance or invalid amount");
    }

    private static Response tooManyRequests(String message, long retryAfterMillis) {
        Response r = jsonError(Response.Status.TOO_MANY_REQUESTS, message);
        r.addHeader("Retry-After", String.valueOf(Math.max(1, (retryAfterMillis + 999) / 1000)));
        return r;
    }

    private static String sanitizeForLog(String value) {
        if (value == null) return "null";
        String s = value.replaceAll("\\p{Cntrl}", "?");
        return s.length() > 64 ? s.substring(0, 64) + "..." : s;
    }
}
