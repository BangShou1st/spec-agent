package com.specagent.skill.importing;

import com.specagent.common.network.OutboundNetworkPolicy;
import java.io.*;
import java.net.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import javax.net.ssl.*;
import org.eclipse.jgit.transport.http.*;

/** Per-clone HTTP connections: normal JVM TLS, explicit redirect validation, interruptible sockets and wire budget. */
final class GuardedGitHttp implements HttpConnectionFactory, AutoCloseable {
    private final OutboundNetworkPolicy policy;
    private final BooleanSupplier active;
    private final long maxBytes;
    private final AtomicLong received=new AtomicLong();
    private final List<HttpURLConnection> connections=new CopyOnWriteArrayList<>();
    GuardedGitHttp(OutboundNetworkPolicy policy,BooleanSupplier active,long maxBytes) {
        this.policy=policy; this.active=active; this.maxBytes=maxBytes;
    }
    void check() throws IOException { if(!active.getAsBoolean()) throw new InterruptedIOException("Git preparation cancelled or timed out"); }
    public HttpConnection create(URL url) throws IOException { return create(url,null); }
    public HttpConnection create(URL url,Proxy proxy) throws IOException {
        check(); policy.validateOutboundUrl(url.toExternalForm(),0);
        if(!url.getProtocol().equalsIgnoreCase("https") || url.getUserInfo()!=null) throw new IOException("Git requires credential-free HTTPS URL");
        HttpURLConnection connection=(HttpURLConnection)(proxy==null?url.openConnection():url.openConnection(proxy));
        connections.add(connection);
        if(!active.getAsBoolean()) { connection.disconnect(); check(); }
        return new Connection(connection);
    }
    public void close() { connections.forEach(HttpURLConnection::disconnect); }
    private final class Connection implements HttpConnection {
        private final HttpURLConnection http;
        Connection(HttpURLConnection http) { this.http=http; }
        public int getResponseCode() throws IOException { check(); int code=http.getResponseCode(); check(); return code; }
        public URL getURL() { return http.getURL(); }
        public String getResponseMessage() throws IOException { check(); return http.getResponseMessage(); }
        public Map<String,List<String>> getHeaderFields() { return http.getHeaderFields(); }
        public void setRequestProperty(String key,String value) { http.setRequestProperty(key,value); }
        public void setRequestMethod(String value) throws ProtocolException { http.setRequestMethod(value); }
        public void setUseCaches(boolean value) { http.setUseCaches(value); }
        public void setConnectTimeout(int value) { http.setConnectTimeout(value); }
        public void setReadTimeout(int value) { http.setReadTimeout(value); }
        public String getContentType() { return http.getContentType(); }
        public InputStream getInputStream() throws IOException {
            check(); var stream=http.getInputStream(); check();
            return new FilterInputStream(stream) {
                private void count(int value) throws IOException {
                    if(value>0 && received.addAndGet(value)>maxBytes) throw new IOException("Git wire byte budget exceeded");
                    check();
                }
                @Override public int read() throws IOException { check(); int value=in.read(); count(value<0?0:1); return value; }
                @Override public int read(byte[] bytes,int offset,int length) throws IOException { check(); int value=in.read(bytes,offset,length); count(value); return value; }
            };
        }
        public String getHeaderField(String key) { return http.getHeaderField(key); }
        public List<String> getHeaderFields(String key) {
            return http.getHeaderFields().entrySet().stream().filter(e->e.getKey()!=null && e.getKey().equalsIgnoreCase(key))
                    .findFirst().map(Map.Entry::getValue).orElse(List.of());
        }
        public int getContentLength() { return http.getContentLength(); }
        // JGit handles redirects itself; every new URL goes through this factory and the host policy.
        public void setInstanceFollowRedirects(boolean value) { http.setInstanceFollowRedirects(false); }
        public void setDoOutput(boolean value) { http.setDoOutput(value); }
        public void setFixedLengthStreamingMode(int value) { http.setFixedLengthStreamingMode(value); }
        public OutputStream getOutputStream() throws IOException { check(); return http.getOutputStream(); }
        public void setChunkedStreamingMode(int value) { http.setChunkedStreamingMode(value); }
        public String getRequestMethod() { return http.getRequestMethod(); }
        public boolean usingProxy() { return http.usingProxy(); }
        public void connect() throws IOException { check(); http.connect(); check(); }
        public void configure(KeyManager[] keys,TrustManager[] trusts,SecureRandom random) throws KeyManagementException {
            throw new KeyManagementException("Git TLS verification cannot be overridden");
        }
        public void setHostnameVerifier(HostnameVerifier verifier) throws KeyManagementException {
            throw new KeyManagementException("Git hostname verification cannot be overridden");
        }
    }
}
