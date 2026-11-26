package com.company.nopar.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * Una acción sobre una cuenta de usuario: quién la hizo, sobre quién y cuándo. Guarda los nombres y no referencias a
 * User: el registro tiene que seguir siendo legible si la cuenta cambia de nombre o desaparece. Solo se añaden filas,
 * nunca se modifican.
 */
@Entity
@Table(name = "audit_events")
@Getter
@Setter
@NoArgsConstructor
public class AuditEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private Action action;

    /** Nombre de quien hizo la acción, o "sistema" si no fue un usuario. */
    @Column(name = "actor_username", nullable = false)
    private String actorUsername;

    @Column(name = "target_user_id")
    private Long targetUserId;

    @Column(name = "target_username")
    private String targetUsername;

    @Column(length = 500)
    private String detail;

    public enum Action {
        USER_CREATED,
        USER_DEACTIVATED,
        USER_ACTIVATED,
        /** El propio usuario cambió su contraseña. */
        PASSWORD_CHANGED,
        /** Un administrador restableció la contraseña de otro. */
        PASSWORD_RESET,
        SUPER_ADMIN_GRANTED
    }
}
