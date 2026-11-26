package com.company.nopar.service;

import com.company.nopar.entity.AuditEvent;
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
import org.springframework.data.domain.Sort;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.same;
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

    @Mock
    private ShiftService shiftService;

    @Mock
    private AuditService auditService;

    @InjectMocks
    private UserService userService;

    @Test
    void whenListUsers_thenReturnsThemSortedByUsernameIgnoringCase() {
        User ana = new User();
        ana.setUsername("ana");
        User zeta = new User();
        zeta.setUsername("Zeta");
        when(userRepository.findAll(any(Sort.class))).thenReturn(List.of(ana, zeta));

        List<User> users = userService.listUsers();

        assertThat(users).containsExactly(ana, zeta);
        ArgumentCaptor<Sort> sort = ArgumentCaptor.forClass(Sort.class);
        verify(userRepository).findAll(sort.capture());
        assertThat(sort.getValue()).isEqualTo(Sort.by(Sort.Order.asc("username").ignoreCase()));
    }

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
        User user = existingManager("encargado1", "$2a$10$viejo");
        when(userRepository.findById(7L)).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("clave-vieja-123", "$2a$10$viejo")).thenReturn(true);
        when(passwordEncoder.encode("clave-nueva-456")).thenReturn("$2a$10$nuevo");

        User result = userService.changePassword(7L, "clave-vieja-123", "clave-nueva-456");

        assertThat(result).isSameAs(user);
        assertThat(user.getPassword()).isEqualTo("$2a$10$nuevo");
        verify(refreshTokenService).deleteByUser("encargado1");
        verify(deviceService).unregisterAllDevices(7L);
    }

    @Test
    void givenAWrongCurrentPassword_whenChangePassword_thenRejectedAndNothingChanges() {
        User user = existingManager("encargado1", "$2a$10$viejo");
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
        User user = existingManager("encargado1", "$2a$10$viejo");
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
        User user = existingManager("encargado1", "$2a$10$viejo");
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
        when(userRepository.findById(7L)).thenReturn(Optional.of(existingManager("encargado1", "$2a$10$viejo")));

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

    @Test
    void givenADriver_whenChangePassword_thenDeniedAndNothingIsTouched() {
        User driver = existingUser("conductor1", "$2a$10$viejo");
        when(userRepository.findById(7L)).thenReturn(Optional.of(driver));

        assertThatThrownBy(() -> userService.changePassword(7L, "clave-vieja-123", "clave-nueva-456"))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("conductores");

        assertThat(driver.getPassword()).isEqualTo("$2a$10$viejo");
        verifyNoInteractions(passwordEncoder, refreshTokenService, deviceService);
    }

    @Test
    void givenAnAdmin_whenChangePassword_thenItIsAllowed() {
        User admin = existingUser("admin", "$2a$10$viejo");
        admin.setRole(User.Role.ADMIN);
        when(userRepository.findById(7L)).thenReturn(Optional.of(admin));
        when(passwordEncoder.matches("clave-vieja-123", "$2a$10$viejo")).thenReturn(true);
        when(passwordEncoder.encode("clave-nueva-456")).thenReturn("$2a$10$nuevo");

        userService.changePassword(7L, "clave-vieja-123", "clave-nueva-456");

        assertThat(admin.getPassword()).isEqualTo("$2a$10$nuevo");
    }

    // ------------------------------------------------------------------ super administrador y desactivación

    @Test
    void whenCreateSuperAdmin_thenSavesAnAdminMarkedAsSuperAdmin() {
        when(passwordEncoder.encode("password-segura-123")).thenReturn("$2a$10$hash");
        when(userRepository.saveAndFlush(any(User.class))).thenAnswer(call -> call.getArgument(0));

        User created = userService.createSuperAdmin("jefe", "password-segura-123");

        assertThat(created.getRole()).isEqualTo(User.Role.ADMIN);
        assertThat(created.isSuperAdmin()).isTrue();
        assertThat(created.isEnabled()).isTrue();
    }

    @Test
    void whenCreateAnOrdinaryUser_thenItIsActiveAndNotSuperAdmin() {
        when(passwordEncoder.encode("password-segura-123")).thenReturn("$2a$10$hash");
        when(userRepository.saveAndFlush(any(User.class))).thenAnswer(call -> call.getArgument(0));

        User created = userService.createUser("admin2", "password-segura-123", User.Role.ADMIN);

        assertThat(created.isSuperAdmin()).isFalse();
        assertThat(created.isEnabled()).isTrue();
    }

    @Test
    void givenAnAdmin_whenPromoteToSuperAdmin_thenMarkedAndActiveWithoutTouchingThePassword() {
        User admin = existingUser("admin", "$2a$10$intacta");
        admin.setRole(User.Role.ADMIN);
        admin.setEnabled(false);
        when(userRepository.findByUsername("admin")).thenReturn(Optional.of(admin));

        userService.promoteToSuperAdmin("admin");

        assertThat(admin.isSuperAdmin()).isTrue();
        assertThat(admin.isEnabled()).isTrue();
        assertThat(admin.getPassword()).isEqualTo("$2a$10$intacta");
        verifyNoInteractions(passwordEncoder);
    }

    @Test
    void givenANonAdmin_whenPromoteToSuperAdmin_thenRejected() {
        User driver = existingUser("conductor1", "$2a$10$hash");
        when(userRepository.findByUsername("conductor1")).thenReturn(Optional.of(driver));

        assertThatThrownBy(() -> userService.promoteToSuperAdmin("conductor1"))
                .isInstanceOf(InvalidRequestException.class);

        assertThat(driver.isSuperAdmin()).isFalse();
    }

    @Test
    void givenADriver_whenASuperAdminDeactivatesHim_thenSessionsDevicesAndUpcomingShiftsAreRemovedButNotTheHistory() {
        User driver = existingUser("conductor1", "$2a$10$hash");
        when(userRepository.findById(7L)).thenReturn(Optional.of(driver));

        userService.deactivateUser(7L, superAdmin(1L));

        assertThat(driver.isEnabled()).isFalse();
        verify(refreshTokenService).deleteByUser("conductor1");
        verify(deviceService).unregisterAllDevices(7L);
        verify(shiftService).deleteShiftsOfDriverFrom(eq(7L), any(LocalDate.class));
    }

    @Test
    void givenAManager_whenASuperAdminDeactivatesHim_thenHeHasNoShiftsToRemove() {
        User manager = existingManager("encargado1", "$2a$10$hash");
        when(userRepository.findById(7L)).thenReturn(Optional.of(manager));

        userService.deactivateUser(7L, superAdmin(1L));

        assertThat(manager.isEnabled()).isFalse();
        verify(refreshTokenService).deleteByUser("encargado1");
        verify(deviceService).unregisterAllDevices(7L);
        verifyNoInteractions(shiftService);
    }

    @Test
    void givenAnAdminThatIsNotSuperAdmin_whenDeactivate_thenDeniedAndNothingIsTouched() {
        User plainAdmin = existingUser("admin2", "$2a$10$hash");
        plainAdmin.setId(2L);
        plainAdmin.setRole(User.Role.ADMIN);

        assertThatThrownBy(() -> userService.deactivateUser(7L, plainAdmin))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> userService.deactivateUser(7L, null))
                .isInstanceOf(AccessDeniedException.class);

        verifyNoInteractions(userRepository, refreshTokenService, deviceService, shiftService);
    }

    @Test
    void givenTheSuperAdminHimself_whenDeactivate_thenRejected() {
        User me = superAdmin(7L);
        when(userRepository.findById(7L)).thenReturn(Optional.of(me));

        assertThatThrownBy(() -> userService.deactivateUser(7L, me))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("propia cuenta");

        assertThat(me.isEnabled()).isTrue();
        verifyNoInteractions(refreshTokenService, deviceService, shiftService);
    }

    @Test
    void givenAnotherSuperAdmin_whenDeactivate_thenRejected() {
        User other = superAdmin(7L);
        when(userRepository.findById(7L)).thenReturn(Optional.of(other));

        assertThatThrownBy(() -> userService.deactivateUser(7L, superAdmin(1L)))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("super administrador");

        assertThat(other.isEnabled()).isTrue();
        verifyNoInteractions(refreshTokenService, deviceService, shiftService);
    }

    @Test
    void givenAnUnknownUser_whenDeactivate_thenNotFound() {
        when(userRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userService.deactivateUser(99L, superAdmin(1L)))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void givenAnAlreadyDeactivatedUser_whenDeactivateAgain_thenNothingHappens() {
        User driver = existingUser("conductor1", "$2a$10$hash");
        driver.setEnabled(false);
        when(userRepository.findById(7L)).thenReturn(Optional.of(driver));

        userService.deactivateUser(7L, superAdmin(1L));

        verifyNoInteractions(refreshTokenService, deviceService, shiftService);
    }

    @Test
    void givenADeactivatedUser_whenASuperAdminActivatesHim_thenHeIsActiveAgain() {
        User driver = existingUser("conductor1", "$2a$10$hash");
        driver.setEnabled(false);
        when(userRepository.findById(7L)).thenReturn(Optional.of(driver));

        userService.activateUser(7L, superAdmin(1L));

        assertThat(driver.isEnabled()).isTrue();
    }

    @Test
    void givenAnAdminThatIsNotSuperAdmin_whenActivate_thenDenied() {
        User plainAdmin = existingUser("admin2", "$2a$10$hash");
        plainAdmin.setRole(User.Role.ADMIN);

        assertThatThrownBy(() -> userService.activateUser(7L, plainAdmin))
                .isInstanceOf(AccessDeniedException.class);

        verifyNoInteractions(userRepository);
    }

    // ------------------------------------------------------------------ registro de auditoría

    @Test
    void whenCreateADriver_thenTheCreationIsAudited() {
        when(passwordEncoder.encode("password-segura-123")).thenReturn("$2a$10$hash");
        when(userRepository.saveAndFlush(any(User.class))).thenAnswer(call -> call.getArgument(0));

        User created = userService.createDriver("conductor2", "password-segura-123");

        verify(auditService).record(AuditEvent.Action.USER_CREATED, created, "rol DRIVER");
    }

    @Test
    void whenCreateASuperAdmin_thenTheAuditSaysSo() {
        when(passwordEncoder.encode("password-segura-123")).thenReturn("$2a$10$hash");
        when(userRepository.saveAndFlush(any(User.class))).thenAnswer(call -> call.getArgument(0));

        User created = userService.createSuperAdmin("jefe", "password-segura-123");

        verify(auditService).record(AuditEvent.Action.USER_CREATED, created, "rol ADMIN (super administrador)");
    }

    @Test
    void givenAnExistingUsername_whenCreateDriver_thenNothingIsAudited() {
        when(userRepository.existsByUsername("conductor1")).thenReturn(true);

        assertThatThrownBy(() -> userService.createDriver("conductor1", "password-segura-123"))
                .isInstanceOf(DuplicateResourceException.class);

        verifyNoInteractions(auditService);
    }

    @Test
    void givenUsernameTakenByAConcurrentRequest_whenCreateDriver_thenNothingIsAudited() {
        when(userRepository.saveAndFlush(any(User.class)))
                .thenThrow(new DataIntegrityViolationException("unique constraint"));

        assertThatThrownBy(() -> userService.createDriver("conductor2", "password-segura-123"))
                .isInstanceOf(DuplicateResourceException.class);

        verifyNoInteractions(auditService);
    }

    @Test
    void whenASuperAdminDeactivatesAUser_thenItIsAuditedWithHimAsTheActor() {
        User driver = existingUser("conductor1", "$2a$10$hash");
        User boss = superAdmin(1L);
        when(userRepository.findById(7L)).thenReturn(Optional.of(driver));

        userService.deactivateUser(7L, boss);

        verify(auditService).record(AuditEvent.Action.USER_DEACTIVATED, boss, driver, "rol DRIVER");
    }

    @Test
    void givenAnAlreadyDeactivatedUser_whenDeactivateAgain_thenItIsNotAuditedAgain() {
        User driver = existingUser("conductor1", "$2a$10$hash");
        driver.setEnabled(false);
        when(userRepository.findById(7L)).thenReturn(Optional.of(driver));

        userService.deactivateUser(7L, superAdmin(1L));

        verifyNoInteractions(auditService);
    }

    @Test
    void givenADeniedDeactivation_thenNothingIsAudited() {
        User plainAdmin = existingUser("admin2", "$2a$10$hash");
        plainAdmin.setRole(User.Role.ADMIN);

        assertThatThrownBy(() -> userService.deactivateUser(7L, plainAdmin)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> userService.activateUser(7L, plainAdmin)).isInstanceOf(AccessDeniedException.class);

        verifyNoInteractions(auditService);
    }

    @Test
    void whenASuperAdminActivatesADeactivatedUser_thenItIsAudited() {
        User driver = existingUser("conductor1", "$2a$10$hash");
        driver.setEnabled(false);
        User boss = superAdmin(1L);
        when(userRepository.findById(7L)).thenReturn(Optional.of(driver));

        userService.activateUser(7L, boss);

        verify(auditService).record(AuditEvent.Action.USER_ACTIVATED, boss, driver, "rol DRIVER");
    }

    @Test
    void givenAnActiveUser_whenActivate_thenNothingIsAudited() {
        User driver = existingUser("conductor1", "$2a$10$hash");
        when(userRepository.findById(7L)).thenReturn(Optional.of(driver));

        userService.activateUser(7L, superAdmin(1L));

        verifyNoInteractions(auditService);
    }

    @Test
    void whenAUserChangesHisPassword_thenItIsAuditedWithHimAsTheActor() {
        User user = existingManager("encargado1", "$2a$10$viejo");
        when(userRepository.findById(7L)).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("clave-vieja-123", "$2a$10$viejo")).thenReturn(true);
        when(passwordEncoder.encode("clave-nueva-456")).thenReturn("$2a$10$nuevo");

        userService.changePassword(7L, "clave-vieja-123", "clave-nueva-456");

        verify(auditService).record(eq(AuditEvent.Action.PASSWORD_CHANGED), same(user), same(user), isNull());
    }

    @Test
    void givenAWrongCurrentPassword_whenChangePassword_thenNothingIsAudited() {
        User user = existingManager("encargado1", "$2a$10$viejo");
        when(userRepository.findById(7L)).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("equivocada-123", "$2a$10$viejo")).thenReturn(false);

        assertThatThrownBy(() -> userService.changePassword(7L, "equivocada-123", "clave-nueva-456"))
                .isInstanceOf(InvalidRequestException.class);

        verifyNoInteractions(auditService);
    }

    @Test
    void whenAnAdminResetsAPassword_thenItIsAudited() {
        User driver = existingUser("conductor1", "$2a$10$olvidada");
        when(userRepository.findById(7L)).thenReturn(Optional.of(driver));
        when(passwordEncoder.encode("clave-nueva-456")).thenReturn("$2a$10$nuevo");

        userService.resetPassword(7L, "clave-nueva-456");

        verify(auditService).record(AuditEvent.Action.PASSWORD_RESET, driver, "rol DRIVER");
    }

    @Test
    void givenAnAdminAsTarget_whenResetPassword_thenNothingIsAudited() {
        User admin = existingUser("admin", "$2a$10$hash");
        admin.setRole(User.Role.ADMIN);
        when(userRepository.findById(7L)).thenReturn(Optional.of(admin));

        assertThatThrownBy(() -> userService.resetPassword(7L, "clave-nueva-456"))
                .isInstanceOf(InvalidRequestException.class);

        verifyNoInteractions(auditService);
    }

    @Test
    void givenAnAdminThatWasDisabled_whenPromoteToSuperAdmin_thenBothChangesAreAudited() {
        User admin = existingUser("admin", "$2a$10$intacta");
        admin.setRole(User.Role.ADMIN);
        admin.setEnabled(false);
        when(userRepository.findByUsername("admin")).thenReturn(Optional.of(admin));

        userService.promoteToSuperAdmin("admin");

        verify(auditService).record(AuditEvent.Action.SUPER_ADMIN_GRANTED, admin, null);
        verify(auditService).record(AuditEvent.Action.USER_ACTIVATED, admin, "rol ADMIN");
    }

    @Test
    void givenAnActiveSuperAdmin_whenPromoteAgain_thenNothingIsAudited() {
        User boss = superAdmin(1L);
        when(userRepository.findByUsername("jefe")).thenReturn(Optional.of(boss));

        userService.promoteToSuperAdmin("jefe");

        verifyNoInteractions(auditService);
    }

    private static User superAdmin(Long id) {
        User user = existingUser("jefe", "$2a$10$hash");
        user.setId(id);
        user.setRole(User.Role.ADMIN);
        user.setSuperAdmin(true);
        return user;
    }

    private static User existingManager(String username, String passwordHash) {
        User user = existingUser(username, passwordHash);
        user.setRole(User.Role.MANAGER);
        return user;
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
    void givenADriver_whenResetPassword_thenStoresTheNewHashRevokesHisSessionsAndDropsHisDevices() {
        User driver = existingUser("conductor1", "$2a$10$olvidada");
        when(userRepository.findById(7L)).thenReturn(Optional.of(driver));
        when(passwordEncoder.encode("clave-nueva-456")).thenReturn("$2a$10$nuevo");

        userService.resetPassword(7L, "clave-nueva-456");

        assertThat(driver.getPassword()).isEqualTo("$2a$10$nuevo");
        verify(refreshTokenService).deleteByUser("conductor1");
        verify(deviceService).unregisterAllDevices(7L);
        // el administrador no necesita conocer la contraseña anterior
        verify(passwordEncoder, never()).matches(any(), any());
    }

    @Test
    void givenAManager_whenResetPassword_thenStoresTheNewHashAndRevokesHisSessionsToo() {
        User manager = existingUser("encargado1", "$2a$10$olvidada");
        manager.setRole(User.Role.MANAGER);
        when(userRepository.findById(7L)).thenReturn(Optional.of(manager));
        when(passwordEncoder.encode("clave-nueva-456")).thenReturn("$2a$10$nuevo");

        userService.resetPassword(7L, "clave-nueva-456");

        assertThat(manager.getPassword()).isEqualTo("$2a$10$nuevo");
        verify(refreshTokenService).deleteByUser("encargado1");
        verify(deviceService).unregisterAllDevices(7L);
    }

    @Test
    void givenAnAdminAsTarget_whenResetPassword_thenRejectedAndNothingChanges() {
        User admin = existingUser("admin", "$2a$10$del-admin");
        admin.setRole(User.Role.ADMIN);
        when(userRepository.findById(7L)).thenReturn(Optional.of(admin));

        assertThatThrownBy(() -> userService.resetPassword(7L, "clave-nueva-456"))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessage("Solo se puede restablecer la contraseña de un conductor o de un encargado");

        assertThat(admin.getPassword()).isEqualTo("$2a$10$del-admin");
        verifyNoInteractions(passwordEncoder, refreshTokenService, deviceService);
    }

    @Test
    void givenAnUnknownUser_whenResetPassword_thenNotFound() {
        when(userRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userService.resetPassword(99L, "clave-nueva-456"))
                .isInstanceOf(ResourceNotFoundException.class);

        verifyNoInteractions(passwordEncoder, refreshTokenService, deviceService);
    }

    @Test
    void givenAPasswordOver72Bytes_whenResetPassword_thenRejectedAndSessionsStayOpen() {
        String tooManyBytes = "ñ".repeat(37);
        User driver = existingUser("conductor1", "$2a$10$olvidada");
        when(userRepository.findById(7L)).thenReturn(Optional.of(driver));

        assertThatThrownBy(() -> userService.resetPassword(7L, tooManyBytes))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("72 bytes");

        assertThat(driver.getPassword()).isEqualTo("$2a$10$olvidada");
        verifyNoInteractions(passwordEncoder, refreshTokenService, deviceService);
    }
}
