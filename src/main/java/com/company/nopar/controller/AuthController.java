package com.company.nopar.controller;

import com.company.nopar.dto.AuthRequest;
import com.company.nopar.dto.ChangePasswordRequest;
import com.company.nopar.dto.RefreshTokenRequest;
import com.company.nopar.dto.TokenResponse;
import com.company.nopar.entity.User;
import com.company.nopar.repository.UserRepository;
import com.company.nopar.security.JwtService;
import com.company.nopar.service.RefreshTokenService;
import com.company.nopar.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@Tag(name = "Autenticación", description = "Inicio, renovación y cierre de sesión")
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthenticationManager authenticationManager;
    private final UserRepository userRepository;
    private final JwtService jwtService;
    private final RefreshTokenService refreshTokenService;
    private final UserService userService;

    @Operation(summary = "Inicia sesión",
            description = "Devuelve un access token (15 minutos) y un refresh token (7 días). No requiere autenticación.")
    @ApiResponse(responseCode = "401", description = "Credenciales inválidas")
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

    /**
     * Cierra la sesión de este dispositivo: revoca solo el refresh token presentado, y las sesiones del mismo
     * usuario en otros dispositivos siguen abiertas. No exige access token (puede haber caducado, dura 15 minutos):
     * el refresh token es la credencial, igual que en /refresh. Responde 204 aunque el token ya no existiera.
     */
    @Operation(summary = "Cierra la sesión de este dispositivo",
            description = "Revoca solo el refresh token presentado; las sesiones del mismo usuario en otros dispositivos siguen abiertas. No exige access token (puede haber caducado): el refresh token es la credencial. Responde 204 aunque el token ya no existiera.")
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@Valid @RequestBody RefreshTokenRequest request) {
        refreshTokenService.revokeRefreshToken(request.refreshToken());
        return ResponseEntity.noContent().build();
    }

    /**
     * Cierra la sesión en todos los dispositivos: revoca todos los refresh tokens del usuario autenticado.
     * Los access tokens ya emitidos siguen valiendo hasta que caduquen (15 minutos), pero ya no se pueden renovar.
     */
    @Operation(summary = "Cierra la sesión en todos los dispositivos",
            description = "Revoca todos los refresh tokens del usuario autenticado. Los access tokens ya emitidos valen hasta que caduquen (15 minutos), pero ya no se pueden renovar.")
    @PostMapping("/logout-all")
    public ResponseEntity<Void> logoutAll(@AuthenticationPrincipal User currentUser) {
        refreshTokenService.deleteByUser(currentUser.getUsername());
        return ResponseEntity.noContent().build();
    }

    /**
     * Cambio de contraseña del usuario autenticado. Solo para encargados y administradores: un conductor no puede
     * cambiar la suya (si la olvida o quiere otra, se la restablece un administrador con
     * PATCH /api/v1/admin/users/{id}/password). Cierra todas sus sesiones (otros dispositivos incluidos) y
     * devuelve un par de tokens nuevo para este dispositivo, de modo que el usuario no tenga que volver a iniciar
     * sesión; el cliente debe guardar los nuevos y descartar los anteriores.
     */
    @Operation(summary = "Cambia la contraseña",
            description = "Solo encargados y administradores; a un conductor se le restablece con PATCH /api/v1/admin/users/{id}/password. Exige la contraseña actual. Revoca todos los refresh tokens del usuario (en todos sus dispositivos) y devuelve un par de tokens nuevo para este: el cliente debe guardarlo y descartar el anterior. También da de baja todos sus dispositivos push, este incluido: la app debe volver a registrarlo con POST /api/v1/devices. Los access tokens ya emitidos valen hasta que caduquen (15 minutos).")
    @ApiResponse(responseCode = "400", description = "Contraseña actual incorrecta, nueva contraseña no válida o igual a la actual. Es un 400 y no un 401: el usuario sigue autenticado")
    @ApiResponse(responseCode = "403", description = "Un conductor no puede cambiar su contraseña: se la restablece un administrador")
    @PreAuthorize("hasAnyRole('MANAGER', 'ADMIN')")
    @PatchMapping("/password")
    public ResponseEntity<TokenResponse> changePassword(@AuthenticationPrincipal User currentUser,
                                                        @Valid @RequestBody ChangePasswordRequest request) {
        User updated = userService.changePassword(currentUser.getId(), request.currentPassword(), request.newPassword());

        var accessToken = jwtService.generateToken(updated);
        var refreshToken = refreshTokenService.createRefreshToken(updated);

        return ResponseEntity.ok(new TokenResponse(accessToken, refreshToken.token()));
    }

    @Operation(summary = "Renueva la sesión",
            description = "Rotación: el refresh token presentado queda invalidado y se emite uno nuevo junto con un nuevo access token. No requiere access token.")
    @ApiResponse(responseCode = "401", description = "Refresh token inválido, caducado o ya utilizado")
    @PostMapping("/refresh")
    public ResponseEntity<TokenResponse> refreshToken(@Valid @RequestBody RefreshTokenRequest request) {
        // Rotación: el refresh token presentado queda invalidado y se emite uno nuevo
        var rotated = refreshTokenService.rotateRefreshToken(request.refreshToken());

        String accessToken = jwtService.generateToken(rotated.user());

        return ResponseEntity.ok(new TokenResponse(accessToken, rotated.token()));
    }
}
