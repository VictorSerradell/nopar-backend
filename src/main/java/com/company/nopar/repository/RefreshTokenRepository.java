package com.company.nopar.repository;

import com.company.nopar.entity.RefreshToken;
import com.company.nopar.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

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

    int deleteByUser(User user);
}
