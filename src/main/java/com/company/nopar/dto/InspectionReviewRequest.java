package com.company.nopar.dto;

import jakarta.validation.constraints.NotNull;

/** Decisión de un administrador sobre una inspección pendiente de revisión. */
public record InspectionReviewRequest(
        @NotNull(message = "La decisión es obligatoria")
        Decision decision
) {

    /** Solo se puede aprobar o rechazar: volver a SUBMITTED no es una decisión. */
    public enum Decision {
        APPROVED, REJECTED
    }
}
