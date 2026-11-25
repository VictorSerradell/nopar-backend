package com.company.nopar.service;

import java.time.LocalDate;
import java.util.List;

/**
 * Se publica cuando alguien escribe en el chat de una ola. Quien lo escucha, después de confirmarse la transacción,
 * avisa por push a los demás miembros del chat. Lleva los datos ya copiados y no las entidades: el aviso se envía en
 * otro hilo, sin sesión de base de datos. {@code text} es null si el mensaje es solo una foto.
 */
public record WaveChatMessageEvent(
        Long messageId,
        Long waveId,
        String waveName,
        LocalDate workDate,
        String senderUsername,
        String text,
        boolean hasPhoto,
        List<Long> recipientIds
) {
}
