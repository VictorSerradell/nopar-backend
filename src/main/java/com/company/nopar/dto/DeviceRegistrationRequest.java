package com.company.nopar.dto;

import com.company.nopar.entity.DeviceToken;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Registro de un dispositivo para las notificaciones push: su token de FCM y la plataforma de la app. */
public record DeviceRegistrationRequest(
        @NotBlank(message = "El token del dispositivo es obligatorio")
        @Size(max = 512, message = "El token del dispositivo no puede superar los 512 caracteres")
        String token,

        @NotNull(message = "La plataforma es obligatoria")
        DeviceToken.Platform platform
) {
}
