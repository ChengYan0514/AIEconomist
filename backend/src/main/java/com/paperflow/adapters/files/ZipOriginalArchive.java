package com.paperflow.adapters.files;

import com.paperflow.original.ImportRow;
import com.paperflow.original.OriginalArchive;
import com.paperflow.paper.model.PaperMetadata;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVRecord;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

public final class ZipOriginalArchive implements OriginalArchive {
    private static final int MAX_ROWS = 3000;
    private static final int MAX_MANIFEST = 8 * 1024 * 1024;
    private static final long MAX_UNPACKED = 3L * 1024 * 1024 * 1024;
    private final FileStorage files;

    public ZipOriginalArchive(FileStorage files) {
        this.files = files;
    }

    @Override
    public List<Row> preflight(java.util.UUID id, InputStream content) throws IOException {
        String relative = location(id.toString());
        Path stored = files.newPath(relative);
        try {
            try (var output = Files.newOutputStream(stored)) {
                FileStorage.copy(content, output, FileStorage.MAX_ZIP_BYTES);
            }
            try (ZipFile zip = new ZipFile(stored.toFile(), StandardCharsets.UTF_8)) {
                return manifest(zip, manifestEntry(zip));
            }
        } catch (ZipException error) {
            discard(id);
            throw new IllegalArgumentException("请上传有效的 ZIP 压缩包", error);
        } catch (RuntimeException | IOException error) {
            discard(id);
            throw error;
        }
    }

    @Override
    public <T> T read(ImportRow row, Reader<T> reader) throws IOException {
        var zipPath = files.read(false, location(row.archive()));
        try (ZipFile zip = new ZipFile(zipPath.toFile(), StandardCharsets.UTF_8)) {
            ZipEntry entry = zip.getEntry(row.entry());
            if (entry == null || entry.isDirectory()) throw new IOException("压缩包中的原件不可用");
            try (var content = zip.getInputStream(entry)) {
                return reader.read(content);
            }
        }
    }

    @Override
    public void discard(java.util.UUID id) {
        files.discardNewFile(location(id.toString()));
    }

    private ZipEntry manifestEntry(ZipFile zip) {
        var entries = zip.entries();
        var names = new java.util.HashSet<String>();
        ZipEntry csv = null;
        long declared = 0;
        while (entries.hasMoreElements()) {
            var entry = entries.nextElement();
            String name = entry.getName();
            if (!safe(name) || !names.add(name) || names.size() > 10000) {
                throw new IllegalArgumentException("压缩包含非法路径、重复名称或过多文件");
            }
            if (entry.isDirectory()) continue;
            long size = Math.max(0, entry.getSize());
            if (size > MAX_UNPACKED - declared) throw new IllegalArgumentException("压缩包解压体积超过 3 GiB");
            declared += size;
            if (name.toLowerCase(Locale.ROOT).endsWith(".csv")) {
                if (csv != null) throw new IllegalArgumentException("压缩包应包含且仅包含一个 CSV");
                csv = entry;
            }
        }
        if (csv == null) throw new IllegalArgumentException("压缩包内找不到 CSV");
        return csv;
    }

    private List<Row> manifest(ZipFile zip, ZipEntry csv) throws IOException {
        byte[] bytes;
        try (var input = zip.getInputStream(csv)) {
            bytes = input.readNBytes(MAX_MANIFEST + 1);
        }
        if (bytes.length > MAX_MANIFEST) throw new IllegalArgumentException("CSV 超过 8 MiB");
        var decoder = StandardCharsets.UTF_8.newDecoder();
        String text;
        try {
            text = decoder.decode(java.nio.ByteBuffer.wrap(bytes)).toString().replaceFirst("^\\uFEFF", "");
        } catch (java.nio.charset.CharacterCodingException error) {
            throw new IllegalArgumentException("CSV 必须是 UTF-8 编码", error);
        }
        var format = CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true).setTrim(true).build();
        try (var parser = format.parse(new StringReader(text))) {
            if (!parser.getHeaderNames().containsAll(List.of("source_id", "year", "paper_title", "authors", "file_path"))) {
                throw new IllegalArgumentException("CSV 缺少 source_id、year、paper_title、authors 或 file_path");
            }
            List<Row> rows = new ArrayList<>();
            for (CSVRecord record : parser) {
                if (rows.size() == MAX_ROWS) throw new IllegalArgumentException("每个 ZIP 最多导入 3000 条记录");
                rows.add(row(zip, rows.size() + 1, record));
            }
            if (rows.isEmpty()) throw new IllegalArgumentException("CSV 没有可预检的行");
            return List.copyOf(rows);
        } catch (java.io.UncheckedIOException error) {
            throw new IllegalArgumentException("CSV 格式有误", error);
        }
    }

    private Row row(ZipFile zip, int number, CSVRecord row) {
        String title = value(row, "paper_title");
        try {
            var data = new PaperMetadata(value(row, "source_id"), Integer.parseInt(value(row, "year")), title,
                    value(row, "authors"), value(row, "doi"), value(row, "url"), value(row, "provider"));
            String path = value(row, "file_path");
            String type = entryType(zip, path);
            return new Row(number, title, path, type, data, null);
        } catch (NumberFormatException error) {
            return new Row(number, title, null, null, null, "发表年份应为四位数字");
        } catch (IllegalArgumentException | IOException error) {
            String message = error instanceof IOException ? "包内文件无法读取" : error.getMessage();
            return new Row(number, title, null, null, null, message);
        }
    }

    private String entryType(ZipFile zip, String path) throws IOException {
        if (!safe(path)) throw new IllegalArgumentException("file_path 不是有效的包内相对路径");
        ZipEntry entry = zip.getEntry(path);
        if (entry == null || entry.isDirectory()) throw new IllegalArgumentException("包内找不到该原件");
        if (entry.getSize() > FileStorage.MAX_PDF_BYTES) throw new IllegalArgumentException("单个原件超过 100 MiB");
        String lower = path.toLowerCase(Locale.ROOT);
        String type = lower.endsWith(".pdf") ? "PDF" : lower.endsWith(".xml") ? "XML" :
                lower.endsWith(".html") || lower.endsWith(".htm") ? "HTML" : "";
        try (var stream = zip.getInputStream(entry)) {
            FileStorage.validateHeader(stream.readNBytes(1024), type);
        }
        return type;
    }

    private static boolean safe(String name) {
        if (name == null || name.isBlank() || name.indexOf('\\') >= 0 || name.indexOf(':') >= 0 || name.indexOf('\0') >= 0) return false;
        Path path = Path.of(name);
        return !path.isAbsolute() && path.getNameCount() > 0 && !path.normalize().toString().isEmpty()
                && path.normalize().equals(path) && !path.startsWith("..");
    }

    private static String value(CSVRecord record, String key) {
        return record.isMapped(key) && record.isSet(key) ? record.get(key) : "";
    }

    private static String location(String id) {
        return "imports/" + java.util.UUID.fromString(id) + "/batch.zip";
    }
}
