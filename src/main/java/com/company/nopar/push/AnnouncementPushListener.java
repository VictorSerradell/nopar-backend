package com.company.nopar.push;

import com.company.nopar.service.AnnouncementPublishedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Avisa a los conductores cuando se publica un aviso. Se ejecuta cuando la transacción del aviso ya se ha confirmado
 * (si se deshiciera, nadie recibiría el aviso de algo que no existe) y en otro hilo, para que el administrador no
 * espere a Firebase. Un fallo aquí solo se anota en el log: el aviso ya está publicado.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AnnouncementPushListener {

    private final PushService pushService;

    @Async("applicationTaskExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onAnnouncementPublished(AnnouncementPublishedEvent event) {
        try {
            pushService.notifyDrivers(
                    PushNotification.forAnnouncement(event.announcementId(), event.title(), event.body()));
        } catch (RuntimeException e) {
            log.warn("No se pudo enviar la notificación del aviso {}: {}", event.announcementId(), e.toString());
        }
    }
}
