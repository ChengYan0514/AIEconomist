package com.paperflow.adapters.files;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paperflow.fulltext.ParseArtifact;
import com.paperflow.fulltext.ParseTask;
import com.paperflow.fulltext.PdfParser;
import com.paperflow.paper.model.Paper;
import com.paperflow.settings.ServiceSettings.MineruConfig;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.Supplier;

public final class MineruTasks implements PdfParser {
    private final HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(10)).build();
    private final FileStorage files;
    private final MineruBundle bundle;
    private final ObjectMapper json;
    private final Supplier<MineruConfig> config;

    public MineruTasks(FileStorage files, ObjectMapper json, URI base, Duration timeout, String tier) {
        this(files, json, () -> new MineruConfig(base, tier, (int) Math.max(10, Math.min(86400, timeout.toSeconds()))));
    }

    public MineruTasks(FileStorage files, ObjectMapper json, Supplier<MineruConfig> config) {
        this.files = files;
        this.bundle = new MineruBundle(files);
        this.json = json;
        this.config = config;
    }

    @Override
    public ParseArtifact parse(Paper paper, ParseTask previous, Consumer<ParseTask> remember)
            throws IOException, InterruptedException {
        MineruConfig current = config.get();
        URI base = normalizeBase(current.url());
        Path pdf = files.original(paper);
        long deadline = System.nanoTime() + current.timeout().toNanos();
        Progress progress = resume(base, previous, deadline);
        if (progress == null) {
            progress = submit(base, current.tier(), pdf, deadline);
            remember.accept(progress.task());
        }
        String result = await(base, progress, pdf, remember, deadline);
        var response = client.send(request(link(base, result), deadline).GET().build(), HttpResponse.BodyHandlers.ofInputStream());
        try (InputStream input = response.body()) {
            requireSuccess(response.statusCode());
            return bundle.save(paper, input);
        }
    }

    private Progress resume(URI base, ParseTask previous, long deadline) throws IOException, InterruptedException {
        if (previous == null) return null;
        JsonNode result = query(base, previous, deadline, true);
        if (result == null || terminalFailure(result.path("status").asText())) return null;
        return new Progress(previous, result);
    }

    private Progress submit(URI base, String tier, Path pdf, long deadline) throws IOException, InterruptedException {
        var manifest = json.createObjectNode();
        manifest.putArray("files").addObject().put("file_name", "paper.pdf").put("bytes", Files.size(pdf));
        manifest.putArray("output_formats").add("default");
        manifest.put("ocr_mode", "auto");
        if (!tier.isBlank()) manifest.put("tier", tier);
        JsonNode receipt = readJson(request(base.resolve("v1/tasks"), deadline).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(manifest))).build(), false);
        JsonNode file = receipt.path("files").path(0);
        if (!receipt.path("files").isArray() || receipt.path("files").size() != 1) {
            throw new IOException("MinerU 创建回执的文件数无效");
        }
        return new Progress(new ParseTask(receipt.path("task_id").asText(null), file.path("file_id").asText(null), false), file);
    }

    private String await(URI base, Progress initial, Path pdf, Consumer<ParseTask> remember, long deadline)
            throws IOException, InterruptedException {
        var task = initial.task();
        var file = initial.file();
        while (true) {
            String status = file.path("status").asText();
            if ("completed".equals(status) && file.hasNonNull("result_url")) return file.get("result_url").asText();
            if (terminalFailure(status)) {
                throw new IOException("MinerU 文件解析未完成：" + status);
            }
            if (!java.util.List.of("waiting-file", "pending", "running", "completed").contains(status)) {
                throw new IOException("MinerU 返回未知的文件状态");
            }
            if ("waiting-file".equals(status) && !task.uploaded() && file.path("upload").hasNonNull("url")) {
                upload(base, pdf, file.get("upload"), deadline);
                task = task.afterUpload();
                remember.accept(task);
            } else {
                Thread.sleep(Math.min(1000, remaining(deadline).toMillis()));
            }
            file = query(base, task, deadline, false);
        }
    }

    private void upload(URI base, Path pdf, JsonNode upload, long deadline) throws IOException, InterruptedException {
        var request = request(link(base, upload.path("url").asText()), deadline);
        var fields = upload.path("headers").fields();
        while (fields.hasNext()) {
            var header = fields.next();
            if (!"content-length".equalsIgnoreCase(header.getKey())) {
                request.header(header.getKey(), header.getValue().asText());
            }
        }
        var response = client.send(request.PUT(HttpRequest.BodyPublishers.ofFile(pdf)).build(),
                HttpResponse.BodyHandlers.discarding());
        requireSuccess(response.statusCode());
    }

    private JsonNode query(URI base, ParseTask task, long deadline, boolean optional) throws IOException, InterruptedException {
        var response = readJson(request(base.resolve("v1/tasks/" + task.taskId()), deadline).GET().build(), optional);
        if (response == null) return null;
        var file = response.path("files").path(0);
        if (!task.taskId().equals(response.path("task_id").asText()) || response.path("files").size() != 1
                || !task.fileId().equals(file.path("file_id").asText())) {
            throw new IOException("MinerU 返回的文件与原任务不符");
        }
        return file;
    }

    private JsonNode readJson(HttpRequest request, boolean optional) throws IOException, InterruptedException {
        var response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        try (var input = response.body()) {
            if (optional && response.statusCode() == 404) return null;
            requireSuccess(response.statusCode());
            byte[] bytes = input.readNBytes(262145);
            if (bytes.length > 262144) throw new IOException("MinerU 状态响应超出大小限制");
            JsonNode parsed = json.readTree(bytes);
            if (parsed == null || !parsed.isObject()) throw new IOException("MinerU 状态响应格式无效");
            return parsed;
        }
    }

    private URI link(URI base, String value) throws IOException {
        if (value.isBlank()) throw new IOException("MinerU 缺少文件链接");
        URI uri = base.resolve(value);
        if (uri.getUserInfo() != null || uri.getHost() == null || !uri.getScheme().equalsIgnoreCase(base.getScheme())
                || !uri.getHost().equalsIgnoreCase(base.getHost()) || port(uri) != port(base)) {
            throw new IOException("MinerU 返回了不同源的文件链接");
        }
        return uri;
    }

    private static URI normalizeBase(URI uri) {
        return URI.create(uri.toString().replaceFirst("/+$", "") + "/");
    }

    private int port(URI address) {
        return address.getPort() >= 0 ? address.getPort() : "https".equals(address.getScheme().toLowerCase(Locale.ROOT)) ? 443 : 80;
    }

    private HttpRequest.Builder request(URI uri, long deadline) throws IOException {
        return HttpRequest.newBuilder(uri).timeout(remaining(deadline));
    }

    private Duration remaining(long deadline) throws IOException {
        long nanos = deadline - System.nanoTime();
        if (nanos <= 0) throw new IOException("等待 MinerU 结果超时，可重试以继续查询");
        return Duration.ofNanos(nanos);
    }

    private static boolean terminalFailure(String value) {
        return "failed".equals(value) || "canceled".equals(value);
    }

    private static void requireSuccess(int status) throws IOException {
        if (status < 200 || status >= 300) throw new IOException("MinerU 请求失败（HTTP " + status + "）");
    }

    private record Progress(ParseTask task, JsonNode file) {}
}
