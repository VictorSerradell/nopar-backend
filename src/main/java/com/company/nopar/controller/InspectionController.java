package com.company.nopar.controller;

import com.company.nopar.dto.InspectionCreateRequest;
import com.company.nopar.entity.VehicleInspection;
import com.company.nopar.service.InspectionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/inspections")
@RequiredArgsConstructor
@Validated
public class InspectionController {

    private final InspectionService inspectionService;

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<VehicleInspection> createInspection(
            @Valid @RequestPart("data") InspectionCreateRequest request,
            @RequestPart("odometerPhoto") MultipartFile odometerPhoto,
            @RequestPart(value = "beaconPhoto", required = false) MultipartFile beaconPhoto,
            @RequestPart(value = "exteriorPhoto", required = false) MultipartFile exteriorPhoto,
            @RequestParam(defaultValue = "1") Long userId // Temporal hasta integrar el login con JWT completo
    ) {
        VehicleInspection savedInspection = inspectionService.createInspection(
                request, odometerPhoto, beaconPhoto, exteriorPhoto, userId
        );
        return ResponseEntity.status(HttpStatus.CREATED).body(savedInspection);
    }
}