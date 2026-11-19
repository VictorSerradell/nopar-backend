package com.company.nopar.dto;

import jakarta.validation.constraints.Size;

/** Cambios de un aviso: solo se modifican los campos enviados; el resto se queda como está. */
public record AnnouncementUpdateRequest(
        @Size(min = 1, max = 120, message = "El título debe tener entre 1 y 120 caracteres")
        String title,

        @Size(min = 1, max = 4000, message = "El texto debe tener entre 1 y 4000 caracteres")
        String body,

        Boolean pinned
) {

    public boolean isEmpty() {
        return title == null && body == null && pinned == null;
    }
}
