package com.company.nopar.push;

import lombok.extern.slf4j.Slf4j;

import java.util.Collection;
import java.util.List;

/** Las notificaciones push están desactivadas: no se envía nada. */
@Slf4j
public class NoopPushSender implements PushSender {

    @Override
    public List<String> send(PushNotification notification, Collection<String> tokens) {
        log.debug("Push desactivado: no se envía «{}» a {} dispositivos", notification.title(), tokens.size());
        return List.of();
    }
}
