package com.company.nopar.controller;

import com.company.nopar.dto.ShiftCreateRequest;
import com.company.nopar.dto.ShiftResponse;
import com.company.nopar.dto.ShiftUpdateRequest;
import com.company.nopar.entity.User;
import com.company.nopar.service.ShiftService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Bajo /api/v1/admin: SecurityConfig lo reserva al rol ADMIN. */
@Tag(name = "Administración", description = "Operaciones reservadas al rol ADMIN")
@RestController
@RequestMapping("/api/v1/admin/shifts")
@RequiredArgsConstructor
public class AdminShiftController {

    private final ShiftService shiftService;

    @Operation(summary = "Asigna un turno",
            description = "Conductor y furgoneta de una ola en un día. El usuario tiene que ser DRIVER, y ni él ni la furgoneta pueden tener otro turno ese día.")
    @ApiResponse(responseCode = "400", description = "Datos no válidos, o el usuario no es un conductor")
    @ApiResponse(responseCode = "404", description = "La ola, el conductor o el vehículo no existen")
    @ApiResponse(responseCode = "409", description = "El conductor o la furgoneta ya tienen un turno ese día")
    @PostMapping
    public ResponseEntity<ShiftResponse> createShift(
            @Valid @RequestBody ShiftCreateRequest request,
            @AuthenticationPrincipal User currentUser
    ) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ShiftResponse.from(shiftService.createShift(request, currentUser)));
    }

    @Operation(summary = "Modifica un turno",
            description = "Solo cambian los campos enviados. Hay que enviar al menos uno.")
    @ApiResponse(responseCode = "400", description = "No se envió ningún cambio, o el usuario no es un conductor")
    @ApiResponse(responseCode = "404", description = "El turno, la ola, el conductor o el vehículo no existen")
    @ApiResponse(responseCode = "409", description = "El conductor o la furgoneta ya tienen un turno ese día")
    @PatchMapping("/{id}")
    public ResponseEntity<ShiftResponse> updateShift(
            @PathVariable Long id,
            @RequestBody ShiftUpdateRequest request
    ) {
        return ResponseEntity.ok(ShiftResponse.from(shiftService.updateShift(id, request)));
    }

    @Operation(summary = "Elimina un turno")
    @ApiResponse(responseCode = "204", description = "Turno eliminado")
    @ApiResponse(responseCode = "404", description = "El turno no existe")
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteShift(@PathVariable Long id) {
        shiftService.deleteShift(id);
        return ResponseEntity.noContent().build();
    }
}
