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
    void givenTheCurrentPassword_whenChangePassword_thenStoresTheNewHashAndRevokesEverySession() {
        User user = existingUser("conductor1", "$2a$10$viejo");
        when(userRepository.findById(7L)).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("clave-vieja-123", "$2a$10$viejo")).thenReturn(true);
        when(passwordEncoder.encode("clave-nueva-456")).thenReturn("$2a$10$nuevo");

        User result = userService.changePassword(7L, "clave-vieja-123", "clave-nueva-456");

        assertThat(result).isSameAs(user);
        assertThat(user.getPassword()).isEqualTo("$2a$10$nuevo");
        verify(refreshTokenService).deleteByUser("conductor1");
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
        verifyNoInteractions(refreshTokenService);
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
        verifyNoInteractions(refreshTokenService);
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
        verifyNoInteractions(refreshTokenService);
    }

    @Test
    void givenACurrentPasswordOver72Bytes_whenChangePassword_thenItCannotBeTheStoredOneAndTheEncoderIsNotAsked() {
        String tooManyBytes = "ñ".repeat(37);
        when(userRepository.findById(7L)).thenReturn(Optional.of(existingUser("conductor1", "$2a$10$viejo")));

        assertThatThrownBy(() -> userService.changePassword(7L, tooManyBytes, "clave-nueva-456"))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessage("La contraseña actual no es correcta");

        verifyNoInteractions(passwordEncoder, refreshTokenService);
    }

    @Test
    void givenAnUnknownUser_whenChangePassword_thenNotFound() {
        when(userRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userService.changePassword(99L, "clave-vieja-123", "clave-nueva-456"))
                .isInstanceOf(ResourceNotFoundException.class);

        verifyNoInteractions(passwordEncoder, refreshTokenService);
    }

    private static User existingUser(String username, String passwordHash) {
        User user = new User();
        user.setId(7L);
        user.setUsername(username);
        user.setPassword(passwordHash);
        user.setRole(User.Role.DRIVER);
        return user;
    }
}
