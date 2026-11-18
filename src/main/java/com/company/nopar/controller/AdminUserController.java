package com.company.nopar.controller;

import com.company.nopar.dto.CreateUserRequest;
import com.company.nopar.dto.UserResponse;
import com.company.nopar.entity.User;
import com.company.nopar.service.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Gestión de usuarios. La ruta cuelga de /api/v1/admin, que SecurityConfig reserva al rol ADMIN. */
@RestController
@RequestMapping("/api/v1/admin/users")
@RequiredArgsConstructor
public class AdminUserController {

    private final UserService userService;

    @PostMapping
    public ResponseEntity<UserResponse> createDriver(@Valid @RequestBody CreateUserRequest request) {
        User created = userService.createDriver(request.username(), request.password());
        return ResponseEntity.status(HttpStatus.CREATED).body(UserResponse.from(created));
    }
}
