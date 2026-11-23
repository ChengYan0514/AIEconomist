package com.paperflow.adapters.files;

import com.paperflow.fulltext.ParseArtifact;
import com.paperflow.paper.model.Paper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Comparator;
import java.util.UUID;
import java.util.zip.ZipInputStream;

final class MineruBundle {
    private static final long MAX_RESULT_BYTES = 1024L * 1024 * 1024;
    private final FileStorage files;

    MineruBundle(FileStorage files) {
        this.files = files;
    }

    ParseArtifact save(Paper paper, InputStream input) throws IOException {
        UUID id = UUID.randomUUID();
        String folder = "parsed/" + id;
        Path directory = files.newPath(folder + "/structured_content.json").getParent();
        try {
            unpack(input, directory);
            if (!Files.isRegularFile(directory.resolve("structured_content.json"))) {
                throw new IOException("MinerU 4.1 结果缺少 structured_content.json");
            }
            String markdown = Files.isRegularFile(directory.resolve("markdown.md")) ? folder + "/markdown.md" : null;
            return new ParseArtifact(id, paper.paperId(), paper.revision(), false, folder,
                    folder + "/structured_content.json", markdown);
        } catch (IOException | RuntimeException error) {
            removeOwnFolder(directory, error);
            throw error;
        }
    }

    private void unpack(InputStream input, Path root) throws IOException {
        long remaining = MAX_RESULT_BYTES;
        int count = 0;
        try (var zip = new ZipInputStream(input)) {
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                String name = entry.getName();
                Path path = root.resolve(name).normalize();
                if (++count > 10000 || name.indexOf('\\') >= 0 || !path.startsWith(root) || path.equals(root)) {
                    throw new IOException("MinerU 产物的路径或条目数量无效");
                }
                if (entry.isDirectory()) continue;
                Files.createDirectories(path.getParent());
                try (var output = Files.newOutputStream(path, StandardOpenOption.CREATE_NEW)) {
                    remaining -= FileStorage.copy(zip, output, remaining);
                }
            }
        }
    }

    private void removeOwnFolder(Path directory, Exception error) {
        try (var paths = Files.walk(directory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        } catch (IOException cleanup) {
            error.addSuppressed(cleanup);
        }
    }
}
