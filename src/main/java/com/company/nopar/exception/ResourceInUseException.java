package com.company.nopar.exception;

/** El recurso no puede borrarse o cambiarse porque otros datos dependen de él. */
public class ResourceInUseException extends RuntimeException {

    public ResourceInUseException(String message) {
        super(message);
    }
}
