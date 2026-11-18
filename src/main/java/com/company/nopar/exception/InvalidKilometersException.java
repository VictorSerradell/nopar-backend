package com.company.nopar.exception;

/** Los kilómetros indicados no son coherentes con los ya registrados (un cuentakilómetros no retrocede). */
public class InvalidKilometersException extends RuntimeException {

    public InvalidKilometersException(String message) {
        super(message);
    }

    /** Mismo mensaje para los partes y para las devoluciones: los kilómetros no pueden bajar. */
    public static InvalidKilometersException lowerThanRegistered(int indicated, int registered) {
        return new InvalidKilometersException("Los kilómetros indicados (" + indicated
                + ") son menores que los registrados para el vehículo (" + registered + ")");
    }
}
