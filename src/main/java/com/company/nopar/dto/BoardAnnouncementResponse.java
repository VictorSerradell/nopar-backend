package com.company.nopar.dto;

import com.company.nopar.entity.Announcement;

import java.time.LocalDateTime;

/** Un aviso tal como lo ve quien lee el tablón: además indica si él ya lo ha leído. */
public record BoardAnnouncementResponse(
        Long id,
        String title,
        String body,
        boolean pinned,
        String createdByUsername,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        boolean read
) {

    public static BoardAnnouncementResponse from(Announcement announcement, boolean read) {
        var author = announcement.getCreatedBy();
        return new BoardAnnouncementResponse(
                announcement.getId(),
                announcement.getTitle(),
                announcement.getBody(),
                announcement.isPinned(),
                author != null ? author.getUsername() : null,
                announcement.getCreatedAt(),
                announcement.getUpdatedAt(),
                read
        );
    }
}
