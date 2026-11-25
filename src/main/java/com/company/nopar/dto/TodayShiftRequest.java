package com.company.nopar.dto;

import jakarta.validation.constraints.NotNull;

/** Poner a un conductor, con una furgoneta, en una ola de hoy. El día no se envía: siempre es hoy. */
public record TodayShiftRequest(
        @NotNull(message = "El ID de la ola es obligatorio")
        Long waveId,

        @NotNull(message = "El ID del conductor es obligatorio")
        Long driverId,

        @NotNull(message = "El ID del vehículo es obligatorio")
        Long vehicleId
) {
}
