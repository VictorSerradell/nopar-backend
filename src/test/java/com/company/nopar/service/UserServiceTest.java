package com.company.nopar.service;

import com.company.nopar.entity.User;
import com.company.nopar.exception.DuplicateResourceException;
import com.company.nopar.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;

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
}
