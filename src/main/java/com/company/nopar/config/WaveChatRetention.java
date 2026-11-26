package com.company.nopar.config;

import com.company.nopar.service.WaveChatService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

/**
 * Cada noche borra los mensajes del chat de las olas, y sus fotos, de hace más de unos días: el chat es del día y, sin
 * esto, los mensajes y las fotos se acumularían para siempre en la base de datos y en el disco.
 *
 * Los días se configuran con application.chat.retention.days (CHAT_RETENTION_DAYS; 0 o menos desactiva el borrado y
 * lo conserva todo), y la hora y la zona con application.chat.retention.cron (6 campos, segundos incluidos) y
 * application.chat.retention.zone; con el cron "-" queda desactivado. Si varias instancias de la aplicación lo
 * ejecutan a la vez no pasa nada: borrar lo ya borrado no hace nada.
 */
@Slf4j
@Component
public class WaveChatRetention {

    private final WaveChatService chatService;
    private final int days;

    public WaveChatRetention(WaveChatService chatService, @Value("${application.chat.retention.days:30}") int days) {
        this.chatService = chatService;
        this.days = days;
    }

    @Scheduled(cron = "${application.chat.retention.cron:0 45 3 * * *}",
            zone = "${application.chat.retention.zone:Europe/Madrid}")
    public void deleteOldMessages() {
        if (days < 1) {
            log.info("Retención del chat desactivada: se conservan todos los mensajes");
            return;
        }
        // Se conservan los últimos «days» días: lo anterior a ese día se borra
        LocalDate cutoff = LocalDate.now().minusDays(days);
        int deleted = chatService.deleteMessagesBefore(cutoff);
        log.info("Retención del chat: {} mensaje(s) anteriores al {} eliminados", deleted, cutoff);
    }
}
