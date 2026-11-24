package com.company.nopar.repository;

import com.company.nopar.entity.DeviceToken;
import com.company.nopar.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

@Repository
public interface DeviceTokenRepository extends JpaRepository<DeviceToken, Long> {

    /**
     * Registra el token para el usuario. Si el token ya existía (la misma instalación, o el dispositivo de otro
     * usuario que cerró sesión) pasa a este usuario y se renueva su fecha: la restricción única decide, así que dos
     * peticiones simultáneas tampoco fallan.
     */
    @Modifying
    @Query(value = """
            insert into device_tokens (user_id, token, platform, created_at, last_seen_at)
            values (:userId, :token, :platform, :now, :now)
            on conflict (token) do update
                set user_id = excluded.user_id, platform = excluded.platform, last_seen_at = excluded.last_seen_at
            """, nativeQuery = true)
    int upsert(@Param("userId") Long userId,
               @Param("token") String token,
               @Param("platform") String platform,
               @Param("now") LocalDateTime now);

    /** Deja a lo sumo {@code max} dispositivos al usuario, los usados más recientemente; devuelve cuántos borró. */
    @Modifying
    @Query(value = """
            delete from device_tokens
            where user_id = :userId
              and id not in (select id from device_tokens where user_id = :userId order by last_seen_at desc, id desc limit :max)
            """, nativeQuery = true)
    int deleteOldestBeyond(@Param("userId") Long userId, @Param("max") int max);

    /** Borra el token solo si es de ese usuario: nadie da de baja el dispositivo de otro. Devuelve 1 o 0. */
    @Modifying
    @Query("delete from DeviceToken d where d.token = :token and d.user.id = :userId")
    int deleteByTokenAndUserId(@Param("token") String token, @Param("userId") Long userId);

    /** Borra todos los dispositivos del usuario (al cambiar o restablecer su contraseña); devuelve cuántos. */
    @Modifying
    @Query("delete from DeviceToken d where d.user.id = :userId")
    int deleteAllByUserId(@Param("userId") Long userId);

    /** Los tokens de todos los dispositivos de los usuarios con ese rol: a quién se envía un aviso. */
    @Query("select d.token from DeviceToken d where d.user.role = :role order by d.id")
    List<String> findTokensByUserRole(@Param("role") User.Role role);

    /** Los tokens de todos los dispositivos de un usuario: a quién se envía un aviso personal. */
    @Query("select d.token from DeviceToken d where d.user.id = :userId order by d.id")
    List<String> findTokensByUserId(@Param("userId") Long userId);

    /** Los tokens de todos los dispositivos de varios usuarios: a quién se envía un aviso a un grupo. */
    @Query("select d.token from DeviceToken d where d.user.id in :userIds order by d.id")
    List<String> findTokensByUserIdIn(@Param("userIds") Collection<Long> userIds);

    /** Borra de una sola sentencia estos tokens (los que FCM da por no registrados); devuelve cuántos. */
    @Modifying
    @Query("delete from DeviceToken d where d.token in :tokens")
    int deleteByTokenIn(@Param("tokens") Collection<String> tokens);

    long countByUserId(Long userId);
}
