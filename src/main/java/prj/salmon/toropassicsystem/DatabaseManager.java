package prj.salmon.toropassicsystem;

import prj.salmon.toropassicsystem.types.PaymentHistory;

import java.sql.*;
import java.util.*;

public class DatabaseManager {
    private final String dbPath;
    private Connection conn;

    public DatabaseManager(String dbPath) {
        this.dbPath = dbPath;
    }

    public void connect() throws SQLException {
        conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
        createTables();
    }

    private void createTables() throws SQLException {
        Statement stmt = conn.createStatement();
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
        stmt.close();
    }

    // ユーザー情報取得
    public UserData getUser(UUID uuid) throws SQLException {
        PreparedStatement ps = conn.prepareStatement("SELECT * FROM users WHERE uuid = ?");
        ps.setString(1, uuid.toString());
        ResultSet rs = ps.executeQuery();
        if (!rs.next()) return null;
        UserData user = new UserData();
        user.uuid = uuid;
        user.playername = rs.getString("playername");
        user.balance = rs.getInt("balance");
        user.webChargePassword = rs.getString("webChargePassword");
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
        rs.close();
        ps.close();
        return user;
    }

    // ユーザー情報追加・更新
    public void upsertUser(UserData user) throws SQLException {
        PreparedStatement ps = conn.prepareStatement("""
            INSERT INTO users (uuid, playername, balance, webChargePassword, autoChargeThreshold, autoChargeAmount, lastupdate, ticketType, companyCode, purchaseAmount, expiryDate, checkDigit, routeStart, routeEnd)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(uuid) DO UPDATE SET
                playername=excluded.playername,
                balance=excluded.balance,
                webChargePassword=excluded.webChargePassword,
                autoChargeThreshold=excluded.autoChargeThreshold,
                autoChargeAmount=excluded.autoChargeAmount,
                lastupdate=excluded.lastupdate,
                ticketType=excluded.ticketType,
                companyCode=excluded.companyCode,
                purchaseAmount=excluded.purchaseAmount,
                expiryDate=excluded.expiryDate,
                checkDigit=excluded.checkDigit,
                routeStart=excluded.routeStart,
                routeEnd=excluded.routeEnd
        """);
        ps.setString(1, user.uuid.toString());
        ps.setString(2, user.playername);
        ps.setInt(3, user.balance);
        ps.setString(4, user.webChargePassword);
        if (user.autoChargeThreshold != null) ps.setInt(5, user.autoChargeThreshold); else ps.setNull(5, Types.INTEGER);
        if (user.autoChargeAmount != null) ps.setInt(6, user.autoChargeAmount); else ps.setNull(6, Types.INTEGER);
        ps.setLong(7, user.lastupdate);
        if (user.ticketType != null) ps.setInt(8, user.ticketType); else ps.setNull(8, Types.INTEGER);
        if (user.companyCode != null) ps.setInt(9, user.companyCode); else ps.setNull(9, Types.INTEGER);
        if (user.purchaseAmount != null) ps.setInt(10, user.purchaseAmount); else ps.setNull(10, Types.INTEGER);
        if (user.expiryDate != null) ps.setLong(11, user.expiryDate); else ps.setNull(11, Types.INTEGER);
        if (user.checkDigit != null) ps.setInt(12, user.checkDigit); else ps.setNull(12, Types.INTEGER);
        ps.setString(13, user.routeStart);
        ps.setString(14, user.routeEnd);
        ps.executeUpdate();
        ps.close();
    }

    // 残高更新
    public void updateBalance(UUID uuid, int newBalance) throws SQLException {
        PreparedStatement ps = conn.prepareStatement("UPDATE users SET balance = ?, lastupdate = ? WHERE uuid = ?");
        ps.setInt(1, newBalance);
        ps.setLong(2, System.currentTimeMillis() / 1000L);
        ps.setString(3, uuid.toString());
        ps.executeUpdate();
        ps.close();
    }

    // 履歴取得
    public List<PaymentHistory> getHistory(UUID uuid, int limit) throws SQLException {
        List<PaymentHistory> list = new ArrayList<>();
        String sql = "SELECT * FROM payment_history WHERE uuid = ? ORDER BY timestamp DESC";
        if (limit > 0) sql += " LIMIT ?";
        PreparedStatement ps = conn.prepareStatement(sql);
        ps.setString(1, uuid.toString());
        if (limit > 0) ps.setInt(2, limit);
        ResultSet rs = ps.executeQuery();
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
        rs.close();
        ps.close();
        return list;
    }

    // 履歴追加
    public void addHistory(UUID uuid, PaymentHistory h) throws SQLException {
        PreparedStatement ps = conn.prepareStatement("""
            INSERT INTO payment_history (uuid, timestamp, "from", "to", amount, balance, description)
            VALUES (?, ?, ?, ?, ?, ?, ?)
        """);
        ps.setString(1, uuid.toString());
        ps.setLong(2, h.timestamp);
        ps.setString(3, h.from);
        ps.setString(4, h.to);
        ps.setInt(5, h.amount);
        ps.setInt(6, h.balance);
        ps.setString(7, h.description);
        ps.executeUpdate();
        ps.close();
    }

    // プレイヤー名からUUID取得
    public UUID getUUIDByPlayerName(String playername) throws SQLException {
        PreparedStatement ps = conn.prepareStatement("SELECT uuid FROM users WHERE playername = ?");
        ps.setString(1, playername);
        ResultSet rs = ps.executeQuery();
        if (!rs.next()) return null;
        UUID uuid = UUID.fromString(rs.getString("uuid"));
        rs.close();
        ps.close();
        return uuid;
    }

    public void close() throws SQLException {
        if (conn != null) conn.close();
    }

    // 全ユーザー取得
    public List<UserData> getAllUsers() throws SQLException {
        List<UserData> users = new ArrayList<>();
        Statement stmt = conn.createStatement();
        ResultSet rs = stmt.executeQuery("SELECT * FROM users");
        while (rs.next()) {
            UserData user = new UserData();
            user.uuid = UUID.fromString(rs.getString("uuid"));
            user.playername = rs.getString("playername");
            user.balance = rs.getInt("balance");
            user.webChargePassword = rs.getString("webChargePassword");
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
            users.add(user);
        }
        rs.close();
        stmt.close();
        return users;
    }

    // ユーザーデータ構造
    public static class UserData {
        public UUID uuid;
        public String playername;
        public int balance;
        public String webChargePassword;
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