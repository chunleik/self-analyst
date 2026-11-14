package com.selfanalyst.observability;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.util.*;
import java.util.concurrent.*;

import javax.net.ssl.*;

/** Dedicated developer endpoint policy. Does not relax the independent public-only MCP policy. */
public final class ObservabilityEndpointPolicy {
    public static final String SUFFIX = "/api/public/otel/v1/traces";

    @FunctionalInterface
    public interface Resolver {
        InetAddress[] resolve(String host) throws IOException;
    }

    public record ApprovedEndpoint(URI uri, String category, Set<String> addresses, String caPem) {}

    private final Resolver resolver;
    private final ThreadPoolExecutor dns =
            new ThreadPoolExecutor(
                    1,
                    1,
                    0,
                    TimeUnit.SECONDS,
                    new ArrayBlockingQueue<>(1),
                    r -> {
                        Thread t = new Thread(r, "observability-dns");
                        t.setDaemon(true);
                        return t;
                    });

    public ObservabilityEndpointPolicy() {
        this(InetAddress::getAllByName);
    }

    public ObservabilityEndpointPolicy(Resolver resolver) {
        this.resolver = resolver;
    }

    public static URI normalize(String value) {
        try {
            if (value == null
                    || value.length() > 2048
                    || !value.equals(value.trim())
                    || value.chars().anyMatch(c -> c < 33 || c > 126))
                throw new IllegalArgumentException();
            URI uri = new URI(value);
            String host = uri.getHost(), scheme = uri.getScheme();
            if (!Set.of("http", "https").contains(scheme)
                    || host == null
                    || uri.getRawUserInfo() != null
                    || uri.getRawQuery() != null
                    || uri.getRawFragment() != null
                    || uri.getPort() == 0
                    || uri.getPort() > 65535) throw new IllegalArgumentException();
            host = strip(host).toLowerCase(Locale.ROOT);
            if (host.endsWith(".")
                    || host.contains("%")
                    || host.equals("metadata.google.internal")
                    || host.equals("metadata")
                    || host.matches("[0-9.]+") && !host.matches("(?:[0-9]{1,3}\\.){3}[0-9]{1,3}")
                    || host.matches("(?i)0x.*")
                    || host.matches("[0-9]+")) throw new IllegalArgumentException();
            if (host.matches("[0-9.]+"))
                for (String part : host.split("\\.")) {
                    if (Integer.parseInt(part) > 255 || part.length() > 1 && part.startsWith("0"))
                        throw new IllegalArgumentException();
                }
            if (scheme.equals("http") && !Set.of("localhost", "127.0.0.1", "::1").contains(host))
                throw new IllegalArgumentException();
            String path = uri.getRawPath();
            if (path == null) path = "";
            if (path.contains("%")
                    || path.contains("\\")
                    || path.contains("//")
                    || Arrays.asList(path.split("/")).stream()
                            .anyMatch(p -> p.equals(".") || p.equals("..")))
                throw new IllegalArgumentException();
            while (path.endsWith("/")) path = path.substring(0, path.length() - 1);
            if (!path.endsWith(SUFFIX)) path += SUFFIX;
            return new URI(scheme, null, host, uri.getPort(), path, null, null);
        } catch (Exception invalid) {
            throw new IllegalArgumentException("invalid_endpoint");
        }
    }

    public ApprovedEndpoint approve(ObservabilityConfig config) throws IOException {
        URI uri = normalize(config.endpoint());
        List<InetAddress> values = resolve(uri);
        String category = category(values.getFirst());
        if (values.stream().anyMatch(a -> !category(a).equals(category)))
            throw new IOException("endpoint_mixed_addresses");
        if ((uri.getScheme().equals("http") || strip(uri.getHost()).equals("localhost"))
                && !category.equals("loopback")) throw new IOException("endpoint_not_loopback");
        return new ApprovedEndpoint(
                uri,
                category,
                values.stream()
                        .map(InetAddress::getHostAddress)
                        .collect(java.util.stream.Collectors.toUnmodifiableSet()),
                config.caCertificatePem());
    }

    public InetAddress verify(ApprovedEndpoint approved) throws IOException {
        List<InetAddress> values = resolve(approved.uri());
        for (InetAddress address : values) {
            if (!category(address).equals(approved.category())
                    || !approved.category().equals("public")
                            && !approved.addresses().contains(address.getHostAddress()))
                throw new IOException("endpoint_changed");
        }
        return values.getFirst();
    }

    private List<InetAddress> resolve(URI uri) throws IOException {
        Future<InetAddress[]> future;
        try {
            future = dns.submit(() -> resolver.resolve(strip(uri.getHost())));
        } catch (RejectedExecutionException busy) {
            throw new IOException("endpoint_busy");
        }
        try {
            InetAddress[] addresses = future.get(3, TimeUnit.SECONDS);
            if (addresses == null || addresses.length == 0 || addresses.length > 16)
                throw new IOException("endpoint_resolution_failed");
            for (InetAddress address : addresses) category(address);
            return List.of(addresses);
        } catch (InterruptedException cancelled) {
            Thread.currentThread().interrupt();
            throw new IOException("cancelled");
        } catch (TimeoutException | ExecutionException failure) {
            throw new IOException("endpoint_resolution_failed");
        } finally {
            future.cancel(true);
        }
    }

    static String strip(String host) {
        return host.startsWith("[") ? host.substring(1, host.length() - 1) : host;
    }

    static String category(InetAddress address) {
        byte[] b = address.getAddress();
        if (address.getHostAddress().equals("168.63.129.16"))
            throw new IllegalArgumentException("unsafe_endpoint");
        if (address.isAnyLocalAddress()
                || address.isMulticastAddress()
                || address.isLinkLocalAddress())
            throw new IllegalArgumentException("unsafe_endpoint");
        if (address.isLoopbackAddress()) {
            if (!address.getHostAddress().equals("127.0.0.1")
                    && !address.equals(InetAddress.getLoopbackAddress())
                    && b.length != 16) throw new IllegalArgumentException("unsafe_endpoint");
            return "loopback";
        }
        if (b.length == 4) {
            int a = b[0] & 255, c = b[1] & 255;
            if (a == 0
                    || a >= 224
                    || a == 100 && c >= 64 && c <= 127
                    || a == 198 && (c == 18 || c == 19)
                    || a == 192 && c == 0) throw new IllegalArgumentException("unsafe_endpoint");
            if (a == 10 || a == 172 && c >= 16 && c <= 31 || a == 192 && c == 168) return "private";
        } else if ((b[0] & 0xfe) == 0xfc) return "private";
        else if ((b[0] & 0xe0) != 0x20) throw new IllegalArgumentException("unsafe_endpoint");
        // Reuse the conservative special/transition-range exclusions for public addresses
        // without changing MCP's policy or admitting private addresses to MCP.
        if (!com.selfanalyst.integrations.PublicEndpointPolicy.isPublic(address))
            throw new IllegalArgumentException("unsafe_endpoint");
        return "public";
    }

    public static SSLContext sslContext(String pem) {
        try {
            if (pem == null || pem.isBlank()) return SSLContext.getDefault();
            if (!pem.contains("-----BEGIN CERTIFICATE-----") || pem.contains("PRIVATE KEY"))
                throw new IllegalArgumentException();
            var certs =
                    CertificateFactory.getInstance("X.509")
                            .generateCertificates(
                                    new ByteArrayInputStream(
                                            pem.getBytes(StandardCharsets.US_ASCII)));
            if (certs.isEmpty() || certs.size() > 8) throw new IllegalArgumentException();
            KeyStore store = KeyStore.getInstance(KeyStore.getDefaultType());
            store.load(null);
            int i = 0;
            for (var cert : certs) store.setCertificateEntry("observability-" + i++, cert);
            TrustManagerFactory factory =
                    TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            factory.init(store);
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, factory.getTrustManagers(), null);
            return context;
        } catch (Exception invalid) {
            throw new IllegalArgumentException("invalid_ca_certificate");
        }
    }

    public void close() {
        dns.shutdownNow();
    }
}
