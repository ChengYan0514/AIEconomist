package com.paperflow.client.catalog;

import com.paperflow.client.model.ClientInvalid;

public final class ClientJournalService {
    private final ClientJournalStore store;

    public ClientJournalService(ClientJournalStore store) {
        this.store = store;
    }

    public ClientJournalStore.Catalog search(String query, int limit) {
        if (limit < 1 || limit > 200) throw new ClientInvalid("期刊目录条数必须在 1 到 200 之间");
        if (query != null && query.codePointCount(0, query.length()) > 200) throw new ClientInvalid("期刊搜索关键词过长");
        return store.search(query == null ? "" : query.strip(), limit);
    }
}
