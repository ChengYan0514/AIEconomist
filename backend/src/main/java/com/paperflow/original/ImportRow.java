package com.paperflow.original;

import com.paperflow.paper.model.PaperMetadata;

public record ImportRow(String archive, String entry, String type, PaperMetadata metadata) {}
