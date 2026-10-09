package com.fpt.comparison_tool.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/** A small HTTP API for engine tests: items CRUD, a flaky endpoint, a slow endpoint, an OAuth2 token endpoint. */
final class FakeApi implements AutoCloseable {

    final HttpServer server;
    final String flavour;
    final Map<Integer, String> items = new ConcurrentHashMap<>();
    final AtomicInteger ids = new AtomicInteger(100);
    final AtomicInteger flakyCalls = new AtomicInteger();
    final AtomicInteger tokenCalls = new AtomicInteger();
    final AtomicInteger concurrent = new AtomicInteger();
    final AtomicInteger maxConcurrent = new AtomicInteger();
    final ObjectMapper mapper = new ObjectMapper();

    FakeApi(String flavour) throws IOException {
        this.flavour = flavour;
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newFixedThreadPool(32));
        server.createContext("/", this::handle);
        server.start();
    }

    String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private void handle(HttpExchange ex) throws IOException {
        int now = concurrent.incrementAndGet();
        maxConcurrent.accumulateAndGet(now, Math::max);
        try {
            String path = ex.getRequestURI().getPath();
            String method = ex.getRequestMethod();
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            if (path.equals("/items") && method.equals("POST")) {
                JsonNode in = mapper.readTree(body);
                int id = ids.incrementAndGet();
                items.put(id, in.path("name").asText());
                send(ex, 201, "{\"id\":" + id + ",\"name\":\"" + in.path("name").asText() + "\"}");
            } else if (path.startsWith("/items/") && method.equals("GET")) {
                int id = Integer.parseInt(path.substring(7));
                String name = items.get(id);
                if (name == null) send(ex, 404, "{\"error\":\"not found\"}");
                else send(ex, 200, "{\"id\":" + id + ",\"name\":\"" + name + "\",\"flavour\":\"" + flavour + "\"}");
            } else if (path.startsWith("/items/") && method.equals("DELETE")) {
                items.remove(Integer.parseInt(path.substring(7)));
                send(ex, 204, "");
            } else if (path.equals("/slow")) {
                Thread.sleep(150);
                send(ex, 200, "{\"ok\":true}");
            } else if (path.equals("/flaky")) {
                int n = flakyCalls.incrementAndGet();
                send(ex, n < 3 ? 503 : 200, "{\"attempt\":" + n + "}");
            } else if (path.equals("/token") && method.equals("POST")) {
                tokenCalls.incrementAndGet();
                send(ex, body.contains("client_secret=s3cret") ? 200 : 401,
                        "{\"access_token\":\"tok-" + tokenCalls.get() + "\",\"expires_in\":3600}");
            } else if (path.equals("/secure")) {
                String auth = ex.getRequestHeaders().getFirst("Authorization");
                send(ex, auth != null && auth.startsWith("Bearer tok-") ? 200 : 401, "{\"auth\":\"" + auth + "\"}");
            } else if (path.equals("/headers")) {
                ObjectNode o = mapper.createObjectNode();
                ex.getRequestHeaders().forEach((k, v) -> o.put(k.toLowerCase(), String.join(",", v)));
                send(ex, 200, o.toString());
            } else if (path.equals("/quote")) {
                send(ex, 200, "{\"price\":" + ("baseline".equals(flavour) ? "1278" : "1276") + ",\"ts\":\"" + System.nanoTime() + "\",\"currency\":\"USD\"}");
            } else if (path.equals("/form") && method.equals("POST")) {
                send(ex, 200, "{\"received\":\"" + body.replace("\"", "'") + "\",\"type\":\"" + ex.getRequestHeaders().getFirst("Content-Type") + "\"}");
            } else {
                send(ex, 404, "{\"error\":\"no route " + path + "\"}");
            }
        } catch (Exception e) {
            send(ex, 500, "{\"error\":\"" + e + "\"}");
        } finally {
            concurrent.decrementAndGet();
        }
    }

    private static void send(HttpExchange ex, int status, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(status, status == 204 ? -1 : b.length);
        if (status != 204) ex.getResponseBody().write(b);
        ex.close();
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
