package com.company.nopar.controller;

import com.company.nopar.dto.InspectionCreateRequest;
import com.company.nopar.dto.InspectionResponse;
import com.company.nopar.entity.User;
import com.company.nopar.entity.VehicleInspection;
import com.company.nopar.service.InspectionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
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
}