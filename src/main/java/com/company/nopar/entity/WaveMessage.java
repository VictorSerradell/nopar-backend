package com.company.nopar.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Un mensaje del chat de una ola. El chat es de una ola y un día: lo ven los conductores que ese día tienen turno en
 * la ola, los encargados y los administradores. Lleva texto, una foto o las dos cosas.
 *
 * La ola se guarda como id (no como relación): el chat solo necesita saber a qué ola pertenece, y así leer los
 * mensajes no arrastra ni una consulta ni un proxy más.
 */
@Entity
@Table(name = "wave_messages")
@Getter
@Setter
@NoArgsConstructor
public class WaveMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "wave_id", nullable = false)
    private Long waveId;

    @Column(name = "work_date", nullable = false)
    private LocalDate workDate;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "sender_id", nullable = false)
    private User sender;

    /** El texto, ya sin espacios sobrantes; null si el mensaje es solo una foto. */
    @Column(length = 1000)
    private String body;

    /** Nombre del fichero que guardó FileStorageService; null si el mensaje no lleva foto. */
    @Column(name = "photo_file")
    private String photoFile;

    @Column(name = "sent_at", nullable = false)
    private LocalDateTime sentAt = LocalDateTime.now();

    public boolean hasPhoto() {
        return photoFile != null;
    }
}
