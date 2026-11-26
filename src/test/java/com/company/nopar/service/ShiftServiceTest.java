package com.company.nopar.service;

import com.company.nopar.dto.ShiftCreateRequest;
import com.company.nopar.dto.ShiftUpdateRequest;
import com.company.nopar.dto.WaveRequest;
import com.company.nopar.entity.ShiftAssignment;
import com.company.nopar.entity.User;
import com.company.nopar.entity.Vehicle;
import com.company.nopar.entity.Wave;
import com.company.nopar.exception.DuplicateResourceException;
import com.company.nopar.exception.InvalidRequestException;
import com.company.nopar.exception.InvalidStateException;
import com.company.nopar.exception.ResourceInUseException;
import com.company.nopar.exception.ResourceNotFoundException;
import com.company.nopar.repository.ShiftAssignmentRepository;
import com.company.nopar.repository.UserRepository;
import com.company.nopar.repository.VehicleRepository;
import com.company.nopar.repository.WaveRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ShiftServiceTest {

    private static final LocalDate DAY = LocalDate.of(2026, 11, 19);

    @Mock
    private WaveRepository waveRepository;

    @Mock
    private ShiftAssignmentRepository shiftRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private VehicleRepository vehicleRepository;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @InjectMocks
    private ShiftService shiftService;

    // ---------------------------------------------------------------- olas

    @Test
    void givenNewName_whenCreateWave_thenSavesItWithoutSurroundingSpaces() {
        when(waveRepository.saveAndFlush(any(Wave.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Wave saved = shiftService.createWave(new WaveRequest("  Ola 1 ", LocalTime.of(9, 30)));

        assertThat(saved.getName()).isEqualTo("Ola 1");
        assertThat(saved.getStartTime()).isEqualTo(LocalTime.of(9, 30));
        verify(waveRepository).existsByNameIgnoreCase("Ola 1");
    }

    @Test
    void givenExistingName_whenCreateWave_thenDuplicateAndNothingSaved() {
        when(waveRepository.existsByNameIgnoreCase("Ola 1")).thenReturn(true);

        assertThatThrownBy(() -> shiftService.createWave(new WaveRequest("Ola 1", LocalTime.of(9, 30))))
                .isInstanceOf(DuplicateResourceException.class)
                .hasMessageContaining("Ola 1");

        verify(waveRepository, never()).saveAndFlush(any());
    }

    @Test
    void givenTwoSimultaneousRequests_whenTheDatabaseRejectsTheSecond_thenDuplicate() {
        when(waveRepository.saveAndFlush(any(Wave.class))).thenThrow(new DataIntegrityViolationException("uk_waves_name"));

        assertThatThrownBy(() -> shiftService.createWave(new WaveRequest("Ola 1", LocalTime.of(9, 30))))
                .isInstanceOf(DuplicateResourceException.class);
    }

    @Test
    void whenGetWaves_thenTheRepositoryOrderIsKept() {
        Wave early = wave(1L, "Ola 1", LocalTime.of(9, 30));
        Wave late = wave(2L, "Ola 2", LocalTime.of(11, 0));
        when(waveRepository.findAllByOrderByStartTimeAscNameAsc()).thenReturn(List.of(early, late));

        assertThat(shiftService.getWaves()).containsExactly(early, late);
    }

    @Test
    void givenUnknownWave_whenDeleteWave_thenNotFound() {
        when(waveRepository.existsById(9L)).thenReturn(false);

        assertThatThrownBy(() -> shiftService.deleteWave(9L)).isInstanceOf(ResourceNotFoundException.class);

        verify(waveRepository, never()).deleteById(any());
    }

    @Test
    void givenWaveWithShifts_whenDeleteWave_thenInUseAndNothingDeleted() {
        when(waveRepository.existsById(1L)).thenReturn(true);
        when(shiftRepository.existsByWaveId(1L)).thenReturn(true);

        assertThatThrownBy(() -> shiftService.deleteWave(1L))
                .isInstanceOf(ResourceInUseException.class)
                .hasMessageContaining("turnos");

        verify(waveRepository, never()).deleteById(any());
    }

    @Test
    void givenWaveWithoutShifts_whenDeleteWave_thenDeleted() {
        when(waveRepository.existsById(1L)).thenReturn(true);
        when(shiftRepository.existsByWaveId(1L)).thenReturn(false);

        shiftService.deleteWave(1L);

        verify(waveRepository).deleteById(1L);
    }

    // ---------------------------------------------------------------- alta de turnos

    @Test
    void givenValidRequest_whenCreateShift_thenSavesItWithTheActorAsCreator() {
        User admin = user(1L, "admin1", User.Role.ADMIN);
        Wave wave = wave(1L, "Ola 1", LocalTime.of(9, 30));
        User driver = user(2L, "driver1", User.Role.DRIVER);
        Vehicle van = van(3L, "1111AAA");
        when(waveRepository.findById(1L)).thenReturn(Optional.of(wave));
        when(userRepository.findById(2L)).thenReturn(Optional.of(driver));
        when(vehicleRepository.findById(3L)).thenReturn(Optional.of(van));
        when(shiftRepository.saveAndFlush(any(ShiftAssignment.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ShiftAssignment shift = shiftService.createShift(new ShiftCreateRequest(DAY, 1L, 2L, 3L), admin);

        assertThat(shift.getWorkDate()).isEqualTo(DAY);
        assertThat(shift.getWave()).isSameAs(wave);
        assertThat(shift.getDriver()).isSameAs(driver);
        assertThat(shift.getVehicle()).isSameAs(van);
        assertThat(shift.getCreatedBy()).isSameAs(admin);
        verify(shiftRepository).existsByWorkDateAndDriverId(DAY, 2L);
        verify(shiftRepository).existsByWorkDateAndVehicleId(DAY, 3L);
    }

    @Test
    void givenUnknownWave_whenCreateShift_thenNotFoundAndNothingSaved() {
        when(waveRepository.findById(1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> shiftService.createShift(new ShiftCreateRequest(DAY, 1L, 2L, 3L), null))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Ola");

        verify(shiftRepository, never()).saveAndFlush(any());
    }

    @Test
    void givenUnknownDriver_whenCreateShift_thenNotFoundAndNothingSaved() {
        when(waveRepository.findById(1L)).thenReturn(Optional.of(wave(1L, "Ola 1", LocalTime.of(9, 30))));
        when(userRepository.findById(2L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> shiftService.createShift(new ShiftCreateRequest(DAY, 1L, 2L, 3L), null))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Usuario");

        verify(shiftRepository, never()).saveAndFlush(any());
    }

    @Test
    void givenUnknownVehicle_whenCreateShift_thenNotFoundAndNothingSaved() {
        when(waveRepository.findById(1L)).thenReturn(Optional.of(wave(1L, "Ola 1", LocalTime.of(9, 30))));
        when(userRepository.findById(2L)).thenReturn(Optional.of(user(2L, "driver1", User.Role.DRIVER)));
        when(vehicleRepository.findById(3L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> shiftService.createShift(new ShiftCreateRequest(DAY, 1L, 2L, 3L), null))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Vehículo");

        verify(shiftRepository, never()).saveAndFlush(any());
    }

    @Test
    void givenAnAdminAsDriver_whenCreateShift_thenInvalidRequestAndNothingSaved() {
        when(waveRepository.findById(1L)).thenReturn(Optional.of(wave(1L, "Ola 1", LocalTime.of(9, 30))));
        when(userRepository.findById(2L)).thenReturn(Optional.of(user(2L, "admin1", User.Role.ADMIN)));

        assertThatThrownBy(() -> shiftService.createShift(new ShiftCreateRequest(DAY, 1L, 2L, 3L), null))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("admin1")
                .hasMessageContaining("no es un conductor");

        verify(shiftRepository, never()).saveAndFlush(any());
    }

    @Test
    void givenADeactivatedDriver_whenCreateShift_thenInvalidRequestAndNothingSaved() {
        User deactivated = user(2L, "driver1", User.Role.DRIVER);
        deactivated.setEnabled(false);
        when(waveRepository.findById(1L)).thenReturn(Optional.of(wave(1L, "Ola 1", LocalTime.of(9, 30))));
        when(userRepository.findById(2L)).thenReturn(Optional.of(deactivated));

        assertThatThrownBy(() -> shiftService.createShift(new ShiftCreateRequest(DAY, 1L, 2L, 3L), null))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("driver1")
                .hasMessageContaining("desactivado");

        verify(shiftRepository, never()).saveAndFlush(any());
    }

    @Test
    void givenADriversUpcomingShifts_whenDeleteShiftsOfDriverFrom_thenTheyAreDeletedWithoutNotifyingHim() {
        ShiftAssignment today = existingShift(4L);
        ShiftAssignment later = existingShift(5L);
        when(shiftRepository.findByDriverIdAndWorkDateGreaterThanEqual(2L, DAY)).thenReturn(List.of(today, later));

        assertThat(shiftService.deleteShiftsOfDriverFrom(2L, DAY)).isEqualTo(2);

        verify(shiftRepository).deleteAll(List.of(today, later));
        verifyNoInteractions(eventPublisher);
    }

    @Test
    void givenNoUpcomingShifts_whenDeleteShiftsOfDriverFrom_thenZero() {
        when(shiftRepository.findByDriverIdAndWorkDateGreaterThanEqual(2L, DAY)).thenReturn(List.of());

        assertThat(shiftService.deleteShiftsOfDriverFrom(2L, DAY)).isZero();
    }

    @Test
    void givenDriverWithAnotherShiftThatDay_whenCreateShift_thenDuplicateAndNothingSaved() {
        stubWaveDriverAndVan();
        when(shiftRepository.existsByWorkDateAndDriverId(DAY, 2L)).thenReturn(true);

        assertThatThrownBy(() -> shiftService.createShift(new ShiftCreateRequest(DAY, 1L, 2L, 3L), null))
                .isInstanceOf(DuplicateResourceException.class);

        verify(shiftRepository, never()).saveAndFlush(any());
    }

    @Test
    void givenVehicleWithAnotherShiftThatDay_whenCreateShift_thenDuplicateAndNothingSaved() {
        stubWaveDriverAndVan();
        when(shiftRepository.existsByWorkDateAndVehicleId(DAY, 3L)).thenReturn(true);

        assertThatThrownBy(() -> shiftService.createShift(new ShiftCreateRequest(DAY, 1L, 2L, 3L), null))
                .isInstanceOf(DuplicateResourceException.class);

        verify(shiftRepository, never()).saveAndFlush(any());
    }

    @Test
    void givenTwoSimultaneousRequests_whenTheDatabaseRejectsTheSecondShift_thenDuplicate() {
        stubWaveDriverAndVan();
        when(shiftRepository.saveAndFlush(any(ShiftAssignment.class)))
                .thenThrow(new DataIntegrityViolationException("uk_shift_assignments_driver_day"));

        assertThatThrownBy(() -> shiftService.createShift(new ShiftCreateRequest(DAY, 1L, 2L, 3L), null))
                .isInstanceOf(DuplicateResourceException.class);
    }

    @Test
    void givenVehicleInMaintenance_whenCreateShift_thenInvalidStateAndNothingSaved() {
        stubWaveAndDriver();
        when(vehicleRepository.findById(3L))
                .thenReturn(Optional.of(van(3L, "1111AAA", Vehicle.VehicleStatus.MAINTENANCE)));

        assertThatThrownBy(() -> shiftService.createShift(new ShiftCreateRequest(DAY, 1L, 2L, 3L), null))
                .isInstanceOf(InvalidStateException.class)
                .hasMessageContaining("1111AAA")
                .hasMessageContaining("mantenimiento");

        // ni siquiera se consulta si el conductor o la furgoneta están libres: el estado se comprueba antes
        verify(shiftRepository, never()).existsByWorkDateAndDriverId(any(), any());
        verify(shiftRepository, never()).saveAndFlush(any());
    }

    @Test
    void givenDamagedVehicle_whenCreateShift_thenInvalidStateAndNothingSaved() {
        stubWaveAndDriver();
        when(vehicleRepository.findById(3L))
                .thenReturn(Optional.of(van(3L, "1111AAA", Vehicle.VehicleStatus.DAMAGED)));

        assertThatThrownBy(() -> shiftService.createShift(new ShiftCreateRequest(DAY, 1L, 2L, 3L), null))
                .isInstanceOf(InvalidStateException.class)
                .hasMessageContaining("1111AAA")
                .hasMessageContaining("dañado");

        verify(shiftRepository, never()).saveAndFlush(any());
    }

    @Test
    void givenVehicleInUse_whenCreateShift_thenItCanBeAssigned() {
        // IN_USE solo significa que hoy ya hizo el parte: el turno puede ser de otro día y de noche se libera
        stubWaveAndDriver();
        when(vehicleRepository.findById(3L))
                .thenReturn(Optional.of(van(3L, "1111AAA", Vehicle.VehicleStatus.IN_USE)));
        when(shiftRepository.saveAndFlush(any(ShiftAssignment.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ShiftAssignment shift = shiftService.createShift(new ShiftCreateRequest(DAY, 1L, 2L, 3L), null);

        assertThat(shift.getVehicle().getId()).isEqualTo(3L);
    }

    @Test
    void givenAnAssignedShift_whenCreateShift_thenTheDriverIsToldWithTheDayWaveAndVan() {
        stubWaveDriverAndVan();
        when(shiftRepository.saveAndFlush(any(ShiftAssignment.class))).thenAnswer(invocation -> {
            ShiftAssignment saved = invocation.getArgument(0);
            saved.setId(4L);
            return saved;
        });

        shiftService.createShift(new ShiftCreateRequest(DAY, 1L, 2L, 3L), null);

        ArgumentCaptor<ShiftChangedEvent> captor = ArgumentCaptor.forClass(ShiftChangedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue()).isEqualTo(new ShiftChangedEvent(ShiftChangedEvent.Change.ASSIGNED, 4L, 2L, DAY,
                "Ola 1", LocalTime.of(9, 30), "1111AAA"));
    }

    @Test
    void givenNotifyDriverFalse_whenCreateShift_thenTheShiftIsSavedWithoutTellingAnyone() {
        stubWaveDriverAndVan();
        when(shiftRepository.saveAndFlush(any(ShiftAssignment.class))).thenAnswer(invocation -> invocation.getArgument(0));

        shiftService.createShift(new ShiftCreateRequest(DAY, 1L, 2L, 3L), null, false);

        verify(shiftRepository).saveAndFlush(any(ShiftAssignment.class));
        verifyNoInteractions(eventPublisher);
    }

    @Test
    void givenABusyDriver_whenCreateShift_thenNobodyIsToldBecauseNothingWasSaved() {
        stubWaveDriverAndVan();
        when(shiftRepository.existsByWorkDateAndDriverId(DAY, 2L)).thenReturn(true);

        assertThatThrownBy(() -> shiftService.createShift(new ShiftCreateRequest(DAY, 1L, 2L, 3L), null))
                .isInstanceOf(DuplicateResourceException.class);

        verifyNoInteractions(eventPublisher);
    }

    // ---------------------------------------------------------------- modificación de turnos

    @Test
    void givenNoChanges_whenUpdateShift_thenInvalidRequestWithoutTouchingTheDatabase() {
        assertThatThrownBy(() -> shiftService.updateShift(4L, new ShiftUpdateRequest(null, null, null, null)))
                .isInstanceOf(InvalidRequestException.class);

        verifyNoInteractions(shiftRepository, waveRepository, userRepository, vehicleRepository);
    }

    @Test
    void givenUnknownShift_whenUpdateShift_thenNotFound() {
        when(shiftRepository.findWithDetailsById(4L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> shiftService.updateShift(4L, new ShiftUpdateRequest(null, null, null, 9L)))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Turno");
    }

    @Test
    void givenOnlyTheVehicle_whenUpdateShift_thenChangesJustThatAndIgnoresItselfInTheChecks() {
        ShiftAssignment shift = existingShift(4L);
        Wave wave = shift.getWave();
        User driver = shift.getDriver();
        Vehicle otherVan = van(9L, "2222BBB");
        when(shiftRepository.findWithDetailsById(4L)).thenReturn(Optional.of(shift));
        when(vehicleRepository.findById(9L)).thenReturn(Optional.of(otherVan));
        when(shiftRepository.saveAndFlush(shift)).thenReturn(shift);

        ShiftAssignment updated = shiftService.updateShift(4L, new ShiftUpdateRequest(null, null, null, 9L));

        assertThat(updated.getVehicle()).isSameAs(otherVan);
        assertThat(updated.getWave()).isSameAs(wave);
        assertThat(updated.getDriver()).isSameAs(driver);
        assertThat(updated.getWorkDate()).isEqualTo(DAY);
        verify(shiftRepository).existsByWorkDateAndDriverIdAndIdNot(DAY, 2L, 4L);
        verify(shiftRepository).existsByWorkDateAndVehicleIdAndIdNot(DAY, 9L, 4L);
        verify(shiftRepository, never()).existsByWorkDateAndDriverId(any(), any());
        verify(shiftRepository, never()).existsByWorkDateAndVehicleId(any(), any());
    }

    @Test
    void givenADayWhereTheDriverIsBusy_whenUpdateShift_thenDuplicateAndTheShiftIsNotModified() {
        ShiftAssignment shift = existingShift(4L);
        LocalDate nextDay = DAY.plusDays(1);
        when(shiftRepository.findWithDetailsById(4L)).thenReturn(Optional.of(shift));
        when(shiftRepository.existsByWorkDateAndDriverIdAndIdNot(nextDay, 2L, 4L)).thenReturn(true);

        assertThatThrownBy(() -> shiftService.updateShift(4L, new ShiftUpdateRequest(nextDay, null, null, null)))
                .isInstanceOf(DuplicateResourceException.class);

        // La entidad sigue como estaba: si se modificara antes de comprobar, las consultas de comprobación
        // provocarían un flush del UPDATE y la base de datos respondería con un error en lugar de un 409
        assertThat(shift.getWorkDate()).isEqualTo(DAY);
        verify(shiftRepository, never()).saveAndFlush(any());
    }

    @Test
    void givenAVehicleBusyThatDay_whenUpdateShift_thenDuplicate() {
        ShiftAssignment shift = existingShift(4L);
        when(shiftRepository.findWithDetailsById(4L)).thenReturn(Optional.of(shift));
        when(vehicleRepository.findById(9L)).thenReturn(Optional.of(van(9L, "2222BBB")));
        when(shiftRepository.existsByWorkDateAndVehicleIdAndIdNot(DAY, 9L, 4L)).thenReturn(true);

        assertThatThrownBy(() -> shiftService.updateShift(4L, new ShiftUpdateRequest(null, null, null, 9L)))
                .isInstanceOf(DuplicateResourceException.class);

        assertThat(shift.getVehicle().getId()).isEqualTo(3L);
        verify(shiftRepository, never()).saveAndFlush(any());
    }

    @Test
    void givenAnAdminAsDriver_whenUpdateShift_thenInvalidRequest() {
        ShiftAssignment shift = existingShift(4L);
        when(shiftRepository.findWithDetailsById(4L)).thenReturn(Optional.of(shift));
        when(userRepository.findById(7L)).thenReturn(Optional.of(user(7L, "admin1", User.Role.ADMIN)));

        assertThatThrownBy(() -> shiftService.updateShift(4L, new ShiftUpdateRequest(null, null, 7L, null)))
                .isInstanceOf(InvalidRequestException.class);

        assertThat(shift.getDriver().getId()).isEqualTo(2L);
        verify(shiftRepository, never()).saveAndFlush(any());
    }

    @Test
    void givenUnknownWave_whenUpdateShift_thenNotFound() {
        when(shiftRepository.findWithDetailsById(4L)).thenReturn(Optional.of(existingShift(4L)));
        when(waveRepository.findById(8L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> shiftService.updateShift(4L, new ShiftUpdateRequest(null, 8L, null, null)))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Ola");
    }

    @Test
    void givenVehicleInMaintenance_whenUpdateShiftToIt_thenInvalidStateAndTheShiftIsNotModified() {
        ShiftAssignment shift = existingShift(4L);
        when(shiftRepository.findWithDetailsById(4L)).thenReturn(Optional.of(shift));
        when(vehicleRepository.findById(9L))
                .thenReturn(Optional.of(van(9L, "2222BBB", Vehicle.VehicleStatus.MAINTENANCE)));

        assertThatThrownBy(() -> shiftService.updateShift(4L, new ShiftUpdateRequest(null, null, null, 9L)))
                .isInstanceOf(InvalidStateException.class)
                .hasMessageContaining("2222BBB")
                .hasMessageContaining("mantenimiento");

        assertThat(shift.getVehicle().getId()).isEqualTo(3L);
        verify(shiftRepository, never()).saveAndFlush(any());
    }

    @Test
    void givenDamagedVehicle_whenUpdateShiftToIt_thenInvalidState() {
        ShiftAssignment shift = existingShift(4L);
        when(shiftRepository.findWithDetailsById(4L)).thenReturn(Optional.of(shift));
        when(vehicleRepository.findById(9L))
                .thenReturn(Optional.of(van(9L, "2222BBB", Vehicle.VehicleStatus.DAMAGED)));

        assertThatThrownBy(() -> shiftService.updateShift(4L, new ShiftUpdateRequest(null, null, null, 9L)))
                .isInstanceOf(InvalidStateException.class)
                .hasMessageContaining("dañado");

        verify(shiftRepository, never()).saveAndFlush(any());
    }

    @Test
    void givenTheCurrentVehicleNowInMaintenance_whenUpdateShiftWithoutChangingIt_thenItIsStillEditable() {
        // el turno ya tenía esa furgoneta cuando se asignó; que luego pase a mantenimiento no bloquea cambiar la ola
        ShiftAssignment shift = existingShift(4L);
        shift.setVehicle(van(3L, "1111AAA", Vehicle.VehicleStatus.MAINTENANCE));
        Wave otherWave = wave(8L, "Ola 2", LocalTime.of(11, 0));
        when(shiftRepository.findWithDetailsById(4L)).thenReturn(Optional.of(shift));
        when(waveRepository.findById(8L)).thenReturn(Optional.of(otherWave));
        when(vehicleRepository.findById(3L))
                .thenReturn(Optional.of(van(3L, "1111AAA", Vehicle.VehicleStatus.MAINTENANCE)));
        when(shiftRepository.saveAndFlush(shift)).thenReturn(shift);

        // se manda otra vez la misma furgoneta junto con la ola nueva: no cuenta como "cambiar de furgoneta"
        ShiftAssignment updated = shiftService.updateShift(4L, new ShiftUpdateRequest(null, 8L, null, 3L));

        assertThat(updated.getWave()).isSameAs(otherWave);
        assertThat(updated.getVehicle().getId()).isEqualTo(3L);
    }

    @Test
    void givenAnotherVan_whenUpdateShift_thenTheSameDriverIsToldItWasModified() {
        ShiftAssignment shift = existingShift(4L);
        when(shiftRepository.findWithDetailsById(4L)).thenReturn(Optional.of(shift));
        when(vehicleRepository.findById(9L)).thenReturn(Optional.of(van(9L, "2222BBB")));
        when(shiftRepository.saveAndFlush(shift)).thenReturn(shift);

        shiftService.updateShift(4L, new ShiftUpdateRequest(null, null, null, 9L));

        ArgumentCaptor<ShiftChangedEvent> captor = ArgumentCaptor.forClass(ShiftChangedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue()).isEqualTo(new ShiftChangedEvent(ShiftChangedEvent.Change.UPDATED, 4L, 2L, DAY,
                "Ola 1", LocalTime.of(9, 30), "2222BBB"));
    }

    @Test
    void givenAnotherDriver_whenUpdateShift_thenThePreviousOneLosesItAndTheNewOneReceivesIt() {
        ShiftAssignment shift = existingShift(4L);
        when(shiftRepository.findWithDetailsById(4L)).thenReturn(Optional.of(shift));
        when(userRepository.findById(7L)).thenReturn(Optional.of(user(7L, "driver7", User.Role.DRIVER)));
        when(shiftRepository.saveAndFlush(shift)).thenReturn(shift);

        shiftService.updateShift(4L, new ShiftUpdateRequest(null, null, 7L, null));

        ArgumentCaptor<ShiftChangedEvent> captor = ArgumentCaptor.forClass(ShiftChangedEvent.class);
        verify(eventPublisher, times(2)).publishEvent(captor.capture());
        assertThat(captor.getAllValues()).containsExactly(
                new ShiftChangedEvent(ShiftChangedEvent.Change.CANCELLED, 4L, 2L, DAY, "Ola 1", LocalTime.of(9, 30), "1111AAA"),
                new ShiftChangedEvent(ShiftChangedEvent.Change.ASSIGNED, 4L, 7L, DAY, "Ola 1", LocalTime.of(9, 30), "1111AAA"));
    }

    @Test
    void givenTheSameValues_whenUpdateShift_thenNobodyIsTold() {
        ShiftAssignment shift = existingShift(4L);
        stubWaveDriverAndVan();
        when(shiftRepository.findWithDetailsById(4L)).thenReturn(Optional.of(shift));
        when(shiftRepository.saveAndFlush(shift)).thenReturn(shift);

        shiftService.updateShift(4L, new ShiftUpdateRequest(DAY, 1L, 2L, 3L));

        verifyNoInteractions(eventPublisher);
    }

    @Test
    void givenNotifyDriverFalse_whenUpdateShift_thenTheChangeIsSavedWithoutTellingAnyone() {
        ShiftAssignment shift = existingShift(4L);
        when(shiftRepository.findWithDetailsById(4L)).thenReturn(Optional.of(shift));
        when(userRepository.findById(7L)).thenReturn(Optional.of(user(7L, "driver7", User.Role.DRIVER)));
        when(shiftRepository.saveAndFlush(shift)).thenReturn(shift);

        ShiftAssignment updated = shiftService.updateShift(4L, new ShiftUpdateRequest(null, null, 7L, null), false);

        assertThat(updated.getDriver().getId()).isEqualTo(7L);
        verifyNoInteractions(eventPublisher);
    }

    @Test
    void givenABusyDay_whenUpdateShift_thenNobodyIsTold() {
        ShiftAssignment shift = existingShift(4L);
        LocalDate nextDay = DAY.plusDays(1);
        when(shiftRepository.findWithDetailsById(4L)).thenReturn(Optional.of(shift));
        when(shiftRepository.existsByWorkDateAndDriverIdAndIdNot(nextDay, 2L, 4L)).thenReturn(true);

        assertThatThrownBy(() -> shiftService.updateShift(4L, new ShiftUpdateRequest(nextDay, null, null, null)))
                .isInstanceOf(DuplicateResourceException.class);

        verifyNoInteractions(eventPublisher);
    }

    // ---------------------------------------------------------------- baja de turnos

    @Test
    void givenUnknownShift_whenDeleteShift_thenNotFound() {
        when(shiftRepository.findWithDetailsById(4L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> shiftService.deleteShift(4L)).isInstanceOf(ResourceNotFoundException.class);

        verify(shiftRepository, never()).delete(any());
        verifyNoInteractions(eventPublisher);
    }

    @Test
    void givenExistingShift_whenDeleteShift_thenDeletedAndTheDriverIsToldItIsCancelled() {
        ShiftAssignment shift = existingShift(4L);
        when(shiftRepository.findWithDetailsById(4L)).thenReturn(Optional.of(shift));

        shiftService.deleteShift(4L);

        verify(shiftRepository).delete(shift);
        ArgumentCaptor<ShiftChangedEvent> captor = ArgumentCaptor.forClass(ShiftChangedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue()).isEqualTo(new ShiftChangedEvent(ShiftChangedEvent.Change.CANCELLED, 4L, 2L, DAY,
                "Ola 1", LocalTime.of(9, 30), "1111AAA"));
    }

    @Test
    void givenNotifyDriverFalse_whenDeleteShift_thenDeletedWithoutTellingAnyone() {
        ShiftAssignment shift = existingShift(4L);
        when(shiftRepository.findWithDetailsById(4L)).thenReturn(Optional.of(shift));

        shiftService.deleteShift(4L, false);

        verify(shiftRepository).delete(shift);
        verifyNoInteractions(eventPublisher);
    }

    // ---------------------------------------------------------------- consulta del cuadrante

    @Test
    void givenARange_whenGetShifts_thenReturnsWhatTheRepositoryFinds() {
        ShiftAssignment shift = existingShift(4L);
        when(shiftRepository.findInRange(DAY, DAY.plusDays(6))).thenReturn(List.of(shift));

        assertThat(shiftService.getShifts(DAY, DAY.plusDays(6))).containsExactly(shift);
    }

    @Test
    void givenASingleDay_whenGetShifts_thenItIsAValidRange() {
        when(shiftRepository.findInRange(DAY, DAY)).thenReturn(List.of());

        assertThat(shiftService.getShifts(DAY, DAY)).isEmpty();
    }

    @Test
    void given31Days_whenGetShifts_thenItIsTheLongestAllowedRange() {
        when(shiftRepository.findInRange(DAY, DAY.plusDays(30))).thenReturn(List.of());

        assertThat(shiftService.getShifts(DAY, DAY.plusDays(30))).isEmpty();
    }

    @Test
    void given32Days_whenGetShifts_thenInvalidRequestWithoutQuerying() {
        assertThatThrownBy(() -> shiftService.getShifts(DAY, DAY.plusDays(31)))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("31");

        verifyNoInteractions(shiftRepository);
    }

    @Test
    void givenEndBeforeStart_whenGetShifts_thenInvalidRequestWithoutQuerying() {
        assertThatThrownBy(() -> shiftService.getShifts(DAY, DAY.minusDays(1)))
                .isInstanceOf(InvalidRequestException.class);

        verifyNoInteractions(shiftRepository);
    }

    @Test
    void givenADriver_whenGetShiftsOfDriver_thenAsksOnlyForThatDriver() {
        User driver = user(2L, "driver1", User.Role.DRIVER);
        ShiftAssignment shift = existingShift(4L);
        when(shiftRepository.findByDriverInRange(2L, DAY, DAY.plusDays(6))).thenReturn(List.of(shift));

        assertThat(shiftService.getShiftsOfDriver(driver, DAY, DAY.plusDays(6))).containsExactly(shift);
    }

    @Test
    void givenAnInvalidRange_whenGetShiftsOfDriver_thenInvalidRequestWithoutQuerying() {
        User driver = user(2L, "driver1", User.Role.DRIVER);

        assertThatThrownBy(() -> shiftService.getShiftsOfDriver(driver, DAY, DAY.plusDays(40)))
                .isInstanceOf(InvalidRequestException.class);

        verifyNoInteractions(shiftRepository);
    }

    // ---------------------------------------------------------------- datos de prueba

    private void stubWaveAndDriver() {
        when(waveRepository.findById(1L)).thenReturn(Optional.of(wave(1L, "Ola 1", LocalTime.of(9, 30))));
        when(userRepository.findById(2L)).thenReturn(Optional.of(user(2L, "driver1", User.Role.DRIVER)));
    }

    private void stubWaveDriverAndVan() {
        when(waveRepository.findById(1L)).thenReturn(Optional.of(wave(1L, "Ola 1", LocalTime.of(9, 30))));
        when(userRepository.findById(2L)).thenReturn(Optional.of(user(2L, "driver1", User.Role.DRIVER)));
        when(vehicleRepository.findById(3L)).thenReturn(Optional.of(van(3L, "1111AAA")));
    }

    /** Turno 'id' el día DAY: ola 1, conductor 2 y furgoneta 3. */
    private static ShiftAssignment existingShift(Long id) {
        ShiftAssignment shift = new ShiftAssignment();
        shift.setId(id);
        shift.setWorkDate(DAY);
        shift.setWave(wave(1L, "Ola 1", LocalTime.of(9, 30)));
        shift.setDriver(user(2L, "driver1", User.Role.DRIVER));
        shift.setVehicle(van(3L, "1111AAA"));
        return shift;
    }

    private static Wave wave(Long id, String name, LocalTime startTime) {
        Wave wave = new Wave();
        wave.setId(id);
        wave.setName(name);
        wave.setStartTime(startTime);
        return wave;
    }

    private static User user(Long id, String username, User.Role role) {
        User user = new User();
        user.setId(id);
        user.setUsername(username);
        user.setRole(role);
        return user;
    }

    private static Vehicle van(Long id, String licensePlate) {
        return van(id, licensePlate, Vehicle.VehicleStatus.AVAILABLE);
    }

    private static Vehicle van(Long id, String licensePlate, Vehicle.VehicleStatus status) {
        Vehicle vehicle = new Vehicle();
        vehicle.setId(id);
        vehicle.setLicensePlate(licensePlate);
        vehicle.setStatus(status);
        return vehicle;
    }
}
