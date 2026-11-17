package com.company.nopar.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "vehicle_inspections")
public class VehicleInspection {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "vehicle_id", nullable = false)
    private Vehicle vehicle;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User driver;

    @Column(nullable = false)
    private LocalDateTime inspectionDate = LocalDateTime.now();

    @Column(nullable = false)
    private Integer kilometers;

    // Campos del checklist de seguridad
    private Boolean tiresOk;
    private Boolean lightsOk;
    private Boolean beaconV16Present;

    // Campos para rutas de fotos y evidencias
    private String odometerPhotoPath;
    private String beaconPhotoPath;
    private String exteriorPhotosPath;

    @Column(columnDefinition = "TEXT")
    private String damagesReported;

    private Boolean acceptedExclusiveUse;
    private Boolean acceptedLiability;
    private Boolean acceptedCleanliness;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private InspectionStatus status;

    public enum InspectionStatus {
        SUBMITTED, APPROVED, REJECTED
    }

    public VehicleInspection() {
    }

    // Getters y Setters completos
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Vehicle getVehicle() { return vehicle; }
    public void setVehicle(Vehicle vehicle) { this.vehicle = vehicle; }

    public User getDriver() { return driver; }
    public void setDriver(User driver) { this.driver = driver; }

    public LocalDateTime getInspectionDate() { return inspectionDate; }
    public void setInspectionDate(LocalDateTime inspectionDate) { this.inspectionDate = inspectionDate; }

    public Integer getKilometers() { return kilometers; }
    public void setKilometers(Integer kilometers) { this.kilometers = kilometers; }

    public Boolean getTiresOk() { return tiresOk; }
    public void setTiresOk(Boolean tiresOk) { this.tiresOk = tiresOk; }

    public Boolean getLightsOk() { return lightsOk; }
    public void setLightsOk(Boolean lightsOk) { this.lightsOk = lightsOk; }

    public Boolean getBeaconV16Present() { return beaconV16Present; }
    public void setBeaconV16Present(Boolean beaconV16Present) { this.beaconV16Present = beaconV16Present; }

    public String getOdometerPhotoPath() { return odometerPhotoPath; }
    public void setOdometerPhotoPath(String odometerPhotoPath) { this.odometerPhotoPath = odometerPhotoPath; }

    public String getBeaconPhotoPath() { return beaconPhotoPath; }
    public void setBeaconPhotoPath(String beaconPhotoPath) { this.beaconPhotoPath = beaconPhotoPath; }

    public String getExteriorPhotosPath() { return exteriorPhotosPath; }
    public void setExteriorPhotosPath(String exteriorPhotosPath) { this.exteriorPhotosPath = exteriorPhotosPath; }

    public String getDamagesReported() { return damagesReported; }
    public void setDamagesReported(String damagesReported) { this.damagesReported = damagesReported; }

    public Boolean getAcceptedExclusiveUse() { return acceptedExclusiveUse; }
    public void setAcceptedExclusiveUse(Boolean acceptedExclusiveUse) { this.acceptedExclusiveUse = acceptedExclusiveUse; }

    public Boolean getAcceptedLiability() { return acceptedLiability; }
    public void setAcceptedLiability(Boolean acceptedLiability) { this.acceptedLiability = acceptedLiability; }

    public Boolean getAcceptedCleanliness() { return acceptedCleanliness; }
    public void setAcceptedCleanliness(Boolean acceptedCleanliness) { this.acceptedCleanliness = acceptedCleanliness; }

    public InspectionStatus getStatus() { return status; }
    public void setStatus(InspectionStatus status) { this.status = status; }
}