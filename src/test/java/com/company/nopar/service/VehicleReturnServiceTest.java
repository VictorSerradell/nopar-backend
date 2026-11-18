package com.company.nopar.service;

import com.company.nopar.dto.VehicleReturnRequest;
import com.company.nopar.entity.User;
import com.company.nopar.entity.Vehicle;
import com.company.nopar.entity.VehicleInspection;
import com.company.nopar.entity.VehicleReturn;
import com.company.nopar.exception.InvalidKilometersException;
import com.company.nopar.exception.InvalidStateException;
import com.company.nopar.exception.ResourceNotFoundException;
import com.company.nopar.repository.VehicleInspectionRepository;
import com.company.nopar.repository.VehicleRepository;
import com.company.nopar.repository.VehicleReturnRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class VehicleReturnServiceTest {

    @Mock
    private VehicleRepository vehicleRepository;

    @Mock
    private VehicleInspectionRepository inspectionRepository;

    @Mock
    private VehicleReturnRepository returnRepository;

    @InjectMocks
    private VehicleReturnService vehicleReturnService;

    private final User driver = user(7L, User.Role.DRIVER);
    private final User otherDriver = user(8L, User.Role.DRIVER);
    private final User admin = user(1L, User.Role.ADMIN);

    @Test
    void givenTheDriverOfTheLastReportAndNoDamages_whenReturnVehicle_thenAvailableAndReturnStored() {
        Vehicle vehicle = vehicleInUse();
        givenVehicle(vehicle);
        givenLastReportBy(driver);
        givenReturnSaved();

        VehicleReturn result = vehicleReturnService.returnVehicle(5L, new VehicleReturnRequest(15200, null), driver);

        assertThat(vehicle.getStatus()).isEqualTo(Vehicle.VehicleStatus.AVAILABLE);
        assertThat(vehicle.getCurrentKilometers()).isEqualTo(15200);
        assertThat(result.getVehicle()).isSameAs(vehicle);
        assertThat(result.getReturnedBy()).isSameAs(driver);
        assertThat(result.getKilometers()).isEqualTo(15200);
        assertThat(result.getDamagesReported()).isNull();
        assertThat(result.getReturnedAt()).isNotNull();
        verify(vehicleRepository).save(vehicle);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    void givenBlankDamages_whenReturnVehicle_thenTreatedAsNoDamages(String blank) {
        Vehicle vehicle = vehicleInUse();
        givenVehicle(vehicle);
        givenLastReportBy(driver);
        givenReturnSaved();

        VehicleReturn result = vehicleReturnService.returnVehicle(5L, new VehicleReturnRequest(15200, blank), driver);

        assertThat(vehicle.getStatus()).isEqualTo(Vehicle.VehicleStatus.AVAILABLE);
        assertThat(result.getDamagesReported()).isNull();
    }

    @Test
    void givenDamages_whenReturnVehicle_thenMaintenanceAndTrimmedDescriptionStored() {
        Vehicle vehicle = vehicleInUse();
        givenVehicle(vehicle);
        givenLastReportBy(driver);
        givenReturnSaved();

        VehicleReturn result = vehicleReturnService.returnVehicle(
                5L, new VehicleReturnRequest(15100, "  Espejo roto  "), driver);

        assertThat(vehicle.getStatus()).isEqualTo(Vehicle.VehicleStatus.MAINTENANCE);
        assertThat(vehicle.getCurrentKilometers()).isEqualTo(15100);
        assertThat(result.getDamagesReported()).isEqualTo("Espejo roto");
    }

    @Test
    void givenAnAdminWhoDidNotMakeTheReport_whenReturnVehicle_thenAllowedWithoutLookingUpTheReport() {
        givenVehicle(vehicleInUse());
        givenReturnSaved();

        VehicleReturn result = vehicleReturnService.returnVehicle(5L, new VehicleReturnRequest(15200, null), admin);

        assertThat(result.getReturnedBy()).isSameAs(admin);
        verifyNoInteractions(inspectionRepository);
    }

    @Test
    void givenAnotherDriver_whenReturnVehicle_thenAccessDeniedAndNothingChanges() {
        Vehicle vehicle = vehicleInUse();
        givenVehicle(vehicle);
        givenLastReportBy(driver);

        assertThatThrownBy(() -> vehicleReturnService.returnVehicle(
                5L, new VehicleReturnRequest(15200, null), otherDriver))
                .isInstanceOf(AccessDeniedException.class);

        assertThat(vehicle.getStatus()).isEqualTo(Vehicle.VehicleStatus.IN_USE);
        verify(vehicleRepository, never()).save(any());
        verify(returnRepository, never()).save(any());
    }

    @Test
    void givenAVehicleWithoutAnyReport_whenADriverReturnsIt_thenAccessDenied() {
        givenVehicle(vehicleInUse());
        when(inspectionRepository.findFirstByVehicleIdOrderByInspectionDateDescIdDesc(5L))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> vehicleReturnService.returnVehicle(
                5L, new VehicleReturnRequest(15200, null), driver))
                .isInstanceOf(AccessDeniedException.class);

        verify(returnRepository, never()).save(any());
    }

    @Test
    void givenAVehicleNotInUse_whenReturnVehicle_thenInvalidState() {
        Vehicle vehicle = vehicleInUse();
        vehicle.setStatus(Vehicle.VehicleStatus.AVAILABLE);
        givenVehicle(vehicle);
        givenLastReportBy(driver);

        assertThatThrownBy(() -> vehicleReturnService.returnVehicle(
                5L, new VehicleReturnRequest(15200, null), driver))
                .isInstanceOf(InvalidStateException.class)
                .hasMessageContaining("no está en uso");

        verify(vehicleRepository, never()).save(any());
        verify(returnRepository, never()).save(any());
    }

    @Test
    void givenAnUnknownVehicle_whenReturnVehicle_thenNotFound() {
        when(vehicleRepository.findByIdForUpdate(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> vehicleReturnService.returnVehicle(
                99L, new VehicleReturnRequest(15200, null), driver))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("Vehículo no encontrado con ID: 99");

        verifyNoInteractions(inspectionRepository, returnRepository);
    }

    @Test
    void givenKilometersBelowTheRegisteredOnes_whenReturnVehicle_thenInvalidKilometersAndNothingStored() {
        Vehicle vehicle = vehicleInUse(); // 15000 km registrados
        givenVehicle(vehicle);
        givenLastReportBy(driver);

        assertThatThrownBy(() -> vehicleReturnService.returnVehicle(
                5L, new VehicleReturnRequest(14999, null), driver))
                .isInstanceOf(InvalidKilometersException.class)
                .hasMessageContaining("14999")
                .hasMessageContaining("15000");

        assertThat(vehicle.getCurrentKilometers()).isEqualTo(15000);
        assertThat(vehicle.getStatus()).isEqualTo(Vehicle.VehicleStatus.IN_USE);
        verify(vehicleRepository, never()).save(any());
        verify(returnRepository, never()).save(any());
    }

    @Test
    void givenTheSameKilometersAsRegistered_whenReturnVehicle_thenAccepted() {
        Vehicle vehicle = vehicleInUse();
        givenVehicle(vehicle);
        givenLastReportBy(driver);
        givenReturnSaved();

        VehicleReturn result = vehicleReturnService.returnVehicle(5L, new VehicleReturnRequest(15000, null), driver);

        assertThat(result.getKilometers()).isEqualTo(15000);
        assertThat(vehicle.getStatus()).isEqualTo(Vehicle.VehicleStatus.AVAILABLE);
    }

    private void givenVehicle(Vehicle vehicle) {
        when(vehicleRepository.findByIdForUpdate(5L)).thenReturn(Optional.of(vehicle));
    }

    private void givenLastReportBy(User reportDriver) {
        VehicleInspection report = new VehicleInspection();
        report.setDriver(reportDriver);
        when(inspectionRepository.findFirstByVehicleIdOrderByInspectionDateDescIdDesc(5L))
                .thenReturn(Optional.of(report));
    }

    private void givenReturnSaved() {
        when(returnRepository.save(any(VehicleReturn.class))).thenAnswer(call -> call.getArgument(0));
    }

    private static Vehicle vehicleInUse() {
        Vehicle vehicle = new Vehicle();
        vehicle.setId(5L);
        vehicle.setLicensePlate("1234-ABC");
        vehicle.setModel("Ford Transit");
        vehicle.setCurrentKilometers(15000);
        vehicle.setStatus(Vehicle.VehicleStatus.IN_USE);
        return vehicle;
    }

    private static User user(Long id, User.Role role) {
        User user = new User();
        user.setId(id);
        user.setUsername("user" + id);
        user.setRole(role);
        return user;
    }
}
