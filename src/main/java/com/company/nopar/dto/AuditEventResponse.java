package com.company.nopar.dto;

import com.company.nopar.entity.AuditEvent;

import java.time.LocalDateTime;

/** Una línea del registro de auditoría. Los nombres son los de ese momento. */
public record AuditEventResponse(
        Long id,
        LocalDateTime createdAt,
        AuditEvent.Action action,
        /** Quien hizo la acción; "sistema" si no fue un usuario (por ejemplo, el arranque de la aplicación). */
        String actorUsername,
        Long targetUserId,
        String targetUsername,
        String detail
) {

    public static AuditEventResponse from(AuditEvent event) {
        return new AuditEventResponse(event.getId(), event.getCreatedAt(), event.getAction(), event.getActorUsername(),
                event.getTargetUserId(), event.getTargetUsername(), event.getDetail());
    }
}
