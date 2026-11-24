package com.company.nopar.controller;

import com.company.nopar.dto.MyWaveCallResponse;
import com.company.nopar.entity.User;
import com.company.nopar.service.WaveCallService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

@Tag(name = "Llamadas de ola", description = "Dar la orden de subir a una ola (administradores y encargados) y confirmarla con «Enterado» (conductores)")
@RestController
@RequestMapping("/api/v1/wave-calls")
@RequiredArgsConstructor
public class WaveCallController {

    private final WaveCallService waveCallService;

    @Operation(summary = "Mis llamadas",
            description = "Las llamadas de ola que ha recibido el conductor autenticado, con si ya confirmó cada una; la más reciente primero. Sin fecha, las de hoy. Solo DRIVER: la app lo pide al abrirse por si no vio la notificación.")
    @ApiResponse(responseCode = "400", description = "El formato de la fecha no es yyyy-MM-dd")
    @GetMapping("/me")
    @PreAuthorize("hasRole('DRIVER')")
    public ResponseEntity<List<MyWaveCallResponse>> getMyCalls(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @AuthenticationPrincipal User currentUser // el conductor sale del token JWT, no lo envía el cliente
    ) {
        LocalDate day = date != null ? date : LocalDate.now();
        return ResponseEntity.ok(MyWaveCallResponse.fromAll(waveCallService.getCallsOfDriver(currentUser, day)));
    }

    @Operation(summary = "Enterado",
            description = "El conductor confirma que ha visto la orden. Solo vale para los conductores a los que se llamó. Confirmar otra vez devuelve la confirmación de la primera vez.")
    @ApiResponse(responseCode = "403", description = "No eres uno de los conductores a los que se llamó")
    @ApiResponse(responseCode = "404", description = "La llamada no existe")
    @PostMapping("/{id}/acknowledge")
    @PreAuthorize("hasRole('DRIVER')")
    public ResponseEntity<MyWaveCallResponse> acknowledge(
            @PathVariable Long id,
            @AuthenticationPrincipal User currentUser
    ) {
        return ResponseEntity.ok(MyWaveCallResponse.from(waveCallService.acknowledge(id, currentUser)));
    }
}
