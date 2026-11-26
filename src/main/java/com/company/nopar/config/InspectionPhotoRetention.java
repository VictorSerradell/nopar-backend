package com.company.nopar.config;

import com.company.nopar.service.InspectionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Cada noche borra las fotos de los partes de hace más de un año: ocupan casi todo el disco y, pasado ese tiempo, ya
 * no sirven para revisar un parte. El parte en sí (kilómetros, checklist, daños anotados, quién lo revisó) se conserva.
 *
 * Los días se configuran con application.inspection.photo-retention.days (INSPECTION_PHOTO_RETENTION_DAYS; 0 o menos
 * desactiva el borrado y lo conserva todo), y la hora y la zona con application.inspection.photo-retention.cron
 * (6 campos, segundos incluidos) y application.inspection.photo-retention.zone; con el cron "-" queda desactivado. Si
 * varias instancias lo ejecutan a la vez no pasa nada: lo ya borrado no se vuelve a borrar.
 */
@Slf4j
@Component
public class InspectionPhotoRetention {

    private final InspectionService inspectionService;
    private final int days;

    public InspectionPhotoRetention(
            InspectionService inspectionService,
            @Value("${application.inspection.photo-retention.days:365}") int days) {
        this.inspectionService = inspectionService;
        this.days = days;
    }

    @Scheduled(cron = "${application.inspection.photo-retention.cron:0 15 4 * * *}",
            zone = "${application.inspection.photo-retention.zone:Europe/Madrid}")
    public void deleteOldPhotos() {
        if (days < 1) {
            log.info("Retención de fotos de partes desactivada: se conservan todas");
            return;
        }
        // Se conservan los últimos «days» días completos: los partes anteriores a ese día pierden sus fotos
        LocalDateTime cutoff = LocalDate.now().minusDays(days).atStartOfDay();
        int cleared = inspectionService.deletePhotosBefore(cutoff);
        log.info("Retención de fotos de partes: {} parte(s) anteriores al {} se han quedado sin fotos",
                cleared, cutoff.toLocalDate());
    }
}
