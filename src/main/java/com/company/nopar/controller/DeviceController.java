package com.company.nopar.controller;

import com.company.nopar.dto.DeviceRegistrationRequest;
import com.company.nopar.entity.User;
import com.company.nopar.service.DeviceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Dispositivos", description = "Registro de dispositivos para las notificaciones push")
@RestController
@RequestMapping("/api/v1/devices")
@RequiredArgsConstructor
public class DeviceController {

    private final DeviceService deviceService;

    @Operation(summary = "Registra el dispositivo para recibir notificaciones push",
            description = "La app envía el token de Firebase Cloud Messaging y su plataforma (ANDROID, IOS o WEB) después de iniciar sesión, y otra vez cuando FCM renueva el token. Es idempotente. Si el token era de otro usuario, pasa al usuario autenticado. Cada usuario conserva como máximo 10 dispositivos: al registrar más se descartan los menos recientes.")
    @ApiResponse(responseCode = "204", description = "Dispositivo registrado")
    @ApiResponse(responseCode = "400", description = "Token vacío o demasiado largo, o plataforma no válida")
    @PostMapping
    @PreAuthorize("hasAnyRole('DRIVER', 'ADMIN')")
    public ResponseEntity<Void> registerDevice(
            @Valid @RequestBody DeviceRegistrationRequest request,
            @AuthenticationPrincipal User currentUser // el usuario sale del token JWT, no lo envía el cliente
    ) {
        deviceService.registerDevice(currentUser, request.token(), request.platform());
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Da de baja el dispositivo",
            description = "Hay que llamarlo al cerrar sesión para que el dispositivo deje de recibir las notificaciones de ese usuario. Idempotente: si el token no existe, o es de otro usuario, no cambia nada.")
    @ApiResponse(responseCode = "204", description = "Dispositivo dado de baja")
    @ApiResponse(responseCode = "400", description = "Falta el token")
    @DeleteMapping
    @PreAuthorize("hasAnyRole('DRIVER', 'ADMIN')")
    public ResponseEntity<Void> unregisterDevice(
            @RequestParam
            @NotBlank(message = "El token del dispositivo es obligatorio")
            @Size(max = 512, message = "El token del dispositivo no puede superar los 512 caracteres") String token,
            @AuthenticationPrincipal User currentUser
    ) {
        deviceService.unregisterDevice(currentUser, token);
        return ResponseEntity.noContent().build();
    }
}
