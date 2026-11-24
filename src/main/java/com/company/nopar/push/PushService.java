package com.company.nopar.push;

import com.company.nopar.entity.User;
import com.company.nopar.repository.DeviceTokenRepository;
import com.company.nopar.service.DeviceService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Envía una notificación a los dispositivos de los conductores y borra los tokens que el proveedor da por no
 * registrados. A propósito no es transaccional: no se retiene una conexión de la base de datos mientras se espera a
 * la red.
 */
@Service
@RequiredArgsConstructor
public class PushService {

    private final DeviceTokenRepository deviceTokenRepository;
    private final DeviceService deviceService;
    private final PushSender pushSender;

    public void notifyDrivers(PushNotification notification) {
        List<String> tokens = deviceTokenRepository.findTokensByUserRole(User.Role.DRIVER);
        if (tokens.isEmpty()) {
            return;
        }
        List<String> unregistered = pushSender.send(notification, tokens);
        if (!unregistered.isEmpty()) {
            deviceService.removeInvalidTokens(unregistered);
        }
    }
}
