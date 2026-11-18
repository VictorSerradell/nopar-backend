package com.company.nopar.repository;

import com.company.nopar.entity.Vehicle;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface VehicleRepository extends JpaRepository<Vehicle, Long> {
    Optional<Vehicle> findByLicensePlate(String licensePlate);

    /** Lee el vehículo bloqueando su fila hasta el final de la transacción (SELECT ... FOR UPDATE). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select v from Vehicle v where v.id = :id")
    Optional<Vehicle> findByIdForUpdate(@Param("id") Long id);

    /** Cambia de golpe el estado de todos los vehículos que están en oldStatus; devuelve cuántos cambió. */
    @Modifying
    @Query("update Vehicle v set v.status = :newStatus where v.status = :oldStatus")
    int updateStatus(@Param("oldStatus") Vehicle.VehicleStatus oldStatus,
                     @Param("newStatus") Vehicle.VehicleStatus newStatus);
}
