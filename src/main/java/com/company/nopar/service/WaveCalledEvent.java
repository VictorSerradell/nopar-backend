package com.company.nopar.service;

import java.time.LocalDate;
import java.util.List;

/**
 * Se publica cuando un administrador o un encargado llama a una ola ("Ola 1, subid"). Quien lo escucha, después de
 * confirmarse la transacción, avisa por push a los conductores de esa ola. Lleva los datos ya copiados y no las
 * entidades: el aviso se envía en otro hilo, sin sesión de base de datos.
 */
public record WaveCalledEvent(
        Long waveCallId,
        Long waveId,
        String waveName,
        LocalDate workDate,
        String message,
        List<Long> driverIds
) {
}
