package org.firememory.api;

import org.firememory.exception.UnsupportedAudioException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Maps domain exceptions to RFC 9457 problem responses (application/problem+json).
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(UnsupportedAudioException.class)
    public ProblemDetail handleUnsupportedAudio(UnsupportedAudioException error) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNSUPPORTED_MEDIA_TYPE, error.getMessage());
        problem.setTitle("Unsupported audio format");
        return problem;
    }
}
