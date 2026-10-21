package com.imperial.tenant.web;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

/** Only covers failure modes that would otherwise leak as a raw 500 with no @ControllerAdvice anywhere in this service. */
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public org.springframework.http.ResponseEntity<String> handleTooLarge(MaxUploadSizeExceededException e) {
        return org.springframework.http.ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body("uploaded file exceeds the maximum allowed size");
    }
}
