package com.paperflow.adapters.persistence;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class DatabaseStatusTest {
    @Test
    void checksRequiredCausalTablesWithoutRequiringStwLabelTable() throws Exception {
        var primary = mock(DataSource.class);
        var causal = mock(DataSource.class);
        var connection = mock(Connection.class);
        var statement = mock(Statement.class);
        var rows = mock(ResultSet.class);
        when(causal.getConnection()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery(anyString())).thenReturn(rows);
        when(rows.next()).thenReturn(true);

        new DatabaseStatus(primary, "dataset_20241125").checkCausal(causal);

        var sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(statement).setQueryTimeout(2);
        verify(statement).executeQuery(sqlCaptor.capture());
        String sql = sqlCaptor.getValue();
        assertThat(sql).contains("claim_table", "paper_claim_table", "causal_publication_receipt", "causal_dataset_revision");
        assertThat(sql).doesNotContain("stw_label");
    }
}
