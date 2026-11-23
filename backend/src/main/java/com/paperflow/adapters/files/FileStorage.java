package com.paperflow.adapters.files;

import com.paperflow.paper.manage.OriginalStorage;
import com.paperflow.paper.model.Paper;
import com.paperflow.paper.model.StoredOriginal;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.UUID;

public final class FileStorage implements OriginalStorage {
    public static final long MAX_PDF_BYTES = 100L * 1024 * 1024;
    public static final long MAX_ZIP_BYTES = 256L * 1024 * 1024;
    private static final byte[] PDF_MAGIC = HexFormat.of().parseHex("255044462d");
    private final Path storage;
    private final Path legacy;

    public FileStorage(Path storage, Path legacy) {
        this.storage = storage.toAbsolutePath().normalize();
        this.legacy = legacy.toAbsolutePath().normalize();
    }

    @Override
    public StoredOriginal save(InputStream content, String paperId, String type) throws IOException {
        byte[] magic = content.readNBytes(1024);
        validateHeader(magic, type);
        String name = paperId.matches("[a-zA-Z0-9_-]+") ? paperId : "original";
        String filename = name + "." + type.toLowerCase(java.util.Locale.ROOT);
        String relative = "originals/" + UUID.randomUUID() + "/" + filename;
        Path destination = newPath(relative);
        try {
            try (var output = Files.newOutputStream(destination)) {
                output.write(magic);
                copy(content, output, MAX_PDF_BYTES - magic.length);
            }
        } catch (IOException | RuntimeException error) {
            Files.deleteIfExists(destination);
            Files.deleteIfExists(destination.getParent());
            throw error;
        }
        return new StoredOriginal(filename, relative, type, Files.size(destination));
    }

    public static void validateHeader(byte[] prefix, String type) {
        if ("PDF".equals(type)) {
            if (!java.util.Arrays.equals(java.util.Arrays.copyOf(prefix, PDF_MAGIC.length), PDF_MAGIC)) {
                throw new IllegalArgumentException("文件内容必须是 PDF");
            }
        } else if ("XML".equals(type) || "HTML".equals(type)) {
            String start = new String(prefix, java.nio.charset.StandardCharsets.UTF_8)
                    .replaceFirst("^\uFEFF", "").stripLeading();
            if (!start.startsWith("<")) throw new IllegalArgumentException("文件内容与文件格式不符");
        } else {
            throw new IllegalArgumentException("原件只接受 PDF、XML 或 HTML");
        }
    }

    public Path original(Paper paper) throws IOException {
        return read(paper.usesLegacyFile(), paper.originalPath());
    }

    public boolean originalAvailable(Paper paper) {
        try {
            original(paper);
            return true;
        } catch (IOException ignored) {
            return false;
        }
    }

    public Path newPath(String relative) throws IOException {
        Path path = inside(storage, relative);
        Files.createDirectories(path.getParent());
        if (!path.getParent().toRealPath().startsWith(storage.toRealPath())) throw missing();
        return path;
    }

    public Path read(boolean historical, String relative) throws IOException {
        Path root = historical ? legacy : storage;
        Path original = inside(root, relative);
        Path path = original.toRealPath();
        if (!path.startsWith(root.toRealPath()) || !Files.isRegularFile(path) || !Files.isReadable(path)) {
            throw missing();
        }
        return path;
    }

    public void discardNewFile(String relative) {
        try {
            Path path = read(false, relative);
            Files.deleteIfExists(path);
            Files.deleteIfExists(path.getParent());
        } catch (IOException ignored) {
            // A failed write never publishes a file path, including when cleanup is unavailable.
        }
    }

    public static long copy(InputStream input, java.io.OutputStream output, long limit) throws IOException {
        byte[] buffer = new byte[64 * 1024];
        long total = 0;
        for (int count; (count = input.read(buffer)) != -1;) {
            total += count;
            if (total > limit) throw new IllegalArgumentException("文件超过允许的大小");
            output.write(buffer, 0, count);
        }
        return total;
    }

    private static Path inside(Path root, String relative) throws IOException {
        if (relative == null || relative.isBlank() || relative.indexOf('\0') >= 0 || relative.indexOf('\\') >= 0) {
            throw missing();
        }
        Path segment = Path.of(relative);
        Path path = root.resolve(segment).normalize();
        if (segment.isAbsolute() || !path.startsWith(root) || path.equals(root)) throw missing();
        return path;
    }

    private static NoSuchFileException missing() {
        return new NoSuchFileException("unavailable");
    }
}
