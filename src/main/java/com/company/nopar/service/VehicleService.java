package com.company.nopar.service;

import com.company.nopar.entity.Vehicle;
import com.company.nopar.exception.DuplicateResourceException;
import com.company.nopar.exception.ResourceNotFoundException;
import com.company.nopar.repository.VehicleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional; // <--- Import correcto de Java

@Service
@RequiredArgsConstructor
public class VehicleService {

    private final VehicleRepository vehicleRepository;

    @Transactional
    public Vehicle createVehicle(Vehicle vehicle) {
        // Validamos si ya existe una matrícula igual para evitar duplicados
        if (vehicleRepository.findByLicensePlate(vehicle.getLicensePlate()).isPresent()) {
            throw new DuplicateResourceException("Ya existe un vehículo con la matrícula: " + vehicle.getLicensePlate());
        }
        return vehicleRepository.save(vehicle);
    }

    public List<Vehicle> getAllVehicles() {
        return vehicleRepository.findAll();
    }

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

    @Transactional
    public void deleteVehicle(Long id) {
        if (!vehicleRepository.existsById(id)) {
            throw new ResourceNotFoundException("Vehículo no encontrado con ID: " + id);
        }
        vehicleRepository.deleteById(id);
    }

    public Optional<Vehicle> findByLicensePlate(String licensePlate) {
        return vehicleRepository.findByLicensePlate(licensePlate);
    }
}