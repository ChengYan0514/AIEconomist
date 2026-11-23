package com.paperflow.adapters.files;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.concurrent.TimeoutException;

public final class MineruHealth {
    private final HttpClient client;
    private final HttpRequest request;

    public MineruHealth(URI base, Duration timeout) {
        URI address = URI.create(base.toString().replaceFirst("/+$", "") + "/v1/health");
        client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(timeout).build();
        request = HttpRequest.newBuilder(address).timeout(timeout).GET().build();
    }

    public void check() throws IOException, InterruptedException, TimeoutException {
        try {
            var response = client.send(request, HttpResponse.BodyHandlers.replacing(null));
            if (response.statusCode() / 100 != 2) throw new IOException("Unhealthy MinerU response");
        } catch (HttpTimeoutException error) {
            throw new TimeoutException();
        }
    }
}
