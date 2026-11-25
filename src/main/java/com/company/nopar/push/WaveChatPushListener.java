package com.company.nopar.push;

import com.company.nopar.service.WaveChatMessageEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Avisa a los miembros del chat de una ola cuando alguien escribe. Como con los avisos, los turnos y las llamadas, se
 * ejecuta cuando la transacción ya se ha confirmado (no se avisa de un mensaje que no se guardó) y en otro hilo, para
 * que quien escribe no espere a Firebase. Un fallo aquí solo se anota en el log: el mensaje ya está guardado y los
 * demás lo ven al abrir el chat.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WaveChatPushListener {

    private final PushService pushService;

    @Async("applicationTaskExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onMessage(WaveChatMessageEvent event) {
        try {
            pushService.notifyUsers(event.recipientIds(), PushNotification.forChatMessage(event));
        } catch (RuntimeException e) {
            log.warn("No se pudo enviar la notificación del mensaje {}: {}", event.messageId(), e.toString());
        }
    }
}
