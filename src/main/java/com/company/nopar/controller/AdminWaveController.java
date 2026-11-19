package com.company.nopar.controller;

import com.company.nopar.dto.WaveRequest;
import com.company.nopar.dto.WaveResponse;
import com.company.nopar.service.ShiftService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Bajo /api/v1/admin: SecurityConfig lo reserva al rol ADMIN. */
@Tag(name = "Administración", description = "Operaciones reservadas al rol ADMIN")
@RestController
@RequestMapping("/api/v1/admin/waves")
@RequiredArgsConstructor
public class AdminWaveController {

    private final ShiftService shiftService;

    @Operation(summary = "Crea una ola", description = "Una franja de salida con su nombre y su hora (HH:mm).")
    @ApiResponse(responseCode = "409", description = "Ya existe una ola con ese nombre")
    @PostMapping
    public ResponseEntity<WaveResponse> createWave(@Valid @RequestBody WaveRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(WaveResponse.from(shiftService.createWave(request)));
    }

    @Operation(summary = "Lista las olas", description = "Ordenadas por hora de salida.")
    @GetMapping
    public ResponseEntity<List<WaveResponse>> getWaves() {
        return ResponseEntity.ok(WaveResponse.fromAll(shiftService.getWaves()));
    }

    @Operation(summary = "Elimina una ola")
    @ApiResponse(responseCode = "204", description = "Ola eliminada")
    @ApiResponse(responseCode = "404", description = "La ola no existe")
    @ApiResponse(responseCode = "409", description = "La ola tiene turnos asignados")
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteWave(@PathVariable Long id) {
        shiftService.deleteWave(id);
        return ResponseEntity.noContent().build();
    }
}
