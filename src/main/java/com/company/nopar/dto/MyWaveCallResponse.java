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
        LocalDateTime acknowledgedAt
) {

    public static MyWaveCallResponse from(WaveCallRecipient recipient) {
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
                recipient.getAcknowledgedAt());
    }

    public static List<MyWaveCallResponse> fromAll(List<WaveCallRecipient> recipients) {
        return recipients.stream().map(MyWaveCallResponse::from).toList();
    }
}
