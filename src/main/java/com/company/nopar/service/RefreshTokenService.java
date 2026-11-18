package com.company.nopar.service;

import com.company.nopar.entity.RefreshToken;
import com.company.nopar.entity.User;
import com.company.nopar.exception.InvalidRefreshTokenException;
import com.company.nopar.repository.RefreshTokenRepository;
import com.company.nopar.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Emite y renueva refresh tokens. En la base de datos solo se guarda el hash SHA-256 del token:
 * si la tabla se filtra, los hashes no sirven para abrir sesiones. El valor en claro solo se
 * conoce en el momento de emitirlo y se entrega al cliente.
 */
@Service
@RequiredArgsConstructor
public class RefreshTokenService {

    private static final Duration REFRESH_TOKEN_DURATION = Duration.ofDays(7);
    private static final int TOKEN_BYTES = 32; // 256 bits de aleatoriedad
    private static final String INVALID_TOKEN_MESSAGE = "Refresh token no válido o expirado";

    private final RefreshTokenRepository refreshTokenRepository;
    private final UserRepository userRepository;
    private final SecureRandom secureRandom = new SecureRandom();

    /** Token recién emitido: {@code token} es el valor en claro para el cliente; en la BD solo queda su hash. */
    public record IssuedRefreshToken(String token, User user) {}

    @Transactional
    public IssuedRefreshToken createRefreshToken(User user) {
        return issueFor(user);
    }

    /**
     * Rotación: el token presentado se consume (no puede volver a usarse) y se emite uno nuevo.
     * Un token inexistente, ya consumido o caducado produce {@link InvalidRefreshTokenException} (401).
     *
     * noRollbackFor: si el token está caducado se borra y luego se lanza la excepción; sin esta opción
     * la transacción se revertiría y el borrado se perdería.
     */
    @Transactional(noRollbackFor = InvalidRefreshTokenException.class)
    public IssuedRefreshToken rotateRefreshToken(String presentedToken) {
        if (presentedToken == null || presentedToken.isBlank()) {
            throw invalidToken();
        }

        String tokenHash = hash(presentedToken);
        RefreshToken stored = refreshTokenRepository.findByTokenHash(tokenHash)
                .orElseThrow(RefreshTokenService::invalidToken);

        // Consumir el token de forma atómica: si dos peticiones lo presentan a la vez, solo una lo borra
        if (refreshTokenRepository.deleteByTokenHash(tokenHash) == 0) {
            throw invalidToken();
        }
        if (stored.getExpiryDate().isBefore(Instant.now())) {
            throw invalidToken();
        }

        return issueFor(stored.getUser());
    }

    /**
     * Cierra la sesión de un solo dispositivo: borra el refresh token presentado. No falla si no existe
     * (ya revocado, caducado o inventado): repetirlo es inofensivo y no revela si el token era válido.
     */
    @Transactional
    public void revokeRefreshToken(String refreshToken) {
        refreshTokenRepository.deleteByTokenHash(hash(refreshToken));
    }

    /**
     * Cierra la sesión en todos los dispositivos: borra todos los refresh tokens del usuario.
     * Los access tokens ya emitidos siguen valiendo hasta que caduquen.
     */
    @Transactional
    public int deleteByUser(String username) {
        return userRepository.findByUsername(username)
                .map(refreshTokenRepository::deleteByUser)
                .orElse(0);
    }

    /**
     * Borra los refresh tokens caducados. Los tokens se consumen al rotarse y se borran al cerrar sesión,
     * así que solo se acumulan los que caducaron sin que nadie los volviera a presentar. Devuelve cuántos borró.
     */
    @Transactional
    public int deleteExpiredTokens() {
        return refreshTokenRepository.deleteExpiredBefore(Instant.now());
    }

    private IssuedRefreshToken issueFor(User user) {
        byte[] randomBytes = new byte[TOKEN_BYTES];
        secureRandom.nextBytes(randomBytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);

        RefreshToken entity = new RefreshToken();
        entity.setUser(user);
        entity.setTokenHash(hash(token));
        entity.setExpiryDate(Instant.now().plus(REFRESH_TOKEN_DURATION));
        refreshTokenRepository.save(entity);

        return new IssuedRefreshToken(token, user);
    }

    /** SHA-256 en hexadecimal. Basta un hash rápido porque el token ya tiene 256 bits de entropía. */
    static String hash(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 no está disponible en esta JVM", ex);
        }
    }

    private static InvalidRefreshTokenException invalidToken() {
        return new InvalidRefreshTokenException(INVALID_TOKEN_MESSAGE);
    }
}
