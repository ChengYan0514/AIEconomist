package com.paperflow.adapters.persistence;

import com.paperflow.settings.ServiceSettingsStore;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;

public final class JdbcServiceSettingsStore implements ServiceSettingsStore {
    private static final String KEY = "service_config";
    private final JdbcTemplate jdbc;

    public JdbcServiceSettingsStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public StoredSettings load() {
        var rows = jdbc.query(
                "SELECT value_json::text, updated_at, updated_by FROM v2_runtime_setting WHERE setting_key = ?",
                (rs, idx) -> {
                    Timestamp ts = rs.getTimestamp(2);
                    return new StoredSettings(rs.getString(1), ts == null ? null : ts.toInstant(), rs.getString(3));
                },
                KEY);
        return rows.isEmpty() ? null : rows.get(0);
    }

    @Override
    public void save(String valueJson, String updatedBy) {
        jdbc.update("""
                INSERT INTO v2_runtime_setting (setting_key, value_json, updated_at, updated_by)
                VALUES (?, CAST(? AS jsonb), now(), ?)
                ON CONFLICT (setting_key) DO UPDATE
                SET value_json = EXCLUDED.value_json, updated_at = now(), updated_by = EXCLUDED.updated_by
                """, KEY, valueJson, updatedBy);
    }
}
