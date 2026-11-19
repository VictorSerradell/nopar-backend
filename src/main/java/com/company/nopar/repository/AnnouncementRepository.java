package com.company.nopar.repository;

import com.company.nopar.entity.Announcement;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * Las lecturas traen al autor en la misma consulta (EntityGraph): sin ello, mapear cada aviso a su respuesta
 * lanzaría una consulta más por fila (N+1), y con open-in-view desactivado fallaría al leerlo fuera de la transacción.
 */
@Repository
public interface AnnouncementRepository extends JpaRepository<Announcement, Long> {

    /** El tablón: primero los fijados y luego los más recientes (el id desempata). */
    @EntityGraph(attributePaths = "createdBy")
    Page<Announcement> findAllByOrderByPinnedDescCreatedAtDescIdDesc(Pageable pageable);

    @EntityGraph(attributePaths = "createdBy")
    Optional<Announcement> findWithAuthorById(Long id);
}
