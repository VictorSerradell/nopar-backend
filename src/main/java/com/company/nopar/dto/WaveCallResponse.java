package com.company.nopar.dto;

import com.company.nopar.entity.WaveCall;
import com.company.nopar.entity.WaveCallRecipient;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Comparator;
import java.util.List;

/**
 * Vista de una llamada para quien la hace (administrador o encargado): a quién se llamó y quién ha confirmado.
 * No expone la entidad User (hash de contraseña, roles). open dice si la orden sigue vigente (los conductores aún
 * pueden confirmarla); pasado expiresAt, quien no confirmó se queda sin confirmar.
 */
public record WaveCallResponse(
        Long id,
        Long waveId,
        String waveName,
        LocalTime waveStartTime,
        LocalDate workDate,
        String message,
        String calledByUsername,
        LocalDateTime calledAt,
        LocalDateTime expiresAt,
        boolean open,
        int recipientCount,
        int acknowledgedCount,
        List<Recipient> recipients
) {

    /** Un conductor al que se llamó, con su furgoneta y si ya ha confirmado ("Enterado"). */
    public record Recipient(
            Long driverId,
            String driverUsername,
            String vehicleLicensePlate,
            boolean acknowledged,
            LocalDateTime acknowledgedAt
    ) {
        static Recipient from(WaveCallRecipient recipient) {
            return new Recipient(
                    recipient.getDriver().getId(),
                    recipient.getDriver().getUsername(),
                    recipient.getVehicleLicensePlate(),
                    recipient.isAcknowledged(),
                    recipient.getAcknowledgedAt());
        }
    }

    public static WaveCallResponse from(WaveCall call) {
        return from(call, LocalDateTime.now());
    }

    public static WaveCallResponse from(WaveCall call, LocalDateTime now) {
        List<Recipient> recipients = call.getRecipients().stream()
                .map(Recipient::from)
                .sorted(Comparator.comparing(Recipient::driverUsername, String.CASE_INSENSITIVE_ORDER))
                .toList();
        return new WaveCallResponse(
                call.getId(),
                call.getWave().getId(),
                call.getWave().getName(),
                call.getWave().getStartTime(),
                call.getWorkDate(),
                call.getMessage(),
                call.getCalledBy().getUsername(),
                call.getCalledAt(),
                call.getExpiresAt(),
                call.isOpenAt(now),
                recipients.size(),
                (int) recipients.stream().filter(Recipient::acknowledged).count(),
                recipients);
    }

    public static List<WaveCallResponse> fromAll(List<WaveCall> calls) {
        LocalDateTime now = LocalDateTime.now();
        return calls.stream().map(call -> from(call, now)).toList();
    }
}
