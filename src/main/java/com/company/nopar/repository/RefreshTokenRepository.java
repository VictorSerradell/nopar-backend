package com.company.nopar.repository;

import com.company.nopar.entity.RefreshToken;
import com.company.nopar.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;

@Repository
public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {

    Optional<RefreshToken> findByTokenHash(String tokenHash);

    /**
     * Borra el token con una sola sentencia y devuelve cuántas filas borró. Es la forma atómica de
     * "consumirlo": si dos peticiones presentan el mismo token a la vez, solo una obtiene 1.
     */
    @Modifying
    @Query("delete from RefreshToken t where t.tokenHash = :tokenHash")
    int deleteByTokenHash(@Param("tokenHash") String tokenHash);

    /**
     * Borra de una sola sentencia todos los tokens del usuario (cerrar sesión en todos los dispositivos);
     * devuelve cuántos. Un método derivado cargaría cada token y los borraría de uno en uno.
     */
    @Modifying
    @Query("delete from RefreshToken t where t.user = :user")
    int deleteByUser(@Param("user") User user);

    /** Borra de una sola sentencia todos los tokens que caducaron antes de {@code now}; devuelve cuántos. */
    @Modifying
    @Query("delete from RefreshToken t where t.expiryDate < :now")
    int deleteExpiredBefore(@Param("now") Instant now);
}
