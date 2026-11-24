package com.company.nopar.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Cambio de contraseña del propio usuario. Exige la actual para que un access token robado no baste para
 * quedarse con la cuenta. La longitud de la nueva sigue la misma regla que el alta de conductores.
 */
public record ChangePasswordRequest(
        @NotBlank(message = "La contraseña actual es obligatoria")
        String currentPassword,

        @NotBlank(message = "La nueva contraseña es obligatoria")
        @Size(min = 8, max = 72, message = "La nueva contraseña debe tener entre 8 y 72 caracteres")
        String newPassword
) {
    /** El toString de un record imprimiría las contraseñas si alguien registrara la petición. */
    @Override
    public String toString() {
        return "ChangePasswordRequest[***]";
    }
}
