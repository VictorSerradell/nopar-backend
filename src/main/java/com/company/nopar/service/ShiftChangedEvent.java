package com.company.nopar.service;

import com.company.nopar.entity.ShiftAssignment;

import java.time.LocalDate;
import java.time.LocalTime;

/**
 * Se publica cuando cambia el turno de un conductor: se le asigna, se modifica o se le quita (si el administrador no
 * pide hacerlo en silencio). Quien lo escucha, después de confirmarse la transacción, avisa a ese conductor por push.
 * Lleva los datos ya copiados y no la entidad: el aviso se envía en otro hilo, sin sesión de base de datos.
 */
public record ShiftChangedEvent(
        Change change,
        Long shiftId,
        Long driverId,
        LocalDate workDate,
        String waveName,
        LocalTime startTime,
        String licensePlate
) {

    public enum Change {
        /** Turno nuevo para el conductor (también el que recibe un turno que tenía otro). */
        ASSIGNED,
        /** El conductor sigue en el turno pero cambia el día, la ola o la furgoneta. */
        UPDATED,
        /** El conductor ya no tiene ese turno (eliminado, o pasado a otro conductor). */
        CANCELLED
    }

    /** El evento tal como está el turno ahora: hay que llamarlo con ola, conductor y furgoneta ya cargados. */
    static ShiftChangedEvent of(Change change, ShiftAssignment shift) {
        return new ShiftChangedEvent(change, shift.getId(), shift.getDriver().getId(), shift.getWorkDate(),
                shift.getWave().getName(), shift.getWave().getStartTime(), shift.getVehicle().getLicensePlate());
    }
}
