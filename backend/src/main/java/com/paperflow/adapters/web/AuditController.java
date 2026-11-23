package com.paperflow.adapters.web;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import com.paperflow.audit.AuditLog;
import com.paperflow.audit.model.AuditEvent;
import com.paperflow.audit.model.AuditFilter;
import com.paperflow.audit.model.AuditOutcome;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.OffsetDateTime;
import java.util.Map;

@RestController
@RequestMapping("/api/audit-events")
public class AuditController {
    private final AuditLog audit;

    public AuditController(AuditLog audit) {
        this.audit = audit;
    }

    record Criteria(
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to,
            String actor, String action, String objectType, String objectId, AuditOutcome outcome
    ) {
        AuditFilter toFilter() {
            return new AuditFilter(from, to, actor, action, objectType, objectId, outcome);
        }
    }

    record EventView(long eventId, OffsetDateTime occurredAt, String actor, String action,
                     String objectType, String objectId, AuditOutcome outcome) {
        static EventView from(AuditEvent event) {
            return new EventView(event.eventId(), event.occurredAt(), event.actor(), event.action(),
                    event.objectType(), event.objectId(), event.outcome());
        }
    }

    record DetailView(@JsonUnwrapped EventView event, Map<String, Object> summary) {}

    @GetMapping
    Page<EventView> list(@ModelAttribute Criteria criteria, @RequestParam(defaultValue = "0") int page,
                         @RequestParam(defaultValue = "20") int size) {
        AuditFilter filter = criteria.toFilter();
        var items = audit.list(filter, Page.offset(page, size), size).stream().map(EventView::from).toList();
        return new Page<>(items, page, size, audit.count(filter));
    }

    @GetMapping("/{eventId}")
    DetailView get(@PathVariable long eventId) {
        AuditEvent event = audit.get(eventId);
        return new DetailView(EventView.from(event), event.summary());
    }
}
