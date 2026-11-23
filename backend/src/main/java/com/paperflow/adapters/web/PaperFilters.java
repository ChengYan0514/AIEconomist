package com.paperflow.adapters.web;

import com.paperflow.paper.model.PaperFilter;
import com.paperflow.paper.model.PaperStatus;

record PaperFilters(String q, String sourceId, Integer yearFrom, Integer yearTo, PaperStatus status, Boolean archived) {
    PaperFilter toFilter() {
        return new PaperFilter(q, sourceId, yearFrom, yearTo, status, Boolean.TRUE.equals(archived));
    }
}
