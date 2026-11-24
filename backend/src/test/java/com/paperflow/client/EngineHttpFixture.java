package com.paperflow.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paperflow.client.citation.CitationEngine.EngineRequest;
import com.paperflow.client.citation.model.CitationStyle;
import com.paperflow.client.citation.model.RetrievalPolicy;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

final class EngineHttpFixture implements AutoCloseable {
    static final ObjectMapper JSON = new ObjectMapper();
    static final EngineRequest REQUEST = new EngineRequest(UUID.randomUUID(), "Trade grows.", CitationStyle.APA,
            new RetrievalPolicy(null, null, null));

    private final HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);

    EngineHttpFixture() throws IOException {
        server.start();
    }

    URI url() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    void route(String path, Response handler) {
        server.createContext(path, exchange -> {
            try (exchange) {
                handler.respond(exchange);
            }
        });
    }

    static void reply(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    @Override
    public void close() {
        server.stop(0);
    }

    interface Response {
        void respond(HttpExchange exchange) throws IOException;
    }
}
