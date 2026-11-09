package com.selfanalyst.integrations;

import org.junit.jupiter.api.Test;
import java.net.InetAddress;
import java.net.URI;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class PublicEndpointPolicyTest {
    @Test void rejectsCredentialQueryFragmentLocalAndAmbiguousEndpointsWithoutResolving() {
        for (String endpoint : List.of("http://example.com/api", "https://user:pass@example.com", "https://example.com/?key=x",
                "https://example.com/#x", "https://localhost/", "https://foo.local/", "https://foo.internal/",
                "https://127.0.0.1/", "https://127.1/", "https://[::1]/", "https://[fe80::1%25eth0]/",
                "https://169.254.169.254/", "https://10.2.3.4/", "https://192.168.1.1/", "https://2130706433/",
                "https://0177.0.0.1/", "https://999.1.1.1/", "https://example.com./", "https://example.com\\@evil.com/")) {
            assertThrows(IllegalArgumentException.class, () -> PublicEndpointPolicy.validate(endpoint), endpoint);
        }
        assertEquals("https://example.com/a%20b", PublicEndpointPolicy.validate("https://EXAMPLE.com:443/a%20b").toString());
    }
    @Test void deniesIpv4Ipv6PrivateAndSpecialUseNetworks() throws Exception {
        for (String ip : List.of("0.0.0.0", "10.0.0.1", "100.64.0.1", "127.0.0.2", "169.254.169.254",
                "168.63.129.16", "172.16.0.1", "172.31.255.255", "192.0.0.1", "192.0.2.1", "192.168.1.1", "198.18.0.1",
                "198.51.100.1", "203.0.113.1", "224.0.0.1", "255.255.255.255", "::", "::1", "fc00::1",
                "fd00:ec2::254", "fe80::1", "ff02::1", "2001:db8::1", "2002:7f00:1::", "64:ff9b::7f00:1",
                "::ffff:127.0.0.1", "3fff::1")) {
            assertFalse(PublicEndpointPolicy.isPublic(InetAddress.getByName(ip)), ip);
        }
        for (String ip : List.of("8.8.8.8", "1.1.1.1", "2606:4700:4700::1111", "2001:4860:4860::8888"))
            assertTrue(PublicEndpointPolicy.isPublic(InetAddress.getByName(ip)), ip);
    }
    @Test void rejectsAnyPrivateDnsAnswerAndRevalidatesEveryConnection() throws Exception {
        var uri = URI.create("https://mcp.example.com/");
        var mixed = new PublicEndpointPolicy(host -> new InetAddress[]{InetAddress.getByName("8.8.8.8"), InetAddress.getByName("127.0.0.1")});
        assertThrows(IOException.class, () -> mixed.resolve(uri));
        AtomicInteger calls = new AtomicInteger();
        var changed = new PublicEndpointPolicy(host -> new InetAddress[]{InetAddress.getByName(calls.getAndIncrement() == 0 ? "8.8.8.8" : "10.0.0.1")});
        assertEquals("8.8.8.8", changed.resolve(uri).addresses().getFirst().getHostAddress());
        assertThrows(IOException.class, () -> changed.resolve(uri));
        assertEquals(2, calls.get());
    }
}
