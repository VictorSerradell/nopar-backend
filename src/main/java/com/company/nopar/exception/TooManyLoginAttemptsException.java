package com.company.nopar.exception;

import java.time.Duration;

/** Demasiados inicios de sesión fallidos: hay que esperar {@link #getRetryAfter()} antes de volver a intentarlo. */
public class TooManyLoginAttemptsException extends RuntimeException {

    private final Duration retryAfter;

    public TooManyLoginAttemptsException(Duration retryAfter) {
        super("Demasiados intentos fallidos de inicio de sesión");
        this.retryAfter = retryAfter;
    }

    public Duration getRetryAfter() {
        return retryAfter;
    }
}
