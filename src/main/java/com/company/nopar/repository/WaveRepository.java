package com.company.nopar.repository;

import com.company.nopar.entity.Wave;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface WaveRepository extends JpaRepository<Wave, Long> {

    boolean existsByNameIgnoreCase(String name);

    /** Las olas en el orden del día: por hora de salida. */
    List<Wave> findAllByOrderByStartTimeAscNameAsc();
}
