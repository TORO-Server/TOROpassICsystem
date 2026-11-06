package prj.salmon.toropassicsystem.api;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class WebPasswordsTest {
    @Test
    void tokensAreLongAndDistinct() {
        Set<String> tokens = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            String token = WebPasswords.generateToken();
            assertTrue(token.matches("[0-9A-HJKMNP-TV-Z]{4}(-[0-9A-HJKMNP-TV-Z]{4}){3}"), token);
            tokens.add(token);
        }
        assertEquals(1000, tokens.size());
    }

    @Test
    void tokenHashMatchesOnlyTheToken() {
        String token = WebPasswords.generateToken();
        String stored = WebPasswords.hashToken(token);
        assertFalse(stored.contains(token));
        assertFalse(stored.contains(token.replace("-", "")));
        assertTrue(WebPasswords.matches(stored, token));
        assertFalse(WebPasswords.matches(stored, WebPasswords.generateToken()));
        assertFalse(WebPasswords.matches(stored, ""));
    }

    @Test
    void tokenInputIgnoresHyphensSpacesAndCase() {
        String token = WebPasswords.generateToken();
        String stored = WebPasswords.hashToken(token);
        assertTrue(WebPasswords.matches(stored, token.replace("-", "")));
        assertTrue(WebPasswords.matches(stored, token.toLowerCase()));
        assertTrue(WebPasswords.matches(stored, " " + token + " "));
    }

    @Test
    void sameTokenHashesDifferentlyEachTime() {
        String token = WebPasswords.generateToken();
        assertNotEquals(WebPasswords.hashToken(token), WebPasswords.hashToken(token));
    }

    @Test
    void legacyPasswordIsComparedExactly() {
        String stored = WebPasswords.hashLegacyPassword("Ab3dEf7h");
        assertFalse(stored.contains("Ab3dEf7h"));
        assertTrue(WebPasswords.matches(stored, "Ab3dEf7h"));
        assertFalse(WebPasswords.matches(stored, "ab3dEf7h"));
        assertFalse(WebPasswords.matches(stored, "Ab3dEf7"));
        assertFalse(WebPasswords.matches(stored, ""));
    }

    @Test
    void unsetOrMalformedHashNeverMatches() {
        // パスワード未設定 (/webcharge 未実行) のユーザーは常に失敗
        assertFalse(WebPasswords.matches(null, "anything"));
        assertFalse(WebPasswords.matches(WebPasswords.hashToken("x"), null));
        assertFalse(WebPasswords.matches("plaintext-value", "plaintext-value"));
        assertFalse(WebPasswords.matches("sha256$not base64$not base64", "x"));
        assertFalse(WebPasswords.matches("pbkdf2-sha256$abc$AAAA$AAAA", "x"));
        assertFalse(WebPasswords.matches("pbkdf2-sha256$0$AAAA$AAAA", "x"));
    }

    @Test
    void overlongInputIsRejected() {
        String token = WebPasswords.generateToken();
        assertFalse(WebPasswords.matches(WebPasswords.hashToken(token), token + " ".repeat(200)));
    }
}
