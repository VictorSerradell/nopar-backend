package com.company.nopar.exception;

import org.springframework.context.MessageSourceResolvable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Traduce las excepciones de negocio, de validación y de autenticación a respuestas HTTP
 * (ProblemDetail, RFC 9457).
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

    @ExceptionHandler(ResourceInUseException.class)
    public ProblemDetail handleInUse(ResourceInUseException ex) {
        return problem(HttpStatus.CONFLICT, "Recurso en uso", ex.getMessage());
    }

    @ExceptionHandler(InvalidStateException.class)
    public ProblemDetail handleInvalidState(InvalidStateException ex) {
        return problem(HttpStatus.CONFLICT, "Estado no válido", ex.getMessage());
    }

    @ExceptionHandler(InvalidKilometersException.class)
    public ProblemDetail handleInvalidKilometers(InvalidKilometersException ex) {
        return problem(HttpStatus.CONFLICT, "Kilómetros no válidos", ex.getMessage());
    }

    /** JSON mal formado o con valores fuera de lo permitido (p. ej. un enum desconocido). */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ProblemDetail handleUnreadableBody(HttpMessageNotReadableException ex) {
        // Sin el detalle del parser: solo se dice qué revisar
        return problem(HttpStatus.BAD_REQUEST, "Cuerpo no válido",
                "El cuerpo de la petición no se puede leer: revisa el formato y los valores permitidos");
    }

    @ExceptionHandler(InvalidFileException.class)
    public ProblemDetail handleInvalidFile(InvalidFileException ex) {
        return problem(HttpStatus.BAD_REQUEST, "Archivo no válido", ex.getMessage());
    }

    @ExceptionHandler(InvalidRefreshTokenException.class)
    public ProblemDetail handleInvalidRefreshToken(InvalidRefreshTokenException ex) {
        return problem(HttpStatus.UNAUTHORIZED, "No autorizado", ex.getMessage());
    }

    /** Cuerpo JSON que incumple las restricciones de un DTO (@Valid @RequestBody): errors = {campo: mensaje}. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleBodyValidation(MethodArgumentNotValidException ex) {
        Map<String, String> errors = new LinkedHashMap<>();
        ex.getBindingResult().getFieldErrors()
                .forEach(error -> errors.putIfAbsent(error.getField(), error.getDefaultMessage()));
        ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, "Datos no válidos",
                "La petición contiene datos no válidos");
        problem.setProperty("errors", errors);
        return problem;
    }

    /** Parámetros de ruta o de query que incumplen sus restricciones (p. ej. @RequestParam @Min). */
    @ExceptionHandler(HandlerMethodValidationException.class)
    public ProblemDetail handleParameterValidation(HandlerMethodValidationException ex) {
        List<String> errors = ex.getAllErrors().stream()
                .map(MessageSourceResolvable::getDefaultMessage)
                .filter(Objects::nonNull)
                .toList();
        ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, "Datos no válidos",
                "La petición contiene parámetros no válidos");
        problem.setProperty("errors", errors);
        return problem;
    }

    private ProblemDetail problem(HttpStatus status, String title, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);
        return problem;
    }
}
