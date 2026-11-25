package com.company.nopar.controller;

import com.company.nopar.dto.CreateUserRequest;
import com.company.nopar.dto.ResetPasswordRequest;
import com.company.nopar.dto.UserResponse;
import com.company.nopar.entity.User;
import com.company.nopar.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Gestión de usuarios. La ruta cuelga de /api/v1/admin, que SecurityConfig reserva al rol ADMIN. */
@Tag(name = "Administración", description = "Operaciones reservadas al rol ADMIN")
@RestController
@RequestMapping("/api/v1/admin/users")
@RequiredArgsConstructor
public class AdminUserController {

    private final UserService userService;

    @Operation(summary = "Lista los usuarios",
            description = "Todos, administradores incluidos, por nombre. Nunca incluye la contraseña. La app de administración la usa para elegir a quién restablecer la contraseña.")
    @GetMapping
    public List<UserResponse> listUsers() {
        return userService.listUsers().stream().map(UserResponse::from).toList();
    }

    @Operation(summary = "Crea un conductor")
    @ApiResponse(responseCode = "409", description = "Ya existe un usuario con ese nombre")
    @PostMapping
    public ResponseEntity<UserResponse> createDriver(@Valid @RequestBody CreateUserRequest request) {
        User created = userService.createDriver(request.username(), request.password());
        return ResponseEntity.status(HttpStatus.CREATED).body(UserResponse.from(created));
    }

    @Operation(summary = "Crea un encargado",
            description = "Un encargado puede dar la orden de subir a una ola (/api/v1/dispatch) y ver quién la ha confirmado, y nada más de la administración.")
    @ApiResponse(responseCode = "409", description = "Ya existe un usuario con ese nombre")
    @PostMapping("/managers")
    public ResponseEntity<UserResponse> createManager(@Valid @RequestBody CreateUserRequest request) {
        User created = userService.createManager(request.username(), request.password());
        return ResponseEntity.status(HttpStatus.CREATED).body(UserResponse.from(created));
    }

    @Operation(summary = "Restablece la contraseña de un conductor o de un encargado",
            description = "Para quien ha olvidado la suya: no exige la contraseña actual y cierra todas sus sesiones (revoca sus refresh tokens y da de baja sus dispositivos push). Vale para conductores y encargados; los administradores cambian la suya con PATCH /api/v1/auth/password.")
    @ApiResponse(responseCode = "400", description = "El usuario no es un conductor ni un encargado, o la nueva contraseña no es válida")
    @ApiResponse(responseCode = "404", description = "No existe ese usuario")
    @PatchMapping("/{id}/password")
    public ResponseEntity<Void> resetPassword(@PathVariable Long id,
                                              @Valid @RequestBody ResetPasswordRequest request) {
        userService.resetPassword(id, request.newPassword());
        return ResponseEntity.noContent().build();
    }
}
