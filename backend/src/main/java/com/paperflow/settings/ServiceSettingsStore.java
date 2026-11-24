package com.paperflow.settings;

import java.time.Instant;

public interface ServiceSettingsStore {
    record StoredSettings(String valueJson, Instant updatedAt, String updatedBy) {}

    StoredSettings load();

    void save(String valueJson, String updatedBy);
}
