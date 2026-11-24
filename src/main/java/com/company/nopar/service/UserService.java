package com.company.nopar.service;

import com.company.nopar.entity.User;
import com.company.nopar.exception.DuplicateResourceException;
import com.company.nopar.exception.InvalidRequestException;
import com.company.nopar.exception.ResourceNotFoundException;
import com.company.nopar.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;

@Service
@RequiredArgsConstructor
public class UserService {

    /** BCrypt solo lee los primeros 72 bytes de la contraseña: lo que pase de ahí no cuenta (o se rechaza). */
    private static final int MAX_PASSWORD_BYTES = 72;

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final RefreshTokenService refreshTokenService;

    @Transactional
    public User createDriver(String username, String rawPassword) {
        return createUser(username, rawPassword, User.Role.DRIVER);
    }

    @Transactional
    public User createUser(String username, String rawPassword, User.Role role) {
        if (userRepository.existsByUsername(username)) {
            throw new DuplicateResourceException("Ya existe un usuario con el nombre: " + username);
        }

        User user = new User();
        user.setUsername(username);
        user.setPassword(passwordEncoder.encode(rawPassword));
        user.setRole(role);

        try {
            return userRepository.saveAndFlush(user);
        } catch (DataIntegrityViolationException ex) {
            // Otra petición creó el mismo usuario entre la comprobación y el guardado
            throw new DuplicateResourceException("Ya existe un usuario con el nombre: " + username);
        }
    }

    /**
     * Cambia la contraseña de un usuario que conoce la actual y cierra todas sus sesiones: revoca todos sus
     * refresh tokens en la misma transacción, para que quien entrara con la contraseña anterior no pueda seguir
     * renovando su sesión. Los access tokens ya emitidos valen hasta que caduquen (15 minutos).
     *
     * Los datos incorrectos son un 400 (InvalidRequestException) y no un 401: el usuario SÍ está autenticado, y un 401
     * haría que el cliente intentara renovar el token o cerrara la sesión.
     */
    @Transactional
    public User changePassword(Long userId, String currentPassword, String newPassword) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("Usuario no encontrado"));

        // Una contraseña actual de más de 72 bytes no puede ser la guardada: se rechaza sin llegar al encoder
        if (exceedsBcryptLimit(currentPassword) || !passwordEncoder.matches(currentPassword, user.getPassword())) {
            throw new InvalidRequestException("La contraseña actual no es correcta");
        }
        if (exceedsBcryptLimit(newPassword)) {
            throw new InvalidRequestException(
                    "La nueva contraseña no puede superar los 72 bytes en UTF-8 (las letras con tilde y los emojis ocupan más de uno)");
        }
        if (newPassword.equals(currentPassword)) {
            throw new InvalidRequestException("La nueva contraseña debe ser distinta de la actual");
        }

        // Primero se calcula y se revocan las sesiones; la contraseña se asigna al final, ya sin más consultas
        String newHash = passwordEncoder.encode(newPassword);
        refreshTokenService.deleteByUser(user.getUsername());
        user.setPassword(newHash);
        return user;
    }

    private static boolean exceedsBcryptLimit(String password) {
        return password.getBytes(StandardCharsets.UTF_8).length > MAX_PASSWORD_BYTES;
    }
}
