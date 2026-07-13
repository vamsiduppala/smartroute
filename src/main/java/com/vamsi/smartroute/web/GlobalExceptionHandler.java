package com.vamsi.smartroute.web;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.Map;

/**
 * Turns bad-request-shaped failures into a clean 400 instead of a raw 500 + stack trace.
 * Anything else still surfaces as a 500 but without leaking internals to the client.
 *
 * The client-error exceptions below (malformed JSON body, missing/wrong-typed query param,
 * wrong HTTP method, unsupported content type) are ones Spring itself normally maps to a
 * specific 4xx by default -- they're listed explicitly here only because the broad Exception
 * catch-all further down would otherwise shadow that default behavior and turn them into a
 * misleading 500. Found via MockMvc tests exercising each case directly; this class has
 * already had this exact bug once (malformed JSON -> 500 instead of 400, fixed in c3452c5)
 * and a second pass (independent review) found it was incomplete -- GET on a POST-only
 * endpoint was still falling through to 500 instead of 405.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler({
            HttpMessageNotReadableException.class,
            MethodArgumentTypeMismatchException.class,
            MissingServletRequestParameterException.class
    })
    public ResponseEntity<Map<String, String>> malformedRequest(Exception e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", "malformed request"));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Map<String, String>> methodNotAllowed(HttpRequestMethodNotSupportedException e) {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).body(Map.of("error", "method not allowed"));
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<Map<String, String>> unsupportedMediaType(HttpMediaTypeNotSupportedException e) {
        return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE).body(Map.of("error", "unsupported media type"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> internalError(Exception e) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", "internal error"));
    }
}
