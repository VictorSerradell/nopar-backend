package com.company.nopar.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * Devolución de un vehículo al acabar el turno: quién lo devolvió, cuándo, con cuántos kilómetros
 * y si reportó daños. Es el registro que cierra el ciclo que abre el parte matutino.
 */
@Entity
@Table(name = "vehicle_returns")
@Getter
@Setter
@NoArgsConstructor
public class VehicleReturn {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "vehicle_id", nullable = false)
    private Vehicle vehicle;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "returned_by", nullable = false)
    private User returnedBy;

    @Column(name = "returned_at", nullable = false)
    private LocalDateTime returnedAt = LocalDateTime.now();

    @Column(nullable = false)
    private Integer kilometers;

    @Column(name = "damages_reported", columnDefinition = "TEXT")
    private String damagesReported;
}
