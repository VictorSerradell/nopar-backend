package com.company.nopar.exception;

/** Ya existe un recurso con el mismo identificador de negocio (p. ej. la matrícula). Se traduce a HTTP 409. */
public class DuplicateResourceException extends RuntimeException {

    public DuplicateResourceException(String message) {
        super(message);
    }
}
