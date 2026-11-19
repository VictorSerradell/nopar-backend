package com.company.nopar.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Turno: el conductor y la furgoneta que salen en una ola un día concreto. Un conductor y una furgoneta solo
 * pueden estar en un turno al día (lo garantizan también restricciones únicas de la base de datos).
 */
@Entity
@Table(name = "shift_assignments")
@Getter
@Setter
@NoArgsConstructor
public class ShiftAssignment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "work_date", nullable = false)
    private LocalDate workDate;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "wave_id", nullable = false)
    private Wave wave;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "driver_id", nullable = false)
    private User driver;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "vehicle_id", nullable = false)
    private Vehicle vehicle;

    /** El administrador que hizo la asignación. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by", nullable = false)
    private User createdBy;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();
}
