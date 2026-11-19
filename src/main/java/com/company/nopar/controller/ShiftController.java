package com.company.nopar.controller;

import com.company.nopar.dto.ShiftResponse;
import com.company.nopar.entity.User;
import com.company.nopar.service.ShiftService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

@Tag(name = "Cuadrantes", description = "Olas y turnos: qué conductor sale en cada ola y con qué furgoneta")
@RestController
@RequestMapping("/api/v1/shifts")
@RequiredArgsConstructor
public class ShiftController {

    private final ShiftService shiftService;

    @Operation(summary = "Cuadrante de un rango de fechas",
            description = "Todos los turnos entre las dos fechas (ambas incluidas), ordenados por día, hora de la ola y conductor. Máximo 31 días.")
    @ApiResponse(responseCode = "400", description = "Falta una fecha, el formato no es yyyy-MM-dd, el rango está al revés o supera los 31 días")
    @GetMapping
    @PreAuthorize("hasAnyRole('DRIVER', 'ADMIN')")
    public ResponseEntity<List<ShiftResponse>> getShifts(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to
    ) {
        return ResponseEntity.ok(ShiftResponse.fromAll(shiftService.getShifts(from, to)));
    }

    @Operation(summary = "Mis turnos",
            description = "Los turnos del conductor autenticado entre las dos fechas (ambas incluidas). Solo DRIVER. Máximo 31 días.")
    @ApiResponse(responseCode = "400", description = "Falta una fecha, el formato no es yyyy-MM-dd, el rango está al revés o supera los 31 días")
    @GetMapping("/me")
    @PreAuthorize("hasRole('DRIVER')")
    public ResponseEntity<List<ShiftResponse>> getMyShifts(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @AuthenticationPrincipal User currentUser // el conductor sale del token JWT, no lo envía el cliente
    ) {
        return ResponseEntity.ok(ShiftResponse.fromAll(shiftService.getShiftsOfDriver(currentUser, from, to)));
    }
}
