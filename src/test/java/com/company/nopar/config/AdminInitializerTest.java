package com.company.nopar.config;

import com.company.nopar.entity.User;
import com.company.nopar.exception.DuplicateResourceException;
import com.company.nopar.exception.InvalidRequestException;
import com.company.nopar.repository.UserRepository;
import com.company.nopar.service.UserService;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminInitializerTest {

    private static final String PASSWORD = "password-segura-123";

    @Mock
    private UserRepository userRepository;

    @Mock
    private UserService userService;

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void givenNoConfiguration_whenRun_thenDoesNothing() {
        initializer("", "").run(null);
        initializer("  ", "  ").run(null);

        verifyNoInteractions(userRepository, userService);
    }

    @Test
    void givenExistingAdmin_whenRun_thenNothingIsCreated() {
        when(userRepository.existsByRole(User.Role.ADMIN)).thenReturn(true);

        initializer("admin", PASSWORD).run(null);

        verifyNoInteractions(userService);
    }

    @Test
    void givenNoAdmin_whenRun_thenCreatesTheInitialAdmin() {
        initializer("admin", PASSWORD).run(null);

        verify(userService).createUser("admin", PASSWORD, User.Role.ADMIN);
    }

    @Test
    void givenOnlyUsername_whenRun_thenFailsFast() {
        assertThatThrownBy(() -> initializer("admin", "").run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ADMIN_USERNAME y ADMIN_PASSWORD");

        verifyNoInteractions(userService);
    }

    @Test
    void givenPartialConfigurationButAdminExists_whenRun_thenIsIgnored() {
        when(userRepository.existsByRole(User.Role.ADMIN)).thenReturn(true);

        assertThatCode(() -> initializer("admin", "").run(null)).doesNotThrowAnyException();

        verifyNoInteractions(userService);
    }

    @Test
    void givenWeakPassword_whenRun_thenFailsFastWithoutLeakingIt() {
        assertThatThrownBy(() -> initializer("admin", "x-weak!").run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no válidos")
                .hasMessageNotContaining("x-weak!");

        verify(userService, never()).createUser(any(), any(), any());
    }

    @Test
    void givenUsernameAlreadyUsedByANonAdmin_whenRun_thenFailsFastAskingForAnotherName() {
        when(userService.createUser("conductor1", PASSWORD, User.Role.ADMIN))
                .thenThrow(new DuplicateResourceException("Ya existe un usuario con el nombre: conductor1"));

        assertThatThrownBy(() -> initializer("conductor1", PASSWORD).run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("elige otro ADMIN_USERNAME");
    }

    @Test
    void givenAPasswordWithFewCharactersButOver72Bytes_whenRun_thenFailsFastWithoutLeakingIt() {
        // Pasa la validación de longitud (37 caracteres), pero cada "ñ" ocupa 2 bytes: 74 en total
        String tooManyBytes = "ñ".repeat(37);
        when(userService.createUser("admin", tooManyBytes, User.Role.ADMIN))
                .thenThrow(new InvalidRequestException("La contraseña no puede superar los 72 bytes en UTF-8"));

        assertThatThrownBy(() -> initializer("admin", tooManyBytes).run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ADMIN_PASSWORD no válida")
                .hasMessageContaining("72 bytes")
                .hasMessageNotContaining(tooManyBytes);
    }

    private AdminInitializer initializer(String username, String password) {
        return new AdminInitializer(userRepository, userService, validator, username, password);
    }
}
