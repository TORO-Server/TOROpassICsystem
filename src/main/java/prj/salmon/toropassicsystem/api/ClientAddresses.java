package prj.salmon.toropassicsystem.api;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.regex.Pattern;

/**
 * クライアントIPの形式確認と、信頼するプロキシの CIDR 判定を行う。
 * 文字列の解析で DNS 問い合わせが発生しないよう、IPリテラルの形式を確認してから InetAddress に変換する。
 */
public final class ClientAddresses {
    private static final Pattern IPV4 = Pattern.compile("\\d{1,3}(\\.\\d{1,3}){3}");
    private static final Pattern IPV6 = Pattern.compile("[0-9A-Fa-f:.]{2,45}");

    private ClientAddresses() {}

    // IPリテラルを InetAddress に変換する。IPリテラルでなければ null
    public static InetAddress parseLiteral(String value) {
        if (value == null) return null;
        String s = value.trim();
        // リンクローカルアドレスのゾーン指定 (%eth0 など) は判定に使わない
        int zone = s.indexOf('%');
        if (zone >= 0) s = s.substring(0, zone);
        try {
            if (IPV4.matcher(s).matches()) {
                String[] parts = s.split("\\.");
                byte[] bytes = new byte[4];
                for (int i = 0; i < 4; i++) {
                    int octet = Integer.parseInt(parts[i]);
                    if (octet > 255) return null;
                    bytes[i] = (byte) octet;
                }
                return InetAddress.getByAddress(bytes);
            }
            // ":" を含む文字列は IPv6 リテラルとして解析され、名前解決されない
            if (s.indexOf(':') >= 0 && IPV6.matcher(s).matches()) return InetAddress.getByName(s);
        } catch (UnknownHostException e) {
            return null;
        }
        return null;
    }

    // レート制限と認証失敗の追跡に使うキー
    // IPv6 は 1 つの回線に割り当てられる /64 単位でまとめ、アドレスを変えても別のクライアントとして数えない
    public static String trackingKey(InetAddress address) {
        byte[] bytes = address.getAddress();
        if (bytes.length == 4) return address.getHostAddress();
        byte[] prefix = new byte[16];
        System.arraycopy(bytes, 0, prefix, 0, 8);
        try {
            return InetAddress.getByAddress(prefix).getHostAddress() + "/64";
        } catch (UnknownHostException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * CIDR 表記のアドレス範囲。
     */
    public static final class Cidr {
        private final byte[] network;
        private final int prefixLength;

        private Cidr(byte[] network, int prefixLength) {
            this.network = network;
            this.prefixLength = prefixLength;
        }

        // "10.0.0.0/8" や "::1/128" を解析する。プレフィックス長を省略すると単一のアドレスになる
        public static Cidr parse(String text) {
            if (text == null) throw new IllegalArgumentException("CIDR is null");
            String trimmed = text.trim();
            int slash = trimmed.indexOf('/');
            InetAddress address = parseLiteral(slash >= 0 ? trimmed.substring(0, slash) : trimmed);
            if (address == null) throw new IllegalArgumentException("Invalid CIDR: " + text);
            byte[] bytes = address.getAddress();
            int prefix = bytes.length * 8;
            if (slash >= 0) {
                try {
                    prefix = Integer.parseInt(trimmed.substring(slash + 1));
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("Invalid CIDR: " + text);
                }
                if (prefix < 0 || prefix > bytes.length * 8) throw new IllegalArgumentException("Invalid CIDR: " + text);
            }
            return new Cidr(bytes, prefix);
        }

        public boolean contains(InetAddress address) {
            byte[] bytes = address.getAddress();
            if (bytes.length != network.length) return false;
            int remaining = prefixLength;
            for (int i = 0; i < bytes.length && remaining > 0; i++) {
                int mask = remaining >= 8 ? 0xFF : (0xFF << (8 - remaining)) & 0xFF;
                if ((bytes[i] & mask) != (network[i] & mask)) return false;
                remaining -= 8;
            }
            return true;
        }
    }
}
