package com.company.nopar.controller;

import com.company.nopar.dto.InspectionResponse;
import com.company.nopar.dto.InspectionReviewRequest;
import com.company.nopar.dto.PageResponse;
import com.company.nopar.entity.User;
import com.company.nopar.entity.VehicleInspection;
import com.company.nopar.service.InspectionService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Bajo /api/v1/admin: SecurityConfig lo reserva al rol ADMIN. */
@RestController
@RequestMapping("/api/v1/admin/inspections")
@RequiredArgsConstructor
public class AdminInspectionController {

    private final InspectionService inspectionService;

    @GetMapping
    public ResponseEntity<PageResponse<InspectionResponse>> getAllInspections(
            @RequestParam(defaultValue = PageResponse.DEFAULT_PAGE)
            @Min(value = 0, message = PageResponse.PAGE_MIN_MESSAGE) int page,
            @RequestParam(defaultValue = PageResponse.DEFAULT_SIZE)
            @Min(value = 1, message = PageResponse.SIZE_MIN_MESSAGE)
            @Max(value = PageResponse.MAX_SIZE, message = PageResponse.SIZE_MAX_MESSAGE) int size) {
        return ResponseEntity.ok(PageResponse.of(
                inspectionService.getAllInspections(page, size), InspectionResponse::from));
    }

    /** Aprueba o rechaza una inspección pendiente (SUBMITTED). Queda constancia de quién y cuándo. */
    @PatchMapping("/{id}/review")
    public ResponseEntity<InspectionResponse> reviewInspection(
            @PathVariable Long id,
            @Valid @RequestBody InspectionReviewRequest request,
            @AuthenticationPrincipal User currentAdmin
    ) {
        VehicleInspection reviewed = inspectionService.reviewInspection(id, request.decision(), currentAdmin);
        return ResponseEntity.ok(InspectionResponse.from(reviewed));
    }
}
