package com.company.nopar.config;

import com.company.nopar.service.RefreshTokenService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Cada noche borra los refresh tokens caducados, que de otro modo se acumularían en la tabla.
 *
 * Hora y zona se configuran con application.security.refresh-token-cleanup.cron (6 campos, segundos incluidos)
 * y application.security.refresh-token-cleanup.zone; con el cron "-" queda desactivado. Si varias instancias
 * de la aplicación lo ejecutan a la vez no pasa nada: borrar lo ya borrado no hace nada.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ExpiredRefreshTokenCleanup {

    private final RefreshTokenService refreshTokenService;

    @Scheduled(cron = "${application.security.refresh-token-cleanup.cron:0 30 3 * * *}",
            zone = "${application.security.refresh-token-cleanup.zone:Europe/Madrid}")
    public void deleteExpiredTokens() {
        int deleted = refreshTokenService.deleteExpiredTokens();
        log.info("Limpieza de refresh tokens: {} caducado(s) eliminado(s)", deleted);
    }
}
