package com.paperflow.adapters.persistence;

import com.paperflow.paper.manage.PaperStore;
import com.paperflow.paper.model.Paper;
import com.paperflow.paper.model.PaperFilter;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.cursor.Cursor;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.function.Consumer;

public interface PaperMapper extends PaperStore {
    @Override
    Paper get(@Param("schema") String schema, @Param("id") String id);

    @Override
    List<Paper> getMany(@Param("schema") String schema, @Param("ids") List<String> ids);

    @Override
    List<Paper> list(@Param("schema") String schema, @Param("filter") PaperFilter filter,
                     @Param("offset") long offset, @Param("size") int size);

    Cursor<Paper> selectExport(@Param("schema") String schema, @Param("filter") PaperFilter filter);

    @Override
    long count(@Param("filter") PaperFilter filter);

    @Override
    default long export(String schema, PaperFilter filter, Consumer<Paper> output) {
        try (var cursor = selectExport(schema, filter)) {
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
