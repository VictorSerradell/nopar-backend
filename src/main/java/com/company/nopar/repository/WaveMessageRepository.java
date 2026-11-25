package com.company.nopar.repository;

import com.company.nopar.entity.WaveMessage;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Las lecturas traen al autor en la misma consulta (join fetch): las respuestas se construyen fuera de la transacción
 * (open-in-view está desactivado) y sin esto habría una consulta más por mensaje.
 */
@Repository
public interface WaveMessageRepository extends JpaRepository<WaveMessage, Long> {

    /** Los últimos mensajes del chat de una ola un día, el más reciente primero (el tamaño lo da el Pageable). */
    @Query("""
            select m from WaveMessage m
            join fetch m.sender
            where m.waveId = :waveId and m.workDate = :date
            order by m.id desc
            """)
    List<WaveMessage> findLatest(@Param("waveId") Long waveId, @Param("date") LocalDate date, Pageable page);

    /** Los mensajes posteriores a uno dado, por orden de llegada: lo que la app pide para ponerse al día. */
    @Query("""
            select m from WaveMessage m
            join fetch m.sender
            where m.waveId = :waveId and m.workDate = :date and m.id > :afterId
            order by m.id asc
            """)
    List<WaveMessage> findAfter(@Param("waveId") Long waveId, @Param("date") LocalDate date,
                                @Param("afterId") Long afterId, Pageable page);

    /** Un mensaje de esa ola (sin traer a su autor): para servir su foto. */
    Optional<WaveMessage> findByIdAndWaveId(Long id, Long waveId);
}
