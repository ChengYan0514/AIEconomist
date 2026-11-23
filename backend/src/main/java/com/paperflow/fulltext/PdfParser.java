package com.paperflow.fulltext;

import com.paperflow.paper.model.Paper;
import java.io.IOException;
import java.util.function.Consumer;

public interface PdfParser {
    ParseArtifact parse(Paper paper, ParseTask previous, Consumer<ParseTask> remember)
            throws IOException, InterruptedException;
}
