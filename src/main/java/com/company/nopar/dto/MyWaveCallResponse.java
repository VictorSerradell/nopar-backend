package com.company.nopar.dto;

import com.company.nopar.entity.WaveCall;
import com.company.nopar.entity.WaveCallRecipient;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

/** Vista de una llamada para el conductor al que se llamó: qué se ordena, con qué furgoneta y si ya confirmó. */
public record MyWaveCallResponse(
        Long id,
        Long waveId,
        String waveName,
        LocalTime waveStartTime,
        LocalDate workDate,
        String message,
        String calledByUsername,
        LocalDateTime calledAt,
        String vehicleLicensePlate,
        boolean acknowledged,
        LocalDateTime acknowledgedAt,
        LocalDateTime expiresAt,
        boolean canAcknowledge
) {

    public static MyWaveCallResponse from(WaveCallRecipient recipient) {
        return from(recipient, LocalDateTime.now());
    }

    /**
     * canAcknowledge es lo que decide si la app enseña el botón «Enterado»: la orden aún no se ha confirmado y sigue
     * vigente en ese momento (lo calcula el servidor, para no depender del reloj del móvil).
     */
    public static MyWaveCallResponse from(WaveCallRecipient recipient, LocalDateTime now) {
        WaveCall call = recipient.getWaveCall();
        return new MyWaveCallResponse(
                call.getId(),
                call.getWave().getId(),
                call.getWave().getName(),
                call.getWave().getStartTime(),
                call.getWorkDate(),
                call.getMessage(),
                call.getCalledBy().getUsername(),
                call.getCalledAt(),
                recipient.getVehicleLicensePlate(),
                recipient.isAcknowledged(),
                recipient.getAcknowledgedAt(),
                call.getExpiresAt(),
                !recipient.isAcknowledged() && call.isOpenAt(now));
    }

    public static List<MyWaveCallResponse> fromAll(List<WaveCallRecipient> recipients) {
        LocalDateTime now = LocalDateTime.now();
        return recipients.stream().map(recipient -> from(recipient, now)).toList();
    }
}
