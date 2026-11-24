package com.company.nopar.push;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Una notificación push: lo que ve el usuario (título y texto) y los datos que la app usa al abrirla (por ejemplo, a
 * qué aviso ir). Los valores de data son siempre texto: es lo que admite FCM.
 */
public record PushNotification(String title, String body, Map<String, String> data) {

    /** Un texto largo no cabe en la notificación: el resto se lee en la app. */
    static final int MAX_BODY_CHARS = 140;

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
