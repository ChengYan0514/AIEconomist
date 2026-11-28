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
    Paper getWithClaims(@Param("schema") String schema, @Param("id") String id,
                        @Param("claims") CausalClaimSnapshot claims);

    List<Paper> getManyWithClaims(@Param("schema") String schema, @Param("ids") List<String> ids,
                                  @Param("claims") CausalClaimSnapshot claims);

    List<Paper> listWithClaims(@Param("schema") String schema, @Param("filter") PaperFilter filter,
                               @Param("offset") long offset, @Param("size") int size,
                               @Param("claims") CausalClaimSnapshot claims);

    Cursor<Paper> selectExportWithClaims(@Param("schema") String schema, @Param("filter") PaperFilter filter,
                                         @Param("claims") CausalClaimSnapshot claims);

    long countWithClaims(@Param("filter") PaperFilter filter, @Param("claims") CausalClaimSnapshot claims);

    @Override
    default Paper get(String schema, String id) {
        return getWithClaims(schema, id, null);
    }

    @Override
    default List<Paper> getMany(String schema, List<String> ids) {
        return getManyWithClaims(schema, ids, null);
    }

    @Override
    default List<Paper> list(String schema, PaperFilter filter, long offset, int size) {
        return listWithClaims(schema, filter, offset, size, null);
    }

    @Override
    default long export(String schema, PaperFilter filter, Consumer<Paper> output) {
        return exportWithClaims(schema, filter, null, output);
    }

    @Override
    default long count(PaperFilter filter) {
        return countWithClaims(filter, null);
    }

    default long exportWithClaims(String schema, PaperFilter filter, CausalClaimSnapshot claims,
                                  Consumer<Paper> output) {
        try (var cursor = selectExportWithClaims(schema, filter, claims)) {
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
