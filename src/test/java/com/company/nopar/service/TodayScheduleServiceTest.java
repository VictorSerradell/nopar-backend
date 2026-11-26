package com.company.nopar.service;

import com.company.nopar.dto.ShiftCreateRequest;
import com.company.nopar.dto.ShiftUpdateRequest;
import com.company.nopar.entity.ShiftAssignment;
import com.company.nopar.entity.User;
import com.company.nopar.entity.Vehicle;
import com.company.nopar.entity.Wave;
import com.company.nopar.exception.InvalidStateException;
import com.company.nopar.exception.ResourceNotFoundException;
import com.company.nopar.repository.ShiftAssignmentRepository;
import com.company.nopar.repository.UserRepository;
import com.company.nopar.repository.VehicleRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Sort;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TodayScheduleServiceTest {

    @Mock
    private ShiftService shiftService;

    @Mock
    private ShiftAssignmentRepository shiftRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private VehicleRepository vehicleRepository;

    @InjectMocks
    private TodayScheduleService service;

    // ---------------------------------------------------------------- la planificación de hoy

    @Test
    void whenGetToday_thenFreeDriversAndVansExcludeWhoAlreadyHasAShiftAndVansThatCannotBeAssigned() {
        LocalDate today = LocalDate.now();
        Wave wave = wave(1L, "Ola 1");
        User ana = driver(2L, "ana");
        Vehicle van1 = van(11L, "1111AAA", Vehicle.VehicleStatus.AVAILABLE);
        when(shiftService.getWaves()).thenReturn(List.of(wave));
        when(shiftRepository.findInRange(today, today)).thenReturn(List.of(shift(wave, ana, van1)));
        when(userRepository.findByRole(any(User.Role.class), any(Sort.class)))
                .thenReturn(List.of(ana, driver(3L, "bea"), driver(4L, "cris")));
        when(vehicleRepository.findAll(any(Sort.class))).thenReturn(List.of(
                van1,
                van(12L, "2222BBB", Vehicle.VehicleStatus.AVAILABLE),
                van(13L, "3333CCC", Vehicle.VehicleStatus.MAINTENANCE),
                van(14L, "4444DDD", Vehicle.VehicleStatus.DAMAGED),
                // en uso no impide asignarla: así lo permite la regla del administrador
                van(15L, "5555EEE", Vehicle.VehicleStatus.IN_USE)));

        TodayScheduleService.TodaySchedule schedule = service.getToday();

        assertThat(schedule.date()).isEqualTo(today);
        assertThat(schedule.waves()).containsExactly(wave);
        assertThat(schedule.shifts()).hasSize(1);
        assertThat(schedule.freeDrivers()).extracting(User::getUsername).containsExactly("bea", "cris");
        assertThat(schedule.freeVehicles()).extracting(Vehicle::getLicensePlate).containsExactly("2222BBB", "5555EEE");
    }

    @Test
    void whenGetToday_thenDeactivatedDriversAreNotOffered() {
        User deactivated = driver(3L, "bea");
        deactivated.setEnabled(false);
        when(shiftService.getWaves()).thenReturn(List.of());
        when(shiftRepository.findInRange(any(LocalDate.class), any(LocalDate.class))).thenReturn(List.of());
        when(userRepository.findByRole(any(User.Role.class), any(Sort.class)))
                .thenReturn(List.of(driver(2L, "ana"), deactivated));
        when(vehicleRepository.findAll(any(Sort.class))).thenReturn(List.of());

        assertThat(service.getToday().freeDrivers()).extracting(User::getUsername).containsExactly("ana");
    }

    @Test
    void whenGetToday_thenDriversAreAskedForByRoleSortedByNameIgnoringCase() {
        when(shiftService.getWaves()).thenReturn(List.of());
        when(shiftRepository.findInRange(any(LocalDate.class), any(LocalDate.class))).thenReturn(List.of());
        when(userRepository.findByRole(User.Role.DRIVER, Sort.by(Sort.Order.asc("username").ignoreCase())))
                .thenReturn(List.of());
        when(vehicleRepository.findAll(Sort.by("licensePlate"))).thenReturn(List.of());

        assertThat(service.getToday().freeDrivers()).isEmpty();
    }

    // ---------------------------------------------------------------- añadir, mover y quitar

    @Test
    void whenAddShift_thenItIsAShiftForTodayAndTheDriverIsNotified() {
        User manager = manager();
        ShiftAssignment created = new ShiftAssignment();
        when(shiftService.createShift(any(ShiftCreateRequest.class), any(User.class), eq(true)))
                .thenReturn(created);

        ShiftAssignment result = service.addShift(1L, 2L, 3L, manager);

        assertThat(result).isSameAs(created);
        verify(shiftService).createShift(new ShiftCreateRequest(LocalDate.now(), 1L, 2L, 3L), manager, true);
    }

    @Test
    void givenATodayShift_whenMoveShift_thenOnlyTheWaveAndTheVanChange() {
        when(shiftRepository.existsById(9L)).thenReturn(true);
        when(shiftRepository.existsByIdAndWorkDate(9L, LocalDate.now())).thenReturn(true);

        service.moveShift(9L, 2L, null);

        verify(shiftService).updateShift(9L, new ShiftUpdateRequest(null, 2L, null, null), true);
    }

    @Test
    void givenAShiftOfAnotherDay_whenMoveShift_thenConflictAndNothingChanges() {
        when(shiftRepository.existsById(9L)).thenReturn(true);
        when(shiftRepository.existsByIdAndWorkDate(9L, LocalDate.now())).thenReturn(false);

        assertThatThrownBy(() -> service.moveShift(9L, 2L, null))
                .isInstanceOf(InvalidStateException.class)
                .hasMessageContaining("de hoy");

        verify(shiftService, never()).updateShift(any(), any(), anyBoolean());
    }

    @Test
    void givenAnUnknownShift_whenMoveShift_thenNotFound() {
        when(shiftRepository.existsById(9L)).thenReturn(false);

        assertThatThrownBy(() -> service.moveShift(9L, 2L, null)).isInstanceOf(ResourceNotFoundException.class);

        verify(shiftService, never()).updateShift(any(), any(), anyBoolean());
    }

    @Test
    void givenATodayShift_whenRemoveShift_thenItIsDeletedAndTheDriverIsNotified() {
        when(shiftRepository.existsById(9L)).thenReturn(true);
        when(shiftRepository.existsByIdAndWorkDate(9L, LocalDate.now())).thenReturn(true);

        service.removeShift(9L);

        verify(shiftService).deleteShift(9L, true);
    }

    @Test
    void givenAShiftOfAnotherDay_whenRemoveShift_thenConflictAndItIsKept() {
        when(shiftRepository.existsById(9L)).thenReturn(true);
        when(shiftRepository.existsByIdAndWorkDate(9L, LocalDate.now())).thenReturn(false);

        assertThatThrownBy(() -> service.removeShift(9L)).isInstanceOf(InvalidStateException.class);

        verify(shiftService, never()).deleteShift(any(), anyBoolean());
    }

    @Test
    void givenAnUnknownShift_whenRemoveShift_thenNotFound() {
        when(shiftRepository.existsById(9L)).thenReturn(false);

        assertThatThrownBy(() -> service.removeShift(9L)).isInstanceOf(ResourceNotFoundException.class);

        verify(shiftService, never()).deleteShift(any(), anyBoolean());
    }

    // ---------------------------------------------------------------- datos de prueba

    private static User manager() {
        User user = new User();
        user.setId(5L);
        user.setUsername("encargado1");
        user.setRole(User.Role.MANAGER);
        return user;
    }

    private static User driver(Long id, String username) {
        User user = new User();
        user.setId(id);
        user.setUsername(username);
        user.setRole(User.Role.DRIVER);
        return user;
    }

    private static Wave wave(Long id, String name) {
        Wave wave = new Wave();
        wave.setId(id);
        wave.setName(name);
        wave.setStartTime(LocalTime.of(9, 30));
        return wave;
    }

    private static Vehicle van(Long id, String plate, Vehicle.VehicleStatus status) {
        Vehicle van = new Vehicle();
        van.setId(id);
        van.setLicensePlate(plate);
        van.setStatus(status);
        return van;
    }

    private static ShiftAssignment shift(Wave wave, User driver, Vehicle van) {
        ShiftAssignment shift = new ShiftAssignment();
        shift.setWave(wave);
        shift.setDriver(driver);
        shift.setVehicle(van);
        shift.setWorkDate(LocalDate.now());
        return shift;
    }
}
