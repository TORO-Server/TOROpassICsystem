package prj.salmon.toropassicsystem;

import prj.salmon.toropassicsystem.api.WebPasswords;
import prj.salmon.toropassicsystem.types.PaymentHistory;

import java.sql.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * TOROpass の SQLite データベース。
 *
 * 1 つの接続をメインスレッドと API のスレッドで共有するので、メソッド単位で synchronized にしている。
 * 残高を変える処理は残高の条件付き更新と履歴の追加を 1 つのトランザクションで行う。
 * 全列をまとめて書き戻すと、読んだ後に他のスレッドが変えた値を巻き戻してしまうので、更新は列ごとのメソッドで行う。
 */
public class DatabaseManager {
    public static final int MAX_BALANCE = 20000;

    private static final String USER_COLUMNS = "uuid, playername, balance, webPasswordHash, webPasswordExpiresAt, autoChargeThreshold, autoChargeAmount, lastupdate, ticketType, companyCode, purchaseAmount, expiryDate, checkDigit, routeStart, routeEnd";

    private final String dbPath;
    private Connection conn;

    public DatabaseManager(String dbPath) {
        this.dbPath = dbPath;
    }

    // legacyPasswordSeconds: 以前の平文パスワードをハッシュに移行したとき、あと何秒有効にするか
    public synchronized void connect(long legacyPasswordSeconds) throws SQLException {
        conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("PRAGMA busy_timeout = 5000");
            // 消したパスワードの平文をファイルの空き領域に残さない
            stmt.execute("PRAGMA secure_delete = ON");
        }
        createTables();
        migrate(legacyPasswordSeconds);
    }

    private synchronized void createTables() throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            stmt.executeUpdate("""
                CREATE TABLE IF NOT EXISTS users (
                    uuid TEXT PRIMARY KEY,
                    playername TEXT,
                    balance INTEGER,
                    webChargePassword TEXT,
                    autoChargeThreshold INTEGER,
                    autoChargeAmount INTEGER,
                    lastupdate INTEGER,
                    ticketType INTEGER,
                    companyCode INTEGER,
                    purchaseAmount INTEGER,
                    expiryDate INTEGER,
                    checkDigit INTEGER,
                    routeStart TEXT,
                    routeEnd TEXT
                )
            """);
            stmt.executeUpdate("""
                CREATE TABLE IF NOT EXISTS payment_history (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    uuid TEXT,
                    timestamp INTEGER,
                    "from" TEXT,
                    "to" TEXT,
                    amount INTEGER,
                    balance INTEGER,
                    description TEXT
                )
            """);
        }
    }

    // 既存のデータを変えない追加だけのマイグレーション。起動のたびに実行してよい
    // (以前の版へ戻した間に平文で発行されたパスワードも、次の起動でハッシュに移行する)
    private void migrate(long legacyPasswordSeconds) throws SQLException {
        inTransaction(() -> {
            addColumnIfMissing("users", "webPasswordHash", "TEXT");
            addColumnIfMissing("users", "webPasswordExpiresAt", "INTEGER");
            try (Statement stmt = conn.createStatement()) {
                stmt.executeUpdate("CREATE INDEX IF NOT EXISTS idx_history_uuid_ts ON payment_history(uuid, timestamp)");
                stmt.executeUpdate("CREATE INDEX IF NOT EXISTS idx_users_playername ON users(playername)");
            }
            return null;
        });
        migrateLegacyPasswords(legacyPasswordSeconds);
    }

    private void addColumnIfMissing(String table, String column, String type) throws SQLException {
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (rs.next()) {
                if (column.equalsIgnoreCase(rs.getString("name"))) return;
            }
        }
        try (Statement stmt = conn.createStatement()) {
            stmt.executeUpdate("ALTER TABLE " + table + " ADD COLUMN " + column + " " + type);
        }
    }

    // 平文のパスワードをハッシュに置き換えて平文を消す。残高・定期券・履歴には触れない
    private void migrateLegacyPasswords(long legacyPasswordSeconds) throws SQLException {
        Map<String, String> plaintext = new HashMap<>();
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT uuid, webChargePassword FROM users WHERE webChargePassword IS NOT NULL")) {
            while (rs.next()) plaintext.put(rs.getString("uuid"), rs.getString("webChargePassword"));
        }
        if (plaintext.isEmpty()) return;

        // ハッシュの計算には時間がかかるので、起動時間を延ばしすぎないよう並列に計算する
        Map<String, String> hashes = new ConcurrentHashMap<>();
        plaintext.entrySet().parallelStream().forEach(e -> hashes.put(e.getKey(), WebPasswords.hashLegacyPassword(e.getValue())));

        long expiresAt = nowSeconds() + Math.max(0, legacyPasswordSeconds);
        inTransaction(() -> {
            for (Map.Entry<String, String> e : hashes.entrySet()) {
                execute("UPDATE users SET webPasswordHash = ?, webPasswordExpiresAt = ?, webChargePassword = NULL WHERE uuid = ?",
                        e.getValue(), expiresAt, e.getKey());
            }
            return null;
        });
    }

    // ユーザー情報取得
    public synchronized UserData getUser(UUID uuid) throws SQLException {
        String sql = "SELECT " + USER_COLUMNS + " FROM users WHERE uuid = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                UserData user = new UserData();
                user.uuid = uuid;
                user.playername = rs.getString("playername");
                user.balance = rs.getInt("balance");
                user.webPasswordHash = rs.getString("webPasswordHash");
                user.webPasswordExpiresAt = rs.getObject("webPasswordExpiresAt") != null ? rs.getLong("webPasswordExpiresAt") : null;
                user.autoChargeThreshold = rs.getObject("autoChargeThreshold") != null ? rs.getInt("autoChargeThreshold") : null;
                user.autoChargeAmount = rs.getObject("autoChargeAmount") != null ? rs.getInt("autoChargeAmount") : null;
                user.lastupdate = rs.getLong("lastupdate");
                user.ticketType = rs.getObject("ticketType") != null ? rs.getInt("ticketType") : null;
                user.companyCode = rs.getObject("companyCode") != null ? rs.getInt("companyCode") : null;
                user.purchaseAmount = rs.getObject("purchaseAmount") != null ? rs.getInt("purchaseAmount") : null;
                user.expiryDate = rs.getObject("expiryDate") != null ? rs.getLong("expiryDate") : null;
                user.checkDigit = rs.getObject("checkDigit") != null ? rs.getInt("checkDigit") : null;
                user.routeStart = rs.getString("routeStart");
                user.routeEnd = rs.getString("routeEnd");
                return user;
            }
        }
    }

    // ユーザーがいなければ残高 0 で作り、名前が変わっていれば更新して現在の情報を返す
    public synchronized UserData ensureUser(UUID uuid, String playerName) throws SQLException {
        UserData user = getUser(uuid);
        if (user == null) {
            execute("INSERT OR IGNORE INTO users (uuid, playername, balance, lastupdate) VALUES (?, ?, 0, ?)", uuid.toString(), playerName, nowSeconds());
            return getUser(uuid);
        }
        if (!Objects.equals(playerName, user.playername)) {
            updatePlayerName(uuid, playerName);
            user.playername = playerName;
        }
        return user;
    }

    public synchronized void updatePlayerName(UUID uuid, String playerName) throws SQLException {
        execute("UPDATE users SET playername = ?, lastupdate = ? WHERE uuid = ?", playerName, nowSeconds(), uuid.toString());
    }

    public synchronized void updateAutoCharge(UUID uuid, Integer threshold, Integer amount) throws SQLException {
        execute("UPDATE users SET autoChargeThreshold = ?, autoChargeAmount = ?, lastupdate = ? WHERE uuid = ?", threshold, amount, nowSeconds(), uuid.toString());
    }

    // hash が null なら Wallet のパスワードを無効にする
    public synchronized void updateWebPassword(UUID uuid, String hash, Long expiresAt) throws SQLException {
        execute("UPDATE users SET webPasswordHash = ?, webPasswordExpiresAt = ?, webChargePassword = NULL, lastupdate = ? WHERE uuid = ?", hash, expiresAt, nowSeconds(), uuid.toString());
    }

    // 定期券情報を消す
    public synchronized void clearTicket(UUID uuid) throws SQLException {
        writeTicket(uuid, new TicketData(null, null, null, null, null, null, null));
    }

    // 残高に加算し、履歴を同じトランザクションで記録する。
    // 加算後の残高が上限を超える場合や、ユーザーがいない場合は何も変えずに empty を返す
    public synchronized OptionalInt credit(UUID uuid, int amount, int maxBalance, PaymentHistory history) throws SQLException {
        if (amount <= 0) throw new IllegalArgumentException("amount must be positive");
        return inTransaction(() -> {
            int updated = execute("UPDATE users SET balance = balance + ?, lastupdate = ? WHERE uuid = ? AND balance + ? <= ?",
                    amount, nowSeconds(), uuid.toString(), amount, maxBalance);
            if (updated == 0) return OptionalInt.empty();
            return OptionalInt.of(recordHistoryWithBalance(uuid, history));
        });
    }

    // 残高から引き去り、履歴を同じトランザクションで記録する。
    // 残高が足りない場合や、ユーザーがいない場合は何も変えずに empty を返す
    public synchronized OptionalInt debit(UUID uuid, int amount, PaymentHistory history) throws SQLException {
        if (amount < 0) throw new IllegalArgumentException("amount must not be negative");
        return inTransaction(() -> {
            if (!conditionalDebit(uuid, amount)) return OptionalInt.empty();
            return OptionalInt.of(recordHistoryWithBalance(uuid, history));
        });
    }

    // 残高がオートチャージの閾値を下回っていればチャージして、新しい残高を返す。チャージしなければ empty
    public synchronized OptionalInt autoCharge(UUID uuid) throws SQLException {
        return inTransaction(() -> {
            long now = nowSeconds();
            int updated = execute("""
                UPDATE users SET balance = balance + autoChargeAmount, lastupdate = ?
                WHERE uuid = ? AND autoChargeThreshold IS NOT NULL AND autoChargeAmount IS NOT NULL
                  AND balance < autoChargeThreshold AND balance + autoChargeAmount <= ?
            """, now, uuid.toString(), MAX_BALANCE);
            if (updated == 0) return OptionalInt.empty();
            int amount;
            try (PreparedStatement ps = conn.prepareStatement("SELECT autoChargeAmount FROM users WHERE uuid = ?")) {
                ps.setString(1, uuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    amount = rs.getInt(1);
                }
            }
            PaymentHistory history = PaymentHistory.build("Special::autocharge", "", amount, 0, now, "オートチャージ");
            return OptionalInt.of(recordHistoryWithBalance(uuid, history));
        });
    }

    // 残高を指定の値にして、履歴を同じトランザクションで記録する (管理者の残額調整用)
    public synchronized OptionalInt setBalance(UUID uuid, int newBalance, PaymentHistory history) throws SQLException {
        return inTransaction(() -> {
            int updated = execute("UPDATE users SET balance = ?, lastupdate = ? WHERE uuid = ?", newBalance, nowSeconds(), uuid.toString());
            if (updated == 0) return OptionalInt.empty();
            return OptionalInt.of(recordHistoryWithBalance(uuid, history));
        });
    }

    // 購入金額を引き去って定期券情報を書き込み、履歴を同じトランザクションで記録する。残高が足りなければ empty
    public synchronized OptionalInt purchaseTicket(UUID uuid, TicketData ticket, PaymentHistory history) throws SQLException {
        return inTransaction(() -> {
            if (!conditionalDebit(uuid, ticket.purchaseAmount())) return OptionalInt.empty();
            writeTicket(uuid, ticket);
            return OptionalInt.of(recordHistoryWithBalance(uuid, history));
        });
    }

    private boolean conditionalDebit(UUID uuid, int amount) throws SQLException {
        return execute("UPDATE users SET balance = balance - ?, lastupdate = ? WHERE uuid = ? AND balance >= ?",
                amount, nowSeconds(), uuid.toString(), amount) > 0;
    }

    private void writeTicket(UUID uuid, TicketData ticket) throws SQLException {
        execute("""
            UPDATE users SET ticketType = ?, companyCode = ?, purchaseAmount = ?, expiryDate = ?, checkDigit = ?, routeStart = ?, routeEnd = ?, lastupdate = ?
            WHERE uuid = ?
        """, ticket.ticketType(), ticket.companyCode(), ticket.purchaseAmount(), ticket.expiryDate(), ticket.checkDigit(), ticket.routeStart(), ticket.routeEnd(), nowSeconds(), uuid.toString());
    }

    // 更新後の残高を履歴に入れて記録し、その残高を返す
    private int recordHistoryWithBalance(UUID uuid, PaymentHistory history) throws SQLException {
        int balance;
        try (PreparedStatement ps = conn.prepareStatement("SELECT balance FROM users WHERE uuid = ?")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                balance = rs.getInt(1);
            }
        }
        history.balance = balance;
        insertHistory(uuid, history);
        return balance;
    }

    // 履歴取得
    public synchronized List<PaymentHistory> getHistory(UUID uuid, int limit) throws SQLException {
        return getHistory(uuid, limit, 0);
    }

    public synchronized List<PaymentHistory> getHistory(UUID uuid, int limit, int offset) throws SQLException {
        List<PaymentHistory> list = new ArrayList<>();
        String sql = "SELECT * FROM payment_history WHERE uuid = ? ORDER BY timestamp DESC, id DESC LIMIT ? OFFSET ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            ps.setInt(2, limit);
            ps.setInt(3, offset);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    PaymentHistory h = new PaymentHistory();
                    h.from = rs.getString("from");
                    h.to = rs.getString("to");
                    h.amount = rs.getInt("amount");
                    h.balance = rs.getInt("balance");
                    h.timestamp = rs.getLong("timestamp");
                    h.description = rs.getString("description");
                    list.add(h);
                }
            }
        }
        return list;
    }

    // 履歴追加
    public synchronized void addHistory(UUID uuid, PaymentHistory h) throws SQLException {
        insertHistory(uuid, h);
    }

    private void insertHistory(UUID uuid, PaymentHistory h) throws SQLException {
        execute("""
            INSERT INTO payment_history (uuid, timestamp, "from", "to", amount, balance, description)
            VALUES (?, ?, ?, ?, ?, ?, ?)
        """, uuid.toString(), h.timestamp, h.from, h.to, h.amount, h.balance, h.description);
    }

    // プレイヤー名からUUID取得 (名前の変更で同じ名前の行が残っている場合は、最近更新された行を使う)
    public synchronized UUID getUUIDByPlayerName(String playername) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT uuid FROM users WHERE playername = ? ORDER BY lastupdate DESC LIMIT 1")) {
            ps.setString(1, playername);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                return UUID.fromString(rs.getString("uuid"));
            }
        }
    }

    public synchronized void close() throws SQLException {
        if (conn != null && !conn.isClosed()) conn.close();
    }

    // ランキング用: 全ユーザーの UUID と名前
    public synchronized Map<UUID, String> getAllPlayerNames() throws SQLException {
        Map<UUID, String> names = new HashMap<>();
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT uuid, playername FROM users")) {
            while (rs.next()) names.put(UUID.fromString(rs.getString("uuid")), rs.getString("playername"));
        }
        return names;
    }

    // ランキング用: 1 人の移動にかかった金額の合計 (チャージや物販などは含めない)
    public synchronized long getTravelSpending(UUID uuid) throws SQLException {
        String sql = """
            SELECT COALESCE(SUM(ABS(amount)), 0) FROM payment_history
            WHERE uuid = ? AND substr("from", 1, 9) <> 'Special::' AND substr("from", 1, 6) <> 'Shop::'
        """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0;
            }
        }
    }

    private int execute(String sql, Object... params) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) ps.setObject(i + 1, params[i]);
            return ps.executeUpdate();
        }
    }

    private synchronized <T> T inTransaction(SqlWork<T> work) throws SQLException {
        conn.setAutoCommit(false);
        try {
            T result = work.run();
            conn.commit();
            return result;
        } catch (SQLException | RuntimeException e) {
            conn.rollback();
            throw e;
        } finally {
            conn.setAutoCommit(true);
        }
    }

    private static long nowSeconds() {
        return System.currentTimeMillis() / 1000L;
    }

    private interface SqlWork<T> {
        T run() throws SQLException;
    }

    // 定期券情報
    public record TicketData(Integer ticketType, Integer companyCode, Integer purchaseAmount, Long expiryDate, Integer checkDigit, String routeStart, String routeEnd) {}

    // ユーザーデータ構造
    public static class UserData {
        public UUID uuid;
        public String playername;
        public int balance;
        // Wallet のパスワードのハッシュ (WebPasswords) と有効期限 (UNIX 秒、null なら無期限)
        public String webPasswordHash;
        public Long webPasswordExpiresAt;
        public Integer autoChargeThreshold;
        public Integer autoChargeAmount;
        public long lastupdate;
        // 定期券情報
        public Integer ticketType;
        public Integer companyCode;
        public Integer purchaseAmount;
        public Long expiryDate;
        public Integer checkDigit;
        public String routeStart;
        public String routeEnd;
    }
}
