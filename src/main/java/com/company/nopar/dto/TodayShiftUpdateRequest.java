package com.company.nopar.dto;

/** Cambios de un turno de hoy: pasarlo a otra ola y/o cambiarle la furgoneta. Solo se modifican los campos enviados. */
public record TodayShiftUpdateRequest(
        Long waveId,
        Long vehicleId
) {
}
