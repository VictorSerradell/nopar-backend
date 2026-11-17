package com.company.nopar.dto;

import com.company.nopar.entity.VehicleInspection;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * Datos que el cliente puede enviar al registrar una inspección por JSON.
 * Id, fecha, estado, conductor, vehículo y rutas de fotos los decide el servidor.
 */
public record VehicleInspectionRequest(
        @NotNull(message = "Los kilómetros son obligatorios")
        @Min(value = 0, message = "Los kilómetros no pueden ser negativos")
        Integer kilometers,

        Boolean tiresOk,
        Boolean lightsOk,
        Boolean beaconV16Present,
        String damagesReported,
        Boolean acceptedExclusiveUse,
        Boolean acceptedLiability,
        Boolean acceptedCleanliness
) {

    public VehicleInspection toEntity() {
        VehicleInspection inspection = new VehicleInspection();
        inspection.setKilometers(kilometers);
        inspection.setTiresOk(tiresOk);
        inspection.setLightsOk(lightsOk);
        inspection.setBeaconV16Present(beaconV16Present);
        inspection.setDamagesReported(damagesReported);
        inspection.setAcceptedExclusiveUse(acceptedExclusiveUse);
        inspection.setAcceptedLiability(acceptedLiability);
        inspection.setAcceptedCleanliness(acceptedCleanliness);
        return inspection;
    }
}
