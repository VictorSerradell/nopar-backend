package com.company.nopar.repository;

import com.company.nopar.entity.WaveCall;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Las lecturas traen ola, autor, destinatarios y conductores en la misma consulta (join fetch): las respuestas se
 * construyen fuera de la transacción (open-in-view está desactivado) y sin esto habría una consulta más por fila.
 */
@Repository
public interface WaveCallRepository extends JpaRepository<WaveCall, Long> {

    @Query("""
            select c from WaveCall c
            join fetch c.wave
            join fetch c.calledBy
            left join fetch c.recipients r
            left join fetch r.driver
            where c.id = :id
            """)
    Optional<WaveCall> findWithDetailsById(@Param("id") Long id);

    /** Las llamadas de un día, la más reciente primero. */
    @Query("""
            select c from WaveCall c
            join fetch c.wave
            join fetch c.calledBy
            left join fetch c.recipients r
            left join fetch r.driver
            where c.workDate = :date
            order by c.calledAt desc, c.id desc
            """)
    List<WaveCall> findWithDetailsByWorkDate(@Param("date") LocalDate date);
}
