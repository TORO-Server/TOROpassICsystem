package prj.salmon.toropassicsystem;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import prj.salmon.toropassicsystem.api.WebPasswords;
import prj.salmon.toropassicsystem.types.PaymentHistory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.*;

class DatabaseManagerTest {
    private static final long GRACE_SECONDS = 30L * 86400L;

    @TempDir
    Path tempDir;

    private Path dbFile;
    private DatabaseManager db;
    private final UUID steve = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @BeforeEach
    void setUp() {
        dbFile = tempDir.resolve("toropass.db");
    }

    @AfterEach
    void tearDown() throws SQLException {
        if (db != null) db.close();
    }

    private DatabaseManager open() throws SQLException {
        // テストでは fsync を省く (実際の動作は既定の synchronous=FULL)
        db = new DatabaseManager(dbFile + "?synchronous=OFF");
        db.connect(GRACE_SECONDS);
        return db;
    }

    private Connection raw() throws SQLException {
        return DriverManager.getConnection("jdbc:sqlite:" + dbFile + "?synchronous=OFF");
    }

    private static PaymentHistory history(String from, int amount) {
        return PaymentHistory.build(from, "", amount, 0, System.currentTimeMillis() / 1000L, "test");
    }

    private DatabaseManager withUser(int balance) throws SQLException {
        open();
        db.ensureUser(steve, "Steve");
        if (balance != 0) db.setBalance(steve, balance, history("Special::balanceAdjustment", balance));
        return db;
    }

    // 以前の版が作る形式のデータベースを用意する
    private void createLegacyDatabase() throws SQLException {
        try (Connection c = raw(); Statement s = c.createStatement()) {
            s.executeUpdate("""
                CREATE TABLE users (
                    uuid TEXT PRIMARY KEY, playername TEXT, balance INTEGER, webChargePassword TEXT,
                    autoChargeThreshold INTEGER, autoChargeAmount INTEGER, lastupdate INTEGER,
                    ticketType INTEGER, companyCode INTEGER, purchaseAmount INTEGER, expiryDate INTEGER,
                    checkDigit INTEGER, routeStart TEXT, routeEnd TEXT
                )
            """);
            s.executeUpdate("""
                CREATE TABLE payment_history (
                    id INTEGER PRIMARY KEY AUTOINCREMENT, uuid TEXT, timestamp INTEGER, "from" TEXT, "to" TEXT,
                    amount INTEGER, balance INTEGER, description TEXT
                )
            """);
            s.executeUpdate("INSERT INTO users VALUES ('" + steve + "', 'Steve', 12345, 'Ab3dEf7h', 100, 500, 1700000000, 2, 5, 800, 1798729199999, 7, 'StationA', 'StationB')");
            s.executeUpdate("INSERT INTO users VALUES ('00000000-0000-0000-0000-000000000002', 'Alex', 0, NULL, NULL, NULL, 1700000000, NULL, NULL, NULL, NULL, NULL, NULL, NULL)");
            for (int i = 0; i < 25; i++) {
                s.executeUpdate("INSERT INTO payment_history (uuid, timestamp, \"from\", \"to\", amount, balance, description) VALUES ('" + steve + "', " + (1700000000 + i) + ", 'StationA[00]', 'StationB[00]', -10, " + (12345 - i * 10) + ", '出場')");
            }
        }
    }

    @Test
    void migrationKeepsBalancesTicketsAndHistory() throws SQLException {
        createLegacyDatabase();
        open();

        DatabaseManager.UserData user = db.getUser(steve);
        assertEquals("Steve", user.playername);
        assertEquals(12345, user.balance);
        assertEquals(100, user.autoChargeThreshold);
        assertEquals(500, user.autoChargeAmount);
        assertEquals(1700000000L, user.lastupdate);
        assertEquals(2, user.ticketType);
        assertEquals(5, user.companyCode);
        assertEquals(800, user.purchaseAmount);
        assertEquals(1798729199999L, user.expiryDate);
        assertEquals(7, user.checkDigit);
        assertEquals("StationA", user.routeStart);
        assertEquals("StationB", user.routeEnd);

        List<PaymentHistory> rows = db.getHistory(steve, 1000);
        assertEquals(25, rows.size());
        assertEquals(1700000024L, rows.get(0).timestamp);
        assertEquals(12345 - 24 * 10, rows.get(0).balance);
        assertEquals("StationA[00]", rows.get(0).from);

        // パスワードのないユーザーも残る
        assertEquals(0, db.getUser(UUID.fromString("00000000-0000-0000-0000-000000000002")).balance);
    }

    @Test
    void migrationReplacesPlaintextPasswordWithHash() throws Exception {
        createLegacyDatabase();
        long before = System.currentTimeMillis() / 1000L;
        open();

        DatabaseManager.UserData user = db.getUser(steve);
        assertTrue(WebPasswords.matches(user.webPasswordHash, "Ab3dEf7h"));
        assertFalse(WebPasswords.matches(user.webPasswordHash, "wrong"));
        assertTrue(user.webPasswordExpiresAt >= before + GRACE_SECONDS);
        assertTrue(user.webPasswordExpiresAt <= System.currentTimeMillis() / 1000L + GRACE_SECONDS);

        try (Connection c = raw(); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM users WHERE webChargePassword IS NOT NULL")) {
            rs.next();
            assertEquals(0, rs.getInt(1));
        }
        // 平文がファイルの空き領域にも残らない
        db.close();
        db = null;
        assertFalse(new String(Files.readAllBytes(dbFile), StandardCharsets.ISO_8859_1).contains("Ab3dEf7h"));
    }

    @Test
    void migrationIsRepeatableAndDoesNotExtendTheGrace() throws Exception {
        createLegacyDatabase();
        open();
        Long firstExpiry = db.getUser(steve).webPasswordExpiresAt;
        String firstHash = db.getUser(steve).webPasswordHash;
        db.close();

        open();
        DatabaseManager.UserData user = db.getUser(steve);
        assertEquals(12345, user.balance);
        assertEquals(firstHash, user.webPasswordHash);
        assertEquals(firstExpiry, user.webPasswordExpiresAt);
        assertEquals(25, db.getHistory(steve, 1000).size());
    }

    @Test
    void passwordWrittenByAnOlderVersionIsMigratedOnNextStart() throws Exception {
        createLegacyDatabase();
        open();
        db.close();
        db = null;

        // 以前の版へ戻している間に発行されたパスワード
        try (Connection c = raw(); Statement s = c.createStatement()) {
            s.executeUpdate("UPDATE users SET webChargePassword = 'Zz9yXw8v' WHERE uuid = '" + steve + "'");
        }
        open();
        assertTrue(WebPasswords.matches(db.getUser(steve).webPasswordHash, "Zz9yXw8v"));
        assertFalse(WebPasswords.matches(db.getUser(steve).webPasswordHash, "Ab3dEf7h"));
    }

    @Test
    void migrationAddsIndexes() throws SQLException {
        createLegacyDatabase();
        open();
        db.close();
        db = null;
        List<String> indexes = new ArrayList<>();
        try (Connection c = raw(); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT name FROM sqlite_master WHERE type = 'index'")) {
            while (rs.next()) indexes.add(rs.getString(1));
        }
        assertTrue(indexes.contains("idx_history_uuid_ts"));
        assertTrue(indexes.contains("idx_users_playername"));

        // 履歴の検索がインデックスを使う
        try (Connection c = raw(); PreparedStatement ps = c.prepareStatement("EXPLAIN QUERY PLAN SELECT * FROM payment_history WHERE uuid = ? ORDER BY timestamp DESC, id DESC LIMIT 10")) {
            ps.setString(1, steve.toString());
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                assertTrue(rs.getString("detail").contains("idx_history_uuid_ts"));
            }
        }
    }

    @Test
    void newDatabaseHasTheSameShape() throws SQLException {
        open();
        DatabaseManager.UserData user = db.ensureUser(steve, "Steve");
        assertEquals(0, user.balance);
        assertNull(user.webPasswordHash);
        assertNull(user.webPasswordExpiresAt);
        assertEquals(steve, db.getUUIDByPlayerName("Steve"));
    }

    @Test
    void ensureUserRenamesWithoutTouchingOtherColumns() throws SQLException {
        withUser(500);
        db.updateAutoCharge(steve, 100, 200);
        DatabaseManager.UserData renamed = db.ensureUser(steve, "Steve2");
        assertEquals("Steve2", renamed.playername);
        assertEquals(500, db.getUser(steve).balance);
        assertEquals(100, db.getUser(steve).autoChargeThreshold);
        assertEquals(steve, db.getUUIDByPlayerName("Steve2"));
        assertNull(db.getUUIDByPlayerName("Steve"));
    }

    @Test
    void debitIsConditionalOnBalance() throws SQLException {
        withUser(100);
        assertEquals(OptionalInt.of(70), db.debit(steve, 30, history("web::pay", -30)));
        assertEquals(OptionalInt.empty(), db.debit(steve, 71, history("web::pay", -71)));
        assertEquals(70, db.getUser(steve).balance);
        assertEquals(OptionalInt.of(0), db.debit(steve, 70, history("web::pay", -70)));
        assertEquals(OptionalInt.empty(), db.debit(steve, 1, history("web::pay", -1)));
        assertEquals(OptionalInt.empty(), db.debit(UUID.randomUUID(), 1, history("web::pay", -1)));
        assertThrows(IllegalArgumentException.class, () -> db.debit(steve, -1, history("web::pay", 1)));
    }

    @Test
    void creditRespectsTheMaximumWithoutOverflow() throws SQLException {
        withUser(19000);
        assertEquals(OptionalInt.of(20000), db.credit(steve, 1000, DatabaseManager.MAX_BALANCE, history("Special::webcharge", 1000)));
        assertEquals(OptionalInt.empty(), db.credit(steve, 1, DatabaseManager.MAX_BALANCE, history("Special::webcharge", 1)));
        // int の範囲を超える合計でも負の残高にならない
        assertEquals(OptionalInt.empty(), db.credit(steve, Integer.MAX_VALUE, DatabaseManager.MAX_BALANCE, history("Special::webcharge", Integer.MAX_VALUE)));
        assertEquals(20000, db.getUser(steve).balance);
        assertThrows(IllegalArgumentException.class, () -> db.credit(steve, 0, DatabaseManager.MAX_BALANCE, history("Special::webcharge", 0)));
        assertThrows(IllegalArgumentException.class, () -> db.credit(steve, -5, DatabaseManager.MAX_BALANCE, history("Special::webcharge", -5)));
    }

    @Test
    void historyRecordsTheBalanceAfterTheChange() throws SQLException {
        withUser(100);
        db.credit(steve, 50, DatabaseManager.MAX_BALANCE, history("Special::webcharge", 50));
        db.debit(steve, 20, history("web::pay", -20));
        List<PaymentHistory> rows = db.getHistory(steve, 2);
        assertEquals("web::pay", rows.get(0).from);
        assertEquals(130, rows.get(0).balance);
        assertEquals("Special::webcharge", rows.get(1).from);
        assertEquals(150, rows.get(1).balance);
    }

    @Test
    void balanceChangeRollsBackWhenHistoryCannotBeWritten() throws SQLException {
        withUser(100);
        try (Connection c = raw(); Statement s = c.createStatement()) {
            s.executeUpdate("CREATE TRIGGER block_history BEFORE INSERT ON payment_history BEGIN SELECT RAISE(ABORT, 'blocked'); END");
        }
        assertThrows(SQLException.class, () -> db.debit(steve, 30, history("web::pay", -30)));
        assertEquals(100, db.getUser(steve).balance);
        assertThrows(SQLException.class, () -> db.credit(steve, 30, DatabaseManager.MAX_BALANCE, history("Special::webcharge", 30)));
        assertEquals(100, db.getUser(steve).balance);
        DatabaseManager.TicketData ticket = new DatabaseManager.TicketData(1, 0, 30, 1798729199999L, 3, null, null);
        assertThrows(SQLException.class, () -> db.purchaseTicket(steve, ticket, history("Special::writecard", -30)));
        assertEquals(100, db.getUser(steve).balance);
        assertNull(db.getUser(steve).ticketType);

        // トランザクションの後も接続は使える
        try (Connection c = raw(); Statement s = c.createStatement()) {
            s.executeUpdate("DROP TRIGGER block_history");
        }
        assertEquals(OptionalInt.of(70), db.debit(steve, 30, history("web::pay", -30)));
    }

    @Test
    void concurrentDebitsNeverOverspend() throws Exception {
        withUser(1000);
        int attempts = 64;
        int historyBefore = db.getHistory(steve, 10000).size();
        ExecutorService pool = Executors.newFixedThreadPool(16);
        try {
            List<Future<OptionalInt>> results = new ArrayList<>();
            for (int i = 0; i < attempts; i++) {
                results.add(pool.submit(() -> db.debit(steve, 100, history("web::pay", -100))));
            }
            int succeeded = 0;
            for (Future<OptionalInt> result : results) {
                if (result.get().isPresent()) succeeded++;
            }
            assertEquals(10, succeeded);
        } finally {
            pool.shutdownNow();
        }
        assertEquals(0, db.getUser(steve).balance);
        assertEquals(historyBefore + 10, db.getHistory(steve, 10000).size());
    }

    @Test
    void concurrentCreditsAndDebitsLoseNoUpdates() throws Exception {
        withUser(1000);
        ExecutorService pool = Executors.newFixedThreadPool(16);
        try {
            List<Future<OptionalInt>> results = new ArrayList<>();
            for (int i = 0; i < 100; i++) {
                results.add(pool.submit(() -> db.credit(steve, 7, DatabaseManager.MAX_BALANCE, history("Special::webcharge", 7))));
                results.add(pool.submit(() -> db.debit(steve, 3, history("web::pay", -3))));
            }
            for (Future<OptionalInt> result : results) assertTrue(result.get().isPresent());
        } finally {
            pool.shutdownNow();
        }
        assertEquals(1000 + 100 * 7 - 100 * 3, db.getUser(steve).balance);
    }

    @Test
    void columnUpdatesDoNotRewindTheBalance() throws SQLException {
        withUser(500);
        // 他のスレッドが読んだ後に残高が変わっても、列ごとの更新は残高を書き戻さない
        DatabaseManager.UserData stale = db.getUser(steve);
        db.debit(steve, 100, history("web::pay", -100));
        db.updateAutoCharge(stale.uuid, 100, 200);
        db.updateWebPassword(stale.uuid, WebPasswords.hashToken("TOKEN"), null);
        db.updatePlayerName(stale.uuid, "Steve2");
        db.clearTicket(stale.uuid);
        assertEquals(400, db.getUser(steve).balance);
    }

    @Test
    void autoChargeAppliesOnlyBelowTheThreshold() throws SQLException {
        withUser(150);
        assertEquals(OptionalInt.empty(), db.autoCharge(steve));

        db.updateAutoCharge(steve, 100, 500);
        assertEquals(OptionalInt.empty(), db.autoCharge(steve));

        db.debit(steve, 60, history("web::pay", -60));
        assertEquals(OptionalInt.of(590), db.autoCharge(steve));
        PaymentHistory latest = db.getHistory(steve, 1).get(0);
        assertEquals("Special::autocharge", latest.from);
        assertEquals(500, latest.amount);
        assertEquals(590, latest.balance);
        assertEquals(OptionalInt.empty(), db.autoCharge(steve));

        db.updateAutoCharge(steve, null, null);
        db.debit(steve, 590, history("web::pay", -590));
        assertEquals(OptionalInt.empty(), db.autoCharge(steve));
    }

    @Test
    void autoChargeDoesNotExceedTheMaximum() throws SQLException {
        withUser(19000);
        db.updateAutoCharge(steve, 19500, 5000);
        assertEquals(OptionalInt.empty(), db.autoCharge(steve));
        assertEquals(19000, db.getUser(steve).balance);
    }

    @Test
    void purchaseTicketIsAllOrNothing() throws SQLException {
        withUser(500);
        DatabaseManager.TicketData expensive = new DatabaseManager.TicketData(2, 5, 800, 1798729199999L, 7, "StationA", "StationB");
        assertEquals(OptionalInt.empty(), db.purchaseTicket(steve, expensive, history("Special::writecard", -800)));
        assertEquals(500, db.getUser(steve).balance);
        assertNull(db.getUser(steve).ticketType);

        DatabaseManager.TicketData ticket = new DatabaseManager.TicketData(2, 5, 300, 1798729199999L, 7, "StationA", "StationB");
        assertEquals(OptionalInt.of(200), db.purchaseTicket(steve, ticket, history("Special::writecard", -300)));
        DatabaseManager.UserData user = db.getUser(steve);
        assertEquals(200, user.balance);
        assertEquals(2, user.ticketType);
        assertEquals(5, user.companyCode);
        assertEquals(300, user.purchaseAmount);
        assertEquals(1798729199999L, user.expiryDate);
        assertEquals(7, user.checkDigit);
        assertEquals("StationA", user.routeStart);
        assertEquals("StationB", user.routeEnd);
        assertEquals(200, db.getHistory(steve, 1).get(0).balance);

        db.clearTicket(steve);
        user = db.getUser(steve);
        assertNull(user.ticketType);
        assertNull(user.companyCode);
        assertNull(user.purchaseAmount);
        assertNull(user.expiryDate);
        assertNull(user.checkDigit);
        assertNull(user.routeStart);
        assertNull(user.routeEnd);
        assertEquals(200, user.balance);
    }

    @Test
    void historyPagesAreOrderedAndBounded() throws SQLException {
        withUser(0);
        for (int i = 0; i < 10; i++) {
            db.addHistory(steve, PaymentHistory.build("S" + i, "", -1, 0, 1000 + i / 5, ""));
        }
        List<PaymentHistory> first = db.getHistory(steve, 4, 0);
        assertEquals(List.of("S9", "S8", "S7", "S6"), first.stream().map(h -> h.from).toList());
        List<PaymentHistory> second = db.getHistory(steve, 4, 4);
        assertEquals(List.of("S5", "S4", "S3", "S2"), second.stream().map(h -> h.from).toList());
        assertEquals(List.of("S1", "S0"), db.getHistory(steve, 4, 8).stream().map(h -> h.from).toList());
        assertEquals(0, db.getHistory(steve, 4, 12).size());
    }

    @Test
    void travelSpendingCountsOnlyTravel() throws SQLException {
        withUser(0);
        db.addHistory(steve, PaymentHistory.build("StationA[00]", "StationB[00]", -30, 0, 1, ""));
        db.addHistory(steve, PaymentHistory.build("StationB[00]", "StationC[00]", -20, 0, 2, ""));
        db.addHistory(steve, PaymentHistory.build("Special::charge", "", 1000, 0, 3, ""));
        db.addHistory(steve, PaymentHistory.build("Shop::Store", "", -500, 0, 4, ""));
        assertEquals(50, db.getTravelSpending(steve));
        assertEquals(0, db.getTravelSpending(UUID.randomUUID()));
    }

    @Test
    void passwordRevocationClearsTheHash() throws SQLException {
        withUser(0);
        db.updateWebPassword(steve, WebPasswords.hashToken("TOKEN"), 2000000000L);
        assertEquals(2000000000L, db.getUser(steve).webPasswordExpiresAt);
        db.updateWebPassword(steve, null, null);
        assertNull(db.getUser(steve).webPasswordHash);
        assertNull(db.getUser(steve).webPasswordExpiresAt);
    }
}
