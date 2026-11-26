package com.company.nopar.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * Hasta qué mensaje ha leído un usuario el chat de una ola (una fila por usuario y ola). Se escribe con una inserción
 * nativa que solo hace avanzar el marcador (WaveChatReadRepository.markRead); la entidad sirve para consultarlo.
 *
 * Usuario y ola se guardan como id (no como relación): el marcador no necesita cargar a ninguno de los dos.
 */
@Entity
@Table(name = "wave_chat_reads")
@Getter
@Setter
@NoArgsConstructor
public class WaveChatRead {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "wave_id", nullable = false)
    private Long waveId;

    /** El mensaje más reciente que el usuario ha visto en ese chat. */
    @Column(name = "last_read_id", nullable = false)
    private Long lastReadId;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt = LocalDateTime.now();
}
