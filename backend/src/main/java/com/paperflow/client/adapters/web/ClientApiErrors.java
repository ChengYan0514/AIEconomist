package com.paperflow.client.adapters.web;

import com.paperflow.client.model.ClientConflict;
import com.paperflow.client.model.ClientInvalid;
import com.paperflow.client.model.ClientNotFound;
import com.paperflow.client.model.ClientUnavailable;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice(basePackages = "com.paperflow.client")
@Order(Ordered.HIGHEST_PRECEDENCE)
public final class ClientApiErrors {
    @ExceptionHandler(ClientNotFound.class)
    public ResponseEntity<Error> notFound(ClientNotFound error) {
        return response(404, error.code(), error.getMessage());
    }

    @ExceptionHandler(ClientConflict.class)
    public ResponseEntity<Error> conflict(ClientConflict error) {
        return response(409, error.code(), error.getMessage());
    }

    @ExceptionHandler(ClientUnavailable.class)
    public ResponseEntity<Error> unavailable(ClientUnavailable error) {
        return response(503, error.code(), error.getMessage());
    }

    @ExceptionHandler(ClientInvalid.class)
    public ResponseEntity<Error> invalid(ClientInvalid error) {
        return response(400, "INVALID_INPUT", error.getMessage());
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class,
            ServletRequestBindingException.class})
    public ResponseEntity<Error> malformed(Exception error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof ClientInvalid invalid) return invalid(invalid);
        }
        return response(400, "INVALID_INPUT", "请求参数或正文格式无效");
    }

    private ResponseEntity<Error> response(int status, String code, String message) {
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON).body(new Error(code, message));
    }

    public record Error(String code, String message) {}
}
