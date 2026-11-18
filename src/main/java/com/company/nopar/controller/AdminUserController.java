package com.company.nopar.controller;

import com.company.nopar.dto.CreateUserRequest;
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
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Gestión de usuarios. La ruta cuelga de /api/v1/admin, que SecurityConfig reserva al rol ADMIN. */
@Tag(name = "Administración", description = "Solo rol ADMIN: usuarios, estado de los vehículos y revisión de partes")
@RestController
@RequestMapping("/api/v1/admin/users")
@RequiredArgsConstructor
public class AdminUserController {

    private final UserService userService;

    @Operation(summary = "Crea un conductor")
    @ApiResponse(responseCode = "409", description = "Ya existe un usuario con ese nombre")
    @PostMapping
    public ResponseEntity<UserResponse> createDriver(@Valid @RequestBody CreateUserRequest request) {
        User created = userService.createDriver(request.username(), request.password());
        return ResponseEntity.status(HttpStatus.CREATED).body(UserResponse.from(created));
    }
}
