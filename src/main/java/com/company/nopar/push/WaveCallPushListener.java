package com.company.nopar.push;

import com.company.nopar.service.WaveCalledEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Avisa a los conductores de una ola cuando se da la orden de subir. Igual que con los avisos y los turnos, se
 * ejecuta cuando la transacción ya se ha confirmado (no se avisa de una llamada que se deshizo) y en otro hilo, para
 * que quien llama no espere a Firebase. Un fallo aquí solo se anota en el log: la llamada ya está guardada y los
 * conductores la ven al abrir la app.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WaveCallPushListener {

    private final PushService pushService;

    @Async("applicationTaskExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onWaveCalled(WaveCalledEvent event) {
        try {
            pushService.notifyUsers(event.driverIds(), PushNotification.forWaveCall(event));
        } catch (RuntimeException e) {
            log.warn("No se pudo enviar la notificación de la llamada {}: {}", event.waveCallId(), e.toString());
        }
    }
}
