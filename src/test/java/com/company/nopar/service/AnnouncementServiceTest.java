package com.company.nopar.service;

import com.company.nopar.dto.AnnouncementRequest;
import com.company.nopar.dto.AnnouncementUpdateRequest;
import com.company.nopar.dto.BoardAnnouncementResponse;
import com.company.nopar.entity.Announcement;
import com.company.nopar.entity.AnnouncementRead;
import com.company.nopar.entity.User;
import com.company.nopar.exception.InvalidRequestException;
import com.company.nopar.exception.ResourceNotFoundException;
import com.company.nopar.repository.AnnouncementReadRepository;
import com.company.nopar.repository.AnnouncementRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AnnouncementServiceTest {

    private static final LocalDateTime LONG_AGO = LocalDateTime.of(2020, 1, 1, 9, 0);

    @Mock
    private AnnouncementRepository announcementRepository;

    @Mock
    private AnnouncementReadRepository readRepository;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @InjectMocks
    private AnnouncementService announcementService;

    // ---------------------------------------------------------------- alta

    @Test
    void givenRequest_whenCreate_thenSavesItTrimmedNotPinnedAndWithTheAdminAsAuthor() {
        User admin = user(1L, "admin1");
        when(announcementRepository.save(any(Announcement.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Announcement created = announcementService.createAnnouncement(
                new AnnouncementRequest("  Lockers  ", "  Recordad cerrar la puerta  ", null, null), admin);

        assertThat(created.getTitle()).isEqualTo("Lockers");
        assertThat(created.getBody()).isEqualTo("Recordad cerrar la puerta");
        assertThat(created.isPinned()).isFalse();
        assertThat(created.getCreatedBy()).isSameAs(admin);
    }

    @Test
    void givenPinnedRequest_whenCreate_thenItIsPinned() {
        when(announcementRepository.save(any(Announcement.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Announcement created = announcementService.createAnnouncement(
                new AnnouncementRequest("Lockers", "Texto", true, null), user(1L, "admin1"));

        assertThat(created.isPinned()).isTrue();
    }

    @Test
    void givenRequest_whenCreate_thenPublishesTheEventThatNotifiesTheDrivers() {
        when(announcementRepository.save(any(Announcement.class))).thenAnswer(invocation -> {
            Announcement saved = invocation.getArgument(0);
            saved.setId(9L);
            return saved;
        });

        announcementService.createAnnouncement(
                new AnnouncementRequest("  Lockers  ", "  Recordad cerrar la puerta  ", false, null), user(1L, "admin1"));

        verify(eventPublisher).publishEvent(new AnnouncementPublishedEvent(9L, "Lockers", "Recordad cerrar la puerta"));
    }

    @Test
    void givenNotifyDriversTrue_whenCreate_thenPublishesTheEvent() {
        when(announcementRepository.save(any(Announcement.class))).thenAnswer(invocation -> invocation.getArgument(0));

        announcementService.createAnnouncement(
                new AnnouncementRequest("Lockers", "Texto", false, true), user(1L, "admin1"));

        verify(eventPublisher).publishEvent(any(AnnouncementPublishedEvent.class));
    }

    @Test
    void givenSilentPublication_whenCreate_thenTheAnnouncementIsSavedButNobodyIsNotified() {
        when(announcementRepository.save(any(Announcement.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Announcement created = announcementService.createAnnouncement(
                new AnnouncementRequest("Lockers", "Texto", false, false), user(1L, "admin1"));

        assertThat(created.getTitle()).isEqualTo("Lockers");
        verify(announcementRepository).save(created);
        verifyNoInteractions(eventPublisher);
    }

    // ---------------------------------------------------------------- modificación

    @Test
    void givenNoChanges_whenUpdate_thenInvalidRequestWithoutTouchingTheDatabase() {
        assertThatThrownBy(() -> announcementService.updateAnnouncement(5L, new AnnouncementUpdateRequest(null, null, null)))
                .isInstanceOf(InvalidRequestException.class);

        verifyNoInteractions(announcementRepository, readRepository);
    }

    @Test
    void givenUnknownAnnouncement_whenUpdate_thenNotFound() {
        when(announcementRepository.findWithAuthorById(5L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> announcementService.updateAnnouncement(5L, new AnnouncementUpdateRequest(null, null, true)))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Aviso");
    }

    @Test
    void givenOnlyPinned_whenUpdate_thenChangesJustThatAndTouchesTheUpdateDate() {
        Announcement announcement = announcement(5L, "Lockers", false);
        when(announcementRepository.findWithAuthorById(5L)).thenReturn(Optional.of(announcement));
        when(announcementRepository.save(announcement)).thenReturn(announcement);

        Announcement updated = announcementService.updateAnnouncement(5L, new AnnouncementUpdateRequest(null, null, true));

        assertThat(updated.isPinned()).isTrue();
        assertThat(updated.getTitle()).isEqualTo("Lockers");
        assertThat(updated.getBody()).isEqualTo("Texto de Lockers");
        assertThat(updated.getCreatedAt()).isEqualTo(LONG_AGO);
        assertThat(updated.getUpdatedAt()).isAfter(LONG_AGO);
    }

    @Test
    void givenTitleAndBody_whenUpdate_thenBothAreReplacedTrimmed() {
        Announcement announcement = announcement(5L, "Lockers", true);
        when(announcementRepository.findWithAuthorById(5L)).thenReturn(Optional.of(announcement));
        when(announcementRepository.save(announcement)).thenReturn(announcement);

        Announcement updated = announcementService.updateAnnouncement(
                5L, new AnnouncementUpdateRequest(" Nuevo título ", " Nuevo texto ", null));

        assertThat(updated.getTitle()).isEqualTo("Nuevo título");
        assertThat(updated.getBody()).isEqualTo("Nuevo texto");
        assertThat(updated.isPinned()).isTrue();
    }

    @Test
    void givenBlankTitle_whenUpdate_thenInvalidRequestAndNothingSaved() {
        Announcement announcement = announcement(5L, "Lockers", false);
        when(announcementRepository.findWithAuthorById(5L)).thenReturn(Optional.of(announcement));

        assertThatThrownBy(() -> announcementService.updateAnnouncement(5L, new AnnouncementUpdateRequest("   ", null, null)))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("título");

        assertThat(announcement.getTitle()).isEqualTo("Lockers");
        verify(announcementRepository, never()).save(any());
    }

    @Test
    void givenBlankBody_whenUpdate_thenInvalidRequestAndNothingSaved() {
        Announcement announcement = announcement(5L, "Lockers", false);
        when(announcementRepository.findWithAuthorById(5L)).thenReturn(Optional.of(announcement));

        assertThatThrownBy(() -> announcementService.updateAnnouncement(5L, new AnnouncementUpdateRequest(null, "  ", null)))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("texto");

        verify(announcementRepository, never()).save(any());
    }

    // ---------------------------------------------------------------- baja

    @Test
    void givenUnknownAnnouncement_whenDelete_thenNotFoundAndNothingDeleted() {
        when(announcementRepository.existsById(5L)).thenReturn(false);

        assertThatThrownBy(() -> announcementService.deleteAnnouncement(5L))
                .isInstanceOf(ResourceNotFoundException.class);

        verify(announcementRepository, never()).deleteById(any());
    }

    @Test
    void givenExistingAnnouncement_whenDelete_thenDeleted() {
        when(announcementRepository.existsById(5L)).thenReturn(true);

        announcementService.deleteAnnouncement(5L);

        verify(announcementRepository).deleteById(5L);
    }

    // ---------------------------------------------------------------- tablón

    @Test
    void givenAPage_whenGetBoard_thenEachAnnouncementSaysWhetherTheReaderHasReadItWithOneReadsQuery() {
        Page<Announcement> page = new PageImpl<>(
                List.of(announcement(1L, "Uno", true), announcement(2L, "Dos", false), announcement(3L, "Tres", false)),
                PageRequest.of(0, 20), 3);
        when(announcementRepository.findAllByOrderByPinnedDescCreatedAtDescIdDesc(PageRequest.of(0, 20))).thenReturn(page);
        when(readRepository.findReadAnnouncementIds(7L, List.of(1L, 2L, 3L))).thenReturn(List.of(2L));

        Page<BoardAnnouncementResponse> board = announcementService.getBoard(user(7L, "driver1"), 0, 20);

        assertThat(board.getContent())
                .extracting(BoardAnnouncementResponse::id, BoardAnnouncementResponse::read)
                .containsExactly(tuple(1L, false), tuple(2L, true), tuple(3L, false));
        assertThat(board.getTotalElements()).isEqualTo(3);
        verify(readRepository).findReadAnnouncementIds(eq(7L), any());
    }

    @Test
    void givenNoAnnouncements_whenGetBoard_thenTheReadsAreNotQueried() {
        when(announcementRepository.findAllByOrderByPinnedDescCreatedAtDescIdDesc(PageRequest.of(0, 20)))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));

        Page<BoardAnnouncementResponse> board = announcementService.getBoard(user(7L, "driver1"), 0, 20);

        assertThat(board.getContent()).isEmpty();
        verifyNoInteractions(readRepository);
    }

    // ---------------------------------------------------------------- lecturas

    @Test
    void givenUnknownAnnouncement_whenMarkAsRead_thenNotFoundAndNothingRegistered() {
        when(announcementRepository.existsById(5L)).thenReturn(false);

        assertThatThrownBy(() -> announcementService.markAsRead(5L, user(7L, "driver1")))
                .isInstanceOf(ResourceNotFoundException.class);

        verifyNoInteractions(readRepository);
    }

    @Test
    void givenExistingAnnouncement_whenMarkAsRead_thenRegistersTheReaderAtTheCurrentTime() {
        when(announcementRepository.existsById(5L)).thenReturn(true);
        LocalDateTime before = LocalDateTime.now();

        announcementService.markAsRead(5L, user(7L, "driver1"));

        verify(readRepository).markAsRead(eq(5L), eq(7L), org.mockito.ArgumentMatchers.argThat(
                readAt -> !readAt.isBefore(before) && !readAt.isAfter(LocalDateTime.now())));
    }

    @Test
    void givenTheAnnouncementDeletedRightAfterTheCheck_whenMarkAsRead_thenNotFound() {
        when(announcementRepository.existsById(5L)).thenReturn(true);
        when(readRepository.markAsRead(eq(5L), eq(7L), any())).thenThrow(new DataIntegrityViolationException("fk"));

        assertThatThrownBy(() -> announcementService.markAsRead(5L, user(7L, "driver1")))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void givenUnknownAnnouncement_whenGetReaders_thenNotFound() {
        when(announcementRepository.existsById(5L)).thenReturn(false);

        assertThatThrownBy(() -> announcementService.getReaders(5L)).isInstanceOf(ResourceNotFoundException.class);

        verifyNoInteractions(readRepository);
    }

    @Test
    void givenExistingAnnouncement_whenGetReaders_thenReturnsTheReadsInTheRepositoryOrder() {
        AnnouncementRead read = new AnnouncementRead();
        read.setUser(user(7L, "driver1"));
        when(announcementRepository.existsById(5L)).thenReturn(true);
        when(readRepository.findByAnnouncementWithUser(5L)).thenReturn(List.of(read));

        assertThat(announcementService.getReaders(5L)).containsExactly(read);
    }

    // ---------------------------------------------------------------- datos de prueba

    private static Announcement announcement(Long id, String title, boolean pinned) {
        Announcement announcement = new Announcement();
        announcement.setId(id);
        announcement.setTitle(title);
        announcement.setBody("Texto de " + title);
        announcement.setPinned(pinned);
        announcement.setCreatedBy(user(1L, "admin1"));
        announcement.setCreatedAt(LONG_AGO);
        announcement.setUpdatedAt(LONG_AGO);
        return announcement;
    }

    private static User user(Long id, String username) {
        User user = new User();
        user.setId(id);
        user.setUsername(username);
        return user;
    }
}
