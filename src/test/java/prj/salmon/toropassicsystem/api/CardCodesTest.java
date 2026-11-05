package prj.salmon.toropassicsystem.api;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.*;

class CardCodesTest {
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 5);
    private static final String SECRET = "test-signing-secret";

    private static String code(int type, int company, int amount, String expiry) {
        return type + "-" + String.format("%02d", company) + "-" + amount + "-" + expiry + "-" + CardCodes.computeCheckDigit(type, company, amount, expiry);
    }

    private static CardCodes.Result parse(String code) {
        return CardCodes.parse(code, new CardCodes.Settings(), TODAY);
    }

    private static CardCodes.Error errorOf(String code) {
        CardCodes.Result result = parse(code);
        assertFalse(result.ok());
        return result.error();
    }

    @Test
    void checkDigitKeepsTheIssuedFormat() {
        // 発売機が発行するコードと同じ計算であること (値は計算方法から手で求めた)
        assertEquals(2, CardCodes.computeCheckDigit(1, 0, 1000, "20261231"));
        assertEquals(2, CardCodes.computeCheckDigit(1, 5, 2500, "20270131"));
        assertEquals(7, CardCodes.computeCheckDigit(3, 12, 5000, "20261231"));
    }

    @Test
    void acceptsCodesThatAreIssuedToday() {
        CardCodes.Result allLine = parse(code(1, 0, 1000, "20261231"));
        assertTrue(allLine.ok());
        assertEquals(1, allLine.card().ticketType());
        assertEquals(0, allLine.card().companyCode());
        assertEquals(1000, allLine.card().purchaseAmount());
        assertEquals(LocalDate.of(2026, 12, 31), allLine.card().expiry());
        assertNull(allLine.card().routeStart());

        CardCodes.Result route = parse(code(2, 5, 800, "20261231") + "-StationA-StationB");
        assertTrue(route.ok());
        assertEquals("StationA", route.card().routeStart());
        assertEquals("StationB", route.card().routeEnd());

        // 1 日乗車券は当日の日付で発行される
        assertTrue(parse(code(4, 1, 300, "20261105")).ok());
    }

    @Test
    void routeFieldsAreIgnoredForTicketsWithoutRoute() {
        CardCodes.Result result = parse(code(1, 0, 1000, "20261231") + "-StationA-StationB");
        assertTrue(result.ok());
        assertNull(result.card().routeStart());
        assertNull(result.card().routeEnd());
    }

    @Test
    void expiryIsTheEndOfThatDay() {
        CardCodes.Card card = parse(code(1, 0, 1000, "20261231")).card();
        ZoneId tokyo = ZoneId.of("Asia/Tokyo");
        assertEquals(LocalDate.of(2027, 1, 1).atStartOfDay(tokyo).toInstant().toEpochMilli() - 1, card.expiryEpochMillis(tokyo));
    }

    @Test
    void rejectsMalformedCodes() {
        assertEquals(CardCodes.Error.MALFORMED, errorOf(null));
        assertEquals(CardCodes.Error.MALFORMED, errorOf(""));
        assertEquals(CardCodes.Error.MALFORMED, errorOf("1-00-1000"));
        assertEquals(CardCodes.Error.MALFORMED, errorOf("x".repeat(600)));
        // 通勤・通学定期には区間が必要
        assertEquals(CardCodes.Error.MALFORMED, errorOf(code(2, 0, 1000, "20261231")));
        assertEquals(CardCodes.Error.MALFORMED, errorOf(code(3, 0, 1000, "20261231") + "-OnlyOne"));
    }

    @Test
    void rejectsBadFields() {
        assertEquals(CardCodes.Error.BAD_FIELD, errorOf("a-00-1000-20261231-1"));
        assertEquals(CardCodes.Error.BAD_FIELD, errorOf("1-00-99999999999-20261231-1"));
        assertEquals(CardCodes.Error.BAD_FIELD, errorOf(code(1, 0, 1000, "2026123")));
        assertEquals(CardCodes.Error.BAD_FIELD, errorOf(code(1, 0, 1000, "20261301")));
        assertEquals(CardCodes.Error.BAD_FIELD, errorOf(code(1, 0, 1000, "20270230")));
        assertEquals(CardCodes.Error.BAD_FIELD, errorOf("1-00-1000-2026123x-1"));
    }

    @Test
    void rejectsValuesOutOfRange() {
        assertEquals(CardCodes.Error.OUT_OF_RANGE, errorOf(code(0, 0, 1000, "20261231")));
        assertEquals(CardCodes.Error.OUT_OF_RANGE, errorOf(code(5, 0, 1000, "20261231")));
        assertEquals(CardCodes.Error.OUT_OF_RANGE, errorOf(code(1, 100, 1000, "20261231")));
        assertEquals(CardCodes.Error.OUT_OF_RANGE, errorOf(code(1, 0, 0, "20261231")));
        assertEquals(CardCodes.Error.OUT_OF_RANGE, errorOf(code(2, 0, 1000, "20261231") + "-" + "x".repeat(65) + "-StationB"));
    }

    @Test
    void rejectsWrongCheckDigit() {
        String expiry = "20261231";
        int digit = CardCodes.computeCheckDigit(1, 0, 1000, expiry);
        assertEquals(CardCodes.Error.CHECK_DIGIT, errorOf("1-00-1000-" + expiry + "-" + ((digit + 1) % 10)));
    }

    @Test
    void rejectsExpiredCodes() {
        assertEquals(CardCodes.Error.EXPIRED, errorOf(code(1, 0, 1000, "20261104")));
    }

    @Test
    void stricterLimitsAreOptional() {
        CardCodes.Settings settings = new CardCodes.Settings();
        settings.maxValidityDays = 30;
        settings.minPurchaseAmount = 100;
        settings.maxPurchaseAmount = 5000;

        assertTrue(CardCodes.parse(code(1, 0, 1000, "20261205"), settings, TODAY).ok());
        assertEquals(CardCodes.Error.VALIDITY_TOO_LONG, CardCodes.parse(code(1, 0, 1000, "20261206"), settings, TODAY).error());
        assertEquals(CardCodes.Error.AMOUNT_NOT_ALLOWED, CardCodes.parse(code(1, 0, 99, "20261205"), settings, TODAY).error());
        assertEquals(CardCodes.Error.AMOUNT_NOT_ALLOWED, CardCodes.parse(code(1, 0, 5001, "20261205"), settings, TODAY).error());
    }

    private static String signed(String code, int type, int company, int amount, String expiry, String routeStart, String routeEnd) {
        return code + "-sig." + CardCodes.computeSignature(SECRET, type, company, amount, expiry, routeStart, routeEnd);
    }

    private static CardCodes.Settings signatureSettings(boolean required, String secret) {
        CardCodes.Settings settings = new CardCodes.Settings();
        settings.requireSignature = required;
        settings.signingSecret = secret;
        return settings;
    }

    @Test
    void signatureIsVerifiedWhenRequired() {
        CardCodes.Settings settings = signatureSettings(true, SECRET);
        String plain = code(1, 0, 1000, "20261231");
        assertEquals(CardCodes.Error.SIGNATURE_REQUIRED, CardCodes.parse(plain, settings, TODAY).error());
        assertTrue(CardCodes.parse(signed(plain, 1, 0, 1000, "20261231", null, null), settings, TODAY).ok());

        String route = code(2, 0, 800, "20261231") + "-StationA-StationB";
        assertTrue(CardCodes.parse(signed(route, 2, 0, 800, "20261231", "StationA", "StationB"), settings, TODAY).ok());
    }

    @Test
    void signatureCoversEveryField() {
        CardCodes.Settings settings = signatureSettings(true, SECRET);
        String signature = "-sig." + CardCodes.computeSignature(SECRET, 1, 0, 1000, "20261231", null, null);
        // 署名の後で金額や期限を変えたコードは、チェックデジットが合っていても受け付けない
        assertEquals(CardCodes.Error.SIGNATURE_INVALID, CardCodes.parse(code(1, 0, 900, "20261231") + signature, settings, TODAY).error());
        assertEquals(CardCodes.Error.SIGNATURE_INVALID, CardCodes.parse(code(1, 0, 1000, "20270131") + signature, settings, TODAY).error());

        String routeSignature = "-sig." + CardCodes.computeSignature(SECRET, 2, 0, 800, "20261231", "StationA", "StationB");
        assertEquals(CardCodes.Error.SIGNATURE_INVALID, CardCodes.parse(code(2, 0, 800, "20261231") + "-StationA-StationC" + routeSignature, settings, TODAY).error());
    }

    @Test
    void malformedSignatureIsRejected() {
        CardCodes.Settings settings = signatureSettings(true, SECRET);
        String plain = code(1, 0, 1000, "20261231");
        assertEquals(CardCodes.Error.SIGNATURE_INVALID, CardCodes.parse(plain + "-sig.zz", settings, TODAY).error());
        assertEquals(CardCodes.Error.SIGNATURE_INVALID, CardCodes.parse(plain + "-sig.", settings, TODAY).error());
    }

    @Test
    void requireSignatureWithoutSecretRejectsEverything() {
        CardCodes.Settings settings = signatureSettings(true, "");
        String plain = code(1, 0, 1000, "20261231");
        assertEquals(CardCodes.Error.SIGNATURE_REQUIRED, CardCodes.parse(plain, settings, TODAY).error());
        assertEquals(CardCodes.Error.SIGNATURE_REQUIRED, CardCodes.parse(signed(plain, 1, 0, 1000, "20261231", null, null), settings, TODAY).error());
    }

    @Test
    void signedCodesAreAcceptedBeforeSignatureIsRequired() {
        String plain = code(1, 0, 1000, "20261231");
        // 鍵を設定していない間は署名を見ない (発売機だけ先に署名に対応できる)
        assertTrue(CardCodes.parse(signed(plain, 1, 0, 1000, "20261231", null, null), new CardCodes.Settings(), TODAY).ok());
        // 鍵を設定すると、必須でなくても署名付きのコードは検証する
        CardCodes.Settings optional = signatureSettings(false, SECRET);
        assertTrue(CardCodes.parse(plain, optional, TODAY).ok());
        assertTrue(CardCodes.parse(signed(plain, 1, 0, 1000, "20261231", null, null), optional, TODAY).ok());
        assertEquals(CardCodes.Error.SIGNATURE_INVALID, CardCodes.parse(plain + "-sig.00000000000000000000000000000000", optional, TODAY).error());
    }
}
