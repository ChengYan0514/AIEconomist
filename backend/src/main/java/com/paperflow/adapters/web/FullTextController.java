package com.paperflow.adapters.web;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paperflow.adapters.files.FileStorage;
import com.paperflow.fulltext.ContentBlock;
import com.paperflow.fulltext.FullTextService;
import com.paperflow.paper.manage.PaperService;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.NoSuchFileException;
import java.util.Locale;

@RestController
@RequestMapping("/api/papers/{paperId}")
public class FullTextController {
    private final PaperService papers;
    private final FullTextService text;
    private final FileStorage files;
    private final ObjectMapper json;

    public FullTextController(PaperService papers, FullTextService text, FileStorage files, ObjectMapper json) {
        this.papers = papers;
        this.text = text;
        this.files = files;
        this.json = json;
    }

    @GetMapping("/file")
    ResponseEntity<FileSystemResource> original(@PathVariable String paperId) throws IOException {
        var paper = papers.get(paperId);
        var file = files.original(paper);
        boolean pdf = "PDF".equals(paper.originalType());
        String name = paper.originalName() == null ? file.getFileName().toString() : paper.originalName();
        var disposition = (pdf ? ContentDisposition.inline() : ContentDisposition.attachment())
                .filename(name.replaceAll("[\\r\\n]", ""), StandardCharsets.UTF_8).build();
        return ResponseEntity.ok().headers(headers -> headers.setContentDisposition(disposition))
                .contentType(pdf ? MediaType.APPLICATION_PDF : MediaType.APPLICATION_OCTET_STREAM)
                .body(new FileSystemResource(file));
    }

    @GetMapping("/blocks")
    Page<BlockView> blocks(@PathVariable String paperId, @RequestParam(defaultValue = "0") int page,
                           @RequestParam(defaultValue = "20") int size) {
        var paper = papers.get(paperId);
        var blocks = text.blocks(paper, size, Page.offset(page, size)).stream().map(this::view).toList();
        return new Page<>(blocks, page, size, text.count(paper));
    }

    @GetMapping("/blocks/{sequence}/asset")
    ResponseEntity<FileSystemResource> asset(@PathVariable String paperId, @PathVariable int sequence) throws IOException {
        var asset = text.asset(papers.get(paperId), sequence);
        var file = files.read(asset.legacy(), asset.path());
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        if (!name.matches(".*\\.(png|jpe?g|gif|webp)$")) throw new NoSuchFileException("unavailable");
        MediaType type = name.endsWith(".webp") ? MediaType.valueOf("image/webp") :
                name.endsWith(".png") ? MediaType.IMAGE_PNG : name.endsWith(".gif") ? MediaType.IMAGE_GIF : MediaType.IMAGE_JPEG;
        return ResponseEntity.ok().contentType(type).body(new FileSystemResource(file));
    }

    record BlockView(int sequence, String type, String text, int page, boolean hasAsset, JsonNode attributes) {}

    private BlockView view(ContentBlock block) {
        try {
            return new BlockView(block.sequence(), block.type(), block.text(), block.page(), block.imagePath() != null,
                    json.readTree(block.attributes()));
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("内容块属性无效", error);
        }
    }
}
