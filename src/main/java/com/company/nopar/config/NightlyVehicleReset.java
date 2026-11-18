package com.company.nopar.config;

import com.company.nopar.service.VehicleService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Red de seguridad: cada noche devuelve a AVAILABLE los vehículos que siguen IN_USE porque nadie los devolvió.
 *
 * Hora y zona se configuran con application.fleet.nightly-reset.cron (6 campos, segundos incluidos) y
 * application.fleet.nightly-reset.zone; con el cron "-" queda desactivado. Si varias instancias de la
 * aplicación lo ejecutan a la vez no pasa nada: la operación es idempotente.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NightlyVehicleReset {

    private final VehicleService vehicleService;

    @Scheduled(cron = "${application.fleet.nightly-reset.cron:0 0 4 * * *}",
            zone = "${application.fleet.nightly-reset.zone:Europe/Madrid}")
    public void releaseVehiclesInUse() {
        int released = vehicleService.releaseVehiclesInUse();
        log.info("Reseteo nocturno: {} vehículo(s) que seguían en uso vuelven a AVAILABLE", released);
    }
}
