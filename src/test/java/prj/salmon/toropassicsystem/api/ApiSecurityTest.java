package prj.salmon.toropassicsystem.api;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class ApiSecurityTest {
    private static final String INTERNAL_PROXY = "172.18.0.5";
    private static final String EXTERNAL_PEER = "198.51.100.50";

    private final AtomicLong now = new AtomicLong(1_000_000L);

    private ApiSecurity security() {
        return new ApiSecurity(new ApiSecurity.Settings(), now::get);
    }

    @Test
    void headerIsUsedOnlyWhenPeerIsTrustedProxy() {
        ApiSecurity s = security();
        assertEquals("203.0.113.7", s.clientIp(Map.of("cf-connecting-ip", "203.0.113.7"), INTERNAL_PROXY));
        assertEquals("203.0.113.7", s.clientIp(Map.of("cf-connecting-ip", " 203.0.113.7 , 192.0.2.1"), INTERNAL_PROXY));
        assertEquals("203.0.113.7", s.clientIp(Map.of("cf-connecting-ip", "203.0.113.7"), "127.0.0.1"));
        // 内部ネットワーク以外からの接続ではヘッダーを信頼しない
        assertEquals(EXTERNAL_PEER, s.clientIp(Map.of("cf-connecting-ip", "203.0.113.7"), EXTERNAL_PEER));
        assertEquals(INTERNAL_PROXY, s.clientIp(Map.of(), INTERNAL_PROXY));
    }

    @Test
    void invalidHeaderValueFallsBackToPeer() {
        ApiSecurity s = security();
        assertEquals(INTERNAL_PROXY, s.clientIp(Map.of("cf-connecting-ip", "x".repeat(200)), INTERNAL_PROXY));
        assertEquals(INTERNAL_PROXY, s.clientIp(Map.of("cf-connecting-ip", "example.com"), INTERNAL_PROXY));
        assertEquals(INTERNAL_PROXY, s.clientIp(Map.of("cf-connecting-ip", "300.1.1.1"), INTERNAL_PROXY));
        assertEquals(INTERNAL_PROXY, s.clientIp(Map.of("cf-connecting-ip", ""), INTERNAL_PROXY));
    }

    @Test
    void ipv6ClientsAreTrackedPerNetwork() {
        ApiSecurity s = security();
        String a = s.clientIp(Map.of("cf-connecting-ip", "2001:db8:1:2:aaaa::1"), INTERNAL_PROXY);
        String b = s.clientIp(Map.of("cf-connecting-ip", "2001:db8:1:2:bbbb::2"), INTERNAL_PROXY);
        String other = s.clientIp(Map.of("cf-connecting-ip", "2001:db8:1:3::1"), INTERNAL_PROXY);
        assertEquals(a, b);
        assertNotEquals(a, other);
    }

    @Test
    void clientIpHeaderCanBeDisabled() {
        ApiSecurity.Settings settings = new ApiSecurity.Settings();
        settings.clientIpHeader = "";
        ApiSecurity s = new ApiSecurity(settings, now::get);
        assertEquals(INTERNAL_PROXY, s.clientIp(Map.of("cf-connecting-ip", "203.0.113.7"), INTERNAL_PROXY));
    }

    @Test
    void emptyTrustedProxiesIgnoresHeader() {
        ApiSecurity.Settings settings = new ApiSecurity.Settings();
        settings.trustedProxies = List.of();
        ApiSecurity s = new ApiSecurity(settings, now::get);
        assertEquals(INTERNAL_PROXY, s.clientIp(Map.of("cf-connecting-ip", "203.0.113.7"), INTERNAL_PROXY));
    }

    @Test
    void invalidTrustedProxyEntryIsReported() {
        ApiSecurity.Settings settings = new ApiSecurity.Settings();
        settings.trustedProxies = List.of("10.0.0.0/8", "not-a-cidr");
        List<String> invalid = new ArrayList<>();
        new ApiSecurity(settings, now::get, invalid::add);
        assertEquals(List.of("not-a-cidr"), invalid);
    }

    @Test
    void failuresFromOneClientDoNotLockThePlayerForOthers() {
        ApiSecurity s = security();
        for (int i = 0; i < 5; i++) s.recordFailure("Steve", "203.0.113.1", "guess" + i);
        // 失敗した接続元は同じプレイヤーに対してロックされる (大文字小文字の違いでは回避できない)
        assertTrue(s.lockRemainingMillis("Steve", "203.0.113.1") > 0);
        assertTrue(s.lockRemainingMillis("steve", "203.0.113.1") > 0);
        // 本人の接続元はロックされない
        assertEquals(0, s.lockRemainingMillis("Steve", "203.0.113.2"));
        // 別のプレイヤーへの試行もまだ止まらない (IP側のしきい値は高い)
        assertEquals(0, s.lockRemainingMillis("Alex", "203.0.113.1"));
    }

    @Test
    void failuresAcrossClientsOnlyDelayThePlayer() {
        ApiSecurity s = security();
        assertEquals(0, s.authDelayMillis("Steve"));
        for (int i = 0; i < 6; i++) s.recordFailure("Steve", "203.0.113." + i, "guess" + i);
        assertTrue(s.authDelayMillis("Steve") > 0);
        assertTrue(s.authDelayMillis("steve") > 0);
        assertEquals(0, s.lockRemainingMillis("Steve", "203.0.113.200"));
        assertEquals(0, s.authDelayMillis("Alex"));

        // 遅延は設定の最大値を超えない
        for (int i = 6; i < 40; i++) s.recordFailure("Steve", "203.0.113." + i, "guess" + i);
        assertEquals(new ApiSecurity.Settings().authNameDelayMaxMillis, s.authDelayMillis("Steve"));
    }

    @Test
    void ipIsLockedAcrossNames() {
        ApiSecurity s = security();
        int threshold = new ApiSecurity.Settings().authIpMaxFailures;
        for (int i = 0; i < threshold; i++) s.recordFailure("player" + i, "203.0.113.1", "guess");
        assertTrue(s.lockRemainingMillis("someone-else", "203.0.113.1") > 0);
        assertEquals(0, s.lockRemainingMillis("someone-else", "203.0.113.2"));
    }

    @Test
    void successResetsPlayerCountersButNotIpCounter() {
        ApiSecurity.Settings settings = new ApiSecurity.Settings();
        settings.authIpMaxFailures = 5;
        ApiSecurity s = new ApiSecurity(settings, now::get);
        for (int i = 0; i < 4; i++) s.recordFailure("Steve", "203.0.113.1", "guess" + i);
        s.recordSuccess("Steve", "203.0.113.1");
        assertEquals(0, s.authDelayMillis("Steve"));
        // 組の回数はリセットされているが、IP側は成功してもリセットされない
        s.recordFailure("Alex", "203.0.113.1", "guess-b");
        assertTrue(s.lockRemainingMillis("Alex", "203.0.113.1") > 0);
    }

    @Test
    void newPasswordClearsLocksForThePlayer() {
        ApiSecurity s = security();
        for (int i = 0; i < 8; i++) s.recordFailure("Steve", "203.0.113.1", "guess" + i);
        assertTrue(s.lockRemainingMillis("Steve", "203.0.113.1") > 0);
        assertTrue(s.authDelayMillis("Steve") > 0);
        s.clearAuthFailures("Steve");
        assertEquals(0, s.lockRemainingMillis("Steve", "203.0.113.1"));
        assertEquals(0, s.authDelayMillis("Steve"));
    }

    @Test
    void stalePasswordRetriesDoNotLockOut() {
        ApiSecurity s = security();
        // Wallet が古いパスワードで定期的に再送信しても、同じ試行は 1 回として数える
        for (int i = 0; i < 100; i++) s.recordFailure("Steve", "203.0.113.1", "old-password");
        assertEquals(0, s.lockRemainingMillis("Steve", "203.0.113.1"));
        assertEquals(0, s.authDelayMillis("Steve"));
    }

    @Test
    void readAndWriteRateLimitsAreSeparate() {
        ApiSecurity.Settings settings = new ApiSecurity.Settings();
        settings.readMaxRequests = 2;
        settings.writeMaxRequests = 1;
        ApiSecurity s = new ApiSecurity(settings, now::get);
        assertEquals(0, s.tryAcquireRead("ip"));
        assertEquals(0, s.tryAcquireRead("ip"));
        assertTrue(s.tryAcquireRead("ip") > 0);
        assertEquals(0, s.tryAcquireWrite("ip"));
        assertTrue(s.tryAcquireWrite("ip") > 0);
    }

    @Test
    void readAuthIsRequiredByDefault() {
        assertTrue(security().isRequireAuthForRead());
    }

    @Test
    void corsOriginMustMatchExactly() {
        assertFalse(security().isCorsOriginAllowed("https://toro-server.github.io"));
        ApiSecurity.Settings settings = new ApiSecurity.Settings();
        settings.corsAllowedOrigins = List.of("https://toro-server.github.io");
        ApiSecurity s = new ApiSecurity(settings, now::get);
        assertTrue(s.isCorsOriginAllowed("https://toro-server.github.io"));
        assertFalse(s.isCorsOriginAllowed("https://toro-server.github.io.example.com"));
        assertFalse(s.isCorsOriginAllowed(null));
    }
}
