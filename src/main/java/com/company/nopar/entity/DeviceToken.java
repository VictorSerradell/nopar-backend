package com.company.nopar.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * Una instalación de la app (Android, iOS o web) de un usuario, identificada por su token de Firebase Cloud
 * Messaging (FCM). Es a lo que se envían las notificaciones push. Un token pertenece a un solo usuario: si otro inicia
 * sesión en ese dispositivo, el registro pasa a él.
 */
@Entity
@Table(name = "device_tokens")
@Getter
@Setter
@NoArgsConstructor
public class DeviceToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(nullable = false, unique = true, length = 512)
    private String token;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Platform platform;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    /** La última vez que la app registró este token: al pasar del máximo por usuario se descartan los más antiguos. */
    @Column(name = "last_seen_at", nullable = false)
    private LocalDateTime lastSeenAt = LocalDateTime.now();

    public enum Platform {
        ANDROID, IOS, WEB
    }
}
