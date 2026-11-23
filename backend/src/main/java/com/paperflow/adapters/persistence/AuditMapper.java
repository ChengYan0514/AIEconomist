package com.paperflow.adapters.persistence;

import com.paperflow.audit.AuditLog;
import com.paperflow.audit.model.AuditFilter;
import org.apache.ibatis.annotations.Param;

public interface AuditMapper extends AuditLog {
    @Override
    long count(@Param("filter") AuditFilter filter);
}
