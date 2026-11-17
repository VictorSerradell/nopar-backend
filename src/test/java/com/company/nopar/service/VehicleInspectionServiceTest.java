package com.company.nopar.service;

import com.company.nopar.entity.User;
import com.company.nopar.entity.Vehicle;
import com.company.nopar.entity.VehicleInspection;
import com.company.nopar.repository.VehicleInspectionRepository;
import com.company.nopar.repository.VehicleRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class VehicleInspectionServiceTest {

    @Mock
    private VehicleInspectionRepository inspectionRepository;

    @Mock
    private VehicleRepository vehicleRepository;

    @InjectMocks
    private VehicleInspectionService inspectionService;

    @Test
    @DisplayName("Debería registrar y aprobar la inspección correctamente cuando las revisiones son óptimas")
    void testRegisterInspectionSuccess() {
        // 1. Datos de prueba (Given)
        Long vehicleId = 1L;
        User driver = new User();
        driver.setId(10L);

        Vehicle vehicle = new Vehicle();
        vehicle.setId(vehicleId);
        vehicle.setCurrentKilometers(10000);

        VehicleInspection request = new VehicleInspection();
        request.setKilometers(15420);
        request.setTiresOk(true);
        request.setLightsOk(true);
        request.setBeaconV16Present(true);

        // 2. Mocking de comportamientos (When)
        when(vehicleRepository.findById(vehicleId)).thenReturn(Optional.of(vehicle));
        when(inspectionRepository.save(any(VehicleInspection.class))).thenAnswer(invocation -> {
            VehicleInspection saved = invocation.getArgument(0);
            saved.setId(100L);
            return saved;
        });

        // 3. Ejecución (When)
        VehicleInspection result = inspectionService.registerInspection(vehicleId, driver, request);

        // 4. Verificaciones y assertions (Then)
        assertNotNull(result);
        assertEquals(100L, result.getId());
        assertEquals(VehicleInspection.InspectionStatus.APPROVED, result.getStatus());
        assertEquals(15420, vehicle.getCurrentKilometers()); // Comprobamos que actualizó los km del vehículo

        verify(vehicleRepository, times(1)).findById(vehicleId);
        verify(vehicleRepository, times(1)).save(vehicle);
        verify(inspectionRepository, times(1)).save(request);
    }

    @Test
    @DisplayName("Debería lanzar una excepción si el vehículo no existe al registrar la inspección")
    void testRegisterInspectionVehicleNotFound() {
        // 1. Datos de prueba
        Long vehicleId = 99L;
        User driver = new User();
        VehicleInspection request = new VehicleInspection();

        // 2. Mocking: simulamos que el vehículo no se encuentra
        when(vehicleRepository.findById(vehicleId)).thenReturn(Optional.empty());

        // 3 & 4. Ejecución y Assertions: verificamos que lanza la RuntimeException esperada
        RuntimeException exception = assertThrows(RuntimeException.class, () -> {
            inspectionService.registerInspection(vehicleId, driver, request);
        });

        assertEquals("Vehículo no encontrado con ID: 99", exception.getMessage());

        // Verificamos que NUNCA se intentó guardar nada
        verify(vehicleRepository, times(1)).findById(vehicleId);
        verify(vehicleRepository, never()).save(any());
        verify(inspectionRepository, never()).save(any());
    }
    @Test
    @DisplayName("Debería registrar y rechazar la inspección si falla algún componente clave")
    void testRegisterInspectionRejected() {
        // 1. Datos de prueba (Given)
        Long vehicleId = 1L;
        User driver = new User();
        driver.setId(10L);

        Vehicle vehicle = new Vehicle();
        vehicle.setId(vehicleId);
        vehicle.setCurrentKilometers(10000);

        VehicleInspection request = new VehicleInspection();
        request.setKilometers(15420);
        request.setTiresOk(false); // <--- Falla un componente
        request.setLightsOk(true);
        request.setBeaconV16Present(true);

        // 2. Mocking de comportamientos (When)
        when(vehicleRepository.findById(vehicleId)).thenReturn(Optional.of(vehicle));
        when(inspectionRepository.save(any(VehicleInspection.class))).thenAnswer(invocation -> {
            VehicleInspection saved = invocation.getArgument(0);
            saved.setId(101L);
            return saved;
        });

        // 3. Ejecución (When)
        VehicleInspection result = inspectionService.registerInspection(vehicleId, driver, request);

        // 4. Verificaciones y assertions (Then)
        assertNotNull(result);
        assertEquals(101L, result.getId());
        // Verificamos que el estado se marcó como REJECTED debido a que tiresOk es false
        assertEquals(VehicleInspection.InspectionStatus.REJECTED, result.getStatus());

        verify(vehicleRepository, times(1)).findById(vehicleId);
        verify(vehicleRepository, times(1)).save(vehicle);
        verify(inspectionRepository, times(1)).save(request);
    }
}