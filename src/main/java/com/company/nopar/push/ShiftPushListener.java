package com.company.nopar.push;

import com.company.nopar.service.ShiftChangedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Avisa a un conductor cuando le asignan, le cambian o le quitan un turno. Igual que con los avisos generales, se
 * ejecuta cuando la transacción ya se ha confirmado (no se avisa de un turno que se deshizo) y en otro hilo, para
 * que el administrador no espere a Firebase. Un fallo aquí solo se anota en el log: el turno ya está guardado.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ShiftPushListener {

    private final PushService pushService;

    @Async("applicationTaskExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onShiftChanged(ShiftChangedEvent event) {
        try {
            pushService.notifyUser(event.driverId(), PushNotification.forShift(event));
        } catch (RuntimeException e) {
            log.warn("No se pudo enviar la notificación del turno {}: {}", event.shiftId(), e.toString());
        }
    }
}
