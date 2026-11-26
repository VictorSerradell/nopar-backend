package com.company.nopar.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/** Hasta qué mensaje ha leído el usuario el chat de una ola: el más reciente que la app le ha enseñado. */
public record WaveChatReadRequest(
        @NotNull(message = "lastReadId es obligatorio")
        @Positive(message = "lastReadId debe ser positivo")
        Long lastReadId
) {
}
