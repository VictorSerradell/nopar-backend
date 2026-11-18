package com.company.nopar.controller;

import com.company.nopar.dto.AuthRequest;
import com.company.nopar.dto.RefreshTokenRequest;
import com.company.nopar.dto.TokenResponse;
import com.company.nopar.entity.User;
import com.company.nopar.repository.UserRepository;
import com.company.nopar.security.JwtService;
import com.company.nopar.service.RefreshTokenService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthenticationManager authenticationManager;
    private final UserRepository userRepository;
    private final JwtService jwtService;
    private final RefreshTokenService refreshTokenService;

    @PostMapping("/login")
    public ResponseEntity<TokenResponse> authenticateUser(@RequestBody AuthRequest request) {
        authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(request.username(), request.password())
        );
        var user = userRepository.findByUsername(request.username())
                .orElseThrow(() -> new BadCredentialsException("Credenciales inválidas"));

        var accessToken = jwtService.generateToken(user);
        var refreshToken = refreshTokenService.createRefreshToken(user);

        return ResponseEntity.ok(new TokenResponse(accessToken, refreshToken.token()));
    }

    @PostMapping("/logout")
    public ResponseEntity<?> logout(@AuthenticationPrincipal User currentUser) {
        if (currentUser != null) {
            // Eliminamos todos los refresh tokens asociados a este usuario en la base de datos
            refreshTokenService.deleteByUser(currentUser.getUsername());
        }
        return ResponseEntity.ok(Map.of("message", "Sesión cerrada correctamente. Refresh tokens revocados."));
    }

    @PostMapping("/refresh")
    public ResponseEntity<TokenResponse> refreshToken(@Valid @RequestBody RefreshTokenRequest request) {
        // Rotación: el refresh token presentado queda invalidado y se emite uno nuevo
        var rotated = refreshTokenService.rotateRefreshToken(request.refreshToken());

        String accessToken = jwtService.generateToken(rotated.user());

        return ResponseEntity.ok(new TokenResponse(accessToken, rotated.token()));
    }
}
