package com.paperflow.adapters.files;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paperflow.fulltext.ContentBlock;
import com.paperflow.fulltext.ParseArtifact;
import com.paperflow.fulltext.ParsedContent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class MineruContent implements ParsedContent {
    private static final long MAX_JSON_BYTES = 32L * 1024 * 1024;
    private static final int MAX_BLOCKS = 50000;
    private final FileStorage files;
    private final ObjectMapper json;

    public MineruContent(FileStorage files, ObjectMapper json) {
        this.files = files;
        this.json = json;
    }

    @Override
    public List<ContentBlock> read(ParseArtifact artifact) throws IOException {
        Path path = files.read(artifact.legacy(), artifact.contentPath());
        if (Files.size(path) > MAX_JSON_BYTES) throw new IOException("解析 JSON 超过 32 MiB");
        JsonNode root = json.readTree(path.toFile());
        if (root != null && root.has("pages")) return structured(root);
        JsonNode items = root;
        if (root != null && root.isObject()) {
            for (String name : List.of("content_list", "content", "blocks", "items")) {
                if (root.path(name).isArray()) { items = root.get(name); break; }
            }
        }
        if (items == null || !items.isArray() || items.isEmpty() || items.size() > MAX_BLOCKS) {
            throw new IOException("解析产物缺少有效内容块");
        }
        List<ContentBlock> blocks = new ArrayList<>();
        for (JsonNode item : items) {
            if (!item.isObject()) throw new IOException("解析产物中存在无效内容块");
            blocks.add(block(item, blocks.size()));
        }
        return List.copyOf(blocks);
    }

    private List<ContentBlock> structured(JsonNode root) throws IOException {
        JsonNode pages = root.path("pages");
        if (!pages.isArray()) throw new IOException("MinerU 4.1 产物缺少有效的页列表");
        List<ContentBlock> blocks = new ArrayList<>();
        for (JsonNode page : pages) {
            JsonNode pageIndex = page.path("page_idx");
            JsonNode items = page.path("blocks");
            if (!pageIndex.isIntegralNumber() || !pageIndex.canConvertToInt() || pageIndex.asInt() < 0 || !items.isArray()) {
                throw new IOException("MinerU 4.1 的页码或内容块无效");
            }
            for (JsonNode item : items) {
                if (!item.isObject() || blocks.size() == MAX_BLOCKS) {
                    throw new IOException("MinerU 4.1 内容块无效或超过上限");
                }
                blocks.add(structuredBlock(item, blocks.size(), pageIndex.asInt()));
            }
        }
        if (blocks.isEmpty()) throw new IOException("解析产物缺少有效内容块");
        return List.copyOf(blocks);
    }

    private ContentBlock structuredBlock(JsonNode item, int sequence, int page) {
        String raw = item.path("type").asText();
        String type = type(raw, item);
        var attributes = json.createObjectNode().put("mineruType", raw);
        for (String key : List.of("bbox", "level", "captions", "footnotes", "continues_prev", "continues_next")) {
            if (item.hasNonNull(key)) attributes.set(key, item.get(key));
        }
        String image = imagePath(field(item, "image_source"));
        String text = field(item, "content", "text", "items");
        if ("table".equals(type) && text != null) attributes.put("tableHtml", text);
        if (image != null && ("table".equals(type) || "image".equals(type))) {
            text = field(item, "captions");
        }
        return new ContentBlock(sequence, type, text, page, image, attributes.toString());
    }

    private ContentBlock block(JsonNode item, int sequence) {
        String format = field(item, "type", "block_type", "category");
        String type = type(format == null ? "" : format.toLowerCase(Locale.ROOT), item);
        var attributes = json.createObjectNode();
        for (String key : List.of("bbox", "pdf_bbox", "text_level", "title_level", "image_caption", "img_caption",
                "image_footnote", "img_footnote", "table_caption", "table_footnote", "table_body", "format", "sub_type")) {
            if (item.hasNonNull(key)) attributes.set(key, item.get(key));
        }
        String body = field(item, "text", "content", "latex", "md", "list_items", "table_caption", "image_caption", "img_caption", "table_body");
        int page = 0;
        for (String key : List.of("page_idx", "pdf_page", "page_id", "page")) {
            if (item.hasNonNull(key)) { page = Math.max(0, item.get(key).asInt(0)); break; }
        }
        String image = imagePath(field(item, "img_path", "image_path"));
        return new ContentBlock(sequence, type, body, page, image, attributes.toString());
    }

    private static String type(String raw, JsonNode item) {
        if (List.of("header", "footer", "page_header", "page_footer", "page_number", "aside_text",
                "page_aside_text", "discarded").contains(raw)) return "discarded";
        if (raw.contains("title") || item.hasNonNull("text_level")) return "title";
        if (raw.contains("table")) return "table";
        if (raw.contains("image") || raw.contains("figure") || raw.equals("chart")) return "image";
        if (raw.contains("equation") || raw.contains("formula")) return "equation";
        if (raw.contains("ref") || "ref_text".equals(item.path("sub_type").asText())) return "reference";
        if (raw.contains("footnote")) return "page_footnote";
        return "text";
    }

    private static String field(JsonNode item, String... names) {
        for (String name : names) {
            JsonNode node = item.path(name);
            if (node.isTextual() && !node.asText().isBlank()) return node.asText();
            if (node.isObject()) {
                String content = field(node, "content", "text", "items");
                if (content != null) return content;
            } else if (node.isArray() && !node.isEmpty()) {
                List<String> values = new ArrayList<>();
                node.forEach(value -> {
                    String content = value.isTextual() ? value.asText() : field(value, "content", "text", "items");
                    if (content != null && !content.isBlank()) values.add(content);
                });
                if (!values.isEmpty()) return String.join("\n", values);
            }
        }
        return null;
    }

    private static String imagePath(String image) {
        if (image == null || image.contains("\\") || image.indexOf('\0') >= 0) return null;
        var path = Path.of(image).normalize();
        return path.isAbsolute() || path.startsWith("..") ? null : path.toString();
    }
}
