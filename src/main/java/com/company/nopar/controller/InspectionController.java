package com.company.nopar.controller;

import com.company.nopar.dto.InspectionCreateRequest;
import com.company.nopar.dto.InspectionResponse;
import com.company.nopar.dto.PageResponse;
import com.company.nopar.entity.InspectionPhotoType;
import com.company.nopar.entity.User;
import com.company.nopar.entity.VehicleInspection;
import com.company.nopar.exception.ResourceNotFoundException;
import com.company.nopar.service.FileStorageService;
import com.company.nopar.service.InspectionService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.time.Duration;

@RestController
@RequestMapping("/api/v1/inspections")
@RequiredArgsConstructor
public class InspectionController {

    private final InspectionService inspectionService;

    /** Alta del parte de inspección: datos y checklist en la parte "data" (JSON) y las fotos como ficheros. */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAnyRole('DRIVER', 'ADMIN')")
    public ResponseEntity<InspectionResponse> createInspection(
            @Valid @RequestPart("data") InspectionCreateRequest request,
            @RequestPart("odometerPhoto") MultipartFile odometerPhoto,
            @RequestPart(value = "beaconPhoto", required = false) MultipartFile beaconPhoto,
            @RequestPart(value = "exteriorPhoto", required = false) MultipartFile exteriorPhoto,
            @AuthenticationPrincipal User currentUser // el conductor sale del token JWT, no lo envía el cliente
    ) {
        VehicleInspection savedInspection = inspectionService.createInspection(
                request, odometerPhoto, beaconPhoto, exteriorPhoto, currentUser
        );
        return ResponseEntity.status(HttpStatus.CREATED).body(InspectionResponse.from(savedInspection));
    }

    /**
     * Una foto del parte: odometer, beacon o exterior. La ven el conductor que hizo el parte y los administradores.
     * Se pide con la cabecera Authorization, así que un cliente web la descarga como blob en vez de usar
     * {@code <img src>} directamente. Es privada para la caché: solo la guarda el dispositivo de quien la pidió.
     */
    @GetMapping("/{id}/photos/{type}")
    @PreAuthorize("hasAnyRole('DRIVER', 'ADMIN')")
    public ResponseEntity<Resource> getPhoto(
            @PathVariable Long id,
            @PathVariable String type,
            @AuthenticationPrincipal User currentUser) {
        InspectionPhotoType photoType = InspectionPhotoType.fromSlug(type)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Tipo de foto no válido. Use odometer, beacon o exterior"));
        FileStorageService.StoredFile photo = inspectionService.getPhoto(id, photoType, currentUser);
        return ResponseEntity.ok()
                .contentType(photo.mediaType())
                .cacheControl(CacheControl.maxAge(Duration.ofHours(1)).cachePrivate())
                .body(photo.resource());
    }

    @GetMapping("/driver/me")
    @PreAuthorize("hasRole('DRIVER')")
    public ResponseEntity<PageResponse<InspectionResponse>> getMyInspections(
            @AuthenticationPrincipal User currentUser,
            @RequestParam(defaultValue = PageResponse.DEFAULT_PAGE)
            @Min(value = 0, message = PageResponse.PAGE_MIN_MESSAGE) int page,
            @RequestParam(defaultValue = PageResponse.DEFAULT_SIZE)
            @Min(value = 1, message = PageResponse.SIZE_MIN_MESSAGE)
            @Max(value = PageResponse.MAX_SIZE, message = PageResponse.SIZE_MAX_MESSAGE) int size) {
        return ResponseEntity.ok(PageResponse.of(
                inspectionService.getInspectionsByDriver(currentUser.getId(), page, size), InspectionResponse::from));
    }
}
