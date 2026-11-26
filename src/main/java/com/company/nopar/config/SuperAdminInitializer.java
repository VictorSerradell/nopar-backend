package com.company.nopar.config;

import com.company.nopar.dto.CreateUserRequest;
import com.company.nopar.entity.User;
import com.company.nopar.exception.InvalidRequestException;
import com.company.nopar.repository.UserRepository;
import com.company.nopar.service.UserService;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Crea el super administrador al arrancar, a partir de las variables de entorno SUPER_ADMIN_USERNAME y
 * SUPER_ADMIN_PASSWORD. El super administrador es un administrador que además puede desactivar y reactivar a los demás
 * usuarios; no se puede crear desde la app.
 *
 * Reglas:
 * - sin variables definidas no hace nada (avisa en el log si no hay ningún super administrador);
 * - si ya existe algún super administrador no hace nada (nunca cambia contraseñas);
 * - si el usuario indicado ya existe y es administrador, se le da el rango de super administrador sin tocar su
 *   contraseña; si existe y no es administrador, el arranque falla;
 * - si las variables están a medias o no cumplen las reglas de CreateUserRequest, el arranque falla con un mensaje
 *   claro, que nunca incluye la contraseña.
 *
 * Se ejecuta después de AdminInitializer, para poder dar el rango al administrador inicial si se usa el mismo nombre.
 */
@Slf4j
@Component
@Order(2)
public class SuperAdminInitializer implements ApplicationRunner {

    private final UserRepository userRepository;
    private final UserService userService;
    private final Validator validator;
    private final String username;
    private final String password;

    public SuperAdminInitializer(
            UserRepository userRepository,
            UserService userService,
            Validator validator,
            @Value("${application.bootstrap.super-admin.username:}") String username,
            @Value("${application.bootstrap.super-admin.password:}") String password
    ) {
        this.userRepository = userRepository;
        this.userService = userService;
        this.validator = validator;
        this.username = username;
        this.password = password;
    }

    @Override
    public void run(ApplicationArguments args) {
        boolean noUsername = username == null || username.isBlank();
        boolean noPassword = password == null || password.isBlank();

        if (userRepository.existsBySuperAdminTrue()) {
            if (!(noUsername && noPassword)) {
                log.info("Ya existe un super administrador: se ignoran SUPER_ADMIN_USERNAME y SUPER_ADMIN_PASSWORD");
            }
            return;
        }
        if (noUsername && noPassword) {
            log.warn("No hay ningún super administrador: nadie puede desactivar cuentas. "
                    + "Define SUPER_ADMIN_USERNAME y SUPER_ADMIN_PASSWORD para crearlo");
            return;
        }
        if (noUsername || noPassword) {
            throw new IllegalStateException(
                    "SUPER_ADMIN_USERNAME y SUPER_ADMIN_PASSWORD deben definirse a la vez para crear el super administrador");
        }

        Set<ConstraintViolation<CreateUserRequest>> violations =
                validator.validate(new CreateUserRequest(username, password));
        if (!violations.isEmpty()) {
            String reasons = violations.stream()
                    .map(ConstraintViolation::getMessage)
                    .sorted()
                    .collect(Collectors.joining("; "));
            throw new IllegalStateException("SUPER_ADMIN_USERNAME o SUPER_ADMIN_PASSWORD no válidos: " + reasons);
        }

        Optional<User> existing = userRepository.findByUsername(username);
        if (existing.isPresent()) {
            if (existing.get().getRole() != User.Role.ADMIN) {
                throw new IllegalStateException("El usuario '" + username
                        + "' ya existe y no es administrador: elige otro SUPER_ADMIN_USERNAME");
            }
            userService.promoteToSuperAdmin(username);
            log.info("El administrador '{}' es ahora super administrador (su contraseña no se ha tocado). "
                    + "Ya puedes quitar SUPER_ADMIN_USERNAME y SUPER_ADMIN_PASSWORD de la configuración", username);
            return;
        }

        try {
            userService.createSuperAdmin(username, password);
        } catch (InvalidRequestException ex) {
            // p. ej. menos de 72 caracteres pero más de 72 bytes: el mensaje no incluye nunca la contraseña
            throw new IllegalStateException("SUPER_ADMIN_PASSWORD no válida: " + ex.getMessage(), ex);
        }
        log.info("Super administrador '{}' creado. Ya puedes quitar SUPER_ADMIN_USERNAME y SUPER_ADMIN_PASSWORD de la configuración",
                username);
    }
}
