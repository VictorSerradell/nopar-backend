package com.company.nopar.exception;

/** Refresh token inexistente, ya usado o caducado: el cliente debe iniciar sesión de nuevo. */
public class InvalidRefreshTokenException extends RuntimeException {

    public InvalidRefreshTokenException(String message) {
        super(message);
    }
}
