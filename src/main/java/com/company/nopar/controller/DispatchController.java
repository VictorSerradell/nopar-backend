package com.company.nopar.controller;

import com.company.nopar.dto.WaveCallRequest;
import com.company.nopar.dto.WaveCallResponse;
import com.company.nopar.dto.WaveResponse;
import com.company.nopar.entity.User;
import com.company.nopar.service.ShiftService;
import com.company.nopar.service.WaveCallService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/**
 * Dar la orden de subir a una ola y ver quién la ha confirmado. Para administradores y encargados: SecurityConfig
 * reserva /api/v1/dispatch a esos dos roles.
 */
@Tag(name = "Llamadas de ola", description = "Dar la orden de subir a una ola (administradores y encargados) y confirmarla con «Enterado» (conductores)")
@RestController
@RequestMapping("/api/v1/dispatch")
@PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
@RequiredArgsConstructor
public class DispatchController {

    private final WaveCallService waveCallService;
    private final ShiftService shiftService;

    @Operation(summary = "Lista las olas", description = "Ordenadas por hora de salida: para saber a cuál llamar.")
    @GetMapping("/waves")
    public ResponseEntity<List<WaveResponse>> getWaves() {
        return ResponseEntity.ok(WaveResponse.fromAll(shiftService.getWaves()));
    }

    @Operation(summary = "Llama a una ola",
            description = "Da la orden (por defecto «Subid», o el texto del cuerpo) a los conductores que hoy tienen turno en la ola y les avisa por push; cada uno confirma con «Enterado». Se puede llamar a la misma ola varias veces el mismo día.")
    @ApiResponse(responseCode = "400", description = "El mensaje supera los 140 caracteres")
    @ApiResponse(responseCode = "404", description = "La ola no existe")
    @ApiResponse(responseCode = "409", description = "Hoy nadie tiene turno en esa ola")
    @PostMapping("/waves/{waveId}/calls")
    public ResponseEntity<WaveCallResponse> callWave(
            @PathVariable Long waveId,
            @Valid @RequestBody(required = false) WaveCallRequest request,
            @AuthenticationPrincipal User currentUser // quien llama sale del token JWT
    ) {
        String message = request != null ? request.message() : null;
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(WaveCallResponse.from(waveCallService.callWave(waveId, message, currentUser)));
    }

    @Operation(summary = "Llamadas de un día",
            description = "Con a quién se llamó y quién ha confirmado, la más reciente primero. Sin fecha, las de hoy.")
    @ApiResponse(responseCode = "400", description = "El formato de la fecha no es yyyy-MM-dd")
    @GetMapping("/wave-calls")
    public ResponseEntity<List<WaveCallResponse>> getCalls(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date
    ) {
        LocalDate day = date != null ? date : LocalDate.now();
        return ResponseEntity.ok(WaveCallResponse.fromAll(waveCallService.getCalls(day)));
    }

    @Operation(summary = "Una llamada", description = "A quién se llamó y quién ha confirmado.")
    @ApiResponse(responseCode = "404", description = "La llamada no existe")
    @GetMapping("/wave-calls/{id}")
    public ResponseEntity<WaveCallResponse> getCall(@PathVariable Long id) {
        return ResponseEntity.ok(WaveCallResponse.from(waveCallService.getCall(id)));
    }
}
