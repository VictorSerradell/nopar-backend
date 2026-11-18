package com.company.nopar.config;

import com.company.nopar.dto.CreateUserRequest;
import com.company.nopar.entity.User;
import com.company.nopar.exception.DuplicateResourceException;
import com.company.nopar.repository.UserRepository;
import com.company.nopar.service.UserService;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.stream.Collectors;

/**
 * Crea el administrador inicial al arrancar, a partir de las variables de entorno ADMIN_USERNAME y ADMIN_PASSWORD.
 *
 * Como el alta de usuarios es solo para administradores, en una base de datos vacía no habría forma
 * de crear el primero. Reglas:
 * - sin variables definidas no hace nada;
 * - si ya existe algún administrador no hace nada (nunca cambia contraseñas ni promociona usuarios);
 * - si las variables están a medias o no cumplen las reglas de CreateUserRequest, el arranque falla
 *   con un mensaje claro, que nunca incluye la contraseña.
 */
@Slf4j
@Component
public class AdminInitializer implements ApplicationRunner {

    private final UserRepository userRepository;
    private final UserService userService;
    private final Validator validator;
    private final String username;
    private final String password;

    public AdminInitializer(
            UserRepository userRepository,
            UserService userService,
            Validator validator,
            @Value("${application.bootstrap.admin.username:}") String username,
            @Value("${application.bootstrap.admin.password:}") String password
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

        if (noUsername && noPassword) {
            return;
        }
        if (userRepository.existsByRole(User.Role.ADMIN)) {
            log.info("Ya existe un administrador: se ignoran ADMIN_USERNAME y ADMIN_PASSWORD");
            return;
        }
        if (noUsername || noPassword) {
            throw new IllegalStateException(
                    "ADMIN_USERNAME y ADMIN_PASSWORD deben definirse a la vez para crear el administrador inicial");
        }

        Set<ConstraintViolation<CreateUserRequest>> violations =
                validator.validate(new CreateUserRequest(username, password));
        if (!violations.isEmpty()) {
            String reasons = violations.stream()
                    .map(ConstraintViolation::getMessage)
                    .sorted()
                    .collect(Collectors.joining("; "));
            throw new IllegalStateException("ADMIN_USERNAME o ADMIN_PASSWORD no válidos: " + reasons);
        }

        try {
            userService.createUser(username, password, User.Role.ADMIN);
        } catch (DuplicateResourceException ex) {
            throw new IllegalStateException(
                    "El usuario '" + username + "' ya existe y no es administrador: elige otro ADMIN_USERNAME", ex);
        }
        log.info("Administrador inicial '{}' creado. Ya puedes quitar ADMIN_USERNAME y ADMIN_PASSWORD de la configuración",
                username);
    }
}
