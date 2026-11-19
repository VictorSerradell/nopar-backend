package com.company.nopar.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Alta de un aviso. Si no se indica pinned, el aviso no queda fijado. */
public record AnnouncementRequest(
        @NotBlank(message = "El título es obligatorio")
        @Size(max = 120, message = "El título no puede superar los 120 caracteres")
        String title,

        @NotBlank(message = "El texto es obligatorio")
        @Size(max = 4000, message = "El texto no puede superar los 4000 caracteres")
        String body,

        Boolean pinned
) {
}
