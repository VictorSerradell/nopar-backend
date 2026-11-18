package com.company.nopar.repository;

import com.company.nopar.entity.VehicleReturn;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface VehicleReturnRepository extends JpaRepository<VehicleReturn, Long> {
}
