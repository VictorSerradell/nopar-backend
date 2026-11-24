package com.company.nopar.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "vehicle_inspections")
@NamedEntityGraph(
        name = VehicleInspection.WITH_DETAILS,
        attributeNodes = {
                @NamedAttributeNode("vehicle"),
                @NamedAttributeNode("driver"),
                @NamedAttributeNode("reviewedBy")
        })
public class VehicleInspection {

    /** Grafo que trae vehículo, conductor y revisor en la misma consulta (evita el N+1 al listar). */
    public static final String WITH_DETAILS = "VehicleInspection.withDetails";

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

    // Trazabilidad de la revisión: se rellenan solo cuando un administrador aprueba o rechaza
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reviewed_by")
    private User reviewedBy;

    private LocalDateTime reviewedAt;

    // Enlace informativo con el cuadrante (V7): el turno que el conductor tenía ese día, si lo tenía, y si la
    // furgoneta del parte era la asignada en él. Nunca bloquea el parte
    @Column(name = "shift_id")
    private Long shiftId;

    @Column(name = "matches_assigned_vehicle")
    private Boolean matchesAssignedVehicle;

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

    public User getReviewedBy() { return reviewedBy; }
    public void setReviewedBy(User reviewedBy) { this.reviewedBy = reviewedBy; }

    public LocalDateTime getReviewedAt() { return reviewedAt; }
    public void setReviewedAt(LocalDateTime reviewedAt) { this.reviewedAt = reviewedAt; }

    public Long getShiftId() { return shiftId; }
    public void setShiftId(Long shiftId) { this.shiftId = shiftId; }

    public Boolean getMatchesAssignedVehicle() { return matchesAssignedVehicle; }
    public void setMatchesAssignedVehicle(Boolean matchesAssignedVehicle) { this.matchesAssignedVehicle = matchesAssignedVehicle; }
}