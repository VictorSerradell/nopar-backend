package com.company.nopar.service;

import com.company.nopar.entity.User;
import com.company.nopar.exception.DuplicateResourceException;
import com.company.nopar.exception.InvalidRequestException;
import com.company.nopar.exception.ResourceNotFoundException;
import com.company.nopar.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private RefreshTokenService refreshTokenService;

    @Mock
    private DeviceService deviceService;

    @InjectMocks
    private UserService userService;

    @Test
    void givenNewUsername_whenCreateDriver_thenSavesDriverWithEncodedPassword() {
        when(passwordEncoder.encode("password-segura-123")).thenReturn("$2a$10$hash");
        when(userRepository.saveAndFlush(any(User.class))).thenAnswer(call -> call.getArgument(0));

        User created = userService.createDriver("conductor2", "password-segura-123");

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).saveAndFlush(captor.capture());
        User saved = captor.getValue();

        assertThat(created).isSameAs(saved);
        assertThat(saved.getUsername()).isEqualTo("conductor2");
        assertThat(saved.getPassword()).isEqualTo("$2a$10$hash").isNotEqualTo("password-segura-123");
        assertThat(saved.getRole()).isEqualTo(User.Role.DRIVER);
    }

    @Test
    void givenAdminRole_whenCreateUser_thenSavesAnAdmin() {
        when(passwordEncoder.encode("password-segura-123")).thenReturn("$2a$10$hash");
        when(userRepository.saveAndFlush(any(User.class))).thenAnswer(call -> call.getArgument(0));

        User created = userService.createUser("admin", "password-segura-123", User.Role.ADMIN);

        assertThat(created.getRole()).isEqualTo(User.Role.ADMIN);
        assertThat(created.getPassword()).isEqualTo("$2a$10$hash");
    }

    @Test
    void givenNewUsername_whenCreateManager_thenSavesAManagerWithEncodedPassword() {
        when(passwordEncoder.encode("password-segura-123")).thenReturn("$2a$10$hash");
        when(userRepository.saveAndFlush(any(User.class))).thenAnswer(call -> call.getArgument(0));

        User created = userService.createManager("encargado1", "password-segura-123");

        assertThat(created.getUsername()).isEqualTo("encargado1");
        assertThat(created.getRole()).isEqualTo(User.Role.MANAGER);
        assertThat(created.getPassword()).isEqualTo("$2a$10$hash");
    }

    @Test
    void givenExistingUsername_whenCreateDriver_thenDuplicateAndNothingSaved() {
        when(userRepository.existsByUsername("conductor1")).thenReturn(true);

        assertThatThrownBy(() -> userService.createDriver("conductor1", "password-segura-123"))
                .isInstanceOf(DuplicateResourceException.class)
                .hasMessageContaining("conductor1");

        verifyNoInteractions(passwordEncoder);
        verify(userRepository, never()).saveAndFlush(any());
    }

    @Test
    void givenUsernameTakenByAConcurrentRequest_whenCreateDriver_thenDuplicate() {
        when(userRepository.saveAndFlush(any(User.class)))
                .thenThrow(new DataIntegrityViolationException("unique constraint"));

        assertThatThrownBy(() -> userService.createDriver("conductor2", "password-segura-123"))
                .isInstanceOf(DuplicateResourceException.class);
    }

    // ------------------------------------------------------------------ cambio de contraseña

    @Test
    void givenTheCurrentPassword_whenChangePassword_thenStoresTheNewHashRevokesEverySessionAndDropsTheDevices() {
        User user = existingUser("conductor1", "$2a$10$viejo");
        when(userRepository.findById(7L)).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("clave-vieja-123", "$2a$10$viejo")).thenReturn(true);
        when(passwordEncoder.encode("clave-nueva-456")).thenReturn("$2a$10$nuevo");

        User result = userService.changePassword(7L, "clave-vieja-123", "clave-nueva-456");

        assertThat(result).isSameAs(user);
        assertThat(user.getPassword()).isEqualTo("$2a$10$nuevo");
        verify(refreshTokenService).deleteByUser("conductor1");
        verify(deviceService).unregisterAllDevices(7L);
    }

    @Test
    void givenAWrongCurrentPassword_whenChangePassword_thenRejectedAndNothingChanges() {
        User user = existingUser("conductor1", "$2a$10$viejo");
        when(userRepository.findById(7L)).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("no-es-esta-1", "$2a$10$viejo")).thenReturn(false);

        assertThatThrownBy(() -> userService.changePassword(7L, "no-es-esta-1", "clave-nueva-456"))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessage("La contraseña actual no es correcta");

        assertThat(user.getPassword()).isEqualTo("$2a$10$viejo");
        verify(passwordEncoder, never()).encode(any());
        verifyNoInteractions(refreshTokenService, deviceService);
    }

    @Test
    void givenTheSamePasswordAsTheNewOne_whenChangePassword_thenRejectedAndSessionsStayOpen() {
        User user = existingUser("conductor1", "$2a$10$viejo");
        when(userRepository.findById(7L)).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("clave-vieja-123", "$2a$10$viejo")).thenReturn(true);

        assertThatThrownBy(() -> userService.changePassword(7L, "clave-vieja-123", "clave-vieja-123"))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessage("La nueva contraseña debe ser distinta de la actual");

        assertThat(user.getPassword()).isEqualTo("$2a$10$viejo");
        verify(passwordEncoder, never()).encode(any());
        verifyNoInteractions(refreshTokenService, deviceService);
    }

    @Test
    void givenANewPasswordOver72Bytes_whenChangePassword_thenRejectedEvenWithFewCharacters() {
        // 37 caracteres, pero cada "ñ" ocupa 2 bytes en UTF-8: 74 bytes, más de lo que BCrypt admite
        String tooManyBytes = "ñ".repeat(37);
        User user = existingUser("conductor1", "$2a$10$viejo");
        when(userRepository.findById(7L)).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("clave-vieja-123", "$2a$10$viejo")).thenReturn(true);

        assertThatThrownBy(() -> userService.changePassword(7L, "clave-vieja-123", tooManyBytes))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("72 bytes");

        assertThat(user.getPassword()).isEqualTo("$2a$10$viejo");
        verify(passwordEncoder, never()).encode(any());
        verifyNoInteractions(refreshTokenService, deviceService);
    }

    @Test
    void givenACurrentPasswordOver72Bytes_whenChangePassword_thenItCannotBeTheStoredOneAndTheEncoderIsNotAsked() {
        String tooManyBytes = "ñ".repeat(37);
        when(userRepository.findById(7L)).thenReturn(Optional.of(existingUser("conductor1", "$2a$10$viejo")));

        assertThatThrownBy(() -> userService.changePassword(7L, tooManyBytes, "clave-nueva-456"))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessage("La contraseña actual no es correcta");

        verifyNoInteractions(passwordEncoder, refreshTokenService, deviceService);
    }

    @Test
    void givenAnUnknownUser_whenChangePassword_thenNotFound() {
        when(userRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userService.changePassword(99L, "clave-vieja-123", "clave-nueva-456"))
                .isInstanceOf(ResourceNotFoundException.class);

        verifyNoInteractions(passwordEncoder, refreshTokenService, deviceService);
    }

    private static User existingUser(String username, String passwordHash) {
        User user = new User();
        user.setId(7L);
        user.setUsername(username);
        user.setPassword(passwordHash);
        user.setRole(User.Role.DRIVER);
        return user;
    }

    @Test
    void givenAPasswordOver72Bytes_whenCreateDriver_thenRejectedBeforeTouchingAnything() {
        // 37 caracteres, pero cada "ñ" ocupa 2 bytes en UTF-8: 74 bytes, más de lo que BCrypt admite
        String tooManyBytes = "ñ".repeat(37);

        assertThatThrownBy(() -> userService.createDriver("conductor2", tooManyBytes))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("72 bytes");

        verifyNoInteractions(userRepository, passwordEncoder);
    }

    // ------------------------------------------------------------------ restablecimiento por un administrador

    @Test
    void givenADriver_whenResetDriverPassword_thenStoresTheNewHashRevokesHisSessionsAndDropsHisDevices() {
        User driver = existingUser("conductor1", "$2a$10$olvidada");
        when(userRepository.findById(7L)).thenReturn(Optional.of(driver));
        when(passwordEncoder.encode("clave-nueva-456")).thenReturn("$2a$10$nuevo");

        userService.resetDriverPassword(7L, "clave-nueva-456");

        assertThat(driver.getPassword()).isEqualTo("$2a$10$nuevo");
        verify(refreshTokenService).deleteByUser("conductor1");
        verify(deviceService).unregisterAllDevices(7L);
        // el administrador no necesita conocer la contraseña anterior
        verify(passwordEncoder, never()).matches(any(), any());
    }

    @Test
    void givenAnAdminAsTarget_whenResetDriverPassword_thenRejectedAndNothingChanges() {
        User admin = existingUser("admin", "$2a$10$del-admin");
        admin.setRole(User.Role.ADMIN);
        when(userRepository.findById(7L)).thenReturn(Optional.of(admin));

        assertThatThrownBy(() -> userService.resetDriverPassword(7L, "clave-nueva-456"))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessage("Solo se puede restablecer la contraseña de un conductor");

        assertThat(admin.getPassword()).isEqualTo("$2a$10$del-admin");
        verifyNoInteractions(passwordEncoder, refreshTokenService, deviceService);
    }

    @Test
    void givenAnUnknownUser_whenResetDriverPassword_thenNotFound() {
        when(userRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userService.resetDriverPassword(99L, "clave-nueva-456"))
                .isInstanceOf(ResourceNotFoundException.class);

        verifyNoInteractions(passwordEncoder, refreshTokenService, deviceService);
    }

    @Test
    void givenAPasswordOver72Bytes_whenResetDriverPassword_thenRejectedAndSessionsStayOpen() {
        String tooManyBytes = "ñ".repeat(37);
        User driver = existingUser("conductor1", "$2a$10$olvidada");
        when(userRepository.findById(7L)).thenReturn(Optional.of(driver));

        assertThatThrownBy(() -> userService.resetDriverPassword(7L, tooManyBytes))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("72 bytes");

        assertThat(driver.getPassword()).isEqualTo("$2a$10$olvidada");
        verifyNoInteractions(passwordEncoder, refreshTokenService, deviceService);
    }
}
