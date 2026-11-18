package com.company.nopar.exception;

/** Los kilómetros indicados no son coherentes con los ya registrados (un cuentakilómetros no retrocede). */
public class InvalidKilometersException extends RuntimeException {

    public InvalidKilometersException(String message) {
        super(message);
    }
}
