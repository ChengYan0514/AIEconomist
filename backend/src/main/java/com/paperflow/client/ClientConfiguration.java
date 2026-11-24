package com.paperflow.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paperflow.client.adapters.engine.DisabledCitationEngine;
import com.paperflow.client.adapters.engine.LegacyCitationsRetrievalEngine;
import com.paperflow.client.adapters.engine.StandardHttpCitationEngine;
import com.paperflow.client.adapters.engine.StubCitationEngine;
import com.paperflow.client.adapters.persistence.JdbcCitationTaskStore;
import com.paperflow.client.adapters.persistence.JdbcClientJournalStore;
import com.paperflow.client.adapters.persistence.JdbcLocalMetadataEnricher;
import com.paperflow.client.catalog.ClientJournalService;
import com.paperflow.client.catalog.ClientModuleService;
import com.paperflow.client.citation.BibtexExporter;
import com.paperflow.client.citation.CitationEngine;
import com.paperflow.client.citation.CitationEventHub;
import com.paperflow.client.citation.CitationFormatter;
import com.paperflow.client.citation.CitationTaskLifecycle;
import com.paperflow.client.citation.CitationTaskRunner;
import com.paperflow.client.citation.CitationTaskService;
import com.paperflow.client.citation.CitationTaskStore;
import com.paperflow.client.citation.LocalMetadataEnricher;
import com.paperflow.main.WorkspaceProperties;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ClientProperties.class)
public class ClientConfiguration {
    @Bean
    CitationEngine citationEngine(ClientProperties properties, ObjectMapper json) {
        var citation = properties.citations();
        return switch (citation.provider()) {
            case "stub" -> new StubCitationEngine();
            case "disabled" -> new DisabledCitationEngine();
            case "citations-retrieval-v1" -> new LegacyCitationsRetrievalEngine(
                    citation.engineUrl(), citation.requestTimeout(), citation.pollInterval(), json);
            case "standard-v1" -> new StandardHttpCitationEngine(
                    citation.engineUrl(), citation.requestTimeout(), citation.pollInterval(), json);
            default -> throw new IllegalArgumentException("Unsupported client citation provider");
        };
    }

    @Bean
    ClientModuleService clientModules(CitationEngine engine, ClientProperties properties) {
        return new ClientModuleService(engine, properties.qa().enabled(), properties.writing().enabled());
    }

    @Bean
    ClientJournalService clientJournals(JdbcTemplate jdbc, WorkspaceProperties workspace) {
        return new ClientJournalService(new JdbcClientJournalStore(jdbc, workspace.openalexSchema()));
    }

    @Bean
    CitationTaskStore citationTaskStore(JdbcTemplate jdbc, TransactionTemplate tx, ObjectMapper json) {
        return new JdbcCitationTaskStore(jdbc, tx, json);
    }

    @Bean
    LocalMetadataEnricher localCitationMetadata(JdbcTemplate jdbc, WorkspaceProperties workspace) {
        return new JdbcLocalMetadataEnricher(jdbc, workspace.openalexSchema());
    }

    @Bean
    CitationEventHub citationEvents() {
        return new CitationEventHub();
    }

    @Bean
    CitationFormatter citationFormatter() {
        return new CitationFormatter();
    }

    @Bean
    CitationTaskLifecycle citationLifecycle(CitationTaskStore store, CitationEventHub events) {
        return new CitationTaskLifecycle(store, events);
    }

    @Bean
    CitationTaskRunner citationRunner(CitationTaskLifecycle lifecycle, CitationEngine engine,
                                      LocalMetadataEnricher enricher, CitationFormatter formatter,
                                      ClientProperties props) {
        return new CitationTaskRunner(lifecycle, engine, enricher, formatter, props.citations().maxConcurrency());
    }

    @Bean
    CitationTaskService citationTasks(CitationTaskStore store, CitationTaskLifecycle lifecycle, CitationTaskRunner runner,
                                      CitationEngine engine, CitationEventHub events, CitationFormatter formatter,
                                      ClientProperties props) {
        return new CitationTaskService(store, lifecycle, runner, engine, events, formatter, new BibtexExporter(),
                props.citations().maxActivePerUser());
    }

    @Bean
    ApplicationRunner recoverCitationTasks(CitationTaskLifecycle lifecycle) {
        return args -> lifecycle.recover();
    }
}
