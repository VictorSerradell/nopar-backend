package com.company.nopar.dto;

import com.company.nopar.entity.Announcement;

import java.time.LocalDateTime;

/** Vista de un aviso para quien lo publica. No expone la entidad User (hash de contraseña, roles). */
public record AnnouncementResponse(
        Long id,
        String title,
        String body,
        boolean pinned,
        String createdByUsername,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {

    public static AnnouncementResponse from(Announcement announcement) {
        var author = announcement.getCreatedBy();
        return new AnnouncementResponse(
                announcement.getId(),
                announcement.getTitle(),
                announcement.getBody(),
                announcement.isPinned(),
                author != null ? author.getUsername() : null,
                announcement.getCreatedAt(),
                announcement.getUpdatedAt()
        );
    }
}
