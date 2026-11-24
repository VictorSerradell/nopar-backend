package com.company.nopar.repository;

import com.company.nopar.entity.WaveCallRecipient;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface WaveCallRecipientRepository extends JpaRepository<WaveCallRecipient, Long> {

    /** Las llamadas de un día que recibió un conductor, la más reciente primero. */
    @Query("""
            select r from WaveCallRecipient r
            join fetch r.waveCall c
            join fetch c.wave
            join fetch c.calledBy
            where r.driver.id = :driverId and c.workDate = :date
            order by c.calledAt desc, c.id desc
            """)
    List<WaveCallRecipient> findOfDriverOnDate(@Param("driverId") Long driverId, @Param("date") LocalDate date);

    /** La fila de un conductor en una llamada, con lo necesario para contestarle; vacío si no era destinatario. */
    @Query("""
            select r from WaveCallRecipient r
            join fetch r.waveCall c
            join fetch c.wave
            join fetch c.calledBy
            where c.id = :callId and r.driver.id = :driverId
            """)
    Optional<WaveCallRecipient> findOfDriver(@Param("callId") Long callId, @Param("driverId") Long driverId);
}
