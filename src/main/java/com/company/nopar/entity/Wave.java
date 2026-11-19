package com.company.nopar.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalTime;

/** Ola (wave): una franja horaria de salida del reparto, p. ej. "Ola 1" a las 09:30. */
@Entity
@Table(name = "waves")
@Getter
@Setter
@NoArgsConstructor
public class Wave {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 50)
    private String name;

    @Column(name = "start_time", nullable = false)
    private LocalTime startTime;
}
