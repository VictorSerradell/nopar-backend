package com.company.nopar.push;

import com.company.nopar.service.ShiftChangedEvent;

import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Una notificación push: lo que ve el usuario (título y texto) y los datos que la app usa al abrirla (por ejemplo, a
 * qué aviso ir). Los valores de data son siempre texto: es lo que admite FCM.
 */
public record PushNotification(String title, String body, Map<String, String> data) {

    /** Un texto largo no cabe en la notificación: el resto se lee en la app. */
    static final int MAX_BODY_CHARS = 140;

    private static final Locale SPANISH = Locale.forLanguageTag("es-ES");
    private static final DateTimeFormatter DAY_FORMAT = DateTimeFormatter.ofPattern("EEEE d 'de' MMMM", SPANISH);
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm");

    public PushNotification {
        Objects.requireNonNull(title, "title");
        body = body == null ? "" : body;
        data = data == null ? Map.of() : Map.copyOf(data);
    }

    /** La notificación de un aviso nuevo: su título, el principio de su texto y su id para abrirlo desde la app. */
    public static PushNotification forAnnouncement(Long announcementId, String title, String body) {
        Map<String, String> data = new LinkedHashMap<>();
        data.put("type", "announcement");
        if (announcementId != null) {
            data.put("announcementId", announcementId.toString());
        }
        return new PushNotification(title, abbreviate(body, MAX_BODY_CHARS), data);
    }

    /**
     * La notificación de un cambio en el turno del conductor: qué ha pasado (nuevo, modificado, cancelado) y el día,
     * la ola y la furgoneta. En data, type = "shift", change (assigned, updated o cancelled) y workDate le dicen a la
     * app qué día abrir; shiftId va si se conoce (en un turno cancelado puede que ya no exista).
     */
    public static PushNotification forShift(ShiftChangedEvent event) {
        String title = switch (event.change()) {
            case ASSIGNED -> "Nuevo turno";
            case UPDATED -> "Turno modificado";
            case CANCELLED -> "Turno cancelado";
        };
        String day = DAY_FORMAT.format(event.workDate());
        StringBuilder body = new StringBuilder(day.substring(0, 1).toUpperCase(SPANISH)).append(day.substring(1))
                .append(" · ").append(event.waveName())
                .append(" (").append(TIME_FORMAT.format(event.startTime())).append(")");
        if (event.change() != ShiftChangedEvent.Change.CANCELLED) {
            body.append(" · furgoneta ").append(event.licensePlate());
        }

        Map<String, String> data = new LinkedHashMap<>();
        data.put("type", "shift");
        data.put("change", event.change().name().toLowerCase(Locale.ROOT));
        data.put("workDate", event.workDate().toString());
        if (event.shiftId() != null) {
            data.put("shiftId", event.shiftId().toString());
        }
        return new PushNotification(title, abbreviate(body.toString(), MAX_BODY_CHARS), data);
    }

    /** El texto en una sola línea y, si pasa de maxCodePoints caracteres, cortado y terminado en «…». */
    static String abbreviate(String text, int maxCodePoints) {
        String oneLine = text == null ? "" : text.strip().replaceAll("\\s+", " ");
        if (oneLine.codePointCount(0, oneLine.length()) <= maxCodePoints) {
            return oneLine;
        }
        int end = oneLine.offsetByCodePoints(0, maxCodePoints - 1);
        return oneLine.substring(0, end).stripTrailing() + "…";
    }
}
