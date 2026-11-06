package prj.salmon.toropassicsystem.api;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * HTTPリクエストボディをサイズ上限付きで最後まで読み込む。
 */
public final class RequestBodies {
    private RequestBodies() {}

    public static String read(InputStream in, String contentLengthHeader, int maxBytes) throws IOException {
        if (contentLengthHeader == null || contentLengthHeader.isBlank()) return "";
        long contentLength;
        try {
            contentLength = Long.parseLong(contentLengthHeader.trim());
        } catch (NumberFormatException e) {
            throw new BodyException(400, "Invalid Content-Length");
        }
        if (contentLength < 0) throw new BodyException(400, "Invalid Content-Length");
        if (contentLength > maxBytes) throw new BodyException(413, "Request body too large");

        // read() は一度に全て読めるとは限らないため、readNBytes で指定サイズまで読み切る
        byte[] buffer = in.readNBytes((int) contentLength);
        if (buffer.length != contentLength) throw new BodyException(400, "Incomplete request body");
        return new String(buffer, StandardCharsets.UTF_8);
    }

    // 読まないボディを maxBytes まで読み捨てる。読み残したまま接続を閉じると、クライアントが応答を受け取る前に接続がリセットされることがある
    public static void discard(InputStream in, String contentLengthHeader, int maxBytes) {
        if (contentLengthHeader == null || contentLengthHeader.isBlank()) return;
        try {
            long remaining = Math.min(Long.parseLong(contentLengthHeader.trim()), maxBytes);
            byte[] buffer = new byte[4096];
            while (remaining > 0) {
                int read = in.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                if (read < 0) return;
                remaining -= read;
            }
        } catch (NumberFormatException | IOException ignored) {
            // 接続を閉じるだけなので、読めなくても構わない
        }
    }

    public static class BodyException extends IOException {
        private final int status;

        public BodyException(int status, String message) {
            super(message);
            this.status = status;
        }

        public int getStatus() {
            return status;
        }
    }
}
