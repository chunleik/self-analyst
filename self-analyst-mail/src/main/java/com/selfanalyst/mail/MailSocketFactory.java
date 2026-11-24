package com.selfanalyst.mail;

import javax.net.ssl.HandshakeCompletedListener;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** Counts plaintext protocol bytes, including encoded MIME and server metadata, across all session sockets. */
final class MailSocketFactory extends SSLSocketFactory implements AutoCloseable {
    private final SSLSocketFactory delegate;
    private final Set<Socket> sockets = ConcurrentHashMap.newKeySet();
    private final AtomicLong received = new AtomicLong();
    private final long maxBytes;
    private final MailCancellation cancellation;
    private final AutoCloseable cancellationRegistration;
    MailSocketFactory(MailCancellation cancellation, long maxBytes) {
        this(cancellation, maxBytes, (SSLSocketFactory) SSLSocketFactory.getDefault());
    }
    MailSocketFactory(MailCancellation cancellation, long maxBytes, SSLSocketFactory delegate) {
        this.maxBytes = maxBytes; this.cancellation = cancellation; this.delegate = delegate;
        cancellationRegistration = cancellation.onCancel(this::closeSockets);
    }
    @Override public String[] getDefaultCipherSuites() { return delegate.getDefaultCipherSuites(); }
    @Override public String[] getSupportedCipherSuites() { return delegate.getSupportedCipherSuites(); }
    @Override public Socket createSocket() throws IOException { return wrap(delegate.createSocket()); }
    @Override public Socket createSocket(String host, int port) throws IOException { return wrap(delegate.createSocket(host, port)); }
    @Override public Socket createSocket(String host, int port, InetAddress local, int localPort) throws IOException { return wrap(delegate.createSocket(host, port, local, localPort)); }
    @Override public Socket createSocket(InetAddress host, int port) throws IOException { return wrap(delegate.createSocket(host, port)); }
    @Override public Socket createSocket(InetAddress host, int port, InetAddress local, int localPort) throws IOException { return wrap(delegate.createSocket(host, port, local, localPort)); }
    @Override public Socket createSocket(Socket socket, String host, int port, boolean autoClose) throws IOException { return wrap(delegate.createSocket(socket, host, port, autoClose)); }
    private Socket wrap(Socket socket) throws IOException {
        SSLSocket ssl = (SSLSocket) socket;
        SSLParameters parameters = ssl.getSSLParameters();
        parameters.setEndpointIdentificationAlgorithm("HTTPS");
        parameters.setProtocols(new String[]{"TLSv1.3", "TLSv1.2"});
        ssl.setSSLParameters(parameters);
        sockets.add(ssl);
        if (cancellation.isCancelled()) { ssl.close(); throw new IOException("cancelled"); }
        return new GuardedSocket(ssl);
    }
    private void closeSockets() {
        for (Socket socket : sockets) { try { socket.close(); } catch (IOException ignored) { } }
        sockets.clear();
    }
    @Override public void close() { closeSockets(); try { cancellationRegistration.close(); } catch (Exception ignored) { } }
    private final class GuardedSocket extends SSLSocket {
        private final SSLSocket socket;
        private InputStream input;
        GuardedSocket(SSLSocket socket) { this.socket = socket; }
        @Override public synchronized InputStream getInputStream() throws IOException {
            if (input == null) input = new FilterInputStream(socket.getInputStream()) {
                private void counted(int count) throws IOException {
                    if (count > 0 && received.addAndGet(count) > maxBytes) { closeSockets(); throw new IOException("mail_wire_budget_exceeded"); }
                }
                @Override public int read() throws IOException { int value = in.read(); counted(value < 0 ? 0 : 1); return value; }
                @Override public int read(byte[] bytes, int offset, int length) throws IOException {
                    int count = in.read(bytes, offset, Math.min(length, (int) Math.max(1, maxBytes - received.get() + 1)));
                    counted(count); return count;
                }
                @Override public long skip(long count) throws IOException {
                    byte[] discard = new byte[(int) Math.min(count, 8192)];
                    long total = 0;
                    while (total < count) { int n = read(discard, 0, (int) Math.min(discard.length, count - total)); if (n < 0) break; total += n; }
                    return total;
                }
            };
            return input;
        }
        @Override public OutputStream getOutputStream() throws IOException { return socket.getOutputStream(); }
        @Override public void connect(SocketAddress endpoint) throws IOException { socket.connect(endpoint); }
        @Override public void connect(SocketAddress endpoint, int timeout) throws IOException { socket.connect(endpoint, timeout); }
        @Override public void bind(SocketAddress bindpoint) throws IOException { socket.bind(bindpoint); }
        @Override public InetAddress getInetAddress() { return socket.getInetAddress(); }
        @Override public InetAddress getLocalAddress() { return socket.getLocalAddress(); }
        @Override public int getPort() { return socket.getPort(); }
        @Override public int getLocalPort() { return socket.getLocalPort(); }
        @Override public SocketAddress getRemoteSocketAddress() { return socket.getRemoteSocketAddress(); }
        @Override public SocketAddress getLocalSocketAddress() { return socket.getLocalSocketAddress(); }
        @Override public synchronized void close() throws IOException { sockets.remove(socket); socket.close(); }
        @Override public void shutdownInput() throws IOException { socket.shutdownInput(); }
        @Override public void shutdownOutput() throws IOException { socket.shutdownOutput(); }
        @Override public boolean isConnected() { return socket.isConnected(); }
        @Override public boolean isBound() { return socket.isBound(); }
        @Override public boolean isClosed() { return socket.isClosed(); }
        @Override public boolean isInputShutdown() { return socket.isInputShutdown(); }
        @Override public boolean isOutputShutdown() { return socket.isOutputShutdown(); }
        @Override public void setSoTimeout(int timeout) throws SocketException { socket.setSoTimeout(timeout); }
        @Override public int getSoTimeout() throws SocketException { return socket.getSoTimeout(); }
        @Override public void setTcpNoDelay(boolean on) throws SocketException { socket.setTcpNoDelay(on); }
        @Override public boolean getTcpNoDelay() throws SocketException { return socket.getTcpNoDelay(); }
        @Override public void setKeepAlive(boolean on) throws SocketException { socket.setKeepAlive(on); }
        @Override public boolean getKeepAlive() throws SocketException { return socket.getKeepAlive(); }
        @Override public void setSoLinger(boolean on, int linger) throws SocketException { socket.setSoLinger(on, linger); }
        @Override public int getSoLinger() throws SocketException { return socket.getSoLinger(); }
        @Override public String[] getSupportedCipherSuites() { return socket.getSupportedCipherSuites(); }
        @Override public String[] getEnabledCipherSuites() { return socket.getEnabledCipherSuites(); }
        @Override public void setEnabledCipherSuites(String[] suites) { socket.setEnabledCipherSuites(suites); }
        @Override public String[] getSupportedProtocols() { return socket.getSupportedProtocols(); }
        @Override public String[] getEnabledProtocols() { return socket.getEnabledProtocols(); }
        @Override public void setEnabledProtocols(String[] protocols) { socket.setEnabledProtocols(protocols); }
        @Override public SSLSession getSession() { return socket.getSession(); }
        @Override public SSLSession getHandshakeSession() { return socket.getHandshakeSession(); }
        @Override public void addHandshakeCompletedListener(HandshakeCompletedListener listener) { socket.addHandshakeCompletedListener(listener); }
        @Override public void removeHandshakeCompletedListener(HandshakeCompletedListener listener) { socket.removeHandshakeCompletedListener(listener); }
        @Override public void startHandshake() throws IOException { socket.startHandshake(); }
        @Override public void setUseClientMode(boolean mode) { socket.setUseClientMode(mode); }
        @Override public boolean getUseClientMode() { return socket.getUseClientMode(); }
        @Override public void setNeedClientAuth(boolean need) { socket.setNeedClientAuth(need); }
        @Override public boolean getNeedClientAuth() { return socket.getNeedClientAuth(); }
        @Override public void setWantClientAuth(boolean want) { socket.setWantClientAuth(want); }
        @Override public boolean getWantClientAuth() { return socket.getWantClientAuth(); }
        @Override public void setEnableSessionCreation(boolean flag) { socket.setEnableSessionCreation(flag); }
        @Override public boolean getEnableSessionCreation() { return socket.getEnableSessionCreation(); }
        @Override public SSLParameters getSSLParameters() { return socket.getSSLParameters(); }
        @Override public void setSSLParameters(SSLParameters parameters) { socket.setSSLParameters(parameters); }
    }
}
