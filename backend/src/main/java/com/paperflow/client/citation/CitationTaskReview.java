package com.paperflow.client.citation;

import com.paperflow.client.citation.model.CitationReference;
import com.paperflow.client.citation.model.CitationResult;
import com.paperflow.client.citation.model.CitationStyle;
import com.paperflow.client.citation.model.CitationTask;
import com.paperflow.client.citation.model.CitationTaskStatus;
import com.paperflow.client.model.ClientConflict;
import com.paperflow.client.model.ClientInvalid;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class CitationTaskReview {
    private final CitationFormatter formatter;
    private final BibtexExporter exporter;

    CitationTaskReview(CitationFormatter formatter, BibtexExporter exporter) {
        this.formatter = formatter;
        this.exporter = exporter;
    }

    CitationTask review(CitationTask task, CitationStyle style, Map<String, List<String>> changes) {
        requireSucceeded(task);
        var result = task.result();
        var selected = new HashMap<>(result.selectedReferenceIds());
        changes.forEach((statement, references) -> selected.put(statement, validated(result, statement, references)));
        var reviewed = result.withSelection(selected);
        var currentStyle = style == null ? task.style() : style;
        var output = formatter.format(task.originalText(), currentStyle, reviewed);
        var state = task.state();
        return task.withOutput(currentStyle, reviewed, output.annotatedText(), output.bibliography())
                .withState(new CitationTask.State(state.status(), state.stage(), state.progress(), state.engineRunId(),
                        state.errorCode(), state.errorMessage(), Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS), state.finishedAt()));
    }

    String export(CitationTask task, CitationTaskService.ExportFormat format, CitationStyle override) {
        requireSucceeded(task);
        if (format == CitationTaskService.ExportFormat.BIBTEX) return exporter.bibtex(task.result());
        var output = override == null || override == task.style()
                ? new CitationFormatter.Output(task.annotatedText(), task.bibliography())
                : formatter.format(task.originalText(), override, task.result());
        return format == CitationTaskService.ExportFormat.TXT ? exporter.text(output) : exporter.markdown(task.title(), output);
    }

    private List<String> validated(CitationResult result, String statement, List<String> references) {
        if (result.statements().stream().noneMatch(item -> item.statementId().equals(statement))) {
            throw new ClientInvalid("审阅包含未知的引文陈述");
        }
        var allowed = result.referencesByStatement().getOrDefault(statement, List.of()).stream()
                .map(CitationReference::referenceId).collect(java.util.stream.Collectors.toSet());
        if (references == null || !allowed.containsAll(references)) {
            throw new ClientInvalid("审阅包含不属于该引文陈述的参考文献");
        }
        return references.stream().distinct().toList();
    }

    private void requireSucceeded(CitationTask task) {
        if (task.state().status() != CitationTaskStatus.SUCCEEDED) {
            throw new ClientConflict("CITATION_TASK_NOT_READY", "引文标注任务尚无可用结果");
        }
    }
}
