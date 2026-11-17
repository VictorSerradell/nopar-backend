package com.company.nopar.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Traduce las excepciones de negocio y de autenticación a respuestas HTTP (ProblemDetail, RFC 9457).
 *
 * No hay un handler genérico para Exception a propósito: AccessDeniedException (@PreAuthorize)
 * debe seguir llegando a Spring Security para que responda 403, y los errores inesperados
 * deben seguir siendo 500 en lugar de ocultarse.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(AuthenticationException.class)
    public ProblemDetail handleAuthentication(AuthenticationException ex) {
        // Mensaje fijo: no se revela si falló el usuario o la contraseña
        return problem(HttpStatus.UNAUTHORIZED, "No autorizado", "Credenciales inválidas");
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    public ProblemDetail handleNotFound(ResourceNotFoundException ex) {
        return problem(HttpStatus.NOT_FOUND, "Recurso no encontrado", ex.getMessage());
    }

    @ExceptionHandler(DuplicateResourceException.class)
    public ProblemDetail handleDuplicate(DuplicateResourceException ex) {
        return problem(HttpStatus.CONFLICT, "Recurso duplicado", ex.getMessage());
    }

    private ProblemDetail problem(HttpStatus status, String title, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);
        return problem;
    }
}
