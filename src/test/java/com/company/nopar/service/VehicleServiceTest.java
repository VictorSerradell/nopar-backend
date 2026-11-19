package com.company.nopar.service;

import com.company.nopar.entity.Vehicle;
import com.company.nopar.exception.DuplicateResourceException;
import com.company.nopar.exception.ResourceInUseException;
import com.company.nopar.exception.ResourceNotFoundException;
import com.company.nopar.repository.ShiftAssignmentRepository;
import com.company.nopar.repository.VehicleInspectionRepository;
import com.company.nopar.repository.VehicleRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class VehicleServiceTest {

    @Mock
    private VehicleRepository vehicleRepository;

    @Mock
    private VehicleInspectionRepository inspectionRepository;

    @Mock
    private ShiftAssignmentRepository shiftAssignmentRepository;

    @InjectMocks
    private VehicleService vehicleService;

    @Test
    void givenNewLicensePlate_whenCreateVehicle_thenSaves() {
        Vehicle vehicle = vehicle("5678-XYZ");
        when(vehicleRepository.save(vehicle)).thenReturn(vehicle);

        assertThat(vehicleService.createVehicle(vehicle)).isSameAs(vehicle);
    }

    @Test
    void givenExistingLicensePlate_whenCreateVehicle_thenDuplicateAndNothingSaved() {
        when(vehicleRepository.findByLicensePlate("5678-XYZ")).thenReturn(Optional.of(vehicle("5678-XYZ")));

        assertThatThrownBy(() -> vehicleService.createVehicle(vehicle("5678-XYZ")))
                .isInstanceOf(DuplicateResourceException.class);

        verify(vehicleRepository, never()).save(any());
    }

    @Test
    void givenUnknownVehicle_whenUpdateKilometers_thenNotFound() {
        when(vehicleRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> vehicleService.updateVehicleKilometers(99L, 1000))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void givenUnknownVehicle_whenDelete_thenNotFound() {
        when(vehicleRepository.existsById(99L)).thenReturn(false);

        assertThatThrownBy(() -> vehicleService.deleteVehicle(99L))
                .isInstanceOf(ResourceNotFoundException.class);

        verify(vehicleRepository, never()).deleteById(any());
    }

    @Test
    void givenVehicleWithInspections_whenDelete_thenInUseAndNothingDeleted() {
        when(vehicleRepository.existsById(10L)).thenReturn(true);
        when(inspectionRepository.existsByVehicleId(10L)).thenReturn(true);

        assertThatThrownBy(() -> vehicleService.deleteVehicle(10L))
                .isInstanceOf(ResourceInUseException.class)
                .hasMessageContaining("inspecciones");

        verify(vehicleRepository, never()).deleteById(any());
    }

    @Test
    void givenVehicleWithShifts_whenDelete_thenInUseAndNothingDeleted() {
        when(vehicleRepository.existsById(10L)).thenReturn(true);
        when(inspectionRepository.existsByVehicleId(10L)).thenReturn(false);
        when(shiftAssignmentRepository.existsByVehicleId(10L)).thenReturn(true);

        assertThatThrownBy(() -> vehicleService.deleteVehicle(10L))
                .isInstanceOf(ResourceInUseException.class)
                .hasMessageContaining("turnos");

        verify(vehicleRepository, never()).deleteById(any());
    }

    @Test
    void givenVehicleWithoutInspections_whenDelete_thenDeleted() {
        when(vehicleRepository.existsById(10L)).thenReturn(true);
        when(inspectionRepository.existsByVehicleId(10L)).thenReturn(false);

        vehicleService.deleteVehicle(10L);

        verify(vehicleRepository).deleteById(10L);
    }

    @Test
    void givenVehicleInMaintenance_whenChangeStatus_thenUpdatesAndSaves() {
        Vehicle vehicle = vehicle("1234-ABC");
        vehicle.setStatus(Vehicle.VehicleStatus.MAINTENANCE);
        when(vehicleRepository.findById(10L)).thenReturn(Optional.of(vehicle));
        when(vehicleRepository.save(vehicle)).thenReturn(vehicle);

        Vehicle result = vehicleService.changeVehicleStatus(10L, Vehicle.VehicleStatus.AVAILABLE);

        assertThat(result.getStatus()).isEqualTo(Vehicle.VehicleStatus.AVAILABLE);
        verify(vehicleRepository).save(vehicle);
    }

    @Test
    void givenUnknownVehicle_whenChangeStatus_thenNotFoundAndNothingSaved() {
        when(vehicleRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> vehicleService.changeVehicleStatus(99L, Vehicle.VehicleStatus.AVAILABLE))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("Vehículo no encontrado con ID: 99");

        verify(vehicleRepository, never()).save(any());
    }

    @Test
    void givenPageAndSize_whenGetAllVehicles_thenSortedByLicensePlate() {
        Page<Vehicle> page = new PageImpl<>(List.of(vehicle("1234-ABC")));
        when(vehicleRepository.findAll(PageRequest.of(1, 5, Sort.by("licensePlate")))).thenReturn(page);

        assertThat(vehicleService.getAllVehicles(1, 5)).isSameAs(page);
    }

    @Test
    void givenVehiclesInUse_whenReleaseVehiclesInUse_thenReturnsHowManyWentBackToAvailable() {
        when(vehicleRepository.updateStatus(Vehicle.VehicleStatus.IN_USE, Vehicle.VehicleStatus.AVAILABLE))
                .thenReturn(3);

        assertThat(vehicleService.releaseVehiclesInUse()).isEqualTo(3);
    }

    private static Vehicle vehicle(String licensePlate) {
        Vehicle vehicle = new Vehicle();
        vehicle.setLicensePlate(licensePlate);
        vehicle.setModel("Ford Transit");
        vehicle.setCurrentKilometers(1000);
        return vehicle;
    }
}
