package com.company.nopar.repository;

import com.company.nopar.entity.VehicleInspection;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface VehicleInspectionRepository extends JpaRepository<VehicleInspection, Long> {
    List<VehicleInspection> findByVehicleId(Long vehicleId);
    List<VehicleInspection> findByDriverId(Long driverId);
}