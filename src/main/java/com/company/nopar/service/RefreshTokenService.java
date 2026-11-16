package com.company.nopar.service;

import com.company.nopar.entity.RefreshToken;
import com.company.nopar.repository.RefreshTokenRepository;
import com.company.nopar.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class RefreshTokenService {

    private final RefreshTokenRepository refreshTokenRepository;
    private final UserRepository userRepository;

    // 7 días de validez para el Refresh Token
    private static final long REFRESH_TOKEN_DURATION_MS = 1000L * 60 * 60 * 24 * 7;

    public RefreshToken createRefreshToken(String username) {
        var user = userRepository.findByUsername(username)
                .orElseThrow(() -> new RuntimeException("Usuario no encontrado: " + username));

        RefreshToken refreshToken = new RefreshToken();
        refreshToken.setUser(user);
        refreshToken.setExpiryDate(Instant.now().plusMillis(REFRESH_TOKEN_DURATION_MS));
        refreshToken.setToken(UUID.randomUUID().toString());

        return refreshTokenRepository.save(refreshToken);
    }

    public RefreshToken verifyExpiration(RefreshToken token) {
        if (token.getExpiryDate().compareTo(Instant.now()) < 0) {
            refreshTokenRepository.delete(token);
            throw new RuntimeException("El Refresh Token ha expirado. Por favor, inicia sesión de nuevo.");
        }
        return token;
    }

    @Transactional
    public RefreshToken rotateRefreshToken(String oldTokenString) {
        // 1. Buscamos el token antiguo en la base de datos
        RefreshToken oldToken = refreshTokenRepository.findByToken(oldTokenString)
                .orElseThrow(() -> new RuntimeException("Refresh Token no válido o no encontrado"));

        // 2. Verificamos que no haya caducado
        verifyExpiration(oldToken);

        // 3. ROTACIÓN: Eliminamos el token antiguo para invalidarlo permanentemente
        refreshTokenRepository.delete(oldToken);

        // 4. Generamos y devolvemos un nuevo par de tokens para el usuario asociado
        return createRefreshToken(oldToken.getUser().getUsername());
    }

    @Transactional
    public int deleteByUser(String username) {
        return userRepository.findByUsername(username)
                .map(refreshTokenRepository::deleteByUser)
                .orElse(0);
    }
}