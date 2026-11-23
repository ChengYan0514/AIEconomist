package com.paperflow.paper.manage;

import com.paperflow.paper.model.StoredOriginal;

import java.io.IOException;
import java.io.InputStream;

public interface OriginalStorage {
    StoredOriginal save(InputStream content, String paperId, String type) throws IOException;
    void discardNewFile(String path);
}
