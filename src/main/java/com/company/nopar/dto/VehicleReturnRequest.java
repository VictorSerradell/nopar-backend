package com.company.nopar.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Devolución del vehículo al acabar el turno: kilómetros finales y, si los hay, los daños observados.
 * Quién devuelve, cuándo y el estado resultante los decide el servidor.
 */
public record VehicleReturnRequest(
        @NotNull(message = "Los kilómetros son obligatorios")
        @Min(value = 0, message = "Los kilómetros no pueden ser negativos")
        Integer kilometers,

        @Size(max = 2000, message = "La descripción de los daños no puede superar los 2000 caracteres")
        String damagesReported
) {}
