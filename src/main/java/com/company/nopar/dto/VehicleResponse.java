package com.company.nopar.dto;

import com.company.nopar.entity.Vehicle;

import java.util.List;

/**
 * Vista pública de un vehículo. Mantiene los mismos nombres de campo que la entidad,
 * de modo que los clientes existentes no notan el cambio.
 */
public record VehicleResponse(
        Long id,
        String licensePlate,
        String model,
        Boolean hasBeacon,
        Integer currentKilometers,
        Vehicle.VehicleStatus status
) {

    public static VehicleResponse from(Vehicle vehicle) {
        return new VehicleResponse(
                vehicle.getId(),
                vehicle.getLicensePlate(),
                vehicle.getModel(),
                vehicle.getHasBeacon(),
                vehicle.getCurrentKilometers(),
                vehicle.getStatus()
        );
    }

    public static List<VehicleResponse> fromAll(List<Vehicle> vehicles) {
        return vehicles.stream().map(VehicleResponse::from).toList();
    }
}
