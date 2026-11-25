package com.company.nopar.dto;

import com.company.nopar.entity.WaveCall;
import com.company.nopar.entity.WaveCallRecipient;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Vista de una llamada para quien la hace (administrador o encargado): a quién se llamó y quién ha confirmado.
 * No expone la entidad User (hash de contraseña, roles). open dice si la orden sigue vigente (los conductores aún
 * pueden confirmarla); pasado expiresAt, quien no confirmó se queda sin confirmar.
 *
 * recallCount dice a cuántos conductores llamaría «volver a llamar a los que faltan» ahora (los del turno de hoy en la
 * ola que no han confirmado, incluidos los que entraron en la ola después) y canRecall si se puede hacer: solo la última
 * llamada de cada ola de hoy, y solo si falta alguien.
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
        int recallCount,
        boolean canRecall,
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
        return from(call, LocalDateTime.now(), 0);
    }

    public static WaveCallResponse from(WaveCall call, LocalDateTime now) {
        return from(call, now, 0);
    }

    public static WaveCallResponse from(WaveCall call, LocalDateTime now, int recallCount) {
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
                recallCount,
                recallCount > 0,
                recipients);
    }

    /** recallCounts: cuántos llamaría «volver a llamar» por id de llamada; las llamadas que no están en el mapa no se pueden repetir. */
    public static List<WaveCallResponse> fromAll(List<WaveCall> calls, Map<Long, Integer> recallCounts) {
        LocalDateTime now = LocalDateTime.now();
        return calls.stream().map(call -> from(call, now, recallCounts.getOrDefault(call.getId(), 0))).toList();
    }
}
