package com.paperflow.fulltext;

public record ContentBlock(int sequence, String type, String text, int page, String imagePath, String attributes) {}
