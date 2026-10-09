package com.fpt.comparison_tool.engine;

import com.fpt.comparison_tool.model.KeyValue;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.cookie.BasicCookieStore;
import org.apache.hc.client5.http.cookie.CookieStore;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.client5.http.protocol.HttpClientContext;
import org.apache.hc.client5.http.ssl.ClientTlsStrategyBuilder;
import org.apache.hc.client5.http.ssl.NoopHostnameVerifier;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.http.io.entity.ByteArrayEntity;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.http.io.support.ClassicRequestBuilder;
import org.apache.hc.core5.util.Timeout;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Sends HTTP calls. Certificates are trusted without validation (self-signed and corporate TLS inspection), there are
 * no automatic retries, redirects are followed and every status code is returned as a normal response.
 */
public final class HttpExecutor implements AutoCloseable {

    /** A fully resolved call. body may be null. */
    public record Call(String method, String url, List<KeyValue> headers, String body, int timeoutMs) {
    }

    /** What came back. body is null for binary content; size is the byte length either way. */
    public record Reply(int status, Map<String, List<String>> headers, String body, boolean binary, long size,
                        String contentType, long timeMs) {
    }

    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final long MAX_BODY_BYTES = 64L * 1024 * 1024;

    private final PoolingHttpClientConnectionManager pool;
    private final CloseableHttpClient client;

    public HttpExecutor() {
        this.pool = PoolingHttpClientConnectionManagerBuilder.create()
                .setTlsSocketStrategy(ClientTlsStrategyBuilder.create()
                        .setSslContext(trustAllContext())
                        .setHostnameVerifier(NoopHostnameVerifier.INSTANCE)
                        .buildClassic())
                .setDefaultConnectionConfig(ConnectionConfig.custom()
                        .setConnectTimeout(Timeout.ofMilliseconds(CONNECT_TIMEOUT_MS))
                        .build())
                .setMaxConnTotal(400)
                .setMaxConnPerRoute(100)
                .build();
        this.client = HttpClients.custom()
                .setConnectionManager(pool)
                .disableAutomaticRetries()
                .build();
    }

    public static CookieStore newCookies() {
        return new BasicCookieStore();
    }

    public Reply send(Call call, CookieStore cookies) throws IOException {
        URI uri = Urls.toUri(call.url());
        ClassicRequestBuilder rb = ClassicRequestBuilder.create(call.method()).setUri(uri);
        String contentType = null;
        for (KeyValue h : call.headers()) {
            if (h.key().equalsIgnoreCase("Content-Length") || h.key().equalsIgnoreCase("Transfer-Encoding")) continue;
            if (h.key().equalsIgnoreCase("Content-Type")) contentType = h.value();
            rb.addHeader(h.key(), h.value());
        }
        if (call.body() != null) {
            ContentType ct = contentType == null ? ContentType.APPLICATION_JSON : parseContentType(contentType);
            Charset cs = ct.getCharset() == null ? StandardCharsets.UTF_8 : ct.getCharset();
            rb.setEntity(new ByteArrayEntity(call.body().getBytes(cs), null));
        }
        int timeout = call.timeoutMs() > 0 ? call.timeoutMs() : 60_000;
        HttpClientContext ctx = HttpClientContext.create();
        ctx.setRequestConfig(RequestConfig.custom()
                .setResponseTimeout(Timeout.ofMilliseconds(timeout))
                .setConnectionRequestTimeout(Timeout.ofMilliseconds(timeout))
                .build());
        if (cookies != null) ctx.setCookieStore(cookies);

        long start = System.nanoTime();
        return client.execute(rb.build(), ctx, response -> {
            Map<String, List<String>> headers = new LinkedHashMap<>();
            for (Header h : response.getHeaders()) headers.computeIfAbsent(h.getName(), k -> new ArrayList<>()).add(h.getValue());
            HttpEntity entity = response.getEntity();
            byte[] bytes = entity == null ? new byte[0] : readLimited(entity);
            long elapsed = (System.nanoTime() - start) / 1_000_000;
            String type = entity == null ? null : entity.getContentType();
            if (type == null) {
                List<String> ct = headers.entrySet().stream().filter(e -> e.getKey().equalsIgnoreCase("Content-Type"))
                        .map(Map.Entry::getValue).findFirst().orElse(null);
                type = ct == null || ct.isEmpty() ? null : ct.get(0);
            }
            boolean binary = isBinary(type);
            String text = binary ? null : new String(bytes, charsetOf(type));
            return new Reply(response.getCode(), headers, text, binary, bytes.length, type, elapsed);
        });
    }

    /** A readable reason for a transport failure. */
    public static String describe(Throwable e) {
        Throwable c = e;
        while (c.getCause() != null && c.getCause() != c) c = c.getCause();
        String base = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
        if (c instanceof SocketTimeoutException || e instanceof SocketTimeoutException) {
            return "Timeout - no response within the configured timeout (" + base + ")";
        }
        if (c instanceof UnknownHostException) return "Unknown host - the URL's host cannot be resolved: " + c.getMessage();
        if (c instanceof ConnectException) return "Connection failed - host unreachable or port closed (" + base + ")";
        if (c instanceof javax.net.ssl.SSLException) return "SSL error: " + base;
        if (c instanceof InterruptedIOException || c instanceof InterruptedException) return "Interrupted - the run was stopped";
        if (c instanceof IllegalArgumentException) return "Invalid request: " + base;
        return "Request failed: " + base;
    }

    private static byte[] readLimited(HttpEntity entity) throws IOException {
        long len = entity.getContentLength();
        if (len > MAX_BODY_BYTES) {
            EntityUtils.consume(entity);
            return ("<response of " + len + " bytes not read: above " + MAX_BODY_BYTES + " bytes>").getBytes(StandardCharsets.UTF_8);
        }
        return EntityUtils.toByteArray(entity);
    }

    private static ContentType parseContentType(String value) {
        try {
            return ContentType.parse(value);
        } catch (Exception e) {
            return ContentType.APPLICATION_JSON;
        }
    }

    private static Charset charsetOf(String contentType) {
        if (contentType != null) {
            try {
                Charset cs = ContentType.parse(contentType).getCharset();
                if (cs != null) return cs;
            } catch (Exception ignored) {
                // fall back to UTF-8
            }
        }
        return StandardCharsets.UTF_8;
    }

    static boolean isBinary(String contentType) {
        if (contentType == null) return false;
        String t = contentType.toLowerCase(Locale.ROOT);
        if (t.startsWith("text/") || t.contains("json") || t.contains("xml") || t.contains("javascript")
                || t.contains("x-www-form-urlencoded") || t.contains("yaml") || t.contains("csv")) {
            return false;
        }
        return t.startsWith("image/") || t.startsWith("audio/") || t.startsWith("video/") || t.contains("octet-stream")
                || t.contains("pdf") || t.contains("zip") || t.contains("excel") || t.contains("spreadsheet")
                || t.contains("officedocument") || t.contains("msword") || t.contains("vnd.");
    }

    private static SSLContext trustAllContext() {
        try {
            TrustManager[] trustAll = {new X509TrustManager() {
                @Override
                public void checkClientTrusted(X509Certificate[] chain, String authType) {
                    // trust all
                }

                @Override
                public void checkServerTrusted(X509Certificate[] chain, String authType) {
                    // trust all
                }

                @Override
                public X509Certificate[] getAcceptedIssuers() {
                    return new X509Certificate[0];
                }
            }};
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, trustAll, new java.security.SecureRandom());
            return ctx;
        } catch (Exception e) {
            throw new IllegalStateException("Cannot create TLS context", e);
        }
    }

    @Override
    public void close() throws IOException {
        client.close();
        pool.close();
    }
}
