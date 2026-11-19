package com.company.nopar.dto;

import java.time.LocalDate;

/** Cambios de un turno: solo se modifican los campos enviados; el resto se queda como está. */
public record ShiftUpdateRequest(
        LocalDate workDate,
        Long waveId,
        Long driverId,
        Long vehicleId
) {

    public boolean isEmpty() {
        return workDate == null && waveId == null && driverId == null && vehicleId == null;
    }
}
