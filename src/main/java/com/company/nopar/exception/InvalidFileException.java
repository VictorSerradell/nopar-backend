package com.company.nopar.exception;

/** Fichero adjunto ausente o no aceptable (formato no permitido, contenido que no es una imagen...). */
public class InvalidFileException extends RuntimeException {

    public InvalidFileException(String message) {
        super(message);
    }
}
