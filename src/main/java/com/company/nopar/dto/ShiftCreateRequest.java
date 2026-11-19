package com.company.nopar.dto;

import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;

/** Alta de un turno: qué conductor sale en qué ola y con qué furgoneta un día concreto. */
public record ShiftCreateRequest(
        @NotNull(message = "La fecha es obligatoria")
        LocalDate workDate,

        @NotNull(message = "El ID de la ola es obligatorio")
        Long waveId,

        @NotNull(message = "El ID del conductor es obligatorio")
        Long driverId,

        @NotNull(message = "El ID del vehículo es obligatorio")
        Long vehicleId
) {
}
