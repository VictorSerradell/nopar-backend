package com.company.nopar.service;

import com.company.nopar.entity.DeviceToken;
import com.company.nopar.entity.User;
import com.company.nopar.repository.DeviceTokenRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collection;

/** Dispositivos de cada usuario para las notificaciones push (token de FCM de cada instalación de la app). */
@Service
@RequiredArgsConstructor
public class DeviceService {

    /** Instalaciones por usuario (móvil, tablet, web...). Al pasar de ahí se descartan las menos recientes. */
    public static final int MAX_DEVICES_PER_USER = 10;

    private final DeviceTokenRepository deviceTokenRepository;

    /**
     * Registra el dispositivo del usuario. Es idempotente: la app puede llamarlo en cada inicio de sesión o cuando
     * FCM renueva el token. Si el token era de otro usuario (alguien más usó ese móvil) pasa a este.
     */
    @Transactional
    public void registerDevice(User user, String token, DeviceToken.Platform platform) {
        deviceTokenRepository.upsert(user.getId(), token.strip(), platform.name(), LocalDateTime.now());
        deviceTokenRepository.deleteOldestBeyond(user.getId(), MAX_DEVICES_PER_USER);
    }

    /** Borra los tokens que el proveedor de push ha dado por no registrados (app desinstalada, token caducado). */
    @Transactional
    public void removeInvalidTokens(Collection<String> tokens) {
        if (!tokens.isEmpty()) {
            deviceTokenRepository.deleteByTokenIn(tokens);
        }
    }

    /** Da de baja el dispositivo del usuario (al cerrar sesión). Si no existe, o es de otro usuario, no hace nada. */
    @Transactional
    public void unregisterDevice(User user, String token) {
        deviceTokenRepository.deleteByTokenAndUserId(token.strip(), user.getId());
    }
}
