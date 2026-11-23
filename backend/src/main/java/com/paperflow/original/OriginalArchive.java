package com.paperflow.original;

import com.paperflow.paper.model.PaperMetadata;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.UUID;

public interface OriginalArchive {
    List<Row> preflight(UUID id, InputStream content) throws IOException;
    <T> T read(ImportRow row, Reader<T> reader) throws IOException;
    void discard(UUID id);

    record Row(int sequence, String title, String file, String type, PaperMetadata metadata, String error) {}

    @FunctionalInterface
    interface Reader<T> {
        T read(InputStream input) throws IOException;
    }
}
