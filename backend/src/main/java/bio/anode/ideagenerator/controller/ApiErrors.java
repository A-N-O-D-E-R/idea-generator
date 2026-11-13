package bio.anode.ideagenerator.controller;

import bio.anode.ideagenerator.AppError;
import bio.anode.ideagenerator.Js;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/** Errors as {error, code}, like upstream; static-file errors as {error}. */
@Slf4j
@RestControllerAdvice
class ApiErrors {
    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<Object> notFound(HttpServletRequest req) {
        // Outside /api only GET and HEAD exist, as upstream.
        if (!req.getMethod().equals("GET") && !req.getMethod().equals("HEAD")) return method();
        return ResponseEntity.status(404).body(Map.of("error", "Not found"));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<Object> method() {
        return ResponseEntity.status(405).body(Map.of("error", "Method not allowed"));
    }

    @ExceptionHandler(AsyncRequestNotUsableException.class)
    void gone() {
        // the client of an event stream went away
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Object> error(Exception e, HttpServletRequest req) {
        int status = e instanceof AppError a ? a.status : 500;
        if (e instanceof AppError && status >= 500) log.error("[{} {}] {}", req.getMethod(), req.getRequestURI(), e.getMessage());
        else if (!(e instanceof AppError)) log.error("[{} {}] {}", req.getMethod(), req.getRequestURI(), e.getMessage(), e);
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON)
                .body(Js.obj("error", e.getMessage() != null ? e.getMessage() : "Server error",
                        "code", AppError.codeOf(e, "server_error")));
    }
}
