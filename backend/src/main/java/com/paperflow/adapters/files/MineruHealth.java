package com.paperflow.adapters.files;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

public final class MineruHealth {
    private final HttpClient client;
    private final Supplier<URI> baseUrl;
    private final Duration timeout;

    public MineruHealth(URI base, Duration timeout) {
        this(() -> base, timeout);
    }

    public MineruHealth(Supplier<URI> baseUrl, Duration timeout) {
        this.baseUrl = baseUrl;
        this.timeout = timeout;
        this.client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(timeout).build();
    }

    public void check() throws IOException, InterruptedException, TimeoutException {
        try {
            URI address = URI.create(baseUrl.get().toString().replaceFirst("/+$", "") + "/v1/health");
            HttpRequest request = HttpRequest.newBuilder(address).timeout(timeout).GET().build();
            var response = client.send(request, HttpResponse.BodyHandlers.replacing(null));
            if (response.statusCode() / 100 != 2) throw new IOException("Unhealthy MinerU response");
        } catch (HttpTimeoutException error) {
            throw new TimeoutException();
        }
    }
}
