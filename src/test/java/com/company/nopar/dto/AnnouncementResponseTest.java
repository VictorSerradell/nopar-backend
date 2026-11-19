package com.company.nopar.dto;

import com.company.nopar.entity.Announcement;
import com.company.nopar.entity.AnnouncementRead;
import com.company.nopar.entity.User;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AnnouncementResponseTest {

    private static final LocalDateTime CREATED = LocalDateTime.of(2026, 11, 19, 9, 0);
    private static final LocalDateTime UPDATED = LocalDateTime.of(2026, 11, 19, 10, 30);

    @Test
    void givenAnnouncement_whenFrom_thenCopiesItsDataAndOnlyTheAuthorsUsername() {
        AnnouncementResponse response = AnnouncementResponse.from(announcement());

        assertThat(response.id()).isEqualTo(5L);
        assertThat(response.title()).isEqualTo("Lockers");
        assertThat(response.body()).isEqualTo("Recordad cerrar la puerta");
        assertThat(response.pinned()).isTrue();
        assertThat(response.createdByUsername()).isEqualTo("admin1");
        assertThat(response.createdAt()).isEqualTo(CREATED);
        assertThat(response.updatedAt()).isEqualTo(UPDATED);
    }

    @Test
    void givenAnnouncementWithoutAuthor_whenFrom_thenTheUsernameIsNull() {
        Announcement announcement = announcement();
        announcement.setCreatedBy(null);

        assertThat(AnnouncementResponse.from(announcement).createdByUsername()).isNull();
        assertThat(BoardAnnouncementResponse.from(announcement, false).createdByUsername()).isNull();
    }

    @Test
    void givenAnnouncement_whenBoardFrom_thenCarriesTheReadFlag() {
        BoardAnnouncementResponse unread = BoardAnnouncementResponse.from(announcement(), false);
        BoardAnnouncementResponse read = BoardAnnouncementResponse.from(announcement(), true);

        assertThat(unread.read()).isFalse();
        assertThat(read.read()).isTrue();
        assertThat(read.id()).isEqualTo(5L);
        assertThat(read.title()).isEqualTo("Lockers");
        assertThat(read.pinned()).isTrue();
        assertThat(read.createdByUsername()).isEqualTo("admin1");
        assertThat(read.updatedAt()).isEqualTo(UPDATED);
    }

    @Test
    void givenReads_whenFromAll_thenCopiesWhoAndWhenWithoutExposingTheEntity() {
        List<AnnouncementReaderResponse> readers = AnnouncementReaderResponse.fromAll(List.of(
                read(7L, "driver1", CREATED), read(8L, "driver2", UPDATED)));

        assertThat(readers).extracting(AnnouncementReaderResponse::userId).containsExactly(7L, 8L);
        assertThat(readers).extracting(AnnouncementReaderResponse::username).containsExactly("driver1", "driver2");
        assertThat(readers).extracting(AnnouncementReaderResponse::readAt).containsExactly(CREATED, UPDATED);
    }

    @Test
    void givenReadWithoutUser_whenFrom_thenTheUserFieldsAreNull() {
        AnnouncementRead read = new AnnouncementRead();
        read.setReadAt(CREATED);

        AnnouncementReaderResponse response = AnnouncementReaderResponse.from(read);

        assertThat(response.userId()).isNull();
        assertThat(response.username()).isNull();
        assertThat(response.readAt()).isEqualTo(CREATED);
    }

    private static Announcement announcement() {
        User author = new User();
        author.setId(1L);
        author.setUsername("admin1");

        Announcement announcement = new Announcement();
        announcement.setId(5L);
        announcement.setTitle("Lockers");
        announcement.setBody("Recordad cerrar la puerta");
        announcement.setPinned(true);
        announcement.setCreatedBy(author);
        announcement.setCreatedAt(CREATED);
        announcement.setUpdatedAt(UPDATED);
        return announcement;
    }

    private static AnnouncementRead read(Long userId, String username, LocalDateTime readAt) {
        User user = new User();
        user.setId(userId);
        user.setUsername(username);

        AnnouncementRead read = new AnnouncementRead();
        read.setUser(user);
        read.setReadAt(readAt);
        return read;
    }
}
