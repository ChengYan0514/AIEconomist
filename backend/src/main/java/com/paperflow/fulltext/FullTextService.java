package com.paperflow.fulltext;

import com.paperflow.paper.manage.PaperService;
import com.paperflow.paper.model.Paper;
import com.paperflow.paper.model.PaperConflict;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

public final class FullTextService {
    private final PaperService papers;
    private final FullTextStore text;
    private final PdfParser parser;
    private final ParsedContent content;
    private final TransactionTemplate transactions;

    public FullTextService(PaperService papers, FullTextStore text, PdfParser parser, ParsedContent content,
                           TransactionTemplate transactions) {
        this.papers = papers;
        this.text = text;
        this.parser = parser;
        this.content = content;
        this.transactions = transactions;
    }

    public void parse(String id, long revision, ParseTask previous, Consumer<ParseTask> remember,
                      Runnable checkClaim, Runnable afterParse) throws IOException, InterruptedException {
        Paper paper = papers.get(id);
        requireRevision(paper, revision);
        if (!"PDF".equals(paper.originalType())) {
            throw new IllegalArgumentException("此原件格式暂不支持解析");
        }
        ParseArtifact artifact = parser.parse(paper, previous, remember);
        transactions.executeWithoutResult(state -> {
            checkClaim.run();
            requireRevision(papers.lockCurrent(id), revision);
            text.addArtifact(artifact);
            text.markParsed(id, artifact.parseId());
            afterParse.run();
        });
    }

    public boolean publish(String id, long revision, UUID targetParseId, Runnable checkClaim, Runnable afterPublish) throws IOException {
        Paper paper = papers.get(id);
        requireRevision(paper, revision);
        if (targetParseId != null && targetParseId.equals(paper.publishedParseId())) return false;
        ParseArtifact artifact = publicationInput(paper, targetParseId);
        List<ContentBlock> blocks = content.read(artifact);
        return Boolean.TRUE.equals(transactions.execute(state -> {
            checkClaim.run();
            Paper current = papers.lockCurrent(id);
            requireRevision(current, revision);
            requireSameParse(current, targetParseId);
            if (artifact.parseId().equals(current.publishedParseId())) return false;
            if (targetParseId == null) text.addArtifact(artifact);
            for (int start = 0; start < blocks.size(); start += 500) {
                text.addBlocks(artifact.parseId(), blocks.subList(start, Math.min(start + 500, blocks.size())));
            }
            text.markPublished(id, artifact.parseId());
            afterPublish.run();
            return true;
        }));
    }

    public List<ContentBlock> blocks(Paper paper, int size, long offset) {
        if (paper.publishedParseId() != null) return text.blocks(paper.publishedParseId(), size, offset);
        return paper.usesLegacyFile() ? text.legacyBlocks(paper.paperId(), size, offset) : List.of();
    }

    public long count(Paper paper) {
        if (paper.publishedParseId() != null) return text.countBlocks(paper.publishedParseId());
        return paper.usesLegacyFile() ? text.countLegacy(paper.paperId()) : 0;
    }

    public Asset asset(Paper paper, int sequence) {
        if (paper.publishedParseId() != null) {
            var block = text.block(paper.publishedParseId(), sequence);
            var parse = text.artifact(paper.publishedParseId());
            if (block != null && block.imagePath() != null) {
                String imagePath = Path.of(parse.storagePath()).resolve(block.imagePath()).toString();
                return new Asset(parse.legacy(), imagePath);
            }
        } else if (paper.usesLegacyFile()) {
            var block = text.legacyBlock(paper.paperId(), sequence);
            if (block != null) return new Asset(true, block.imagePath());
        }
        return new Asset(false, null);
    }

    public record Asset(boolean legacy, String path) {}

    private ParseArtifact publicationInput(Paper paper, UUID targetParseId) {
        requireSameParse(paper, targetParseId);
        if (targetParseId != null) return text.artifact(targetParseId);
        String legacy = paper.usesLegacyFile() ? text.legacyContent(paper.paperId()) : null;
        if (legacy == null) throw new IllegalArgumentException("请先生成解析产物");
        Path parent = Path.of(legacy).getParent();
        return new ParseArtifact(UUID.randomUUID(), paper.paperId(), paper.revision(), true,
                parent == null ? "." : parent.toString(), legacy, null);
    }

    private static void requireRevision(Paper paper, long revision) {
        paper.requireActive();
        if (paper.revision() != revision) {
            throw new PaperConflict("ORIGINAL_CHANGED", "原件已更换，请为新原件重新提交处理");
        }
    }

    private static void requireSameParse(Paper paper, UUID expected) {
        if (!java.util.Objects.equals(paper.latestParseId(), expected)) {
            throw new PaperConflict("PARSE_CHANGED", "解析产物已更新，请重新提交全文导入");
        }
    }
}
