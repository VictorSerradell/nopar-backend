package com.company.nopar.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Una llamada a una ola ("Ola 1, subid"): un administrador o un encargado avisa a los conductores que tienen turno
 * en esa ola ese día. Los destinatarios se guardan en el momento de la llamada y cada uno confirma con "Enterado".
 */
@Entity
@Table(name = "wave_calls")
@Getter
@Setter
@NoArgsConstructor
public class WaveCall {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "wave_id", nullable = false)
    private Wave wave;

    @Column(name = "work_date", nullable = false)
    private LocalDate workDate;

    /** Lo que se ordena: "Subid" si quien llama no escribe otra cosa. */
    @Column(nullable = false, length = 140)
    private String message;

    /** Quien dio la orden (administrador o encargado). */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "called_by", nullable = false)
    private User calledBy;

    @Column(name = "called_at", nullable = false)
    private LocalDateTime calledAt = LocalDateTime.now();

    /** Hasta cuándo vale la orden: pasado ese momento los conductores ya no pueden confirmarla con "Enterado". */
    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @OneToMany(mappedBy = "waveCall", cascade = CascadeType.ALL)
    private List<WaveCallRecipient> recipients = new ArrayList<>();

    /** Si en ese momento la orden sigue vigente, es decir, si todavía se puede confirmar. */
    public boolean isOpenAt(LocalDateTime moment) {
        return expiresAt != null && moment.isBefore(expiresAt);
    }
}
