package com.company.nopar.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/** Un conductor al que se llamó, con la furgoneta que tenía asignada y cuándo confirmó que se había enterado. */
@Entity
@Table(name = "wave_call_recipients")
@Getter
@Setter
@NoArgsConstructor
public class WaveCallRecipient {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "wave_call_id", nullable = false)
    private WaveCall waveCall;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "driver_id", nullable = false)
    private User driver;

    /** La matrícula en el momento de la llamada: si el turno cambia después, la llamada conserva la que se dijo. */
    @Column(name = "vehicle_license_plate", nullable = false, length = 20)
    private String vehicleLicensePlate;

    /** Vacío hasta que el conductor pulsa "Enterado". */
    @Column(name = "acknowledged_at")
    private LocalDateTime acknowledgedAt;

    public boolean isAcknowledged() {
        return acknowledgedAt != null;
    }
}
