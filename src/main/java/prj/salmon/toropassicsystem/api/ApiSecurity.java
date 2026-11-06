package prj.salmon.toropassicsystem.api;

import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * Web API の認証まわりの保護 (総当たり対策・レート制限・接続元の判定) をまとめたクラス。
 *
 * ロックはプレイヤー名とクライアントIPの組、およびクライアントIPに対して行う。
 * プレイヤー名だけのロックは第三者が本人を締め出せるので行わず、失敗が続いたプレイヤー名への試行を遅らせるだけにする。
 */
public class ApiSecurity {
    private static final int MAX_TRACKED_KEYS = 10000;
    private static final String KEY_SEPARATOR = "\u0000";
    // プレイヤー名ごとの遅延の最初の値 (失敗が増えるたびに倍にする)
    private static final long NAME_DELAY_BASE_MILLIS = 250;
    private static final int NAME_DELAY_MAX_DOUBLINGS = 16;

    private final AuthThrottle pairThrottle;
    private final AuthThrottle ipThrottle;
    private final AuthThrottle nameFailures;
    private final RateLimiter readLimiter;
    private final RateLimiter writeLimiter;
    private final String clientIpHeader;
    private final List<ClientAddresses.Cidr> trustedProxies;
    private final Set<String> corsAllowedOrigins;
    private final boolean requireAuthForRead;
    private final boolean webChargeEnabled;
    private final int maxBodyBytes;
    private final long syncTimeoutMillis;
    private final int nameDelayAfter;
    private final long nameDelayMaxMillis;
    private final byte[] fingerprintSalt = new byte[16];

    public ApiSecurity(Settings settings) {
        this(settings, System::currentTimeMillis, invalid -> {});
    }

    public ApiSecurity(Settings settings, LongSupplier clock) {
        this(settings, clock, invalid -> {});
    }

    // invalidProxyEntry: 解釈できなかった trusted-proxies の値を受け取る (ログ出力用)
    public ApiSecurity(Settings settings, LongSupplier clock, Consumer<String> invalidProxyEntry) {
        long window = settings.authWindowSeconds * 1000L;
        long lockout = settings.authLockoutSeconds * 1000L;
        this.pairThrottle = new AuthThrottle(settings.authMaxFailures, window, lockout, MAX_TRACKED_KEYS, clock);
        this.ipThrottle = new AuthThrottle(settings.authIpMaxFailures, window, lockout, MAX_TRACKED_KEYS, clock);
        this.nameFailures = new AuthThrottle(Integer.MAX_VALUE, window, lockout, MAX_TRACKED_KEYS, clock);
        this.readLimiter = new RateLimiter(settings.readMaxRequests, settings.readWindowSeconds * 1000L, MAX_TRACKED_KEYS, clock);
        this.writeLimiter = new RateLimiter(settings.writeMaxRequests, settings.writeWindowSeconds * 1000L, MAX_TRACKED_KEYS, clock);
        this.clientIpHeader = settings.clientIpHeader == null ? "" : settings.clientIpHeader.trim().toLowerCase(Locale.ROOT);
        this.trustedProxies = parseProxies(settings.trustedProxies, invalidProxyEntry);
        this.corsAllowedOrigins = settings.corsAllowedOrigins == null ? Set.of() : Set.copyOf(settings.corsAllowedOrigins);
        this.requireAuthForRead = settings.requireAuthForRead;
        this.webChargeEnabled = settings.webChargeEnabled;
        this.maxBodyBytes = Math.max(0, settings.maxBodyBytes);
        this.syncTimeoutMillis = Math.max(1, settings.syncTimeoutMillis);
        this.nameDelayAfter = settings.authNameDelayAfter;
        this.nameDelayMaxMillis = Math.max(0, settings.authNameDelayMaxMillis);
        new SecureRandom().nextBytes(fingerprintSalt);
    }

    public boolean isRequireAuthForRead() {
        return requireAuthForRead;
    }

    public boolean isWebChargeEnabled() {
        return webChargeEnabled;
    }

    public int getMaxBodyBytes() {
        return maxBodyBytes;
    }

    public long getSyncTimeoutMillis() {
        return syncTimeoutMillis;
    }

    public boolean isCorsOriginAllowed(String origin) {
        return origin != null && corsAllowedOrigins.contains(origin);
    }

    // クライアントIPを取得する。接続元が信頼するプロキシのときだけ、設定したヘッダー (例: CF-Connecting-IP) の値を使う
    // NanoHTTPD のヘッダー名は小文字になっている
    public String clientIp(Map<String, String> headers, String remoteAddress) {
        InetAddress peer = ClientAddresses.parseLiteral(remoteAddress);
        if (peer != null && !clientIpHeader.isEmpty() && headers != null && isTrustedProxy(peer)) {
            String value = headers.get(clientIpHeader);
            if (value != null) {
                InetAddress client = ClientAddresses.parseLiteral(value.split(",", 2)[0]);
                if (client != null) return ClientAddresses.trackingKey(client);
            }
        }
        if (peer != null) return ClientAddresses.trackingKey(peer);
        return remoteAddress == null ? "unknown" : remoteAddress;
    }

    // プレイヤー名とIPの組、またはIPがロック中なら残り時間 (ミリ秒) を返す。ロックされていなければ 0
    public long lockRemainingMillis(String playerName, String clientIp) {
        return Math.max(pairThrottle.remainingLockMillis(pairKey(playerName, clientIp)), ipThrottle.remainingLockMillis(clientIp));
    }

    // 認証失敗を記録する。この失敗で組またはIPがロックされた場合は true
    public boolean recordFailure(String playerName, String clientIp, String password) {
        String fingerprint = fingerprint(nameKey(playerName) + KEY_SEPARATOR + password);
        nameFailures.recordFailure(nameKey(playerName), fingerprint);
        boolean pairLocked = pairThrottle.recordFailure(pairKey(playerName, clientIp), fingerprint);
        boolean ipLocked = ipThrottle.recordFailure(clientIp, fingerprint);
        return pairLocked || ipLocked;
    }

    // 認証成功時はプレイヤー名に関する失敗回数のみリセットする
    // (IP側もリセットすると、攻撃者が自分の正しいパスワードを挟むことで制限を回避できてしまう)
    public void recordSuccess(String playerName, String clientIp) {
        pairThrottle.reset(pairKey(playerName, clientIp));
        nameFailures.reset(nameKey(playerName));
    }

    // ゲーム内でパスワードを発行し直したら、そのプレイヤー名に関する失敗回数とロックを取り消す
    public void clearAuthFailures(String playerName) {
        String name = nameKey(playerName);
        nameFailures.clearMatching(name::equals);
        pairThrottle.clearMatching(key -> key.startsWith(name + KEY_SEPARATOR));
    }

    // 失敗が続いたプレイヤー名への認証で待たせる時間 (ミリ秒)
    public long authDelayMillis(String playerName) {
        if (nameDelayAfter <= 0 || nameDelayMaxMillis <= 0) return 0;
        int over = nameFailures.failureCount(nameKey(playerName)) - nameDelayAfter;
        if (over < 0) return 0;
        return Math.min(nameDelayMaxMillis, NAME_DELAY_BASE_MILLIS << Math.min(over, NAME_DELAY_MAX_DOUBLINGS));
    }

    // 読み取り系APIのレート制限。許可されれば 0、制限されれば待ち時間 (ミリ秒)
    public long tryAcquireRead(String clientIp) {
        return readLimiter.tryAcquire(clientIp);
    }

    // POST APIのレート制限。許可されれば 0、制限されれば待ち時間 (ミリ秒)
    public long tryAcquireWrite(String clientIp) {
        return writeLimiter.tryAcquire(clientIp);
    }

    private boolean isTrustedProxy(InetAddress peer) {
        for (ClientAddresses.Cidr cidr : trustedProxies) {
            if (cidr.contains(peer)) return true;
        }
        return false;
    }

    private static List<ClientAddresses.Cidr> parseProxies(List<String> entries, Consumer<String> invalidEntry) {
        List<ClientAddresses.Cidr> parsed = new ArrayList<>();
        if (entries == null) return parsed;
        for (String entry : entries) {
            try {
                parsed.add(ClientAddresses.Cidr.parse(entry));
            } catch (IllegalArgumentException e) {
                invalidEntry.accept(entry);
            }
        }
        return parsed;
    }

    private static String nameKey(String playerName) {
        return playerName == null ? "" : playerName.toLowerCase(Locale.ROOT);
    }

    private static String pairKey(String playerName, String clientIp) {
        return nameKey(playerName) + KEY_SEPARATOR + clientIp;
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
        public boolean requireAuthForRead = true;
        public boolean webChargeEnabled = true;
        public String clientIpHeader = "CF-Connecting-IP";
        // このヘッダーを信頼する接続元 (cloudflared や toro-edge のある内部ネットワーク)
        public List<String> trustedProxies = List.of("127.0.0.0/8", "::1/128", "10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "fc00::/7");
        // API が直接 CORS を許可するオリジン (Wallet が CORS 対応のプロキシを経由する間は空のままにする)
        public List<String> corsAllowedOrigins = List.of();
        public String bindAddress = "";
        public int maxWorkers = 32;
        public int maxQueuedConnections = 64;
        public int maxBodyBytes = 16384;
        public long syncTimeoutMillis = 5000;
        public int authMaxFailures = 5;
        public int authIpMaxFailures = 30;
        public long authWindowSeconds = 600;
        public long authLockoutSeconds = 900;
        public int authNameDelayAfter = 5;
        public long authNameDelayMaxMillis = 2000;
        public int readMaxRequests = 240;
        public long readWindowSeconds = 60;
        public int writeMaxRequests = 240;
        public long writeWindowSeconds = 60;
    }
}
