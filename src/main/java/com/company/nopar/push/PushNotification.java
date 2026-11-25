package com.company.nopar.push;

import com.company.nopar.service.ShiftChangedEvent;
import com.company.nopar.service.WaveChatMessageEvent;
import com.company.nopar.service.WaveCalledEvent;

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

    static final String WAVE_CALL_TYPE = "wave_call";
    static final String CHAT_TYPE = "chat";
    /** Debe coincidir con PushChannel.WAVE_CALLS de la app Android. */
    static final String WAVE_CALL_CHANNEL = "nopar_wave_calls";

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

    /**
     * La orden de subir a una ola: "Ola 1: Subid" y qué hacer. En data, type = "wave_call" y waveCallId le dicen a la
     * app qué llamada mostrar (con su botón "Enterado") al abrir la notificación.
     */
    public static PushNotification forWaveCall(WaveCalledEvent event) {
        Map<String, String> data = new LinkedHashMap<>();
        data.put("type", WAVE_CALL_TYPE);
        data.put("waveCallId", event.waveCallId().toString());
        data.put("waveId", event.waveId().toString());
        data.put("workDate", event.workDate().toString());
        return new PushNotification(event.waveName() + ": " + event.message(),
                "Abre la app y pulsa «Enterado»", data);
    }

    /**
     * Un mensaje nuevo en el chat de una ola: la ola como título y, en el texto, quién escribe y qué (o «Foto» si solo
     * lleva una imagen). En data, type = "chat", waveId y waveName le dicen a la app qué chat abrir (y cómo titularlo); workDate y messageId, qué mensaje es.
     */
    public static PushNotification forChatMessage(WaveChatMessageEvent event) {
        boolean hasText = event.text() != null && !event.text().isBlank();
        String content = hasText ? (event.hasPhoto() ? "📷 " + event.text() : event.text()) : "📷 Foto";
        Map<String, String> data = new LinkedHashMap<>();
        data.put("type", CHAT_TYPE);
        data.put("waveId", event.waveId().toString());
        data.put("waveName", event.waveName());
        data.put("workDate", event.workDate().toString());
        data.put("messageId", event.messageId().toString());
        return new PushNotification(event.waveName(),
                abbreviate(event.senderUsername() + ": " + content, MAX_BODY_CHARS), data);
    }

    /**
     * Canal de notificaciones de Android en el que debe sonar esta notificación cuando la app está cerrada (con la app
     * abierta lo elige la propia app). La orden de ola va en su canal, de importancia alta y con vibración propia;
     * el resto, sin canal: Android usa el predeterminado de la app.
     */
    public String androidChannelId() {
        return WAVE_CALL_TYPE.equals(data.get("type")) ? WAVE_CALL_CHANNEL : null;
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
