package com.paperflow.fulltext;

import java.io.IOException;
import java.util.List;

public interface ParsedContent {
    List<ContentBlock> read(ParseArtifact artifact) throws IOException;
}
