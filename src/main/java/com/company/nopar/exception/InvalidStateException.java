package com.company.nopar.exception;

/** La operación no es válida en el estado actual del recurso (p. ej. revisar una inspección ya revisada). */
public class InvalidStateException extends RuntimeException {

    public InvalidStateException(String message) {
        super(message);
    }
}
