package com.company.nopar.service;

import com.company.nopar.entity.Vehicle;
import com.company.nopar.exception.DuplicateResourceException;
import com.company.nopar.exception.ResourceInUseException;
import com.company.nopar.exception.ResourceNotFoundException;
import com.company.nopar.repository.VehicleInspectionRepository;
import com.company.nopar.repository.VehicleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional; // <--- Import correcto de Java

@Service
@RequiredArgsConstructor
public class VehicleService {

    private final VehicleRepository vehicleRepository;
    private final VehicleInspectionRepository inspectionRepository;

    @Transactional
    public Vehicle createVehicle(Vehicle vehicle) {
        // Validamos si ya existe una matrícula igual para evitar duplicados
        if (vehicleRepository.findByLicensePlate(vehicle.getLicensePlate()).isPresent()) {
            throw new DuplicateResourceException("Ya existe un vehículo con la matrícula: " + vehicle.getLicensePlate());
        }
        return vehicleRepository.save(vehicle);
    }

    @Transactional(readOnly = true)
    public Page<Vehicle> getAllVehicles(int page, int size) {
        // Orden fijo por matrícula (única e indexada): el cliente no elige por qué columna ordenar
        return vehicleRepository.findAll(PageRequest.of(page, size, Sort.by("licensePlate")));
    }

    @Transactional(readOnly = true)
    public Optional<Vehicle> getVehicleById(Long id) {
        return vehicleRepository.findById(id);
    }

    @Transactional
    public Vehicle updateVehicleKilometers(Long id, int newKilometers) {
        Vehicle vehicle = vehicleRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Vehículo no encontrado con ID: " + id));
        vehicle.setCurrentKilometers(newKilometers);
        return vehicleRepository.save(vehicle);
    }

    /** Cambio de estado decidido por un administrador (p. ej. devolver a AVAILABLE un vehículo ya reparado). */
    @Transactional
    public Vehicle changeVehicleStatus(Long id, Vehicle.VehicleStatus status) {
        Vehicle vehicle = vehicleRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Vehículo no encontrado con ID: " + id));
        vehicle.setStatus(status);
        return vehicleRepository.save(vehicle);
    }

    @Transactional
    public void deleteVehicle(Long id) {
        if (!vehicleRepository.existsById(id)) {
            throw new ResourceNotFoundException("Vehículo no encontrado con ID: " + id);
        }
        if (inspectionRepository.existsByVehicleId(id)) {
            throw new ResourceInUseException(
                    "No se puede eliminar el vehículo " + id + " porque tiene inspecciones registradas");
        }
        vehicleRepository.deleteById(id);
    }

    @Transactional(readOnly = true)
    public Optional<Vehicle> findByLicensePlate(String licensePlate) {
        return vehicleRepository.findByLicensePlate(licensePlate);
    }
}