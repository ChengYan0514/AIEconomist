package com.paperflow.client.citation.model;

public record CitationStatement(String statementId, String text, int startChar, int endChar,
                                boolean needsCitation, String query, Double confidence) {}
