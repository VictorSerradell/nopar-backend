package com.company.nopar.push;

import com.company.nopar.entity.User;
import com.company.nopar.repository.DeviceTokenRepository;
import com.company.nopar.service.DeviceService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.List;

/**
 * Envía una notificación a los dispositivos de los conductores, o de uno solo, y borra los tokens que el proveedor
 * da por no registrados. A propósito no es transaccional: no se retiene una conexión de la base de datos mientras se espera a
 * la red.
 */
@Service
@RequiredArgsConstructor
public class PushService {

    private final DeviceTokenRepository deviceTokenRepository;
    private final DeviceService deviceService;
    private final PushSender pushSender;

    /** A todos los conductores (un aviso general). */
    public void notifyDrivers(PushNotification notification) {
        send(notification, deviceTokenRepository.findTokensByUserRole(User.Role.DRIVER));
    }

    /** A todos los dispositivos de un usuario (algo que le concierne solo a él, como su turno). */
    public void notifyUser(Long userId, PushNotification notification) {
        send(notification, deviceTokenRepository.findTokensByUserId(userId));
    }

    /** A todos los dispositivos de varios usuarios (algo que concierne a un grupo concreto, como una ola). */
    public void notifyUsers(Collection<Long> userIds, PushNotification notification) {
        if (userIds.isEmpty()) {
            return;
        }
        send(notification, deviceTokenRepository.findTokensByUserIdIn(userIds));
    }

    private void send(PushNotification notification, List<String> tokens) {
        if (tokens.isEmpty()) {
            return;
        }
        List<String> unregistered = pushSender.send(notification, tokens);
        if (!unregistered.isEmpty()) {
            deviceService.removeInvalidTokens(unregistered);
        }
    }
}
