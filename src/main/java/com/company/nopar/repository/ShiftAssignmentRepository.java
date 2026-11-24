package com.company.nopar.repository;

import com.company.nopar.entity.ShiftAssignment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Las lecturas traen ola, conductor y furgoneta en la misma consulta (join fetch): sin ello, mapear cada turno a su
 * respuesta lanzaría varias consultas más por fila (N+1), y con open-in-view desactivado fallaría al leer las
 * relaciones fuera de la transacción.
 */
@Repository
public interface ShiftAssignmentRepository extends JpaRepository<ShiftAssignment, Long> {

    /** El cuadrante de un rango de fechas, en el orden en que se lee: día, hora de la ola y conductor. */
    @Query("""
            select s from ShiftAssignment s
            join fetch s.wave w
            join fetch s.driver d
            join fetch s.vehicle v
            where s.workDate between :from and :to
            order by s.workDate, w.startTime, d.username
            """)
    List<ShiftAssignment> findInRange(@Param("from") LocalDate from, @Param("to") LocalDate to);

    @Query("""
            select s from ShiftAssignment s
            join fetch s.wave w
            join fetch s.driver d
            join fetch s.vehicle v
            where d.id = :driverId and s.workDate between :from and :to
            order by s.workDate, w.startTime
            """)
    List<ShiftAssignment> findByDriverInRange(
            @Param("driverId") Long driverId, @Param("from") LocalDate from, @Param("to") LocalDate to);

    @Query("""
            select s from ShiftAssignment s
            join fetch s.wave
            join fetch s.driver
            join fetch s.vehicle
            where s.id = :id
            """)
    Optional<ShiftAssignment> findWithDetailsById(@Param("id") Long id);

    /** Los turnos de una ola un día, con su conductor y su furgoneta: a quién se llama al dar la orden de subir. */
    @Query("""
            select s from ShiftAssignment s
            join fetch s.driver d
            join fetch s.vehicle
            where s.workDate = :date and s.wave.id = :waveId
            order by d.username
            """)
    List<ShiftAssignment> findByWorkDateAndWaveId(@Param("date") LocalDate date, @Param("waveId") Long waveId);

    /** El turno de un conductor un día (a lo sumo uno: lo garantiza la restricción única). Sin traer sus relaciones. */
    Optional<ShiftAssignment> findByWorkDateAndDriverId(LocalDate workDate, Long driverId);

    boolean existsByWorkDateAndDriverId(LocalDate workDate, Long driverId);

    boolean existsByWorkDateAndVehicleId(LocalDate workDate, Long vehicleId);

    /** Igual que las anteriores pero ignorando un turno: al editarlo no choca consigo mismo. */
    boolean existsByWorkDateAndDriverIdAndIdNot(LocalDate workDate, Long driverId, Long id);

    boolean existsByWorkDateAndVehicleIdAndIdNot(LocalDate workDate, Long vehicleId, Long id);

    boolean existsByWaveId(Long waveId);

    boolean existsByVehicleId(Long vehicleId);
}
