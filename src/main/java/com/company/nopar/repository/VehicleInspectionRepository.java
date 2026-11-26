package com.company.nopar.repository;

import com.company.nopar.entity.VehicleInspection;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
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

    /** El último parte de un vehículo: su conductor es quien lo tiene (y puede devolverlo). */
    @EntityGraph(VehicleInspection.WITH_DETAILS)
    Optional<VehicleInspection> findFirstByVehicleIdOrderByInspectionDateDescIdDesc(Long vehicleId);

    boolean existsByVehicleId(Long vehicleId);

    // ---- Retención de fotos: primero se leen los ficheros, después se quitan de las filas y por último se borran del disco

    /** Los ficheros de la foto del cuentakilómetros de los partes anteriores a una fecha. */
    @Query("select i.odometerPhotoPath from VehicleInspection i "
            + "where i.inspectionDate < :cutoff and i.odometerPhotoPath is not null")
    List<String> findOdometerPhotoFilesBefore(@Param("cutoff") LocalDateTime cutoff);

    /** Los ficheros de la foto de la baliza V16 de los partes anteriores a una fecha. */
    @Query("select i.beaconPhotoPath from VehicleInspection i "
            + "where i.inspectionDate < :cutoff and i.beaconPhotoPath is not null")
    List<String> findBeaconPhotoFilesBefore(@Param("cutoff") LocalDateTime cutoff);

    /** Los ficheros de la foto exterior de los partes anteriores a una fecha. */
    @Query("select i.exteriorPhotosPath from VehicleInspection i "
            + "where i.inspectionDate < :cutoff and i.exteriorPhotosPath is not null")
    List<String> findExteriorPhotoFilesBefore(@Param("cutoff") LocalDateTime cutoff);

    /**
     * Quita las fotos de los partes anteriores a una fecha. El parte y el resto de sus datos se conservan. Devuelve
     * cuántos partes tenían alguna foto.
     */
    @Modifying
    @Query("update VehicleInspection i "
            + "set i.odometerPhotoPath = null, i.beaconPhotoPath = null, i.exteriorPhotosPath = null "
            + "where i.inspectionDate < :cutoff "
            + "and (i.odometerPhotoPath is not null or i.beaconPhotoPath is not null or i.exteriorPhotosPath is not null)")
    int clearPhotosBefore(@Param("cutoff") LocalDateTime cutoff);
}
