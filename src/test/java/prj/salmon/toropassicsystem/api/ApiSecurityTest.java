package prj.salmon.toropassicsystem.api;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class ApiSecurityTest {
    private final AtomicLong now = new AtomicLong(1_000_000L);

    private ApiSecurity security() {
        return new ApiSecurity(new ApiSecurity.Settings(), now::get);
    }

    @Test
    void passwordComparison() {
        assertTrue(ApiSecurity.passwordMatches("Ab3dEf7h", "Ab3dEf7h"));
        assertFalse(ApiSecurity.passwordMatches("Ab3dEf7h", "ab3dEf7h"));
        assertFalse(ApiSecurity.passwordMatches("Ab3dEf7h", "Ab3dEf7"));
        assertFalse(ApiSecurity.passwordMatches("Ab3dEf7h", ""));
        // パスワード未設定 (/webcharge 未実行) のユーザーは常に失敗
        assertFalse(ApiSecurity.passwordMatches(null, "anything"));
        assertFalse(ApiSecurity.passwordMatches("Ab3dEf7h", null));
    }

    @Test
    void clientIpPrefersConfiguredHeader() {
        ApiSecurity s = security();
        assertEquals("client-a", s.clientIp(Map.of("cf-connecting-ip", "client-a"), "socket-peer"));
        assertEquals("client-a", s.clientIp(Map.of("cf-connecting-ip", " client-a , proxy-b"), "socket-peer"));
        assertEquals("socket-peer", s.clientIp(Map.of(), "socket-peer"));
        assertEquals("socket-peer", s.clientIp(Map.of("cf-connecting-ip", "x".repeat(200)), "socket-peer"));
    }

    @Test
    void clientIpHeaderCanBeDisabled() {
        ApiSecurity.Settings settings = new ApiSecurity.Settings();
        settings.clientIpHeader = "";
        ApiSecurity s = new ApiSecurity(settings, now::get);
        assertEquals("socket-peer", s.clientIp(Map.of("cf-connecting-ip", "client-a"), "socket-peer"));
    }

    @Test
    void nameIsLockedAcrossIps() {
        ApiSecurity s = security();
        // 攻撃者がIPを変えながら同じプレイヤーを狙う
        for (int i = 0; i < 5; i++) s.recordFailure("Steve", "client-" + i, "guess" + i);
        assertTrue(s.lockRemainingMillis("Steve", "client-x") > 0);
        // 大文字小文字の違いでは回避できない
        assertTrue(s.lockRemainingMillis("steve", "client-x") > 0);
        assertEquals(0, s.lockRemainingMillis("Alex", "client-x"));
    }

    @Test
    void ipIsLockedAcrossNames() {
        ApiSecurity s = security();
        // 1つのIPから複数のプレイヤーを狙う
        for (int i = 0; i < 5; i++) s.recordFailure("player" + i, "client-1", "guess");
        assertTrue(s.lockRemainingMillis("someone-else", "client-1") > 0);
        assertEquals(0, s.lockRemainingMillis("someone-else", "client-2"));
    }

    @Test
    void successResetsNameCounterButNotIpCounter() {
        ApiSecurity s = security();
        for (int i = 0; i < 4; i++) s.recordFailure("Steve", "client-1", "guess" + i);
        s.recordSuccess("Steve");
        // プレイヤー名側はリセットされている
        s.recordFailure("Steve", "client-2", "guess-a");
        assertEquals(0, s.lockRemainingMillis("Steve", "client-2"));
        // IP側は成功してもリセットされない
        s.recordFailure("Alex", "client-1", "guess-b");
        assertTrue(s.lockRemainingMillis("Alex", "client-1") > 0);
    }

    @Test
    void stalePasswordRetriesDoNotLockOut() {
        ApiSecurity s = security();
        // Wallet が古いパスワードで定期的に再送信しても、同じ試行は 1 回として数える
        for (int i = 0; i < 100; i++) s.recordFailure("Steve", "client-1", "old-password");
        assertEquals(0, s.lockRemainingMillis("Steve", "client-1"));
    }

    @Test
    void readRateLimit() {
        ApiSecurity.Settings settings = new ApiSecurity.Settings();
        settings.readMaxRequests = 2;
        ApiSecurity s = new ApiSecurity(settings, now::get);
        assertEquals(0, s.tryAcquireRead("ip"));
        assertEquals(0, s.tryAcquireRead("ip"));
        assertTrue(s.tryAcquireRead("ip") > 0);
    }
}
