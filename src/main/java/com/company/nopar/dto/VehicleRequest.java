package com.company.nopar.dto;

import com.company.nopar.entity.Vehicle;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Datos que el cliente puede enviar para dar de alta un vehículo.
 * El id lo genera la base de datos y el estado inicial (AVAILABLE) lo decide el servidor:
 * al no existir en el record, un cliente no puede fijarlos aunque los incluya en el JSON.
 */
public record VehicleRequest(
        @NotBlank(message = "La matrícula es obligatoria")
        @Size(max = 20, message = "La matrícula no puede superar los 20 caracteres")
        String licensePlate,

        @NotBlank(message = "El modelo es obligatorio")
        @Size(max = 255, message = "El modelo no puede superar los 255 caracteres")
        String model,

        Boolean hasBeacon,

        @NotNull(message = "Los kilómetros son obligatorios")
        @Min(value = 0, message = "Los kilómetros no pueden ser negativos")
        Integer currentKilometers
) {

    /** Solo debe llamarse con la petición ya validada (@Valid en el controlador). */
    public Vehicle toEntity() {
        Vehicle vehicle = new Vehicle();
        vehicle.setLicensePlate(licensePlate.strip());
        vehicle.setModel(model.strip());
        vehicle.setHasBeacon(Boolean.TRUE.equals(hasBeacon));
        vehicle.setCurrentKilometers(currentKilometers);
        return vehicle;
    }
}
