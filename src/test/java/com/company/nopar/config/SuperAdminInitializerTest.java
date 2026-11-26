package com.company.nopar.config;

import com.company.nopar.entity.User;
import com.company.nopar.exception.InvalidRequestException;
import com.company.nopar.repository.UserRepository;
import com.company.nopar.service.UserService;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SuperAdminInitializerTest {

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

        verifyNoInteractions(userService);
    }

    @Test
    void givenExistingSuperAdmin_whenRun_thenNothingIsCreatedEvenWithConfiguration() {
        when(userRepository.existsBySuperAdminTrue()).thenReturn(true);

        initializer("jefe", PASSWORD).run(null);

        verifyNoInteractions(userService);
    }

    @Test
    void givenNoSuperAdmin_whenRun_thenCreatesIt() {
        initializer("jefe", PASSWORD).run(null);

        verify(userService).createSuperAdmin("jefe", PASSWORD);
    }

    @Test
    void givenAnExistingAdminWithThatName_whenRun_thenItIsPromotedWithoutTouchingHisPassword() {
        User admin = new User();
        admin.setUsername("admin");
        admin.setRole(User.Role.ADMIN);
        when(userRepository.findByUsername("admin")).thenReturn(Optional.of(admin));

        initializer("admin", PASSWORD).run(null);

        verify(userService).promoteToSuperAdmin("admin");
        verify(userService, never()).createSuperAdmin(any(), any());
    }

    @Test
    void givenANonAdminWithThatName_whenRun_thenFailsFastAskingForAnotherName() {
        User driver = new User();
        driver.setUsername("conductor1");
        driver.setRole(User.Role.DRIVER);
        when(userRepository.findByUsername("conductor1")).thenReturn(Optional.of(driver));

        assertThatThrownBy(() -> initializer("conductor1", PASSWORD).run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("elige otro SUPER_ADMIN_USERNAME");

        verifyNoInteractions(userService);
    }

    @Test
    void givenOnlyUsername_whenRun_thenFailsFast() {
        assertThatThrownBy(() -> initializer("jefe", "").run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SUPER_ADMIN_USERNAME y SUPER_ADMIN_PASSWORD");

        verifyNoInteractions(userService);
    }

    @Test
    void givenPartialConfigurationButASuperAdminExists_whenRun_thenIsIgnored() {
        when(userRepository.existsBySuperAdminTrue()).thenReturn(true);

        assertThatCode(() -> initializer("jefe", "").run(null)).doesNotThrowAnyException();

        verifyNoInteractions(userService);
    }

    @Test
    void givenWeakPassword_whenRun_thenFailsFastWithoutLeakingIt() {
        assertThatThrownBy(() -> initializer("jefe", "x-weak!").run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no válidos")
                .hasMessageNotContaining("x-weak!");

        verifyNoInteractions(userService);
    }

    @Test
    void givenAPasswordWithFewCharactersButOver72Bytes_whenRun_thenFailsFastWithoutLeakingIt() {
        String tooManyBytes = "ñ".repeat(37);
        when(userService.createSuperAdmin("jefe", tooManyBytes))
                .thenThrow(new InvalidRequestException("La contraseña no puede superar los 72 bytes en UTF-8"));

        assertThatThrownBy(() -> initializer("jefe", tooManyBytes).run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SUPER_ADMIN_PASSWORD no válida")
                .hasMessageContaining("72 bytes")
                .hasMessageNotContaining(tooManyBytes);
    }

    private SuperAdminInitializer initializer(String username, String password) {
        return new SuperAdminInitializer(userRepository, userService, validator, username, password);
    }
}
