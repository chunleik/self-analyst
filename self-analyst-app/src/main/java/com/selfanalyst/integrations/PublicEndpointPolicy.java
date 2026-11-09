package com.selfanalyst.integrations;

import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** Strict public HTTPS target policy. DNS checks alone are NOT a safe HTTP transport. */
public final class PublicEndpointPolicy {
    @FunctionalInterface public interface Resolver { InetAddress[] resolve(String host) throws IOException; }
    private final Resolver resolver;
    public PublicEndpointPolicy() { this(InetAddress::getAllByName); }
    public PublicEndpointPolicy(Resolver resolver) { this.resolver = java.util.Objects.requireNonNull(resolver); }

    /** Local syntax validation only. Configuration save must never trigger DNS. */
    public static URI validate(String endpoint) {
        try {
            if (endpoint == null || endpoint.length() > 2048 || endpoint.indexOf('\\') >= 0)
                throw new IllegalArgumentException("invalid_endpoint");
            URI uri = new URI(endpoint);
            String host = uri.getHost();
            if (!"https".equalsIgnoreCase(uri.getScheme()) || host == null || uri.getRawUserInfo() != null
                    || uri.getRawQuery() != null || uri.getRawFragment() != null || uri.isOpaque()
                    || (uri.getPort() != -1 && (uri.getPort() < 1 || uri.getPort() > 65535)))
                throw new IllegalArgumentException("invalid_endpoint");
            host = host.toLowerCase(Locale.ROOT);
            if (host.startsWith("[")) host = host.substring(1, host.length() - 1);
            if (host.contains("%") || host.endsWith(".") || host.equals("localhost") || host.endsWith(".localhost")
                    || host.endsWith(".local") || host.endsWith(".internal") || host.endsWith(".invalid")
                    || host.endsWith(".test") || (!host.contains(".") && !host.contains(":")))
                throw new IllegalArgumentException("non_public_endpoint");
            if (host.contains(":") || host.matches("[0-9.]+")) {
                if (!host.contains(":") && !host.matches("(?:[0-9]{1,3}\\.){3}[0-9]{1,3}"))
                    throw new IllegalArgumentException("invalid_endpoint");
                InetAddress literal;
                if (host.contains(":")) literal = InetAddress.getByName(host);
                else {
                    byte[] bytes = new byte[4]; String[] octets = host.split("\\.");
                    for (int i = 0; i < 4; i++) {
                        int value = Integer.parseInt(octets[i]);
                        if (value > 255 || (octets[i].length() > 1 && octets[i].startsWith("0")))
                            throw new IllegalArgumentException("invalid_endpoint");
                        bytes[i] = (byte) value;
                    }
                    literal = InetAddress.getByAddress(bytes);
                }
                if (!isPublic(literal)) throw new IllegalArgumentException("non_public_endpoint");
            }
            String path = uri.getRawPath();
            if (path == null || path.isEmpty()) path = "/";
            // Preserve encoded path exactly; credentials are bound to this canonical URI.
            return new URI("https://" + (host.contains(":") ? "[" + host + "]" : host)
                    + (uri.getPort() == -1 || uri.getPort() == 443 ? "" : ":" + uri.getPort()) + path);
        } catch (IOException | URISyntaxException e) {
            throw new IllegalArgumentException("invalid_endpoint");
        }
    }

    public PinnedEndpoint resolve(URI endpoint) throws IOException {
        URI canonical = validate(endpoint.toASCIIString());
        String host = canonical.getHost().replace("[", "").replace("]", "");
        InetAddress[] addresses;
        try { addresses = resolver.resolve(host); }
        catch (IOException | RuntimeException failure) { throw new IOException("endpoint_resolution_failed"); }
        if (addresses == null || addresses.length == 0 || addresses.length > 32) throw new IOException("endpoint_resolution_failed");
        for (InetAddress address : addresses) if (address == null || !isPublic(address)) throw new IOException("non_public_endpoint");
        return new PinnedEndpoint(canonical, Arrays.asList(addresses.clone()));
    }
    public PinnedEndpoint resolve(String endpoint) throws IOException { return resolve(validate(endpoint)); }

    public static boolean isPublic(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) return false;
        byte[] a = address.getAddress();
        int x = a[0] & 255, y = a[1] & 255;
        if (a.length == 4) {
            int z = a[2] & 255;
            return !(x == 0 || x == 10 || x == 127 || x >= 224 || (x == 100 && y >= 64 && y <= 127)
                    || (x == 169 && y == 254) || (x == 168 && y == 63 && z == 129 && (a[3] & 255) == 16)
                    || (x == 172 && y >= 16 && y <= 31)
                    || (x == 192 && (y == 168 || (y == 0 && (z == 0 || z == 2)) || (y == 88 && z == 99)))
                    || (x == 198 && (y == 18 || y == 19 || (y == 51 && z == 100)))
                    || (x == 203 && y == 0 && z == 113));
        }
        // Conservative global-unicast allowlist, excluding transition, special and documentation ranges.
        if (a.length != 16 || (x & 0xe0) != 0x20) return false;
        int z = a[2] & 255, w = a[3] & 255;
        return !(x == 0x20 && y == 0x01 && (z <= 1 || (z == 0x0d && w == 0xb8)))
                && !(x == 0x20 && y == 0x02) && !(x == 0x3f && y == 0xff && (z & 0xf0) == 0);
    }

    public static final class PinnedEndpoint {
        private final URI uri;
        private final List<InetAddress> addresses;
        private PinnedEndpoint(URI uri, List<InetAddress> addresses) { this.uri = uri; this.addresses = List.copyOf(addresses); }
        public URI uri() { return uri; }
        public List<InetAddress> addresses() { return addresses; }

        /** Connects directly to a checked address; no proxy, redirects or second DNS lookup. */
        public SSLSocket openSocket(Duration timeout) throws IOException {
            return openSocket(timeout, ignored -> { });
        }
        public SSLSocket openSocket(Duration timeout, java.util.function.Consumer<SSLSocket> connected) throws IOException {
            long millis = timeout.toMillis();
            if (millis < 1 || millis > 30000) throw new IllegalArgumentException("invalid_timeout");
            String host = uri.getHost().replace("[", "").replace("]", "");
            int port = uri.getPort() == -1 ? 443 : uri.getPort();
            Socket raw = new Socket();
            try {
                raw.connect(new InetSocketAddress(addresses.getFirst(), port), (int) millis);
                raw.setSoTimeout((int) millis);
                SSLSocket tls = (SSLSocket) ((SSLSocketFactory) SSLSocketFactory.getDefault()).createSocket(raw, host, port, true);
                SSLParameters parameters = tls.getSSLParameters();
                parameters.setEndpointIdentificationAlgorithm("HTTPS");
                if (!host.contains(":") && !host.matches("[0-9.]+")) parameters.setServerNames(List.of(new SNIHostName(host)));
                tls.setSSLParameters(parameters);
                connected.accept(tls); // Cancellation may close the socket even during the TLS handshake.
                if (Thread.currentThread().isInterrupted()) throw new IOException("endpoint_cancelled");
                tls.startHandshake();
                return tls;
            } catch (IOException | RuntimeException failure) {
                raw.close();
                throw new IOException("endpoint_connection_failed");
            }
        }
    }
}
