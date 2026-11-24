package com.company.nopar.service;

/**
 * Se publica al crear un aviso (si el administrador no pide publicarlo en silencio). Quien lo escucha, después de
 * confirmarse la transacción, envía la notificación push a los conductores.
 */
public record AnnouncementPublishedEvent(Long announcementId, String title, String body) {
}
