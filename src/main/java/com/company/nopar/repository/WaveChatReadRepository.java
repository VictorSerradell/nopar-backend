package com.company.nopar.repository;

import com.company.nopar.entity.WaveChatRead;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface WaveChatReadRepository extends JpaRepository<WaveChatRead, Long> {

    /**
     * Marca el chat de una ola como leído hasta un mensaje. Solo avanza: si el usuario ya había leído más (otro
     * dispositivo, o una petición que llegó tarde) se queda con lo mayor. La restricción única decide, así que dos
     * peticiones simultáneas tampoco fallan.
     */
    @Modifying
    @Query(value = """
            insert into wave_chat_reads (user_id, wave_id, last_read_id, updated_at)
            values (:userId, :waveId, :lastReadId, :updatedAt)
            on conflict (user_id, wave_id) do update
                set last_read_id = greatest(wave_chat_reads.last_read_id, excluded.last_read_id),
                    updated_at = excluded.updated_at
            """, nativeQuery = true)
    int markRead(@Param("userId") Long userId,
                 @Param("waveId") Long waveId,
                 @Param("lastReadId") Long lastReadId,
                 @Param("updatedAt") LocalDateTime updatedAt);

    /**
     * Cuántos mensajes sin leer tiene el usuario en cada ola un día: los de otros (no cuentan los suyos) posteriores
     * al último que leyó en esa ola (sin marcador, todos). Cada fila es {@code [waveId, cantidad]}; solo salen las
     * olas con algún mensaje de otros ese día.
     */
    @Query(value = """
            select m.wave_id, count(*)
            from wave_messages m
            left join wave_chat_reads r on r.user_id = :userId and r.wave_id = m.wave_id
            where m.work_date = :date
              and m.sender_id <> :userId
              and m.id > coalesce(r.last_read_id, 0)
            group by m.wave_id
            order by m.wave_id
            """, nativeQuery = true)
    List<Object[]> countUnread(@Param("userId") Long userId, @Param("date") LocalDate date);
}
