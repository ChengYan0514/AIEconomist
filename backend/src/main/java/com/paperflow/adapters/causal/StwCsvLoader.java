package com.paperflow.adapters.causal;

import com.paperflow.causal.graph.StwVocabulary;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVRecord;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public final class StwCsvLoader {
    public static final String BUNDLED_RESOURCE = "causal/stw_labels.csv";
    private static final String LABEL_COLUMN = "label_en";
    private static final CSVFormat FORMAT = CSVFormat.DEFAULT.builder()
            .setHeader()
            .setSkipHeaderRecord(true)
            .setTrim(true)
            .setIgnoreEmptyLines(true)
            .build();

    private StwCsvLoader() {}

    public static StwVocabulary loadBundled() {
        try (InputStream stream = StwCsvLoader.class.getClassLoader().getResourceAsStream(BUNDLED_RESOURCE)) {
            if (stream == null) {
                throw new IllegalStateException("缺少内置 STW 词表资源: " + BUNDLED_RESOURCE);
            }
            return load(stream);
        } catch (IOException error) {
            throw new IllegalStateException("读取内置 STW 词表失败: " + BUNDLED_RESOURCE, error);
        }
    }

    public static StwVocabulary load(InputStream input) throws IOException {
        try (var reader = new InputStreamReader(input, StandardCharsets.UTF_8);
             var parser = FORMAT.parse(reader)) {
            if (!parser.getHeaderMap().containsKey(LABEL_COLUMN)) {
                throw new IllegalStateException("STW 词表 CSV 缺少必需列: " + LABEL_COLUMN);
            }
            List<String> labels = new ArrayList<>();
            for (CSVRecord record : parser) {
                labels.add(record.get(LABEL_COLUMN));
            }
            return new StwVocabulary(labels);
        }
    }
}
