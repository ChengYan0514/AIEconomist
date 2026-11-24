package com.paperflow.main;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paperflow.adapters.files.FileStorage;
import com.paperflow.adapters.files.MineruContent;
import com.paperflow.adapters.files.MineruTasks;
import com.paperflow.adapters.files.ZipOriginalArchive;
import com.paperflow.adapters.web.AuditAttempt;
import com.paperflow.audit.AuditLog;
import com.paperflow.fulltext.FullTextService;
import com.paperflow.fulltext.FullTextStore;
import com.paperflow.fulltext.ParsedContent;
import com.paperflow.fulltext.PdfParser;
import com.paperflow.job.JobAudit;
import com.paperflow.job.JobJson;
import com.paperflow.job.JobService;
import com.paperflow.job.JobStore;
import com.paperflow.job.JobWorker;
import com.paperflow.openalex.OpenAlexService;
import com.paperflow.openalex.OpenAlexStore;
import com.paperflow.original.OriginalArchive;
import com.paperflow.original.OriginalImports;
import com.paperflow.paper.journal.JournalService;
import com.paperflow.paper.journal.JournalStore;
import com.paperflow.paper.manage.PaperService;
import com.paperflow.paper.manage.PaperStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration(proxyBeanMethods = false)
class WorkspaceConfiguration {
    @Bean
    FileStorage fileStorage(WorkspaceProperties config) {
        return new FileStorage(config.storage(), config.legacyStorage());
    }

    @Bean
    PaperService paperService(PaperStore store, FileStorage files, TransactionTemplate transactions,
                              WorkspaceProperties config, AuditLog audit) {
        return new PaperService(store, files, transactions, config.openalexSchema(), audit);
    }

    @Bean
    JournalService journalService(JournalStore store, WorkspaceProperties config) {
        return new JournalService(store, config.openalexSchema());
    }

    @Bean
    OpenAlexService openAlexService(OpenAlexStore store, PaperService papers,
                                   TransactionTemplate transactions, WorkspaceProperties config, AuditLog audit) {
        return new OpenAlexService(store, papers, transactions, config.openalexSchema(), audit);
    }

    @Bean
    PdfParser pdfParser(FileStorage files, ObjectMapper json, com.paperflow.settings.ServiceSettingsService settings) {
        return new MineruTasks(files, json, () -> settings.current().mineru());
    }

    @Bean
    ParsedContent parsedContent(FileStorage files, ObjectMapper json) {
        return new MineruContent(files, json);
    }

    @Bean
    FullTextService fullTextService(PaperService papers, FullTextStore store, PdfParser parser,
                                    ParsedContent content, TransactionTemplate transactions) {
        return new FullTextService(papers, store, parser, content, transactions);
    }

    @Bean
    OriginalArchive originalArchive(FileStorage files) {
        return new ZipOriginalArchive(files);
    }

    @Bean
    JobJson jobJson(ObjectMapper mapper) {
        return new JobJson(mapper);
    }

    @Bean
    AuditAttempt auditAttempt(AuditLog audit) {
        return new AuditAttempt(audit);
    }

    @Bean
    JobAudit jobAudit(AuditLog audit) {
        return new JobAudit(audit);
    }

    @Bean
    JobService jobService(JobStore jobs, PaperService papers, JobJson json, TransactionTemplate transactions, JobAudit audit) {
        return new JobService(jobs, papers, json, transactions, audit);
    }

    @Bean
    OriginalImports originalImports(OriginalArchive archive, JobService jobs, JobJson json, PaperService papers) {
        return new OriginalImports(archive, jobs, json, papers);
    }

    @Bean
    JobSchedule jobSchedule(JobStore jobs, JobJson json, OriginalImports originals, FullTextService text,
                            OpenAlexService openAlex, TransactionTemplate transactions, WorkspaceProperties config, JobAudit audit) {
        return new JobSchedule(new JobWorker(jobs, json, originals, text, openAlex, transactions, config.mineruTimeout(), audit));
    }

    static class JobSchedule {
        private final JobWorker worker;

        JobSchedule(JobWorker worker) {
            this.worker = worker;
        }

        @Scheduled(fixedDelayString = "${paperflow.job-delay}")
        void tick() {
            worker.drain();
        }
    }
}
