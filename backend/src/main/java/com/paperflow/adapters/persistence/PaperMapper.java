package com.paperflow.adapters.persistence;

import com.paperflow.paper.manage.PaperStore;
import com.paperflow.paper.model.Paper;
import com.paperflow.paper.model.PaperFilter;
import org.apache.ibatis.cursor.Cursor;
import org.apache.ibatis.annotations.Param;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.function.Consumer;

public interface PaperMapper extends PaperStore {
    Cursor<Paper> selectExport(@Param("filter") PaperFilter filter);

    @Override
    long count(@Param("filter") PaperFilter filter);

    @Override
    default long export(PaperFilter filter, Consumer<Paper> output) {
        try (var cursor = selectExport(filter)) {
            long rows = 0;
            for (Paper paper : cursor) {
                output.accept(paper);
                rows++;
            }
            return rows;
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }
}
