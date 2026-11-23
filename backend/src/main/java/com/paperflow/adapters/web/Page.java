package com.paperflow.adapters.web;

import java.util.List;

public record Page<T>(List<T> items, int page, int size, long total) {
    public static long offset(int page, int size) {
        if (page < 0 || size < 1 || size > 100) {
            throw new IllegalArgumentException("分页页码或每页条数超出范围");
        }
        return (long) page * size;
    }
}
