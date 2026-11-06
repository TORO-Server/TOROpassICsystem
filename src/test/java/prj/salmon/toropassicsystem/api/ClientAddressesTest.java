package prj.salmon.toropassicsystem.api;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;

import static org.junit.jupiter.api.Assertions.*;

class ClientAddressesTest {
    @Test
    void parsesIpLiterals() {
        assertEquals("203.0.113.7", ClientAddresses.parseLiteral("203.0.113.7").getHostAddress());
        assertEquals("203.0.113.7", ClientAddresses.parseLiteral(" 203.0.113.7 ").getHostAddress());
        assertEquals("2001:db8:0:0:0:0:0:1", ClientAddresses.parseLiteral("2001:db8::1").getHostAddress());
        // IPv4 射影アドレスは IPv4 として扱う
        assertEquals("203.0.113.7", ClientAddresses.parseLiteral("::ffff:203.0.113.7").getHostAddress());
        assertEquals("fe80:0:0:0:0:0:0:1", ClientAddresses.parseLiteral("fe80::1%eth0").getHostAddress());
    }

    @Test
    void rejectsAnythingElseWithoutNameLookup() {
        assertNull(ClientAddresses.parseLiteral(null));
        assertNull(ClientAddresses.parseLiteral(""));
        assertNull(ClientAddresses.parseLiteral("localhost"));
        assertNull(ClientAddresses.parseLiteral("example.com"));
        assertNull(ClientAddresses.parseLiteral("256.0.0.1"));
        assertNull(ClientAddresses.parseLiteral("1.2.3"));
        assertNull(ClientAddresses.parseLiteral("1.2.3.4.5"));
        assertNull(ClientAddresses.parseLiteral("2001:db8:::1"));
        assertNull(ClientAddresses.parseLiteral("abcd"));
    }

    @Test
    void cidrMembership() {
        InetAddress inside = ClientAddresses.parseLiteral("172.18.0.5");
        InetAddress outside = ClientAddresses.parseLiteral("172.32.0.1");
        ClientAddresses.Cidr docker = ClientAddresses.Cidr.parse("172.16.0.0/12");
        assertTrue(docker.contains(inside));
        assertFalse(docker.contains(outside));
        assertFalse(docker.contains(ClientAddresses.parseLiteral("2001:db8::1")));

        ClientAddresses.Cidr ula = ClientAddresses.Cidr.parse("fc00::/7");
        assertTrue(ula.contains(ClientAddresses.parseLiteral("fd12:3456::1")));
        assertFalse(ula.contains(ClientAddresses.parseLiteral("2001:db8::1")));

        // プレフィックス長を省略すると単一のアドレス
        assertTrue(ClientAddresses.Cidr.parse("192.0.2.1").contains(ClientAddresses.parseLiteral("192.0.2.1")));
        assertFalse(ClientAddresses.Cidr.parse("192.0.2.1").contains(ClientAddresses.parseLiteral("192.0.2.2")));
        // /0 はすべてのアドレスに一致する
        assertTrue(ClientAddresses.Cidr.parse("0.0.0.0/0").contains(outside));
    }

    @Test
    void invalidCidrIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> ClientAddresses.Cidr.parse("not-a-cidr"));
        assertThrows(IllegalArgumentException.class, () -> ClientAddresses.Cidr.parse("10.0.0.0/33"));
        assertThrows(IllegalArgumentException.class, () -> ClientAddresses.Cidr.parse("10.0.0.0/x"));
        assertThrows(IllegalArgumentException.class, () -> ClientAddresses.Cidr.parse(null));
    }

    @Test
    void trackingKeyGroupsIpv6ByNetwork() {
        assertEquals("203.0.113.7", ClientAddresses.trackingKey(ClientAddresses.parseLiteral("203.0.113.7")));
        assertEquals(
                ClientAddresses.trackingKey(ClientAddresses.parseLiteral("2001:db8:1:2::1")),
                ClientAddresses.trackingKey(ClientAddresses.parseLiteral("2001:db8:1:2:ffff::9")));
        assertNotEquals(
                ClientAddresses.trackingKey(ClientAddresses.parseLiteral("2001:db8:1:2::1")),
                ClientAddresses.trackingKey(ClientAddresses.parseLiteral("2001:db8:1:3::1")));
    }
}
