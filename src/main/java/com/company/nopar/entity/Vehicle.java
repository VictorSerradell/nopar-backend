package com.company.nopar.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "vehicles")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class Vehicle {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "license_plate", nullable = false, unique = true, length = 20)
    private String licensePlate;

    @Column(nullable = false)
    private String model;

    @Column(name = "has_beacon", nullable = false)
    private Boolean hasBeacon = false;

    @Column(name = "current_kilometers", nullable = false)
    private Integer currentKilometers;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private VehicleStatus status = VehicleStatus.AVAILABLE;

    public enum VehicleStatus {
        AVAILABLE, IN_USE, MAINTENANCE, DAMAGED
    }
}