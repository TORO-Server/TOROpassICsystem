package prj.salmon.toropassicsystem;

import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.Sign;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Openable;
import org.bukkit.block.sign.Side;
import org.bukkit.block.sign.SignSide;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.vehicle.VehicleEnterEvent;
import org.bukkit.event.vehicle.VehicleMoveEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.IllegalPluginAccessException;
import org.bukkit.plugin.java.JavaPlugin;
import prj.salmon.toropassicsystem.api.ApiSecurity;
import prj.salmon.toropassicsystem.api.ApiServer;
import prj.salmon.toropassicsystem.api.CardCodes;
import prj.salmon.toropassicsystem.api.WebPasswords;
import prj.salmon.toropassicsystem.types.PaymentHistory;

import java.io.IOException;
import java.sql.SQLException;
import java.text.SimpleDateFormat;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.util.logging.Level;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class TOROpassICsystem extends JavaPlugin implements Listener, CommandExecutor {
    private static final int API_PORT = 5744;
    private static final long SECONDS_PER_DAY = 86400L;

    final HashMap<UUID, StationData> playerData = new HashMap<>();
    private final Map<UUID, Long> rankCommandUsedAt = new HashMap<>();
    private ApiServer httpserver;
    private ApiSecurity apiSecurity;
    private RankingService rankingService;

    private final NamespacedKey signMarkKey = new NamespacedKey(this, "authorized_sign");

    private CardCodes.Settings cardCodeSettings;
    private boolean autoChargeEnabled;
    private boolean requireSignMark;
    private long rankCooldownMillis;
    private long webPasswordTtlSeconds;

    public DatabaseManager dbManager;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        cardCodeSettings = loadCardCodeSettings();
        autoChargeEnabled = getConfig().getBoolean("charge.auto-charge-enabled", true);
        requireSignMark = getConfig().getBoolean("signs.require-authorized-mark", false);
        rankCooldownMillis = Math.max(0, getConfig().getLong("ranking.cooldown-seconds", 5)) * 1000L;
        webPasswordTtlSeconds = Math.max(0, getConfig().getLong("api.web-password.ttl-days", 90)) * SECONDS_PER_DAY;
        long legacyPasswordSeconds = Math.max(0, getConfig().getLong("api.web-password.legacy-grace-days", 30)) * SECONDS_PER_DAY;
        try {
            dbManager = new DatabaseManager(getDataFolder() + "/toropass.db");
            dbManager.connect(legacyPasswordSeconds);
        } catch (Exception e) {
            getLogger().severe("DB初期化失敗: " + e.getMessage());
            throw new RuntimeException(e);
        }
        long rankingCacheMillis = Math.max(0, getConfig().getLong("ranking.cache-seconds", 300)) * 1000L;
        rankingService = new RankingService(dbManager, task -> Bukkit.getScheduler().runTaskAsynchronously(this, task), rankingCacheMillis, System::currentTimeMillis);
        Bukkit.getPluginManager().registerEvents(this, this);
        getCommand("charge").setExecutor(this);
        getCommand("autocharge").setExecutor(this);
        getCommand("writecard").setExecutor(this);
        getCommand("toropassrank").setExecutor(this);
        getCommand("webcharge").setExecutor(this);
        ApiSecurity.Settings apiSettings = loadApiSettings();
        apiSecurity = new ApiSecurity(apiSettings, System::currentTimeMillis,
                invalid -> getLogger().warning("api.trusted-proxies の値を解釈できません: " + invalid));
        try {
            httpserver = new ApiServer(API_PORT, apiSettings, apiSecurity, dbManager, new BukkitBridge(), getLogger());
        } catch (IOException e) {
            getLogger().warning(e.getMessage());
        }
    }

    @Override
    public void onDisable() {
        // 処理中のリクエストが閉じた DB を使わないよう、先に API を止める
        if (httpserver != null) httpserver.stop();
        try {
            if (dbManager != null) dbManager.close();
        } catch (Exception e) {
            getLogger().warning("DBクローズ失敗: " + e.getMessage());
        }
    }

    // config.yml の api セクションを読み込む (項目がない場合は安全なデフォルト値を使用)
    private ApiSecurity.Settings loadApiSettings() {
        ApiSecurity.Settings settings = new ApiSecurity.Settings();
        settings.webChargeEnabled = getConfig().getBoolean("charge.web-charge-enabled", settings.webChargeEnabled);
        ConfigurationSection api = getConfig().getConfigurationSection("api");
        if (api == null) return settings;
        settings.requireAuthForRead = api.getBoolean("require-auth-for-read", settings.requireAuthForRead);
        settings.clientIpHeader = api.getString("client-ip-header", settings.clientIpHeader);
        if (api.isList("trusted-proxies")) settings.trustedProxies = api.getStringList("trusted-proxies");
        if (api.isList("cors-allowed-origins")) settings.corsAllowedOrigins = api.getStringList("cors-allowed-origins");
        settings.bindAddress = api.getString("bind-address", settings.bindAddress);
        settings.maxWorkers = api.getInt("max-workers", settings.maxWorkers);
        settings.maxQueuedConnections = api.getInt("max-queued-connections", settings.maxQueuedConnections);
        settings.maxBodyBytes = api.getInt("max-body-bytes", settings.maxBodyBytes);
        settings.syncTimeoutMillis = api.getLong("sync-timeout-ms", settings.syncTimeoutMillis);
        settings.authMaxFailures = api.getInt("auth-throttle.max-failures", settings.authMaxFailures);
        settings.authIpMaxFailures = api.getInt("auth-throttle.ip-max-failures", settings.authIpMaxFailures);
        settings.authWindowSeconds = api.getLong("auth-throttle.window-seconds", settings.authWindowSeconds);
        settings.authLockoutSeconds = api.getLong("auth-throttle.lockout-seconds", settings.authLockoutSeconds);
        settings.authNameDelayAfter = api.getInt("auth-throttle.name-delay-after-failures", settings.authNameDelayAfter);
        settings.authNameDelayMaxMillis = api.getLong("auth-throttle.name-delay-max-ms", settings.authNameDelayMaxMillis);
        settings.readMaxRequests = api.getInt("read-rate-limit.max-requests", settings.readMaxRequests);
        settings.readWindowSeconds = api.getLong("read-rate-limit.window-seconds", settings.readWindowSeconds);
        settings.writeMaxRequests = api.getInt("write-rate-limit.max-requests", settings.writeMaxRequests);
        settings.writeWindowSeconds = api.getLong("write-rate-limit.window-seconds", settings.writeWindowSeconds);
        return settings;
    }

    // config.yml の card-codes セクションを読み込む (既定では発売機の現在のコードをそのまま受け付ける)
    private CardCodes.Settings loadCardCodeSettings() {
        CardCodes.Settings settings = new CardCodes.Settings();
        settings.requireSignature = getConfig().getBoolean("card-codes.require-signature", settings.requireSignature);
        settings.signingSecret = getConfig().getString("card-codes.signing-secret", settings.signingSecret);
        settings.maxValidityDays = getConfig().getInt("card-codes.max-validity-days", settings.maxValidityDays);
        settings.minPurchaseAmount = getConfig().getInt("card-codes.min-purchase-amount", settings.minPurchaseAmount);
        settings.maxPurchaseAmount = getConfig().getInt("card-codes.max-purchase-amount", settings.maxPurchaseAmount);
        if (settings.requireSignature && (settings.signingSecret == null || settings.signingSecret.isEmpty())) {
            getLogger().severe("card-codes.require-signature が有効ですが signing-secret が空です。定期券コードはすべて拒否されます。");
        }
        return settings;
    }

    // API から使うゲーム側の処理
    private final class BukkitBridge implements ApiServer.GameBridge {
        @Override
        public <T> Future<T> callSync(Callable<T> task) {
            return Bukkit.getScheduler().callSyncMethod(TOROpassICsystem.this, task);
        }

        @Override
        public boolean isOnline(UUID uuid) {
            return Bukkit.getPlayer(uuid) != null;
        }

        @Override
        public boolean hasICCard(UUID uuid) {
            Player player = Bukkit.getPlayer(uuid);
            return player != null && findValidICCard(player) != null;
        }

        @Override
        public boolean writeCard(UUID uuid, String cardData) {
            Player player = Bukkit.getPlayer(uuid);
            return player != null && TOROpassICsystem.this.writeCard(player, cardData, "Special::webwritecard");
        }

        @Override
        public void notifyPlayer(UUID uuid, String message) {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null) player.sendMessage(ChatColor.GREEN + message);
        }
    }

    // 内部エラーの詳細はサーバーログにのみ出力し、プレイヤーには一般的なメッセージを表示する
    private void sendInternalError(CommandSender target, String context, Exception e) {
        getLogger().log(Level.WARNING, context + " (" + target.getName() + ")", e);
        target.sendMessage(ChatColor.RED + context + "が発生しました。時間をおいて再度お試しください。");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("このコマンドはプレイヤーのみ使用できます。");
            return true;
        }
        try {
            DatabaseManager.UserData user = dbManager.ensureUser(player.getUniqueId(), player.getName());
            if (command.getName().equalsIgnoreCase("charge")) {
                if (!player.hasPermission("toropassicsystem.admin")) {
                    player.sendMessage(ChatColor.RED + "チャージコマンドを実行する権限がありません。");
                    return true;
                }
                if (args.length != 1) {
                    player.sendMessage(ChatColor.RED + "使用方法: /charge <金額>");
                    return true;
                }
                int amount;
                try {
                    amount = Integer.parseInt(args[0]);
                } catch (NumberFormatException e) {
                    player.sendMessage(ChatColor.RED + "チャージ額が不正です");
                    return true;
                }
                if (amount <= 0) {
                    player.sendMessage(ChatColor.RED + "チャージ額が不正です");
                    return true;
                }
                OptionalInt balance = dbManager.credit(user.uuid, amount, DatabaseManager.MAX_BALANCE, chargeHistory(amount));
                if (balance.isEmpty()) {
                    player.sendMessage(ChatColor.RED + "最大チャージ額は20000トロポまでです");
                    return true;
                }
                player.sendMessage(ChatColor.GREEN + String.valueOf(amount) + "トロポをチャージしました。現在の残高: " + balance.getAsInt() + "トロポ");
                return true;
            }

            if (command.getName().equalsIgnoreCase("autocharge")) {
                if (args.length == 2) {
                    try {
                        int threshold = Integer.parseInt(args[0]);
                        int amount = Integer.parseInt(args[1]);
                        if (threshold < 0 || amount <= 0 || amount > 10000 || threshold > 10000) {
                            player.sendMessage(ChatColor.RED + (threshold < 0 || amount <= 0 ? "不正な値です" : "1万トロポを超える設定はできません"));
                            return true;
                        }
                        if (!autoChargeEnabled) {
                            player.sendMessage(ChatColor.RED + "オートチャージは現在利用できません。");
                            return true;
                        }
                        dbManager.updateAutoCharge(user.uuid, threshold, amount);
                        player.sendMessage(ChatColor.GREEN + "残高が " + threshold + "トロポを下回った場合に " + amount + "トロポをチャージします。");
                    } catch (NumberFormatException e) {
                        player.sendMessage(ChatColor.RED + "無効な数値が入力されました。");
                    }
                } else if (args.length == 1 && args[0].equalsIgnoreCase("stop")) {
                    dbManager.updateAutoCharge(user.uuid, null, null);
                    player.sendMessage(ChatColor.GREEN + "オートチャージが停止されました。");
                } else {
                    player.sendMessage(ChatColor.RED + "使用方法: /autocharge <閾値> <チャージ額> または /autocharge stop");
                }
                return true;
            }

            if (command.getName().equalsIgnoreCase("writecard")) {
                if (args.length < 1) {
                    player.sendMessage(ChatColor.RED + "使用方法: /writecard <券種>-<事業者コード>-<購入金額>-<有効期限>-<チェックデジット>[-<区間開始>-<区間終了>]");
                    return true;
                }
                writeCard(player, args[0], "Special::writecard");
                return true;
            }

            if (command.getName().equalsIgnoreCase("toropassrank")) {
                showRanking(player);
                return true;
            }

            if (command.getName().equalsIgnoreCase("webcharge")) {
                if (args.length == 1 && args[0].equalsIgnoreCase("revoke")) {
                    dbManager.updateWebPassword(user.uuid, null, null);
                    player.sendMessage(ChatColor.GREEN + "Walletのパスワードを無効にしました。");
                    return true;
                }
                issueWebPassword(player, user);
                return true;
            }
        } catch (Exception e) {
            sendInternalError(sender, "エラー", e);
        }
        return false;
    }

    // Wallet のパスワードを発行する。DB にはハッシュだけを保存するので、表示できるのはこのときだけ
    private void issueWebPassword(Player player, DatabaseManager.UserData user) throws SQLException {
        String token = WebPasswords.generateToken();
        long now = System.currentTimeMillis() / 1000L;
        Long expiresAt = webPasswordTtlSeconds > 0 ? now + webPasswordTtlSeconds : null;
        dbManager.updateWebPassword(user.uuid, WebPasswords.hashToken(token), expiresAt);
        // 第三者の認証失敗で本人が待たされている場合も、新しいパスワードですぐに使えるようにする
        apiSecurity.clearAuthFailures(player.getName());

        TextComponent message = new TextComponent("Webチャージ用パスワードを生成しました: " + token);
        message.setColor(net.md_5.bungee.api.ChatColor.GREEN);
        message.setClickEvent(new ClickEvent(ClickEvent.Action.COPY_TO_CLIPBOARD, token));
        player.spigot().sendMessage(message);
        player.sendMessage(ChatColor.YELLOW + "クリックするとコピーできます。このパスワードを使用してWebからチャージできます。次の /webcharge 実行でこのパスワードは無効になります。");
        if (expiresAt != null) {
            String expiry = new SimpleDateFormat("yyyy年MM月dd日").format(new Date(expiresAt * 1000L));
            player.sendMessage(ChatColor.YELLOW + "有効期限: " + expiry + " / 無効にするには /webcharge revoke");
        } else {
            player.sendMessage(ChatColor.YELLOW + "無効にするには /webcharge revoke");
        }
    }

    // ランキングはメインスレッドの外で集計し、結果の表示だけメインスレッドで行う
    private void showRanking(Player player) {
        long now = System.currentTimeMillis();
        rankCommandUsedAt.values().removeIf(usedAt -> now - usedAt >= rankCooldownMillis);
        if (rankCommandUsedAt.containsKey(player.getUniqueId())) {
            player.sendMessage(ChatColor.RED + "しばらく待ってから再度実行してください。");
            return;
        }
        if (rankCooldownMillis > 0) rankCommandUsedAt.put(player.getUniqueId(), now);

        rankingService.snapshot().whenComplete((snapshot, error) -> runOnMainThread(() -> {
            if (!player.isOnline()) return;
            if (error != null) {
                sendInternalError(player, "ランキング取得エラー", error instanceof Exception e ? e : new RuntimeException(error));
                return;
            }
            sendRanking(player, snapshot);
        }));
    }

    private void runOnMainThread(Runnable task) {
        if (Bukkit.isPrimaryThread()) {
            task.run();
            return;
        }
        try {
            Bukkit.getScheduler().runTask(this, task);
        } catch (IllegalPluginAccessException ignored) {
            // プラグインの停止中は表示しない
        }
    }

    private void sendRanking(Player player, RankingService.Snapshot snapshot) {
        player.sendMessage(ChatColor.GOLD + "=====TOROpass 移動量ランキング=====");
        boolean playerInTop10 = false;
        for (RankingService.Entry entry : snapshot.top(10)) {
            String playerName = Objects.requireNonNullElse(entry.name(), "不明なプレイヤー");
            String distance = formatDistance(entry.consumption() * 5.0);
            String message = ChatColor.YELLOW + String.valueOf(snapshot.rankOf(entry.consumption())) + "位 " + playerName + " " + entry.consumption() + "トロポ " + distance;
            if (entry.uuid().equals(player.getUniqueId())) {
                message += " (自分)";
                playerInTop10 = true;
            }
            player.sendMessage(message);
        }

        if (!playerInTop10) {
            long playerConsumption = snapshot.consumptionOf(player.getUniqueId());
            String playerDistance = formatDistance(playerConsumption * 5.0);
            player.sendMessage(ChatColor.YELLOW + String.valueOf(snapshot.rankOf(playerConsumption)) + "位 " + player.getName() + "(あなた) " + playerConsumption + "トロポ " + playerDistance);
        }

        String totalDistance = formatDistance(snapshot.totalConsumption() * 5.0);
        player.sendMessage(ChatColor.GOLD + "全ユーザーの移動量: " + snapshot.totalConsumption() + "トロポ " + totalDistance);
    }

    // 定期券コードを検証して書き込み、購入金額を引き去る。書き込んだ場合は true
    private boolean writeCard(Player player, String cardDataStr, String historyFrom) {
        CardCodes.Result parsed = CardCodes.parse(cardDataStr, cardCodeSettings, LocalDate.now());
        if (!parsed.ok()) {
            player.sendMessage(ChatColor.RED + cardCodeErrorMessage(parsed.error()) + "コードを再度発行し直してください。");
            return false;
        }
        CardCodes.Card card = parsed.card();

        if (card.companyCode() == 99 && !player.hasPermission("toropassicsystem.admin")) {
            player.sendMessage(ChatColor.RED + "TORO全線定期を発行する権限がありません。");
            return false;
        }

        DatabaseManager.TicketData ticket = new DatabaseManager.TicketData(card.ticketType(), card.companyCode(), card.purchaseAmount(),
                card.expiryEpochMillis(ZoneId.systemDefault()), card.checkDigit(), card.routeStart(), card.routeEnd());
        PaymentHistory h = PaymentHistory.build(historyFrom, "", card.purchaseAmount() * -1, 0, System.currentTimeMillis() / 1000L, "");
        try {
            OptionalInt balance = dbManager.purchaseTicket(player.getUniqueId(), ticket, h);
            if (balance.isEmpty()) {
                DatabaseManager.UserData current = dbManager.getUser(player.getUniqueId());
                int currentBalance = current != null ? current.balance : 0;
                player.sendMessage(ChatColor.RED + "残高が不足しています。現在の残高: " + currentBalance + "トロポ, 購入金額: " + card.purchaseAmount() + "トロポ");
                return false;
            }
        } catch (SQLException ex) {
            sendInternalError(player, "DBエラー", ex);
            return false;
        }
        player.sendMessage(ChatColor.GREEN + "定期券情報を書き込みました。");
        return true;
    }

    private static String cardCodeErrorMessage(CardCodes.Error error) {
        return switch (error) {
            case MALFORMED -> "入力内容が正しくありません。";
            case BAD_FIELD -> "引数の形式が正しくありません。";
            case OUT_OF_RANGE -> "コードの内容が正しくありません。";
            case CHECK_DIGIT -> "チェックデジットが不正です。";
            case EXPIRED -> "有効期限が切れたコードです。";
            case VALIDITY_TOO_LONG -> "有効期限が長すぎます。";
            case AMOUNT_NOT_ALLOWED -> "購入金額が範囲外です。";
            case SIGNATURE_REQUIRED, SIGNATURE_INVALID -> "コードの署名を確認できません。";
        };
    }

    private static PaymentHistory chargeHistory(int amount) {
        return PaymentHistory.build("Special::charge", "", amount, 0, System.currentTimeMillis() / 1000L, "チャージ");
    }

    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || !(event.getClickedBlock().getState() instanceof Sign sign)) return;
        Player player = event.getPlayer();
        ItemStack item = player.getInventory().getItemInMainHand();
        if (!isValidICCard(item)) {
            handlePassSaleSign(sign, player, event);
            return;
        }
        event.setCancelled(true);

        try {
            DatabaseManager.UserData user = dbManager.getUser(player.getUniqueId());
            if (user == null) {
                player.sendMessage(ChatColor.RED + "ユーザーデータが見つかりません。管理者に連絡してください。");
                return;
            } else {
                if (!player.getName().equals(user.playername)) {
                    dbManager.updatePlayerName(user.uuid, player.getName());
                    user.playername = player.getName();
                }
            }

            StationData data = playerData.computeIfAbsent(player.getUniqueId(), k -> {
                StationData newData = new StationData();
                newData.balance = user.balance;
                newData.autoChargeThreshold = user.autoChargeThreshold;
                newData.autoChargeAmount = user.autoChargeAmount;
                return newData;
            });
            
            if (data.balance != user.balance) {
                data.balance = user.balance;
            }
            if (!Objects.equals(data.autoChargeThreshold, user.autoChargeThreshold)) {
                data.autoChargeThreshold = user.autoChargeThreshold;
            }
            if (!Objects.equals(data.autoChargeAmount, user.autoChargeAmount)) {
                data.autoChargeAmount = user.autoChargeAmount;
            }
            
            SignSide frontSide = sign.getSide(Side.FRONT);
            SignSide backSide = sign.getSide(Side.BACK);
            String frontLine1 = ChatColor.stripColor(frontSide.getLine(0));
            String frontLine2 = ChatColor.stripColor(frontSide.getLine(1));
            String frontLine3 = ChatColor.stripColor(frontSide.getLine(2));
            String frontLine4 = ChatColor.stripColor(frontSide.getLine(3));
            String backLine2 = ChatColor.stripColor(backSide.getLine(1));
            String backLine3 = ChatColor.stripColor(backSide.getLine(2));

            switch (frontLine1) {
                case "[入場]":
                case "[出場]":
                case "[入出場]":
                    handleStationSign(frontLine1, frontLine2, frontLine4, player, item, data, event, user);
                    break;
                case "[チャージ]":
                    if (!isSignUsable(sign, player)) return;
                    handleChargeSign(frontLine2, player, data, user);
                    break;
                case "[残高確認]":
                    player.sendMessage(ChatColor.GREEN + "現在の残高: " + user.balance + "トロポ");
                    break;
                case "[強制出場]":
                    if (!player.hasPermission("toropassicsystem.admin")) {
                        player.sendMessage(ChatColor.RED + "強制出場を実行する権限がありません。");
                        return;
                    }
                    if (data.isInStation) {
                        player.sendMessage(ChatColor.GREEN + "強制出場しました。");
                        data.exitStation();
                    } else player.sendMessage(ChatColor.RED + "入場記録がありません。");
                    break;
                case "[残額調整]":
                    if (!player.hasPermission("toropassicsystem.admin")) {
                        player.sendMessage(ChatColor.RED + "残額調整を実行する権限がありません。");
                        return;
                    }
                    handleBalanceAdjustment(frontLine2, player, data, user);
                    break;
                case "[定期券情報削除]":
                    handleTicketInfoRemoval(player, user);
                    break;
                case "[定期券情報照会]":
                    handleTicketInfoInquiry(player, user);
                    break;
                case "[乗換]":
                    handleTransferSign(frontLine2, frontLine3, frontLine4, player, item, data, event, user);
                    break;
                case "[物販]":
                    if (!isSignUsable(sign, player)) return;
                    if ("[IC]".equals(backLine2) && "ここにタッチ".equals(backLine3))
                        handleShopSign(frontLine2, frontLine3, player, data, user);
                    else player.sendMessage(ChatColor.RED + "この看板は物販用に正しく設定されていません。");
                    break;
            }
        } catch (Exception e) {
            sendInternalError(player, "エラー", e);
        }
    }

    private void handleStationSign(String signType, String stationName, String companyCodes, Player player, ItemStack item, StationData data, PlayerInteractEvent event, DatabaseManager.UserData user) {
        if (signType.equals("[入場]") || (signType.equals("[入出場]") && !data.isInStation)) {
            if (data.isInStation) {
                player.sendMessage(ChatColor.RED + "すでに入場しています。出場してから再度入場してください。");
                return;
            }
            data.enterStation(stationName, companyCodes);
            openFenceGate(companyCodes, event);
            player.sendMessage(ChatColor.GREEN + "入場: " + stationName);
            player.sendMessage(ChatColor.GREEN + "残高: " + user.balance + "トロポ");
            data.setRideStartLocation(player.getLocation());
            player.playSound(player.getLocation(), "custom.kaisatsu", 1.0F, 1.0F);
            return;
        }

        if (!data.isInStation) {
            player.sendMessage(ChatColor.RED + "入場記録がありません。");
            return;
        }

        int fare = data.stationName.equals(stationName) ? 100 : data.calculateFare();
        TicketValidationResult result = validateTicketFromDB(user, data, stationName, companyCodes);

        if (result.isExpired && result.ticketType != null) {
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy年MM月dd日 HH:mm:ss");
            player.sendMessage(ChatColor.RED + "定期券の有効期限 (" + sdf.format(new Date(result.expiryDateLong)) + ") が切れています。更新または消去処理をしてください。");
        }

        if (result.isFree) {
            player.sendMessage(ChatColor.GREEN + result.ticketText);
            player.sendMessage(ChatColor.GREEN + "出場: " + stationName);
            player.sendMessage(ChatColor.GREEN + "残高: " + user.balance + "トロポ");
            stationName = stationName + "[[定期利用]]";
            PaymentHistory h = PaymentHistory.build(data.stationName + data.entryCompanyCodes, stationName + data.validateCompanyCodes(companyCodes), 0, user.balance, System.currentTimeMillis() / 1000L, "定期利用");
            try {
                dbManager.addHistory(user.uuid, h);
            } catch (SQLException e) {
                sendInternalError(player, "履歴保存エラー", e);
            }
        } else {
            if (!runAutoCharge(player, data, user)) return;

            PaymentHistory h = PaymentHistory.build(data.stationName + data.entryCompanyCodes, stationName + data.validateCompanyCodes(companyCodes), fare * -1, 0, System.currentTimeMillis() / 1000L, "出場");
            if (!payFromBalance(player, data, user, fare, h)) return;

            player.sendMessage(ChatColor.GREEN + "出場: " + stationName + (data.stationName.equals(stationName) ? "(入場サービス)" : "") + " 引去: " + fare + "トロポ");
            player.sendMessage(ChatColor.GREEN + "残高: " + user.balance + "トロポ");
        }

        openFenceGate(companyCodes, event);
        player.playSound(player.getLocation(), "custom.kaisatsu", 1.0F, 1.0F);
        data.exitStation();
    }

    // オートチャージの設定があり残高が閾値を下回っていればチャージする。DBエラーの場合は false
    private boolean runAutoCharge(Player player, StationData data, DatabaseManager.UserData user) {
        if (!autoChargeEnabled) return true;
        try {
            OptionalInt balance = dbManager.autoCharge(user.uuid);
            if (balance.isPresent()) {
                user.balance = balance.getAsInt();
                data.balance = user.balance;  // StationDataも同期
                player.sendMessage(ChatColor.GREEN + "オートチャージが実行されました。新しい残高: " + user.balance + "トロポ");
            }
            return true;
        } catch (SQLException e) {
            sendInternalError(player, "オートチャージエラー", e);
            return false;
        }
    }

    // 残高から引き去る。残高不足やDBエラーの場合はメッセージを出して false
    private boolean payFromBalance(Player player, StationData data, DatabaseManager.UserData user, int amount, PaymentHistory history) {
        try {
            OptionalInt balance = dbManager.debit(user.uuid, amount, history);
            if (balance.isEmpty()) {
                DatabaseManager.UserData current = dbManager.getUser(user.uuid);
                int currentBalance = current != null ? current.balance : user.balance;
                player.sendMessage(ChatColor.RED + String.valueOf(amount - currentBalance) + "トロポ不足しています。チャージしてください。");
                return false;
            }
            user.balance = balance.getAsInt();
            data.balance = user.balance;  // StationDataも同期
            return true;
        } catch (SQLException e) {
            sendInternalError(player, "DBエラー", e);
            return false;
        }
    }

    private void handleChargeSign(String amountStr, Player player, StationData data, DatabaseManager.UserData user) {
        try {
            int chargeAmount = Integer.parseInt(amountStr);
            if (chargeAmount <= 0) {
                player.sendMessage(ChatColor.RED + "チャージ額が不正です");
                return;
            }
            try {
                OptionalInt balance = dbManager.credit(user.uuid, chargeAmount, DatabaseManager.MAX_BALANCE, chargeHistory(chargeAmount));
                if (balance.isEmpty()) {
                    player.sendMessage(ChatColor.RED + "最大チャージ額は20000トロポまでです");
                    return;
                }
                user.balance = balance.getAsInt();
                data.balance = user.balance;  // StationDataも同期
            } catch (SQLException e) {
                sendInternalError(player, "DBエラー", e);
                return;
            }

            player.sendMessage(ChatColor.GREEN + String.valueOf(chargeAmount) + "トロポをチャージしました。");
            player.sendMessage(ChatColor.GREEN + "現在の残高: " + user.balance + "トロポ");
        } catch (NumberFormatException e) {
            player.sendMessage(ChatColor.RED + "チャージ額が不正です");
        }
    }

    private void handleBalanceAdjustment(String balanceStr, Player player, StationData data, DatabaseManager.UserData user) {
        try {
            int newBalance = Integer.parseInt(balanceStr);
            if (newBalance < 0) {
                player.sendMessage(ChatColor.RED + "値が不正です。");
                return;
            }
            PaymentHistory h = PaymentHistory.build("Special::balanceAdjustment", "", newBalance, 0, System.currentTimeMillis() / 1000L, "残額調整");
            try {
                dbManager.setBalance(user.uuid, newBalance, h);
            } catch (SQLException e) {
                sendInternalError(player, "DBエラー", e);
                return;
            }
            user.balance = newBalance;
            data.balance = user.balance;  // StationDataも同期

            player.sendMessage(ChatColor.GREEN + "新しい残高: " + user.balance + "トロポ");
        } catch (NumberFormatException e) {
            player.sendMessage(ChatColor.RED + "無効な残高値です");
        }
    }

    private void handleTicketInfoRemoval(Player player, DatabaseManager.UserData user) {
        if (user.ticketType == null) {
            player.sendMessage(ChatColor.YELLOW + "定期券情報が登録されていません。");
            return;
        }

        // DBの定期券情報を削除
        try {
            dbManager.clearTicket(user.uuid);
            player.sendMessage(ChatColor.GREEN + "定期券情報を削除しました。");
        } catch (SQLException e) {
            sendInternalError(player, "DBエラー", e);
        }
    }

    private void handleTicketInfoInquiry(Player player, DatabaseManager.UserData user) {
        if (user.ticketType == null) {
            player.sendMessage(ChatColor.YELLOW + "このユーザーには定期券情報が登録されていません。");
            return;
        }

        player.sendMessage(ChatColor.GREEN + "===== 定期券情報 =====");
        player.sendMessage(ChatColor.GREEN + "券種: " + user.ticketType);
        player.sendMessage(ChatColor.GREEN + "事業者コード: " + (user.companyCode != null ? String.format("%02d", user.companyCode) : "不明"));
        player.sendMessage(ChatColor.GREEN + "購入金額: " + (user.purchaseAmount != null ? user.purchaseAmount + "トロポ" : "不明"));
        player.sendMessage(ChatColor.GREEN + "有効期限: " + (user.expiryDate != null ? new SimpleDateFormat("yyyy年MM月dd日 HH:mm:ss").format(new Date(user.expiryDate)) : "不明"));
        player.sendMessage(ChatColor.GREEN + "チェックデジット: " + (user.checkDigit != null ? user.checkDigit : "不明"));
        if (user.ticketType == 2 || user.ticketType == 3) {
            player.sendMessage(ChatColor.GREEN + "定期区間: " + (user.routeStart != null ? user.routeStart : "不明") + " - " + (user.routeEnd != null ? user.routeEnd : "不明"));
        }
        player.sendMessage(ChatColor.GREEN + "=======================");
    }

    private void handleTransferSign(String departureStation, String transferStation, String companyCodes, Player player, ItemStack item, StationData data, PlayerInteractEvent event, DatabaseManager.UserData user) {
        if (!data.isInStation) {
            player.sendMessage(ChatColor.RED + "入場記録がないため、乗換改札を利用できません。先に入場してください。");
            return;
        }
        int fare = data.calculateFare();
        TicketValidationResult result = validateTicketFromDB(user, data, departureStation, companyCodes);

        if (result.isExpired && result.ticketType != null) {
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy年MM月dd日 HH:mm:ss");
            player.sendMessage(ChatColor.RED + "定期券の有効期限 (" + sdf.format(new Date(result.expiryDateLong)) + ") が切れています。更新または消去処理をしてください。");
        }

        if (result.isFree) {
            player.sendMessage(ChatColor.GREEN + result.ticketText);
            player.sendMessage(ChatColor.GREEN + "出場: " + departureStation);
            departureStation = departureStation + "[[定期利用]]";
            PaymentHistory h = PaymentHistory.build(data.stationName + data.entryCompanyCodes, departureStation + data.validateCompanyCodes(companyCodes), 0, user.balance, System.currentTimeMillis() / 1000L, "定期利用");
            try {
                dbManager.addHistory(user.uuid, h);
            } catch (SQLException e) {
                sendInternalError(player, "履歴保存エラー", e);
            }
        } else {
            if (!runAutoCharge(player, data, user)) return;

            PaymentHistory h = PaymentHistory.build(data.stationName + data.entryCompanyCodes, departureStation + data.validateCompanyCodes(companyCodes), fare * -1, 0, System.currentTimeMillis() / 1000L, "出場");
            if (!payFromBalance(player, data, user, fare, h)) return;

            player.sendMessage(ChatColor.GREEN + "出場: " + departureStation + " 引去: " + fare + "トロポ");
        }

        openFenceGate(companyCodes, event);
        data.exitStation();
        data.enterStation(transferStation, companyCodes);
        player.sendMessage(ChatColor.GREEN + "入場: " + transferStation);
        player.sendMessage(ChatColor.GREEN + "残高: " + user.balance + "トロポ");
        data.setRideStartLocation(player.getLocation());
        player.playSound(player.getLocation(), "custom.kaisatsu", 1.0F, 1.0F);
    }

    private void handleShopSign(String storeName, String amountStr, Player player, StationData data, DatabaseManager.UserData user) {
        try {
            int amount = Integer.parseInt(amountStr);
            if (amount <= 0) {
                player.sendMessage(ChatColor.RED + "店舗看板の金額が不正です。管理者に連絡してください。");
                return;
            }
            
            if (!runAutoCharge(player, data, user)) return;

            PaymentHistory h = PaymentHistory.build("Shop::" + storeName, "", amount * -1, 0, System.currentTimeMillis() / 1000L, "購入");
            if (!payFromBalance(player, data, user, amount, h)) return;

            player.sendMessage(ChatColor.GREEN + "店舗: " + storeName);
            player.sendMessage(ChatColor.GREEN + "支払額: " + amount + "トロポ");
            player.sendMessage(ChatColor.GREEN + "残高: " + user.balance + "トロポ");
            player.playSound(player.getLocation(), "minecraft:block.note_block.bell", 1.0F, 1.0F);
        } catch (NumberFormatException e) {
            player.sendMessage(ChatColor.RED + "店舗看板の金額が不正です。管理者に連絡してください。");
        }
    }

    private void handlePassSaleSign(Sign sign, Player player, PlayerInteractEvent event) {
        if (player.isSneaking()) return;
        SignSide frontSide = sign.getSide(Side.FRONT);
        String frontLine1 = ChatColor.stripColor(frontSide.getLine(0));
        if (!"[TOROpass販売]".equals(frontLine1)) return;
        event.setCancelled(true);
        if (!isSignUsable(sign, player)) return;

        try {
            int customModelData = Integer.parseInt(ChatColor.stripColor(frontSide.getLine(1)));
            String[] costParts = ChatColor.stripColor(frontSide.getLine(2)).split(" ");
            if (costParts.length != 2) {
                player.sendMessage(ChatColor.RED + "看板の3行目が不正です（例: 3 diamond）");
                return;
            }
            int costAmount = Integer.parseInt(costParts[0]);
            Material costMaterial = Material.matchMaterial(costParts[1].toLowerCase().replace("s$", ""));
            if (costMaterial == null) {
                player.sendMessage(ChatColor.RED + "無効な素材名です: " + costParts[1]);
                return;
            }
            if (!player.getInventory().contains(costMaterial, costAmount)) {
                player.sendMessage(ChatColor.RED + String.valueOf(costAmount) + "個の" + costMaterial.name().toLowerCase() + "が不足しています。");
                return;
            }
            ItemStack costItem = new ItemStack(costMaterial, costAmount);
            player.getInventory().removeItem(costItem);
            ItemStack pass = new ItemStack(Material.PAPER, 1);
            ItemMeta meta = pass.getItemMeta();
            meta.setCustomModelData(customModelData);
            meta.setDisplayName(ChatColor.RESET + getPassName(customModelData));
            pass.setItemMeta(meta);
            player.getInventory().addItem(pass);
            player.sendMessage(ChatColor.GREEN + getPassName(customModelData) + "を購入しました！");
            player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_YES, 1.0F, 1.0F);
        } catch (NumberFormatException e) {
            player.sendMessage(ChatColor.RED + "看板の2行目または3行目の形式が不正です。");
        } catch (Exception e) {
            player.sendMessage(ChatColor.RED + "購入処理中にエラーが発生しました。");
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onSignChange(SignChangeEvent event) {
        String line1 = ChatColor.stripColor(event.getLine(0));
        Player player = event.getPlayer();
        if (isSpecialSignHeader(line1) && !player.hasPermission("toropassicsystem.admin")) {
            player.sendMessage(ChatColor.RED + "特殊看板を設置する権限がありません。");
            event.setCancelled(true);
            return;
        }
        handleSpecialSignSetup(event, line1, player);
        // 権限のあるプレイヤーが作った特殊看板に印を付ける (看板の内容が確定した次のティックで書き込む)
        if (isSpecialSignHeader(line1) && !event.isCancelled()) {
            Block block = event.getBlock();
            Bukkit.getScheduler().runTask(this, () -> markAuthorizedSign(block));
        }
    }

    private void markAuthorizedSign(Block block) {
        if (!(block.getState() instanceof Sign sign)) return;
        sign.getPersistentDataContainer().set(signMarkKey, PersistentDataType.STRING, signMarkValue(block));
        sign.update(true, false);
    }

    // 印には看板の位置を入れ、構造物の貼り付けなどで別の場所へ複製された看板を区別する
    private static String signMarkValue(Block block) {
        return block.getWorld().getUID() + ":" + block.getX() + ":" + block.getY() + ":" + block.getZ();
    }

    // signs.require-authorized-mark が有効なら、印のない金銭の看板 (チャージ・物販・販売) を使えないようにする
    private boolean isSignUsable(Sign sign, Player player) {
        if (!requireSignMark) return true;
        String mark = sign.getPersistentDataContainer().get(signMarkKey, PersistentDataType.STRING);
        if (signMarkValue(sign.getBlock()).equals(mark)) return true;
        player.sendMessage(ChatColor.RED + "この看板は正しく設定されていません。管理者に連絡してください。");
        return false;
    }

    private void handleSpecialSignSetup(SignChangeEvent event, String line1, Player player) {
        switch (line1) {
            case "[入場]":
            case "[出場]":
            case "[チャージ]":
            case "[入出場]":
                if (ChatColor.stripColor(event.getLine(1)).isEmpty()) {
                    player.sendMessage(ChatColor.RED + "必要な情報を2行目に記載してください。");
                    event.setCancelled(true);
                } else player.sendMessage(ChatColor.GREEN + "看板が正常に設定されました。");
                break;
            case "[定期券情報削除]":
            case "[定期券情報照会]":
                event.setLine(0, line1);
                player.sendMessage(ChatColor.GREEN + line1 + "看板が正常に設定されました。");
                break;
            case "[物販]":
                handleShopSignSetup(event, player);
                break;
            case "[乗換]":
                handleTransferSignSetup(event, player);
                break;
        }
    }

    private void handleShopSignSetup(SignChangeEvent event, Player player) {
        String storeName = ChatColor.stripColor(event.getLine(1));
        String amountStr = ChatColor.stripColor(event.getLine(2));
        if (storeName.isEmpty() || amountStr.isEmpty()) {
            player.sendMessage(ChatColor.RED + "2行目に店舗名、3行目に金額を記載してください。");
            event.setCancelled(true);
            return;
        }
        try {
            int amount = Integer.parseInt(amountStr);
            if (amount <= 0) {
                player.sendMessage(ChatColor.RED + "金額は正の整数で指定してください。");
                event.setCancelled(true);
                return;
            }
            event.setLine(0, "[物販]");
            event.setLine(1, storeName);
            event.setLine(2, String.valueOf(amount));
            org.bukkit.block.Sign signState = (org.bukkit.block.Sign) event.getBlock().getState();
            SignSide backSide = signState.getSide(Side.BACK);
            backSide.setLine(1, ChatColor.GREEN + "[IC]");
            backSide.setLine(2, "ここにタッチ");
            signState.update(true);
            player.sendMessage(ChatColor.GREEN + "物販看板が正常に設定されました。");
            player.sendMessage(ChatColor.GREEN + "裏面に支払い情報が書き込まれました。");
        } catch (NumberFormatException e) {
            player.sendMessage(ChatColor.RED + "3行目の金額が正しい数値ではありません。");
            event.setCancelled(true);
        }
    }

    private void handleTransferSignSetup(SignChangeEvent event, Player player) {
        String departureStation = ChatColor.stripColor(event.getLine(1));
        String transferStation = ChatColor.stripColor(event.getLine(2));
        String companyCodesLine = ChatColor.stripColor(event.getLine(3));
        if (departureStation.isEmpty() || transferStation.isEmpty()) {
            player.sendMessage(ChatColor.RED + "2行目に出発駅、3行目に乗り換え先駅を記載してください。");
            event.setCancelled(true);
            return;
        }
        event.setLine(0, "[乗換]");
        event.setLine(1, departureStation);
        event.setLine(2, transferStation);
        if (!companyCodesLine.isEmpty()) {
            String[] codes = companyCodesLine.split(" ");
            List<String> validCodes = new ArrayList<>();
            for (String code : codes) {
                try {
                    int codeNum = Integer.parseInt(code);
                    if (codeNum < 0 || codeNum > 99) {
                        player.sendMessage(ChatColor.RED + "事業者コードは00から99までの整数で指定してください。無効な値: " + code);
                        event.setCancelled(true);
                        return;
                    }
                    validCodes.add(String.format("%02d", codeNum));
                } catch (NumberFormatException e) {
                    return;
                }
            }
            event.setLine(3, String.join(" ", validCodes));
        }
        player.sendMessage(ChatColor.GREEN + "乗換看板が正常に設定されました。");
    }

    @EventHandler
    public void onVehicleEnter(VehicleEnterEvent event) {
        if (event.getEntered() instanceof Player player) {
            StationData data = playerData.computeIfAbsent(player.getUniqueId(), k -> new StationData());
            if (data.isInStation) data.setRideStartLocation(player.getLocation());
        }
    }

    @EventHandler
    public void onVehicleMove(VehicleMoveEvent event) {
        if (!event.getVehicle().getPassengers().isEmpty() && event.getVehicle().getPassengers().get(0) instanceof Player player) {
            StationData data = playerData.computeIfAbsent(player.getUniqueId(), k -> new StationData());
            if (data.isInStation) data.addTravelDistance(event.getTo());
        }
    }

    public static int computeCheckDigit(int ticketType, int companyCode, int purchaseAmount, String expiryDate) {
        return CardCodes.computeCheckDigit(ticketType, companyCode, purchaseAmount, expiryDate);
    }

    private static boolean isSpecialSignHeader(String header) {
        if (header == null) return false;
        return switch (header) {
            case "[入場]", "[出場]", "[入出場]", "[チャージ]", "[残高確認]", "[強制出場]",
                 "[残額調整]", "[定期券情報削除]", "[定期券情報照会]", "[乗換]", "[物販]", "[TOROpass販売]" -> true;
            default -> false;
        };
    }

    private static boolean isValidICCard(ItemStack item) {
        return item != null && item.getType() == Material.PAPER && item.getItemMeta().hasCustomModelData();
    }

    // 新しいメソッド：DBの定期券情報で判定
    private TicketValidationResult validateTicketFromDB(DatabaseManager.UserData user, StationData data, String exitStation, String companyCodesLine) {
        TicketValidationResult result = new TicketValidationResult();
        
        if (user.ticketType == null || user.companyCode == null || user.checkDigit == null || user.purchaseAmount == null || user.expiryDate == null) {
            return result; // 定期券情報なし
        }

        SimpleDateFormat sdfDate = new SimpleDateFormat("yyyyMMdd");
        String expStr = sdfDate.format(new Date(user.expiryDate));
        if (user.checkDigit != computeCheckDigit(user.ticketType, user.companyCode, user.purchaseAmount, expStr)) {
            return result; // チェックデジット不整合
        }
        
        result.ticketType = user.ticketType;
        result.expiryDateLong = user.expiryDate != null ? user.expiryDate : 0L;

        // 有効期限チェック
        if (user.expiryDate != null && new Date(user.expiryDate).before(new Date())) {
            result.isExpired = true;
        }

        if (result.isExpired) return result;

        String companyCodeStr = String.format("%02d", user.companyCode);
        List<String> exitCompanyCodes = data.validateCompanyCodes(companyCodesLine);

        // 定期券種別による判定
        if (user.ticketType == 1 && (user.companyCode == 99 || (data.entryCompanyCodes.contains(companyCodeStr) && exitCompanyCodes.contains(companyCodeStr)))) {
            result.isFree = true;
            result.ticketText = user.companyCode == 99 ? "定期利用:TORO全線" : "定期利用:全線定期";
        } else if (user.ticketType == 4 && !result.isExpired) {
            if (user.companyCode == 99) {
                result.isFree = true;
                result.ticketText = "定期利用:TORO全線";
            } else {
                SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMdd");
                Date purchaseDate = new Date(user.expiryDate);
                Date today = new Date();
                Calendar todayCalendar = Calendar.getInstance();
                todayCalendar.setTime(today);
                todayCalendar.set(Calendar.HOUR_OF_DAY, 23);
                todayCalendar.set(Calendar.MINUTE, 59);
                todayCalendar.set(Calendar.SECOND, 59);
                todayCalendar.set(Calendar.MILLISECOND, 999);
                if (data.entryCompanyCodes.contains(companyCodeStr) && exitCompanyCodes.contains(companyCodeStr) &&
                        sdf.format(purchaseDate).equals(sdf.format(today)) && !today.after(new Date(user.expiryDate))) {
                    result.isFree = true;
                    result.ticketText = "定期利用:1日乗車券";
                }
            }
        } else if ((user.ticketType == 2 || user.ticketType == 3) && user.routeStart != null && user.routeEnd != null
                && data.entryCompanyCodes.contains(companyCodeStr) && exitCompanyCodes.contains(companyCodeStr) &&
                (user.routeStart.equals(data.stationName) && user.routeEnd.equals(exitStation) || user.routeStart.equals(exitStation) && user.routeEnd.equals(data.stationName))) {
            result.isFree = true;
            result.ticketText = "定期利用:通勤･通学定期";
        }
        
        return result;
    }

    private static class TicketValidationResult {
        boolean isFree = false;
        boolean isExpired = false;
        String ticketText = "";
        Integer ticketType = null;
        long expiryDateLong = 0L;
    }

    public void openFenceGate(String frontLine4, PlayerInteractEvent event) {
        if (frontLine4 == null) return;
        Matcher matcher = Pattern.compile("\\(\\s*-?\\d+\\s*,\\s*-?\\d+\\s*,\\s*-?\\d+\\s*,\\s*\\d+\\s*\\)").matcher(frontLine4);
        if (!matcher.find()) return;

        String[] parts = matcher.group().replaceAll("[()\\s]", "").split(",");
        int offsetX = Integer.parseInt(parts[0]);
        int offsetY = Integer.parseInt(parts[1]);
        int offsetZ = Integer.parseInt(parts[2]);
        int seconds = Integer.parseInt(parts[3]);

        Location signLoc = event.getClickedBlock().getState().getLocation();
        Location targetLoc = signLoc.clone().add(offsetX, offsetY, offsetZ);
        Block targetBlock = targetLoc.getBlock();

        if (!targetBlock.getType().name().endsWith("_FENCE_GATE")) return;
        BlockData blockData = targetBlock.getBlockData();
        if (!(blockData instanceof Openable gate) || gate.isOpen()) return;

        gate.setOpen(true);
        targetBlock.setBlockData(gate);
        Bukkit.getScheduler().runTaskLater(this, () -> {
            Block b = targetLoc.getBlock();
            if (b.getType().name().endsWith("_FENCE_GATE")) {
                BlockData bd = b.getBlockData();
                if (bd instanceof Openable g) {
                    g.setOpen(false);
                    b.setBlockData(g);
                }
            }
        }, seconds * 20L);
    }

    private String getPassName(int customModelData) {
        String key = String.valueOf(customModelData);
        if (getConfig().contains("iccard-names." + key)) {
            return getConfig().getString("iccard-names." + key);
        }
        return getConfig().getString("iccard-names.default", "TOROpass");
    }

    private String formatDistance(double meters) {
        return meters >= 1000 ? String.format("%.3f", meters / 1000).replaceAll("0*$", "").replaceAll("\\.$", "") + "km" :
                String.format("%.0f", meters) + "m";
    }

    public static class StationData {
        public boolean isInStation = false;
        public int balance = 0;
        public String stationName = "";
        private Location rideStartLocation;
        private double travelDistance = 0;
        public Integer autoChargeThreshold = null;
        public Integer autoChargeAmount = null;
        private List<String> entryCompanyCodes = new ArrayList<>();

        public void enterStation(String stationName, String companyCodesLine) {
            this.isInStation = true;
            this.stationName = stationName;
            this.travelDistance = 0;
            this.entryCompanyCodes = validateCompanyCodes(companyCodesLine);
        }

        public void exitStation() {
            this.isInStation = false;
            this.stationName = "";
            this.travelDistance = 0;
            this.entryCompanyCodes.clear();
        }

        public List<String> validateCompanyCodes(String line) {
            List<String> validCodes = new ArrayList<>();
            if (line == null || line.trim().isEmpty()) return validCodes;
            String[] codes = line.split("[,\\s]+");
            for (String code : codes) {
                try {
                    int codeNum = Integer.parseInt(code);
                    if (codeNum >= 0 && codeNum <= 99) validCodes.add(String.format("%02d", codeNum));
                } catch (NumberFormatException ignored) {}
            }
            return validCodes;
        }

        public void setRideStartLocation(Location location) {
            this.rideStartLocation = location;
        }

        public void addTravelDistance(Location newLocation) {
            if (rideStartLocation != null) {
                travelDistance += rideStartLocation.distance(newLocation);
                rideStartLocation = newLocation;
            }
        }

        public int calculateFare() {
            return (int) (travelDistance * 0.2);
        }
    }

    private static ItemStack findValidICCard(Player player) {
        for (ItemStack item : player.getInventory().getContents()) {
            if (isValidICCard(item)) {
                return item;
            }
        }
        return null;
    }
}
