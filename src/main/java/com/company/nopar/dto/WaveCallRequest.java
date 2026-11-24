package com.company.nopar.dto;

import jakarta.validation.constraints.Size;

/** Lo que se ordena a la ola. Opcional: sin texto, "Subid". */
public record WaveCallRequest(
        @Size(max = 140, message = "El mensaje no puede superar los 140 caracteres")
        String message
) {
}
