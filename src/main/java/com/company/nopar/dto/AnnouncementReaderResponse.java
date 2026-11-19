package com.company.nopar.dto;

import com.company.nopar.entity.AnnouncementRead;

import java.time.LocalDateTime;
import java.util.List;

/** Quién ha leído un aviso y cuándo. */
public record AnnouncementReaderResponse(Long userId, String username, LocalDateTime readAt) {

    public static AnnouncementReaderResponse from(AnnouncementRead read) {
        var user = read.getUser();
        return new AnnouncementReaderResponse(
                user != null ? user.getId() : null,
                user != null ? user.getUsername() : null,
                read.getReadAt()
        );
    }

    public static List<AnnouncementReaderResponse> fromAll(List<AnnouncementRead> reads) {
        return reads.stream().map(AnnouncementReaderResponse::from).toList();
    }
}
