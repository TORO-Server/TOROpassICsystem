package prj.salmon.toropassicsystem.api;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * Web API の認証まわりの保護 (総当たり対策・読み取りAPIのレート制限・パスワード比較) をまとめたクラス。
 */
public class ApiSecurity {
    private static final int MAX_IP_LENGTH = 64;

    private final AuthThrottle nameThrottle;
    private final AuthThrottle ipThrottle;
    private final RateLimiter readLimiter;
    private final String clientIpHeader;
    private final boolean requireAuthForRead;
    private final int maxBodyBytes;
    private final long syncTimeoutMillis;
    private final byte[] fingerprintSalt = new byte[16];

    public ApiSecurity(Settings settings) {
        this(settings, System::currentTimeMillis);
    }

    public ApiSecurity(Settings settings, LongSupplier clock) {
        long window = settings.authWindowSeconds * 1000L;
        long lockout = settings.authLockoutSeconds * 1000L;
        this.nameThrottle = new AuthThrottle(settings.authMaxFailures, window, lockout, 10000, clock);
        this.ipThrottle = new AuthThrottle(settings.authMaxFailures, window, lockout, 10000, clock);
        this.readLimiter = new RateLimiter(settings.readMaxRequests, settings.readWindowSeconds * 1000L, 10000, clock);
        this.clientIpHeader = settings.clientIpHeader == null ? "" : settings.clientIpHeader.trim().toLowerCase(Locale.ROOT);
        this.requireAuthForRead = settings.requireAuthForRead;
        this.maxBodyBytes = Math.max(0, settings.maxBodyBytes);
        this.syncTimeoutMillis = Math.max(1, settings.syncTimeoutMillis);
        new SecureRandom().nextBytes(fingerprintSalt);
    }

    public boolean isRequireAuthForRead() {
        return requireAuthForRead;
    }

    public int getMaxBodyBytes() {
        return maxBodyBytes;
    }

    public long getSyncTimeoutMillis() {
        return syncTimeoutMillis;
    }

    // クライアントIPを取得する。設定したヘッダー (例: CF-Connecting-IP) があればそれを、なければ接続元アドレスを使う
    // NanoHTTPD のヘッダー名は小文字になっている
    public String clientIp(Map<String, String> headers, String remoteAddress) {
        if (!clientIpHeader.isEmpty() && headers != null) {
            String value = headers.get(clientIpHeader);
            if (value != null) {
                String first = value.split(",", 2)[0].trim();
                if (!first.isEmpty() && first.length() <= MAX_IP_LENGTH) return first;
            }
        }
        return remoteAddress == null ? "unknown" : remoteAddress;
    }

    // プレイヤー名またはIPがロック中なら残り時間 (ミリ秒) を返す。ロックされていなければ 0
    public long lockRemainingMillis(String playerName, String clientIp) {
        return Math.max(nameThrottle.remainingLockMillis(nameKey(playerName)), ipThrottle.remainingLockMillis(clientIp));
    }

    // 認証失敗を記録する。この失敗でプレイヤー名またはIPがロックされた場合は true
    public boolean recordFailure(String playerName, String clientIp, String password) {
        String fingerprint = fingerprint(nameKey(playerName) + "\u0000" + password);
        boolean nameLocked = nameThrottle.recordFailure(nameKey(playerName), fingerprint);
        boolean ipLocked = ipThrottle.recordFailure(clientIp, fingerprint);
        return nameLocked || ipLocked;
    }

    // 認証成功時はプレイヤー名側の失敗回数のみリセットする
    // (IP側もリセットすると、攻撃者が自分の正しいパスワードを挟むことで制限を回避できてしまう)
    public void recordSuccess(String playerName) {
        nameThrottle.reset(nameKey(playerName));
    }

    // 読み取り系APIのレート制限。許可されれば 0、制限されれば待ち時間 (ミリ秒)
    public long tryAcquireRead(String clientIp) {
        return readLimiter.tryAcquire(clientIp);
    }

    // パスワードを一定時間で比較する (長さの違いも漏れないよう、ハッシュ化してから比較)
    public static boolean passwordMatches(String expected, String provided) {
        if (expected == null || provided == null) return false;
        return MessageDigest.isEqual(sha256(expected.getBytes(StandardCharsets.UTF_8)), sha256(provided.getBytes(StandardCharsets.UTF_8)));
    }

    private static String nameKey(String playerName) {
        return playerName == null ? "" : playerName.toLowerCase(Locale.ROOT);
    }

    // 失敗した試行の識別子 (パスワードそのものはメモリに保持しない)
    private String fingerprint(String attempt) {
        byte[] data = attempt.getBytes(StandardCharsets.UTF_8);
        byte[] salted = new byte[fingerprintSalt.length + data.length];
        System.arraycopy(fingerprintSalt, 0, salted, 0, fingerprintSalt.length);
        System.arraycopy(data, 0, salted, fingerprintSalt.length, data.length);
        return HexFormat.of().formatHex(sha256(salted));
    }

    private static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    // config.yml の api セクションの値 (デフォルト値は config.yml と同じ)
    public static class Settings {
        public boolean requireAuthForRead = false;
        public String clientIpHeader = "CF-Connecting-IP";
        public int maxBodyBytes = 16384;
        public long syncTimeoutMillis = 5000;
        public int authMaxFailures = 5;
        public long authWindowSeconds = 600;
        public long authLockoutSeconds = 900;
        public int readMaxRequests = 240;
        public long readWindowSeconds = 60;
    }
}
