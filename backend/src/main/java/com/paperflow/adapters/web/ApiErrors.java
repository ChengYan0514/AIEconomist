package com.paperflow.adapters.web;

import com.paperflow.account.model.UserManagementException;
import com.paperflow.account.model.RoleManagementException;
import com.paperflow.audit.model.AuditEventNotFound;
import com.paperflow.job.JobConflict;
import com.paperflow.job.JobNotFound;
import com.paperflow.paper.journal.JournalNotFound;
import com.paperflow.paper.model.PaperConflict;
import com.paperflow.paper.model.PaperNotFound;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MissingRequestValueException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.io.IOException;
import java.nio.file.NoSuchFileException;

@RestControllerAdvice
public class ApiErrors {
    private static final Logger log = LoggerFactory.getLogger(ApiErrors.class);

    public record Error(String code, String message) {}

    @ExceptionHandler(UserManagementException.class)
    public ResponseEntity<Error> userManagement(UserManagementException error) {
        var status = error.reason() == UserManagementException.Reason.USER_NOT_FOUND ? HttpStatus.NOT_FOUND : HttpStatus.CONFLICT;
        return response(status, error.reason().name(), error.getMessage());
    }

    @ExceptionHandler(RoleManagementException.class)
    public ResponseEntity<Error> roleManagement(RoleManagementException error) {
        var status = error.reason() == RoleManagementException.Reason.ROLE_NOT_FOUND
                ? HttpStatus.NOT_FOUND : HttpStatus.CONFLICT;
        return response(status, error.reason().name(), error.getMessage());
    }

    @ExceptionHandler(WorkspaceAccess.Denied.class)
    public ResponseEntity<Error> denied(WorkspaceAccess.Denied error) {
        return response(HttpStatus.FORBIDDEN, error.code(), error.getMessage());
    }

    @ExceptionHandler(PaperNotFound.class)
    public ResponseEntity<Error> paperNotFound(PaperNotFound error) {
        return response(HttpStatus.NOT_FOUND, code(error), error.getMessage());
    }

    @ExceptionHandler(JournalNotFound.class)
    public ResponseEntity<Error> journalNotFound(JournalNotFound error) {
        return response(HttpStatus.NOT_FOUND, "JOURNAL_NOT_FOUND", error.getMessage());
    }

    @ExceptionHandler(JobNotFound.class)
    public ResponseEntity<Error> jobNotFound(JobNotFound error) {
        return response(HttpStatus.NOT_FOUND, code(error), error.getMessage());
    }

    @ExceptionHandler(AuditEventNotFound.class)
    public ResponseEntity<Error> auditEventNotFound(AuditEventNotFound error) {
        return response(HttpStatus.NOT_FOUND, "AUDIT_EVENT_NOT_FOUND", error.getMessage());
    }

    @ExceptionHandler(NoSuchFileException.class)
    public ResponseEntity<Error> missingFile(NoSuchFileException error) {
        return response(HttpStatus.NOT_FOUND, code(error), "已登记的文件当前不可读取");
    }

    @ExceptionHandler(PaperConflict.class)
    public ResponseEntity<Error> paperConflict(PaperConflict error) {
        return response(HttpStatus.CONFLICT, code(error), error.getMessage());
    }

    @ExceptionHandler(JobConflict.class)
    public ResponseEntity<Error> jobConflict(JobConflict error) {
        return response(HttpStatus.CONFLICT, code(error), error.getMessage());
    }

    @ExceptionHandler(DuplicateKeyException.class)
    public ResponseEntity<Error> conflict(DuplicateKeyException error) {
        return response(HttpStatus.CONFLICT, code(error), "数据已更新，请刷新后重试");
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Error> invalid(IllegalArgumentException error) {
        return response(HttpStatus.BAD_REQUEST, code(error), error.getMessage());
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MissingRequestValueException.class, org.springframework.validation.BindException.class,
            MethodArgumentTypeMismatchException.class, org.springframework.web.multipart.support.MissingServletRequestPartException.class})
    public ResponseEntity<Error> malformed() {
        return response(HttpStatus.BAD_REQUEST, "INVALID_INPUT", "请检查请求中的字段和值");
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Error> oversized() {
        return response(HttpStatus.PAYLOAD_TOO_LARGE, "FILE_TOO_LARGE", "上传超过文件大小上限");
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<Error> badLogin() {
        return response(HttpStatus.UNAUTHORIZED, "INVALID_LOGIN", "用户名或密码错误");
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Error> unexpected(Exception error) {
        log.error("API request failed", error);
        return response(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL", "暂时无法完成操作，请稍后重试");
    }

    static String code(Exception error) {
        if (error instanceof PaperConflict conflict) return conflict.code();
        if (error instanceof PaperNotFound) return "PAPER_NOT_FOUND";
        if (error instanceof JobConflict) return "JOB_CONFLICT";
        if (error instanceof JobNotFound) return "JOB_NOT_FOUND";
        if (error instanceof DuplicateKeyException) return "CONCURRENT_CHANGE";
        if (error instanceof IllegalArgumentException) return "INVALID_INPUT";
        if (error instanceof NoSuchFileException) return "FILE_MISSING";
        if (error instanceof IOException) return "FILE_PROCESSING_FAILED";
        return "INTERNAL";
    }

    private ResponseEntity<Error> response(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).body(new Error(code, message));
    }
}
