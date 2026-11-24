package com.company.nopar.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Restablecimiento de la contraseña de un conductor o de un encargado por un administrador. No lleva la contraseña
 * actual: quien la tenía la ha olvidado. La longitud sigue la misma regla que el alta.
 */
public record ResetPasswordRequest(
        @NotBlank(message = "La nueva contraseña es obligatoria")
        @Size(min = 8, max = 72, message = "La nueva contraseña debe tener entre 8 y 72 caracteres")
        String newPassword
) {
    /** El toString de un record imprimiría la contraseña si alguien registrara la petición. */
    @Override
    public String toString() {
        return "ResetPasswordRequest[***]";
    }
}
