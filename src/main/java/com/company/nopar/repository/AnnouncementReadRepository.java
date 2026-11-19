package com.company.nopar.repository;

import com.company.nopar.entity.AnnouncementRead;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

@Repository
public interface AnnouncementReadRepository extends JpaRepository<AnnouncementRead, Long> {

    /**
     * Marca el aviso como leído por el usuario. Si ya lo había leído no hace nada (conserva la primera fecha):
     * la restricción única decide, así que dos peticiones simultáneas tampoco fallan. Devuelve 1 si lo registró
     * y 0 si ya estaba leído.
     */
    @Modifying
    @Query(value = """
            insert into announcement_reads (announcement_id, user_id, read_at)
            values (:announcementId, :userId, :readAt)
            on conflict (announcement_id, user_id) do nothing
            """, nativeQuery = true)
    int markAsRead(@Param("announcementId") Long announcementId,
                   @Param("userId") Long userId,
                   @Param("readAt") LocalDateTime readAt);

    /** Cuáles de estos avisos ha leído el usuario: una sola consulta para toda una página del tablón. */
    @Query("select r.announcement.id from AnnouncementRead r where r.user.id = :userId and r.announcement.id in :announcementIds")
    List<Long> findReadAnnouncementIds(@Param("userId") Long userId,
                                       @Param("announcementIds") Collection<Long> announcementIds);

    /** Quién ha leído un aviso y cuándo, por orden de lectura. */
    @Query("""
            select r from AnnouncementRead r
            join fetch r.user
            where r.announcement.id = :announcementId
            order by r.readAt, r.id
            """)
    List<AnnouncementRead> findByAnnouncementWithUser(@Param("announcementId") Long announcementId);
}
