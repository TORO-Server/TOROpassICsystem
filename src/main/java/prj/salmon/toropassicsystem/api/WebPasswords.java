package prj.salmon.toropassicsystem.api;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Locale;

/**
 * Wallet 用パスワードの発行と検証。DB には平文を保存せず、ハッシュだけを保存する。
 *
 * /webcharge が発行するトークンは十分な長さの乱数なので、ソルト付き SHA-256 で保存する (Wallet の定期的な読み取りでも負荷にならない)。
 * 以前の 8 文字のパスワードは短いので、有効期限までのあいだ PBKDF2 で保存する。
 */
public final class WebPasswords {
    // 見間違えやすい文字を除いた Crockford Base32。16 文字で 80 ビット
    private static final String ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";
    private static final int TOKEN_CHARS = 16;
    private static final int TOKEN_GROUP = 4;
    private static final int SALT_BYTES = 16;
    private static final int LEGACY_ITERATIONS = 20_000;
    private static final int LEGACY_KEY_BITS = 256;
    private static final int MAX_INPUT_CHARS = 128;

    private static final String TOKEN_SCHEME = "sha256";
    private static final String LEGACY_SCHEME = "pbkdf2-sha256";

    private static final SecureRandom RANDOM = new SecureRandom();

    private WebPasswords() {}

    // 新しいトークンを発行する (例: 7K3M-Q9TZ-2XWD-HN4R)
    public static String generateToken() {
        StringBuilder token = new StringBuilder(TOKEN_CHARS + TOKEN_CHARS / TOKEN_GROUP);
        for (int i = 0; i < TOKEN_CHARS; i++) {
            if (i > 0 && i % TOKEN_GROUP == 0) token.append('-');
            token.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        }
        return token.toString();
    }

    public static String hashToken(String token) {
        byte[] salt = newSalt();
        return TOKEN_SCHEME + "$" + encode(salt) + "$" + encode(saltedSha256(salt, normalizeToken(token)));
    }

    public static String hashLegacyPassword(String password) {
        byte[] salt = newSalt();
        return LEGACY_SCHEME + "$" + LEGACY_ITERATIONS + "$" + encode(salt) + "$" + encode(pbkdf2(password, salt, LEGACY_ITERATIONS));
    }

    // 保存されたハッシュと入力を一定時間で比較する。未設定や形式の不正は常に不一致
    public static boolean matches(String stored, String provided) {
        if (stored == null || provided == null || provided.length() > MAX_INPUT_CHARS) return false;
        String[] parts = stored.split("\\$");
        try {
            if (parts.length == 3 && parts[0].equals(TOKEN_SCHEME)) {
                return MessageDigest.isEqual(decode(parts[2]), saltedSha256(decode(parts[1]), normalizeToken(provided)));
            }
            if (parts.length == 4 && parts[0].equals(LEGACY_SCHEME)) {
                int iterations = Integer.parseInt(parts[1]);
                if (iterations < 1) return false;
                return MessageDigest.isEqual(decode(parts[3]), pbkdf2(provided, decode(parts[2]), iterations));
            }
        } catch (IllegalArgumentException e) {
            return false;
        }
        return false;
    }

    // 手入力でも通るよう、ハイフン・空白・大文字小文字の違いを無視する
    private static String normalizeToken(String token) {
        return token.replaceAll("[\\s-]", "").toUpperCase(Locale.ROOT);
    }

    private static byte[] newSalt() {
        byte[] salt = new byte[SALT_BYTES];
        RANDOM.nextBytes(salt);
        return salt;
    }

    private static byte[] saltedSha256(byte[] salt, String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(salt);
            return digest.digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] pbkdf2(String password, byte[] salt, int iterations) {
        PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, iterations, LEGACY_KEY_BITS);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        } finally {
            spec.clearPassword();
        }
    }

    private static String encode(byte[] bytes) {
        return Base64.getEncoder().withoutPadding().encodeToString(bytes);
    }

    private static byte[] decode(String text) {
        return Base64.getDecoder().decode(text);
    }
}
