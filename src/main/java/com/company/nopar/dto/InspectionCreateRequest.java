package com.company.nopar.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * Parte de inspección completo (parte "data" del multipart): kilómetros, checklist y aceptaciones.
 * El checklist es obligatorio: cada punto se responde expresamente con true o false.
 * Conductor, estado, fecha y rutas de las fotos los decide el servidor.
 */
public record InspectionCreateRequest(
        @NotNull(message = "El ID del vehículo es obligatorio")
        Long vehicleId,

        @NotNull(message = "Los kilómetros son obligatorios")
        @Min(value = 0, message = "Los kilómetros no pueden ser negativos")
        Integer kilometers,

        @NotNull(message = "Debe indicar si los neumáticos están correctos")
        Boolean tiresOk,

        @NotNull(message = "Debe indicar si las luces están correctas")
        Boolean lightsOk,

        @NotNull(message = "Debe indicar si la baliza V16 está presente")
        Boolean beaconV16Present,

        String damagesReported,

        @NotNull(message = "Debe aceptar el uso exclusivo laboral")
        @AssertTrue(message = "Debe aceptar las condiciones de uso exclusivo")
        Boolean acceptedExclusiveUse,

        @NotNull(message = "Debe aceptar la responsabilidad sobre el vehículo")
        @AssertTrue(message = "Debe aceptar la responsabilidad de daños por negligencia")
        Boolean acceptedLiability,

        @NotNull(message = "Debe aceptar el compromiso de limpieza")
        @AssertTrue(message = "Debe aceptar el compromiso de limpieza")
        Boolean acceptedCleanliness
) {}
