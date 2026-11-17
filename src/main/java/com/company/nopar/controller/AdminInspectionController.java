package com.company.nopar.controller;

import com.company.nopar.dto.InspectionResponse;
import com.company.nopar.entity.VehicleInspection;
import com.company.nopar.service.InspectionService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/admin/inspections")
@RequiredArgsConstructor
public class AdminInspectionController {

    private final InspectionService inspectionService;

    @GetMapping
    public ResponseEntity<List<InspectionResponse>> getAllInspections() {
        List<VehicleInspection> inspections = inspectionService.getAllInspections();
        return ResponseEntity.ok(InspectionResponse.fromAll(inspections));
    }
}