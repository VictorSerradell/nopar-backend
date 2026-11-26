package com.company.nopar.config;

import com.company.nopar.service.InspectionService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class InspectionPhotoRetentionTest {

    @Mock
    private InspectionService inspectionService;

    @Test
    void givenOneYear_whenTheNightlyJobRuns_thenPhotosOfInspectionsBeforeThatDayAreDeleted() {
        LocalDateTime cutoff = LocalDate.now().minusDays(365).atStartOfDay();
        when(inspectionService.deletePhotosBefore(cutoff)).thenReturn(7);

        new InspectionPhotoRetention(inspectionService, 365).deleteOldPhotos();

        verify(inspectionService).deletePhotosBefore(cutoff);
    }

    @Test
    void givenZeroOrNegativeDays_whenTheNightlyJobRuns_thenNothingIsDeleted() {
        new InspectionPhotoRetention(inspectionService, 0).deleteOldPhotos();
        new InspectionPhotoRetention(inspectionService, -5).deleteOldPhotos();

        verifyNoInteractions(inspectionService);
    }
}
