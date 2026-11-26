package com.company.nopar.dto;

import java.util.List;
import java.util.Map;

/** Los mensajes sin leer de hoy en el chat de una ola. Solo se envían las olas que tienen alguno. */
public record ChatUnreadResponse(Long waveId, long unread) {

    public static List<ChatUnreadResponse> fromMap(Map<Long, Long> unreadByWave) {
        return unreadByWave.entrySet().stream()
                .map(entry -> new ChatUnreadResponse(entry.getKey(), entry.getValue()))
                .toList();
    }
}
