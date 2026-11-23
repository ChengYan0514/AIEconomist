package com.paperflow.main;

import com.paperflow.adapters.files.FileStorage;
import com.paperflow.adapters.files.MineruHealth;
import com.paperflow.adapters.persistence.DatabaseStatus;
import com.paperflow.job.JobService;
import com.paperflow.systemstatus.SystemStatus;
import com.paperflow.systemstatus.SystemStatus.Probe;
import jakarta.servlet.MultipartConfigElement;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.util.List;

import static com.paperflow.systemstatus.SystemStatus.Dependency.*;

@Configuration(proxyBeanMethods = false)
class SystemStatusConfiguration {
    @Bean
    SystemStatus systemStatus(DataSource source, FileStorage files, WorkspaceProperties config, BuildProperties build,
                              ObjectProvider<MultipartConfigElement> multipart) {
        var database = new DatabaseStatus(source, config.openalexSchema());
        var timeout = config.mineruTimeout().compareTo(SystemStatus.CHECK_TIMEOUT) < 0
                ? config.mineruTimeout() : SystemStatus.CHECK_TIMEOUT;
        var mineru = new MineruHealth(config.mineruUrl(), timeout);
        return new SystemStatus(List.of(
                new Probe(POSTGRESQL, database::checkApplication, "查询成功", "应用查询失败", "查询超时"),
                new Probe(MINERU, mineru::check, "健康接口响应正常", "健康接口不可用", "连接超时"),
                new Probe(CURRENT_STORAGE, files::checkReadWrite, "读写检查通过", "读写检查失败", "读写检查超时"),
                new Probe(LEGACY_STORAGE, files::checkLegacyRead, "读取检查通过", "读取检查失败", "读取检查超时"),
                new Probe(OPENALEX_SNAPSHOT, database::checkSnapshot, "快照表可读", "无法读取必需的快照表", "快照查询超时")
        ), effectiveConfiguration(build, multipart.getIfAvailable()));
    }

    private SystemStatus.Configuration effectiveConfiguration(BuildProperties build, MultipartConfigElement multipart) {
        long limit = Long.MAX_VALUE;
        if (multipart != null) {
            if (multipart.getMaxFileSize() >= 0) limit = multipart.getMaxFileSize();
            if (multipart.getMaxRequestSize() >= 0) limit = Math.min(limit, multipart.getMaxRequestSize());
        }
        return new SystemStatus.Configuration(build.getVersion(), Math.min(limit, FileStorage.MAX_PDF_BYTES),
                Math.min(limit, FileStorage.MAX_ZIP_BYTES), JobService.MAX_TARGETS);
    }
}
