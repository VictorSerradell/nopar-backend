package com.company.nopar.service;

import com.company.nopar.entity.User;
import com.company.nopar.exception.DuplicateResourceException;
import com.company.nopar.exception.InvalidRequestException;
import com.company.nopar.exception.ResourceNotFoundException;
import com.company.nopar.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;

@Service
@RequiredArgsConstructor
public class UserService {

    /** BCrypt solo lee los primeros 72 bytes de la contraseña: lo que pase de ahí no cuenta (o se rechaza). */
    private static final int MAX_PASSWORD_BYTES = 72;

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final RefreshTokenService refreshTokenService;
    private final DeviceService deviceService;
    private final ShiftService shiftService;

    /**
     * Todos los usuarios, por nombre sin distinguir mayúsculas, para que un administrador elija a quién restablecer la
     * contraseña. Son pocas decenas (conductores y encargados de una estación), así que no se pagina.
     */
    @Transactional(readOnly = true)
    public List<User> listUsers() {
        return userRepository.findAll(Sort.by(Sort.Order.asc("username").ignoreCase()));
    }

    @Transactional
    public User createDriver(String username, String rawPassword) {
        return createUser(username, rawPassword, User.Role.DRIVER);
    }

    /** Un encargado: puede dar la orden de subir a una ola, pero no administra nada más. */
    @Transactional
    public User createManager(String username, String rawPassword) {
        return createUser(username, rawPassword, User.Role.MANAGER);
    }

    @Transactional
    public User createUser(String username, String rawPassword, User.Role role) {
        return create(username, rawPassword, role, false);
    }

    /** Un super administrador: administrador que además puede desactivar y reactivar a otros usuarios. */
    @Transactional
    public User createSuperAdmin(String username, String rawPassword) {
        return create(username, rawPassword, User.Role.ADMIN, true);
    }

    /**
     * Da el rango de super administrador a un administrador que ya existe, sin tocar su contraseña, y se asegura de que
     * esté activo. Solo lo usa el arranque (SuperAdminInitializer).
     */
    @Transactional
    public User promoteToSuperAdmin(String username) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new ResourceNotFoundException("Usuario no encontrado: " + username));
        if (user.getRole() != User.Role.ADMIN) {
            throw new InvalidRequestException("El usuario " + username + " no es administrador");
        }
        user.setSuperAdmin(true);
        user.setEnabled(true);
        return user;
    }

    /**
     * Desactiva una cuenta: conserva todo su historial, pero ya no puede iniciar sesión ni usar la app. Se cierran sus
     * sesiones (refresh tokens), se dan de baja sus dispositivos push y, si es un conductor, se le quitan los turnos de
     * hoy y de días futuros (sin avisarle). Los turnos y partes pasados no se tocan.
     *
     * Solo un super administrador puede hacerlo, y no a sí mismo ni a otro super administrador. Repetirlo sobre una
     * cuenta ya desactivada no hace nada.
     */
    @Transactional
    public void deactivateUser(Long userId, User actor) {
        requireSuperAdmin(actor);
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("Usuario no encontrado"));
        if (user.getId().equals(actor.getId())) {
            throw new InvalidRequestException("No puedes desactivar tu propia cuenta");
        }
        if (user.isSuperAdmin()) {
            throw new InvalidRequestException("No se puede desactivar a un super administrador");
        }
        if (!user.isEnabled()) {
            return;
        }

        user.setEnabled(false);
        refreshTokenService.deleteByUser(user.getUsername());
        deviceService.unregisterAllDevices(user.getId());
        if (user.getRole() == User.Role.DRIVER) {
            shiftService.deleteShiftsOfDriverFrom(user.getId(), LocalDate.now());
        }
    }

    /** Reactiva una cuenta desactivada: puede volver a iniciar sesión con su contraseña. No devuelve los turnos que se le quitaron. */
    @Transactional
    public void activateUser(Long userId, User actor) {
        requireSuperAdmin(actor);
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("Usuario no encontrado"));
        user.setEnabled(true);
    }

    private static void requireSuperAdmin(User actor) {
        if (actor == null || !actor.isSuperAdmin()) {
            throw new AccessDeniedException("Solo un super administrador puede desactivar o reactivar cuentas");
        }
    }

    private User create(String username, String rawPassword, User.Role role, boolean superAdmin) {
        requireWithinBcryptLimit(rawPassword, "La contraseña");
        if (userRepository.existsByUsername(username)) {
            throw new DuplicateResourceException("Ya existe un usuario con el nombre: " + username);
        }

        User user = new User();
        user.setUsername(username);
        user.setPassword(passwordEncoder.encode(rawPassword));
        user.setRole(role);
        user.setSuperAdmin(superAdmin);

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
     * También da de baja todos sus dispositivos push, el de quien cambia la contraseña incluido: la app debe volver
     * a registrarlo (POST /api/v1/devices) con los tokens nuevos.
     *
     * Los datos incorrectos son un 400 (InvalidRequestException) y no un 401: el usuario SÍ está autenticado, y un 401
     * haría que el cliente intentara renovar el token o cerrara la sesión.
     *
     * Un conductor no puede cambiar su contraseña (AccessDeniedException, un 403): se la restablece un administrador.
     * El endpoint ya lo exige con @PreAuthorize; esta comprobación evita que otra vía de entrada se lo salte.
     */
    @Transactional
    public User changePassword(Long userId, String currentPassword, String newPassword) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("Usuario no encontrado"));
        if (user.getRole() == User.Role.DRIVER) {
            throw new AccessDeniedException("Los conductores no pueden cambiar su contraseña: pídesela a un administrador");
        }

        // Una contraseña actual de más de 72 bytes no puede ser la guardada: se rechaza sin llegar al encoder
        if (exceedsBcryptLimit(currentPassword) || !passwordEncoder.matches(currentPassword, user.getPassword())) {
            throw new InvalidRequestException("La contraseña actual no es correcta");
        }
        requireWithinBcryptLimit(newPassword, "La nueva contraseña");
        if (newPassword.equals(currentPassword)) {
            throw new InvalidRequestException("La nueva contraseña debe ser distinta de la actual");
        }

        // Primero se calcula y se revocan las sesiones; la contraseña se asigna al final, ya sin más consultas
        String newHash = passwordEncoder.encode(newPassword);
        refreshTokenService.deleteByUser(user.getUsername());
        deviceService.unregisterAllDevices(user.getId());
        user.setPassword(newHash);
        return user;
    }

    /**
     * Restablecimiento por un administrador: asigna una contraseña nueva a un conductor o a un encargado que ha olvidado
     * la suya y cierra todas sus sesiones y da de baja sus dispositivos push. No exige la contraseña actual. No vale
     * para administradores: un administrador cambia la suya con su contraseña actual, y así ninguno puede quedarse en
     * silencio con la cuenta de otro.
     */
    @Transactional
    public void resetPassword(Long userId, String newPassword) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("Usuario no encontrado"));
        if (user.getRole() != User.Role.DRIVER && user.getRole() != User.Role.MANAGER) {
            throw new InvalidRequestException("Solo se puede restablecer la contraseña de un conductor o de un encargado");
        }
        requireWithinBcryptLimit(newPassword, "La nueva contraseña");

        String newHash = passwordEncoder.encode(newPassword);
        refreshTokenService.deleteByUser(user.getUsername());
        deviceService.unregisterAllDevices(user.getId());
        user.setPassword(newHash);
    }

    private static boolean exceedsBcryptLimit(String password) {
        return password.getBytes(StandardCharsets.UTF_8).length > MAX_PASSWORD_BYTES;
    }

    private static void requireWithinBcryptLimit(String password, String what) {
        if (exceedsBcryptLimit(password)) {
            throw new InvalidRequestException(what + " no puede superar los " + MAX_PASSWORD_BYTES
                    + " bytes en UTF-8 (las letras con tilde y los emojis ocupan más de uno)");
        }
    }
}
