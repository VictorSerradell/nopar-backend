package com.company.nopar.exception;

/** Los datos de la petición no son válidos por una regla de negocio (p. ej. un rango de fechas al revés). HTTP 400. */
public class InvalidRequestException extends RuntimeException {
    public InvalidRequestException(String message) {
        super(message);
    }
}
