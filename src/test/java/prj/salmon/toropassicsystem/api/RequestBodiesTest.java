package prj.salmon.toropassicsystem.api;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class RequestBodiesTest {

    // 1 回の read() で 1 バイトしか返さないストリーム (ネットワーク越しの分割受信を再現)
    private static InputStream trickle(byte[] data) {
        return new ByteArrayInputStream(data) {
            @Override
            public synchronized int read(byte[] b, int off, int len) {
                return super.read(b, off, Math.min(1, len));
            }
        };
    }

    @Test
    void readsWholeBodyEvenWhenDeliveredInPieces() throws IOException {
        byte[] body = "{\"playername\":\"とろ\",\"password\":\"abc\"}".getBytes(StandardCharsets.UTF_8);
        String result = RequestBodies.read(trickle(body), String.valueOf(body.length), 16384);
        assertEquals("{\"playername\":\"とろ\",\"password\":\"abc\"}", result);
    }

    @Test
    void rejectsBodyLargerThanLimitWithoutReading() {
        InputStream in = new InputStream() {
            @Override
            public int read() {
                fail("body must not be read");
                return -1;
            }
        };
        RequestBodies.BodyException e = assertThrows(RequestBodies.BodyException.class,
                () -> RequestBodies.read(in, "2147483647", 16384));
        assertEquals(413, e.getStatus());
    }

    @Test
    void acceptsBodyExactlyAtLimit() throws IOException {
        byte[] body = new byte[16];
        assertEquals(16, RequestBodies.read(new ByteArrayInputStream(body), "16", 16).length());
    }

    @Test
    void rejectsInvalidContentLength() {
        for (String header : new String[]{"abc", "-1", "1.5", "99999999999999999999"}) {
            RequestBodies.BodyException e = assertThrows(RequestBodies.BodyException.class,
                    () -> RequestBodies.read(new ByteArrayInputStream(new byte[0]), header, 16384), header);
            assertEquals(400, e.getStatus(), header);
        }
    }

    @Test
    void rejectsTruncatedBody() {
        RequestBodies.BodyException e = assertThrows(RequestBodies.BodyException.class,
                () -> RequestBodies.read(new ByteArrayInputStream(new byte[5]), "10", 16384));
        assertEquals(400, e.getStatus());
    }

    @Test
    void missingContentLengthMeansEmptyBody() throws IOException {
        assertEquals("", RequestBodies.read(new ByteArrayInputStream(new byte[10]), null, 16384));
    }
}
