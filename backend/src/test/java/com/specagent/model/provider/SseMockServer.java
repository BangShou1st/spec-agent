package com.specagent.model.provider;

import com.sun.net.httpserver.HttpServer;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;

/** Tiny deterministic SSE mock server for transport tests. No internet. */
final class SseMockServer implements AutoCloseable {

    private final HttpServer server;
    volatile String payload = "";
    volatile int chunkDelayMs;

    SseMockServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/sse", exchange -> {
            byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream out = exchange.getResponseBody()) {
                int at = 0;
                while (at < bytes.length) {
                    int end = Math.min(bytes.length, at + 4096);
                    try {
                        out.write(bytes, at, end - at);
                        out.flush();
                    } catch (java.io.IOException ex) {
                        break;
                    }
                    at = end;
                    if (chunkDelayMs > 0 && at < bytes.length) {
                        try {
                            Thread.sleep(chunkDelayMs);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                }
            } catch (java.io.IOException ignored) {
            }
        });
        server.setExecutor(Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r);
            t.setDaemon(true);
            return t;
        }));
        server.start();
    }

    String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/sse";
    }

    @Override
    public void close() {
        server.stop(0);
        if (server.getExecutor() instanceof java.util.concurrent.ExecutorService es) {
            es.shutdownNow();
        }
    }
}
