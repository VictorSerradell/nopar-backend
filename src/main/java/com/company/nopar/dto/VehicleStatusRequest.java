package com.company.nopar.dto;

import com.company.nopar.entity.Vehicle;
import jakarta.validation.constraints.NotNull;

/** Cambio de estado de un vehículo decidido por un administrador. */
public record VehicleStatusRequest(
        @NotNull(message = "El estado es obligatorio")
        Status status
) {

    /** IN_USE no se fija a mano: lo marca el parte matutino de un conductor. */
    public enum Status {
        AVAILABLE, MAINTENANCE, DAMAGED
    }

    public Vehicle.VehicleStatus toVehicleStatus() {
        return Vehicle.VehicleStatus.valueOf(status.name());
    }
}
