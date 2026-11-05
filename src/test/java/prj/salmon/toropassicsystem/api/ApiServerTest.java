package prj.salmon.toropassicsystem.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import prj.salmon.toropassicsystem.DatabaseManager;
import prj.salmon.toropassicsystem.types.PaymentHistory;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.logging.Level;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * HTTP API を実際のソケットと SQLite で確認するテスト。Bukkit 側の処理は FakeGame で置き換える。
 */
class ApiServerTest {
    private static final String PASSWORD_HEADER = "X-TOROpass-Password";
    private static final String CLIENT_IP = "203.0.113.10";
    private static final String OTHER_IP = "203.0.113.20";

    @TempDir
    Path tempDir;

    private final UUID steve = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final FakeGame game = new FakeGame();

    private DatabaseManager db;
    private ApiServer server;
    private String token;

    @BeforeEach
    void setUp() throws Exception {
        db = new DatabaseManager(tempDir.resolve("toropass.db") + "?synchronous=OFF");
        db.connect(0);
        db.ensureUser(steve, "Steve");
        token = WebPasswords.generateToken();
        db.updateWebPassword(steve, WebPasswords.hashToken(token), null);
        db.setBalance(steve, 1000, PaymentHistory.build("Special::balanceAdjustment", "", 1000, 0, 1, ""));
    }

    @AfterEach
    void tearDown() throws SQLException {
        if (server != null) server.stop();
        game.shutdown();
        db.close();
    }

    private void startServer(ApiSecurity.Settings settings) throws IOException {
        settings.bindAddress = "127.0.0.1";
        Logger logger = Logger.getLogger("ApiServerTest");
        logger.setLevel(Level.SEVERE);
        server = new ApiServer(0, settings, new ApiSecurity(settings), db, game, logger);
    }

    private void startServer() throws IOException {
        startServer(new ApiSecurity.Settings());
    }

    private HttpRequest.Builder request(String path, String clientIp) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.getListeningPort() + path))
                .timeout(Duration.ofSeconds(15))
                .header("CF-Connecting-IP", clientIp);
    }

    private HttpResponse<String> send(HttpRequest request) throws Exception {
        return client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private HttpResponse<String> get(String path, String password) throws Exception {
        HttpRequest.Builder builder = request(path, CLIENT_IP).GET();
        if (password != null) builder.header(PASSWORD_HEADER, password);
        return send(builder.build());
    }

    private HttpResponse<String> post(String path, String json, String clientIp) throws Exception {
        return send(request(path, clientIp).header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(json)).build());
    }

    private HttpResponse<String> post(String path, String json) throws Exception {
        return post(path, json, CLIENT_IP);
    }

    private String credentials(String password) {
        return "\"playername\":\"Steve\",\"password\":\"" + password + "\"";
    }

    private JsonNode json(HttpResponse<String> response) throws IOException {
        return mapper.readTree(response.body());
    }

    @Test
    void readsRequireThePasswordByDefault() throws Exception {
        startServer();
        assertEquals(401, get("/api/balance/Steve", null).statusCode());
        assertEquals(401, get("/api/history/Steve", null).statusCode());
        assertEquals(401, get("/api/fullhistory/Steve", null).statusCode());
        assertEquals(401, get("/api/balance/Steve", "wrong").statusCode());

        HttpResponse<String> balance = get("/api/balance/Steve", token);
        assertEquals(200, balance.statusCode());
        assertEquals(1000, json(balance).get("balance").asInt());

        HttpResponse<String> history = get("/api/history/Steve", token);
        assertEquals(200, history.statusCode());
        assertEquals(1000, json(history).get(0).get("balance").asInt());
    }

    @Test
    void passwordInTheQueryStringIsNotAccepted() throws Exception {
        startServer();
        String encoded = URLEncoder.encode(token, StandardCharsets.UTF_8);
        assertEquals(401, get("/api/balance/Steve?password=" + encoded, null).statusCode());
    }

    @Test
    void unknownPlayerLooksLikeAWrongPassword() throws Exception {
        startServer();
        HttpResponse<String> unknown = get("/api/balance/Nobody", "wrong");
        HttpResponse<String> wrong = get("/api/balance/Steve", "wrong");
        assertEquals(wrong.statusCode(), unknown.statusCode());
        assertEquals(wrong.body(), unknown.body());

        HttpResponse<String> unknownPost = post("/api/getcardinfo", "{\"playername\":\"Nobody\",\"password\":\"wrong\"}");
        HttpResponse<String> wrongPost = post("/api/getcardinfo", "{" + credentials("wrong") + "}");
        assertEquals(wrongPost.statusCode(), unknownPost.statusCode());
        assertEquals(wrongPost.body(), unknownPost.body());
    }

    @Test
    void expiredAndRevokedPasswordsAreRefused() throws Exception {
        startServer();
        db.updateWebPassword(steve, WebPasswords.hashToken(token), System.currentTimeMillis() / 1000L - 1);
        assertEquals(401, get("/api/balance/Steve", token).statusCode());
        db.updateWebPassword(steve, WebPasswords.hashToken(token), System.currentTimeMillis() / 1000L + 3600);
        assertEquals(200, get("/api/balance/Steve", token).statusCode());
        db.updateWebPassword(steve, null, null);
        assertEquals(401, get("/api/balance/Steve", token).statusCode());
    }

    @Test
    void readAuthCanBeDisabledByConfig() throws Exception {
        ApiSecurity.Settings settings = new ApiSecurity.Settings();
        settings.requireAuthForRead = false;
        startServer(settings);
        assertEquals(200, get("/api/balance/Steve", null).statusCode());
        assertEquals(404, get("/api/balance/Nobody", null).statusCode());
    }

    @Test
    void responsesAreNotCacheable() throws Exception {
        startServer();
        HttpResponse<String> balance = get("/api/balance/Steve", token);
        assertEquals("no-store", balance.headers().firstValue("Cache-Control").orElse(""));
        assertEquals("nosniff", balance.headers().firstValue("X-Content-Type-Options").orElse(""));
    }

    @Test
    void preflightDoesNotRequireAuthAndAllowsThePasswordHeader() throws Exception {
        startServer();
        HttpResponse<String> response = send(request("/api/history/Steve", CLIENT_IP)
                .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
                .header("Origin", "https://toro-server.github.io")
                .header("Access-Control-Request-Headers", "x-toropass-password")
                .build());
        assertEquals(204, response.statusCode());
        assertTrue(response.headers().firstValue("Access-Control-Allow-Headers").orElse("").contains("X-TOROpass-Password"));
        // 既定ではオリジンを許可しない (CORS は Wallet の経路のプロキシが担当している)
        assertTrue(response.headers().firstValue("Access-Control-Allow-Origin").isEmpty());
    }

    @Test
    void browserRequestsWithGzipGetValidResponses() throws Exception {
        startServer();
        // ブラウザは Accept-Encoding: gzip を付ける。204 に本文が付くと接続が壊れる
        HttpResponse<String> preflight = send(request("/api/history/Steve", CLIENT_IP)
                .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
                .header("Accept-Encoding", "gzip")
                .build());
        assertEquals(204, preflight.statusCode());
        assertTrue(preflight.headers().firstValue("Content-Encoding").isEmpty());
        assertTrue(preflight.headers().firstValue("Transfer-Encoding").isEmpty());
        assertEquals("", preflight.body());

        HttpResponse<byte[]> balance = client.send(request("/api/balance/Steve", CLIENT_IP).GET()
                .header(PASSWORD_HEADER, token).header("Accept-Encoding", "gzip").build(), HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(200, balance.statusCode());
        assertEquals("gzip", balance.headers().firstValue("Content-Encoding").orElse(""));
        try (InputStream gunzip = new java.util.zip.GZIPInputStream(new java.io.ByteArrayInputStream(balance.body()))) {
            assertEquals(1000, mapper.readTree(gunzip).get("balance").asInt());
        }
    }

    @Test
    void corsIsLimitedToConfiguredOrigins() throws Exception {
        ApiSecurity.Settings settings = new ApiSecurity.Settings();
        settings.corsAllowedOrigins = List.of("https://toro-server.github.io");
        startServer(settings);
        HttpResponse<String> allowed = send(request("/api/balance/Steve", CLIENT_IP).GET()
                .header("Origin", "https://toro-server.github.io").header(PASSWORD_HEADER, token).build());
        assertEquals("https://toro-server.github.io", allowed.headers().firstValue("Access-Control-Allow-Origin").orElse(""));
        HttpResponse<String> other = send(request("/api/balance/Steve", CLIENT_IP).GET()
                .header("Origin", "https://example.com").header(PASSWORD_HEADER, token).build());
        assertTrue(other.headers().firstValue("Access-Control-Allow-Origin").isEmpty());
    }

    @Test
    void walletLoginProbeStillWorks() throws Exception {
        startServer();
        // Wallet はログインの確認に金額 0 のチャージを送り、この応答を成功として扱う
        HttpResponse<String> probe = post("/api/webcharge", "{" + credentials(token) + ",\"chargebalance\":0}");
        assertEquals(400, probe.statusCode());
        assertEquals("Invalid charge amount", json(probe).get("message").asText());

        HttpResponse<String> wrong = post("/api/webcharge", "{" + credentials("wrong") + ",\"chargebalance\":0}");
        assertEquals(401, wrong.statusCode());
    }

    @Test
    void webChargeRecordsHistory() throws Exception {
        startServer();
        HttpResponse<String> response = post("/api/webcharge", "{" + credentials(token) + ",\"chargebalance\":500}");
        assertEquals(200, response.statusCode());
        assertEquals(1500, json(response).get("balance").asInt());
        PaymentHistory latest = db.getHistory(steve, 1).get(0);
        assertEquals("Special::webcharge", latest.from);
        assertEquals(500, latest.amount);
        assertEquals(1500, latest.balance);
    }

    @Test
    void webChargeRespectsTheMaximum() throws Exception {
        startServer();
        HttpResponse<String> tooMuch = post("/api/webcharge", "{" + credentials(token) + ",\"chargebalance\":19001}");
        assertEquals(400, tooMuch.statusCode());
        assertEquals("Maximum balance is 20000 Tropo", json(tooMuch).get("message").asText());
        HttpResponse<String> huge = post("/api/webcharge", "{" + credentials(token) + ",\"chargebalance\":2147483647}");
        assertEquals(400, huge.statusCode());
        assertEquals(1000, db.getUser(steve).balance);
    }

    @Test
    void webChargeCanBeDisabled() throws Exception {
        ApiSecurity.Settings settings = new ApiSecurity.Settings();
        settings.webChargeEnabled = false;
        startServer(settings);
        assertEquals(403, post("/api/webcharge", "{" + credentials(token) + ",\"chargebalance\":100}").statusCode());
        assertEquals(1000, db.getUser(steve).balance);
        // ログインの確認は使える
        assertEquals("Invalid charge amount", json(post("/api/webcharge", "{" + credentials(token) + ",\"chargebalance\":0}")).get("message").asText());
    }

    @Test
    void inputOfTheWrongTypeGets400() throws Exception {
        startServer();
        assertEquals(400, post("/api/webcharge", "{\"playername\":123,\"password\":\"x\",\"chargebalance\":1}").statusCode());
        assertEquals(400, post("/api/getcardinfo", "{\"playername\":[],\"password\":{}}").statusCode());
        assertEquals(400, post("/api/writecard", "{" + credentials(token) + ",\"carddata\":12}").statusCode());
        assertEquals(400, post("/api/pay", "{" + credentials(token) + ",\"amount\":1,\"description\":{}}").statusCode());
        assertEquals(400, post("/api/pay", "{" + credentials(token) + ",\"amount\":\"abc\"}").statusCode());
        assertEquals(400, post("/api/webcharge", "{" + credentials(token) + ",\"chargebalance\":1.5}").statusCode());
        assertEquals(400, post("/api/webcharge", "[1,2,3]").statusCode());
        assertEquals(400, post("/api/webcharge", "not json").statusCode());
    }

    @Test
    void deeplyNestedJsonIsRejected() throws Exception {
        startServer();
        assertEquals(400, post("/api/webcharge", "[".repeat(8000)).statusCode());
    }

    @Test
    void oversizedBodyGets413() throws Exception {
        startServer();
        assertEquals(413, post("/api/webcharge", "{\"playername\":\"" + "x".repeat(20000) + "\"}").statusCode());
    }

    @Test
    void longPlayerNamesAreRejected() throws Exception {
        startServer();
        assertEquals(400, post("/api/getcardinfo", "{\"playername\":\"" + "x".repeat(100) + "\",\"password\":\"x\"}").statusCode());
        assertEquals(400, get("/api/balance/" + "x".repeat(100), "x").statusCode());
    }

    @Test
    void unauthenticatedRequestsNeverReachTheMainThread() throws Exception {
        startServer();
        for (String route : List.of("/api/getcardinfo", "/api/delcardinfo")) {
            assertEquals(401, post(route, "{" + credentials("wrong") + "}").statusCode());
        }
        assertEquals(401, post("/api/writecard", "{" + credentials("wrong") + ",\"carddata\":\"x\"}").statusCode());
        assertEquals(0, game.syncCalls.size());
        // 在席状況は認証の後でしか分からない
        game.online = false;
        assertEquals(401, post("/api/getcardinfo", "{" + credentials("wrong") + "}").statusCode());
        assertEquals(400, post("/api/getcardinfo", "{" + credentials(token) + "}").statusCode());
    }

    @Test
    void cardInfoRoutes() throws Exception {
        startServer();
        HttpResponse<String> empty = post("/api/getcardinfo", "{" + credentials(token) + "}");
        assertEquals(200, empty.statusCode());
        assertEquals("No ticket information registered", json(empty).get("message").asText());

        db.purchaseTicket(steve, new DatabaseManager.TicketData(2, 5, 100, 1798729199999L, 7, "StationA", "StationB"),
                PaymentHistory.build("Special::writecard", "", -100, 0, 2, ""));
        HttpResponse<String> info = post("/api/getcardinfo", "{" + credentials(token) + "}");
        JsonNode card = json(info).get("cardInfo");
        assertEquals(2, card.get("ticketType").asInt());
        assertEquals("05", card.get("companyCode").asText());
        assertEquals("StationA", card.get("routeStart").asText());

        game.hasCard = false;
        assertEquals(400, post("/api/getcardinfo", "{" + credentials(token) + "}").statusCode());
        game.hasCard = true;

        HttpResponse<String> deleted = post("/api/delcardinfo", "{" + credentials(token) + "}");
        assertEquals("Ticket information deleted", json(deleted).get("message").asText());
        assertEquals(1, game.notifications.size());
        assertNull(db.getUser(steve).ticketType);
        assertEquals(900, db.getUser(steve).balance);
        assertEquals("No ticket information to delete", json(post("/api/delcardinfo", "{" + credentials(token) + "}")).get("message").asText());
    }

    @Test
    void writeCardReportsTheResult() throws Exception {
        startServer();
        game.acceptCard = false;
        HttpResponse<String> rejected = post("/api/writecard", "{" + credentials(token) + ",\"carddata\":\"bad\"}");
        assertEquals(400, rejected.statusCode());
        assertEquals("Card data rejected", json(rejected).get("message").asText());

        game.acceptCard = true;
        HttpResponse<String> accepted = post("/api/writecard", "{" + credentials(token) + ",\"carddata\":\"good\"}");
        assertEquals(200, accepted.statusCode());
        assertEquals(1000, json(accepted).get("balance").asInt());
        assertEquals(List.of("bad", "good"), game.writtenCards);
    }

    @Test
    void payChecksBalanceAtomically() throws Exception {
        startServer();
        HttpResponse<String> paid = post("/api/pay", "{" + credentials(token) + ",\"amount\":400,\"description\":\"item\"}");
        assertEquals(200, paid.statusCode());
        assertEquals(600, json(paid).get("balance").asInt());
        PaymentHistory latest = db.getHistory(steve, 1).get(0);
        assertEquals("web::pay", latest.from);
        assertEquals(-400, latest.amount);
        assertEquals(600, latest.balance);
        assertEquals("item", latest.description);

        assertEquals(400, post("/api/pay", "{" + credentials(token) + ",\"amount\":601}").statusCode());
        assertEquals(400, post("/api/pay", "{" + credentials(token) + ",\"amount\":0}").statusCode());
        assertEquals(400, post("/api/pay", "{" + credentials(token) + ",\"amount\":-5}").statusCode());
        assertEquals(400, post("/api/pay", "{" + credentials(token) + ",\"amount\":1,\"description\":\"" + "x".repeat(300) + "\"}").statusCode());
        assertEquals(600, db.getUser(steve).balance);
    }

    @Test
    void concurrentPaymentsCannotOverspend() throws Exception {
        startServer();
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<Integer>> results = new ArrayList<>();
            for (int i = 0; i < 24; i++) {
                results.add(pool.submit(() -> post("/api/pay", "{" + credentials(token) + ",\"amount\":100}").statusCode()));
            }
            int ok = 0;
            for (Future<Integer> result : results) {
                if (result.get() == 200) ok++;
            }
            assertEquals(10, ok);
        } finally {
            pool.shutdownNow();
        }
        assertEquals(0, db.getUser(steve).balance);
    }

    @Test
    void fullHistoryIsPaged() throws Exception {
        for (int i = 0; i < 620; i++) db.addHistory(steve, PaymentHistory.build("S" + i, "", -1, 0, 1000 + i, ""));
        startServer();
        // 指定がなければ 1 ページの上限まで
        assertEquals(500, json(get("/api/fullhistory/Steve", token)).size());
        assertEquals(500, json(get("/api/fullhistory/Steve?limit=100000", token)).size());
        JsonNode firstPage = json(get("/api/fullhistory/Steve?limit=10", token));
        assertEquals(10, firstPage.size());
        assertEquals("S619", firstPage.get(0).get("from").asText());
        JsonNode nextPage = json(get("/api/fullhistory/Steve?limit=10&offset=10", token));
        assertEquals("S609", nextPage.get(0).get("from").asText());
        assertEquals(1, json(get("/api/fullhistory/Steve?limit=500&offset=620", token)).size());

        assertEquals(400, get("/api/fullhistory/Steve?limit=0", token).statusCode());
        assertEquals(400, get("/api/fullhistory/Steve?limit=-1", token).statusCode());
        assertEquals(400, get("/api/fullhistory/Steve?offset=-1", token).statusCode());
        assertEquals(400, get("/api/fullhistory/Steve?limit=abc", token).statusCode());

        // 最近の履歴は従来どおり 100 件
        assertEquals(100, json(get("/api/history/Steve", token)).size());
    }

    @Test
    void anotherClientCannotLockThePlayerOut() throws Exception {
        ApiSecurity.Settings settings = new ApiSecurity.Settings();
        settings.authNameDelayMaxMillis = 1;
        startServer(settings);
        for (int i = 0; i < 5; i++) {
            assertEquals(401, post("/api/getcardinfo", "{" + credentials("guess" + i) + "}", OTHER_IP).statusCode());
        }
        // 失敗した接続元はロックされる
        assertEquals(429, post("/api/getcardinfo", "{" + credentials(token) + "}", OTHER_IP).statusCode());
        // 本人は使える
        assertEquals(200, post("/api/getcardinfo", "{" + credentials(token) + "}", CLIENT_IP).statusCode());
        assertEquals(200, get("/api/balance/Steve", token).statusCode());
    }

    @Test
    void clientIpHeaderFromAnUntrustedPeerIsIgnored() throws Exception {
        ApiSecurity.Settings settings = new ApiSecurity.Settings();
        settings.trustedProxies = List.of("10.0.0.0/8");
        settings.authNameDelayMaxMillis = 1;
        startServer(settings);
        // ヘッダーを変えても同じ接続元として数える
        for (int i = 0; i < 5; i++) {
            assertEquals(401, post("/api/getcardinfo", "{" + credentials("guess" + i) + "}", "203.0.113." + (100 + i)).statusCode());
        }
        assertEquals(429, post("/api/getcardinfo", "{" + credentials(token) + "}", "203.0.113.200").statusCode());
    }

    @Test
    void postRequestsAreRateLimited() throws Exception {
        ApiSecurity.Settings settings = new ApiSecurity.Settings();
        settings.writeMaxRequests = 2;
        startServer(settings);
        assertEquals(401, post("/api/getcardinfo", "{" + credentials("wrong") + "}").statusCode());
        assertEquals(401, post("/api/getcardinfo", "{" + credentials("wrong") + "}").statusCode());
        HttpResponse<String> limited = post("/api/getcardinfo", "{" + credentials("wrong") + "}");
        assertEquals(429, limited.statusCode());
        assertTrue(limited.headers().firstValue("Retry-After").isPresent());
        // 別の接続元には影響しない
        assertEquals(401, post("/api/getcardinfo", "{" + credentials("wrong") + "}", OTHER_IP).statusCode());
    }

    @Test
    void readsAreRateLimited() throws Exception {
        ApiSecurity.Settings settings = new ApiSecurity.Settings();
        settings.readMaxRequests = 1;
        startServer(settings);
        assertEquals(200, get("/api/balance/Steve", token).statusCode());
        assertEquals(429, get("/api/balance/Steve", token).statusCode());
    }

    @Test
    void connectionsAboveTheLimitAreClosed() throws Exception {
        ApiSecurity.Settings settings = new ApiSecurity.Settings();
        settings.maxWorkers = 1;
        settings.maxQueuedConnections = 1;
        startServer(settings);
        InetAddress loopback = InetAddress.getByName("127.0.0.1");
        // 何も送らない接続で処理スレッドと待ち行列を埋める
        try (Socket first = new Socket(loopback, server.getListeningPort());
             Socket second = new Socket(loopback, server.getListeningPort())) {
            Thread.sleep(200);
            long start = System.currentTimeMillis();
            boolean closedPromptly = false;
            for (int i = 0; i < 5 && !closedPromptly; i++) {
                try (Socket extra = new Socket(loopback, server.getListeningPort())) {
                    extra.setSoTimeout(2000);
                    InputStream in = extra.getInputStream();
                    closedPromptly = in.read() == -1;
                } catch (IOException e) {
                    closedPromptly = true;
                }
            }
            assertTrue(closedPromptly);
            assertTrue(System.currentTimeMillis() - start < 4000);
        }
        // 接続が終われば通常どおり処理する (受付を待つ接続の読み取りタイムアウトより長く待てるようにする)
        long deadline = System.currentTimeMillis() + 15000;
        int status = 0;
        while (status != 200 && System.currentTimeMillis() < deadline) {
            try {
                status = get("/api/balance/Steve", token).statusCode();
            } catch (IOException e) {
                Thread.sleep(100);
            }
        }
        assertEquals(200, status);
    }

    @Test
    void requestWithAnUnreadBodyClosesTheConnection() throws Exception {
        startServer();
        HttpResponse<String> unknown = post("/api/unknown", "{}");
        assertEquals(404, unknown.statusCode());
        assertEquals("close", unknown.headers().firstValue("connection").orElse(""));

        HttpResponse<String> wrongMethod = post("/api/balance/Steve", "{}");
        assertEquals(405, wrongMethod.statusCode());
        assertEquals("close", wrongMethod.headers().firstValue("connection").orElse(""));

        HttpResponse<String> getWithBody = send(request("/", CLIENT_IP).method("GET", HttpRequest.BodyPublishers.ofString("abcd")).build());
        assertEquals(200, getWithBody.statusCode());
        assertEquals("close", getWithBody.headers().firstValue("connection").orElse(""));

        // ボディを読み切った要求や、ボディのない要求は接続を閉じない
        assertNotEquals("close", post("/api/getcardinfo", "{" + credentials(token) + "}").headers().firstValue("connection").orElse(""));
        assertNotEquals("close", get("/", null).headers().firstValue("connection").orElse(""));
    }

    @Test
    void chunkedBodyIsRefusedAndClosesTheConnection() throws Exception {
        startServer();
        // 長さの分からない本文は HttpClient がチャンク形式で送る
        HttpRequest request = request("/api/webcharge", CLIENT_IP)
                .POST(HttpRequest.BodyPublishers.ofInputStream(() -> new java.io.ByteArrayInputStream("{}".getBytes(StandardCharsets.UTF_8))))
                .build();
        HttpResponse<String> response = send(request);
        assertEquals(400, response.statusCode());
        assertEquals("close", response.headers().firstValue("connection").orElse(""));
    }

    private static final class FakeGame implements ApiServer.GameBridge {
        final List<String> syncCalls = new ArrayList<>();
        final List<String> writtenCards = new ArrayList<>();
        final List<String> notifications = new ArrayList<>();
        private final ExecutorService mainThread = Executors.newSingleThreadExecutor();
        volatile boolean online = true;
        volatile boolean hasCard = true;
        volatile boolean acceptCard = true;

        @Override
        public <T> Future<T> callSync(Callable<T> task) {
            synchronized (syncCalls) {
                syncCalls.add("call");
            }
            return mainThread.submit(task);
        }

        @Override
        public boolean isOnline(UUID uuid) {
            return online;
        }

        @Override
        public boolean hasICCard(UUID uuid) {
            return hasCard;
        }

        @Override
        public boolean writeCard(UUID uuid, String cardData) {
            writtenCards.add(cardData);
            return acceptCard;
        }

        @Override
        public void notifyPlayer(UUID uuid, String message) {
            notifications.add(message);
        }

        void shutdown() {
            mainThread.shutdownNow();
        }
    }
}
