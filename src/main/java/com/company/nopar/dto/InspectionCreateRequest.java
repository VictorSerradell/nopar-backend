package com.company.nopar.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record InspectionCreateRequest(
        @NotNull(message = "El ID del vehículo es obligatorio")
        Long vehicleId,

        @NotNull(message = "Los kilómetros son obligatorios")
        @Min(value = 0, message = "Los kilómetros no pueden ser negativos")
        Integer kilometers,

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