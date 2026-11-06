package prj.salmon.toropassicsystem.api;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.regex.Pattern;

/**
 * 発売機が発行する定期券コードの解析と検証。
 *
 * 形式: 券種-事業者コード-購入金額-有効期限(yyyyMMdd)-チェックデジット[-区間開始-区間終了][-sig.署名]
 * チェックデジットは誤入力の検出にしか使えないので、コードの真正性を確かめるには署名を必須にする
 * (require-signature)。署名は発売機側の対応が必要なので既定では任意とする。
 */
public final class CardCodes {
    private static final String SIGNATURE_PREFIX = "sig.";
    // HMAC-SHA256 の先頭 128 ビット (貼り付けるコードが長くなりすぎないようにする)
    private static final int SIGNATURE_BYTES = 16;
    private static final int MAX_ROUTE_LENGTH = 64;
    private static final int MAX_CODE_LENGTH = 512;
    private static final Pattern EXPIRY_FORMAT = Pattern.compile("\\d{8}");
    private static final DateTimeFormatter EXPIRY_PARSER = DateTimeFormatter.ofPattern("uuuuMMdd").withResolverStyle(ResolverStyle.STRICT);

    private CardCodes() {}

    public enum Error {
        // コードの区切りや項目数が不正
        MALFORMED,
        // 数値や日付として読めない
        BAD_FIELD,
        // 券種・事業者コード・金額・区間の値が範囲外
        OUT_OF_RANGE,
        CHECK_DIGIT,
        EXPIRED,
        VALIDITY_TOO_LONG,
        AMOUNT_NOT_ALLOWED,
        SIGNATURE_REQUIRED,
        SIGNATURE_INVALID
    }

    // config.yml の card-codes セクションの値
    // 既定値は、設定が読み込めない場合に loadCardCodeSettings の初期値として使われる。
    // その場合でも無制限に倒れないよう、有効期限と購入金額の上限は有限の値にしてある
    // (0 を明示すれば従来どおり無制限・制限なしにもできる)
    public static class Settings {
        public boolean requireSignature = false;
        public String signingSecret = "";
        // 今日から何日先までの有効期限を受け付けるか (0 で無制限)
        public int maxValidityDays = 366;
        // 購入金額の下限と上限 (0 で制限なし)
        public int minPurchaseAmount = 0;
        public int maxPurchaseAmount = 300000;
    }

    public record Card(int ticketType, int companyCode, int purchaseAmount, LocalDate expiry, int checkDigit, String routeStart, String routeEnd) {
        // 有効期限の日の終わり (DB の expiryDate に保存する値)
        public long expiryEpochMillis(ZoneId zone) {
            return expiry.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1;
        }
    }

    public record Result(Card card, Error error) {
        public boolean ok() {
            return card != null;
        }

        private static Result failed(Error error) {
            return new Result(null, error);
        }
    }

    public static Result parse(String code, Settings settings, LocalDate today) {
        if (code == null || code.isEmpty() || code.length() > MAX_CODE_LENGTH) return Result.failed(Error.MALFORMED);
        String[] parts = code.split("-");

        String signature = null;
        int fieldCount = parts.length;
        if (fieldCount > 0 && parts[fieldCount - 1].startsWith(SIGNATURE_PREFIX)) {
            signature = parts[fieldCount - 1].substring(SIGNATURE_PREFIX.length());
            fieldCount--;
        }
        if (fieldCount < 5) return Result.failed(Error.MALFORMED);

        int ticketType;
        int companyCode;
        int purchaseAmount;
        int checkDigit;
        try {
            ticketType = Integer.parseInt(parts[0]);
            companyCode = Integer.parseInt(parts[1]);
            purchaseAmount = Integer.parseInt(parts[2]);
            checkDigit = Integer.parseInt(parts[4]);
        } catch (NumberFormatException e) {
            return Result.failed(Error.BAD_FIELD);
        }

        boolean hasRoute = ticketType == 2 || ticketType == 3;
        if (hasRoute && fieldCount != 7) return Result.failed(Error.MALFORMED);
        if (ticketType < 1 || ticketType > 4 || companyCode < 0 || companyCode > 99 || purchaseAmount <= 0) {
            return Result.failed(Error.OUT_OF_RANGE);
        }

        String routeStart = hasRoute ? parts[5] : null;
        String routeEnd = hasRoute ? parts[6] : null;
        if (hasRoute && !(isValidRouteName(routeStart) && isValidRouteName(routeEnd))) return Result.failed(Error.OUT_OF_RANGE);

        String expiryText = parts[3];
        if (!EXPIRY_FORMAT.matcher(expiryText).matches()) return Result.failed(Error.BAD_FIELD);
        LocalDate expiry;
        try {
            expiry = LocalDate.parse(expiryText, EXPIRY_PARSER);
        } catch (DateTimeParseException e) {
            return Result.failed(Error.BAD_FIELD);
        }

        if (checkDigit != computeCheckDigit(ticketType, companyCode, purchaseAmount, expiryText)) return Result.failed(Error.CHECK_DIGIT);

        // 署名を求める設定なら署名なしを拒否し、鍵があれば署名付きのコードは常に検証する
        // (先に発売機だけ署名に対応し、後から require-signature を有効にできるようにする)
        boolean hasSecret = settings.signingSecret != null && !settings.signingSecret.isEmpty();
        if (settings.requireSignature && (signature == null || !hasSecret)) return Result.failed(Error.SIGNATURE_REQUIRED);
        if (signature != null && hasSecret
                && !signatureMatches(signature, settings.signingSecret, ticketType, companyCode, purchaseAmount, expiryText, routeStart, routeEnd)) {
            return Result.failed(Error.SIGNATURE_INVALID);
        }

        if (expiry.isBefore(today)) return Result.failed(Error.EXPIRED);
        if (settings.maxValidityDays > 0 && ChronoUnit.DAYS.between(today, expiry) > settings.maxValidityDays) {
            return Result.failed(Error.VALIDITY_TOO_LONG);
        }
        if ((settings.minPurchaseAmount > 0 && purchaseAmount < settings.minPurchaseAmount)
                || (settings.maxPurchaseAmount > 0 && purchaseAmount > settings.maxPurchaseAmount)) {
            return Result.failed(Error.AMOUNT_NOT_ALLOWED);
        }

        return new Result(new Card(ticketType, companyCode, purchaseAmount, expiry, checkDigit, routeStart, routeEnd), null);
    }

    public static int computeCheckDigit(int ticketType, int companyCode, int purchaseAmount, String expiryDate) {
        String data = String.format("%d%02d%d%s", ticketType, companyCode, purchaseAmount, expiryDate);
        int sum = 0;
        for (int i = 0; i < data.length(); i++) {
            char c = data.charAt(i);
            if (Character.isDigit(c)) {
                int digit = c - '0';
                if (i % 2 == 1) digit *= 2;
                sum += (digit / 10) + (digit % 10);
            }
        }
        return (10 - (sum % 10)) % 10;
    }

    // 発売機が付ける署名 (16 進数)。各項目を "|" でつないだ文字列の HMAC-SHA256 の先頭 128 ビット
    public static String computeSignature(String secret, int ticketType, int companyCode, int purchaseAmount, String expiryDate, String routeStart, String routeEnd) {
        return HexFormat.of().formatHex(signatureBytes(secret, ticketType, companyCode, purchaseAmount, expiryDate, routeStart, routeEnd));
    }

    private static byte[] signatureBytes(String secret, int ticketType, int companyCode, int purchaseAmount, String expiryDate, String routeStart, String routeEnd) {
        String message = ticketType + "|" + companyCode + "|" + purchaseAmount + "|" + expiryDate + "|"
                + (routeStart == null ? "" : routeStart) + "|" + (routeEnd == null ? "" : routeEnd);
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] full = mac.doFinal(message.getBytes(StandardCharsets.UTF_8));
            byte[] truncated = new byte[SIGNATURE_BYTES];
            System.arraycopy(full, 0, truncated, 0, SIGNATURE_BYTES);
            return truncated;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static boolean signatureMatches(String signature, String secret, int ticketType, int companyCode, int purchaseAmount, String expiryDate, String routeStart, String routeEnd) {
        byte[] provided;
        try {
            provided = HexFormat.of().parseHex(signature);
        } catch (IllegalArgumentException e) {
            return false;
        }
        return MessageDigest.isEqual(provided, signatureBytes(secret, ticketType, companyCode, purchaseAmount, expiryDate, routeStart, routeEnd));
    }

    private static boolean isValidRouteName(String name) {
        return name != null && !name.isEmpty() && name.length() <= MAX_ROUTE_LENGTH;
    }
}
