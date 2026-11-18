package com.company.nopar.repository;

import com.company.nopar.entity.VehicleInspection;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * Todas las lecturas traen vehículo, conductor y revisor con el grafo WITH_DETAILS: sin él, mapear cada
 * inspección a su respuesta lanzaría varias consultas más por fila (N+1), y con open-in-view desactivado
 * fallaría al leer las relaciones fuera de la transacción.
 */
@Repository
public interface VehicleInspectionRepository extends JpaRepository<VehicleInspection, Long> {

    @Override
    @EntityGraph(VehicleInspection.WITH_DETAILS)
    Page<VehicleInspection> findAll(Pageable pageable);

    @EntityGraph(VehicleInspection.WITH_DETAILS)
    Optional<VehicleInspection> findWithDetailsById(Long id);

    @EntityGraph(VehicleInspection.WITH_DETAILS)
    Page<VehicleInspection> findByVehicleId(Long vehicleId, Pageable pageable);

    @EntityGraph(VehicleInspection.WITH_DETAILS)
    Page<VehicleInspection> findByDriverId(Long driverId, Pageable pageable);

    @EntityGraph(VehicleInspection.WITH_DETAILS)
    Page<VehicleInspection> findByStatus(VehicleInspection.InspectionStatus status, Pageable pageable);

    boolean existsByVehicleId(Long vehicleId);
}
