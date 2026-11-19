package com.company.nopar.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalTime;

/** Alta de una ola: su nombre ("Ola 1") y la hora de salida (HH:mm). */
public record WaveRequest(
        @NotBlank(message = "El nombre de la ola es obligatorio")
        @Size(max = 50, message = "El nombre de la ola no puede superar los 50 caracteres")
        String name,

        @NotNull(message = "La hora de salida es obligatoria")
        LocalTime startTime
) {
}
