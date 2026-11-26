package com.company.nopar.service;

import com.company.nopar.entity.ShiftAssignment;
import com.company.nopar.entity.User;
import com.company.nopar.entity.Wave;
import com.company.nopar.entity.WaveMessage;
import com.company.nopar.exception.InvalidFileException;
import com.company.nopar.exception.InvalidRequestException;
import com.company.nopar.exception.ResourceNotFoundException;
import com.company.nopar.repository.ShiftAssignmentRepository;
import com.company.nopar.repository.UserRepository;
import com.company.nopar.repository.WaveChatReadRepository;
import com.company.nopar.repository.WaveMessageRepository;
import com.company.nopar.repository.WaveRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.access.AccessDeniedException;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WaveChatServiceTest {

    @Mock
    private WaveRepository waveRepository;

    @Mock
    private ShiftAssignmentRepository shiftRepository;

    @Mock
    private WaveMessageRepository messageRepository;

    @Mock
    private WaveChatReadRepository readRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private FileStorageService fileStorage;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @InjectMocks
    private WaveChatService service;

    private final User ana = user(2L, "ana", User.Role.DRIVER);
    private final User manager = user(5L, "encargado1", User.Role.MANAGER);
    private final User admin = user(1L, "admin1", User.Role.ADMIN);

    // ---------------------------------------------------------------- leer el chat

    @Test
    void givenAnUnknownWave_whenGetMessages_thenNotFoundAndNothingIsRead() {
        when(waveRepository.findById(9L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getMessages(9L, null, 100, ana))
                .isInstanceOf(ResourceNotFoundException.class);

        verifyNoInteractions(messageRepository);
    }

    @Test
    void givenADriverWithoutAShiftInThatWaveToday_whenGetMessages_thenForbiddenAndNothingIsRead() {
        givenWave();
        givenDriverHasShift(ana, false);

        assertThatThrownBy(() -> service.getMessages(1L, null, 100, ana))
                .isInstanceOf(AccessDeniedException.class);

        verifyNoInteractions(messageRepository);
    }

    @Test
    void givenADriverOfTheWave_whenOpeningTheChat_thenTheLatestMessagesComeInArrivalOrder() {
        givenWave();
        givenDriverHasShift(ana, true);
        WaveMessage first = message(10L, ana, "hola");
        WaveMessage second = message(11L, manager, "buenos días");
        // El repositorio devuelve el más reciente primero; el chat se lee de más antiguo a más reciente
        when(messageRepository.findLatest(eq(1L), eq(LocalDate.now()), any(Pageable.class)))
                .thenReturn(List.of(second, first));

        List<WaveMessage> messages = service.getMessages(1L, null, 100, ana);

        assertThat(messages).containsExactly(first, second);
    }

    @Test
    void givenTheLastMessageTheAppHas_whenGetMessages_thenOnlyTheNewerOnesAreRequested() {
        givenWave();
        givenDriverHasShift(ana, true);
        WaveMessage newer = message(12L, manager, "subid");
        when(messageRepository.findAfter(eq(1L), eq(LocalDate.now()), eq(11L), any(Pageable.class)))
                .thenReturn(List.of(newer));

        assertThat(service.getMessages(1L, 11L, 100, ana)).containsExactly(newer);

        verify(messageRepository, never()).findLatest(any(), any(), any());
    }

    @Test
    void givenAManagerOrAnAdmin_whenGetMessages_thenAnyWaveIsOpenWithoutLookingUpShifts() {
        givenWave();
        when(messageRepository.findLatest(eq(1L), eq(LocalDate.now()), any(Pageable.class))).thenReturn(List.of());

        assertThat(service.getMessages(1L, null, 100, manager)).isEmpty();
        assertThat(service.getMessages(1L, null, 100, admin)).isEmpty();

        verifyNoInteractions(shiftRepository);
    }

    @Test
    void givenALimitOutOfRange_whenGetMessages_thenItIsKeptBetweenOneAndTheMaximum() {
        givenWave();
        when(messageRepository.findLatest(eq(1L), eq(LocalDate.now()), any(Pageable.class))).thenReturn(List.of());

        service.getMessages(1L, null, 5000, manager);
        service.getMessages(1L, null, 0, manager);

        ArgumentCaptor<Pageable> page = ArgumentCaptor.forClass(Pageable.class);
        verify(messageRepository, times(2)).findLatest(eq(1L), any(LocalDate.class), page.capture());
        assertThat(page.getAllValues()).extracting(Pageable::getPageSize).containsExactly(200, 1);
    }

    // ---------------------------------------------------------------- escribir

    @Test
    void givenADriverOfTheWave_whenSendText_thenItIsSavedTrimmedAndTheOthersAreNotified() {
        givenWave();
        givenDriverHasShift(ana, true);
        givenSaveAssignsId(9L);
        givenRecipients(List.of(2L, 3L), List.of(5L, 1L));

        WaveMessage saved = service.send(1L, "  Llego en 5 minutos  ", null, ana);

        assertThat(saved.getId()).isEqualTo(9L);
        assertThat(saved.getWaveId()).isEqualTo(1L);
        assertThat(saved.getWorkDate()).isEqualTo(LocalDate.now());
        assertThat(saved.getSender()).isSameAs(ana);
        assertThat(saved.getBody()).isEqualTo("Llego en 5 minutos");
        assertThat(saved.getPhotoFile()).isNull();
        assertThat(saved.getSentAt()).isBetween(LocalDateTime.now().minusMinutes(1), LocalDateTime.now().plusSeconds(1));

        // Se avisa a los demás conductores de la ola y a los encargados y administradores, pero no a quien escribe
        ArgumentCaptor<WaveChatMessageEvent> event = ArgumentCaptor.forClass(WaveChatMessageEvent.class);
        verify(eventPublisher).publishEvent(event.capture());
        assertThat(event.getValue()).isEqualTo(new WaveChatMessageEvent(
                9L, 1L, "Ola 1", LocalDate.now(), "ana", "Llego en 5 minutos", false, List.of(3L, 5L, 1L)));
        verifyNoInteractions(fileStorage);
    }

    @Test
    void givenAPhotoWithoutText_whenSend_thenThePhotoIsStoredAndTheMessageHasNoText() {
        givenWave();
        givenDriverHasShift(ana, true);
        givenSaveAssignsId(9L);
        givenRecipients(List.of(2L), List.of(5L));
        MockMultipartFile photo = photo();
        when(fileStorage.storeFile(photo)).thenReturn("abc.jpg");

        WaveMessage saved = service.send(1L, "   ", photo, ana);

        assertThat(saved.getBody()).isNull();
        assertThat(saved.getPhotoFile()).isEqualTo("abc.jpg");
        assertThat(saved.hasPhoto()).isTrue();
        ArgumentCaptor<WaveChatMessageEvent> event = ArgumentCaptor.forClass(WaveChatMessageEvent.class);
        verify(eventPublisher).publishEvent(event.capture());
        assertThat(event.getValue().text()).isNull();
        assertThat(event.getValue().hasPhoto()).isTrue();
        assertThat(event.getValue().recipientIds()).containsExactly(5L);
    }

    @Test
    void givenATextAndAPhoto_whenSend_thenBothAreKept() {
        givenWave();
        givenSaveAssignsId(9L);
        givenRecipients(List.of(2L), List.of());
        MockMultipartFile photo = photo();
        when(fileStorage.storeFile(photo)).thenReturn("abc.jpg");

        WaveMessage saved = service.send(1L, "Mirad este golpe", photo, manager);

        assertThat(saved.getBody()).isEqualTo("Mirad este golpe");
        assertThat(saved.getPhotoFile()).isEqualTo("abc.jpg");
    }

    @Test
    void givenAnEmptyMessage_whenSend_thenBadRequestAndNothingIsStoredOrSent() {
        givenWave();
        givenDriverHasShift(ana, true);
        MockMultipartFile emptyFile = new MockMultipartFile("photo", "x.jpg", "image/jpeg", new byte[0]);

        assertThatThrownBy(() -> service.send(1L, "  \n ", null, ana)).isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> service.send(1L, null, emptyFile, ana)).isInstanceOf(InvalidRequestException.class);

        verifyNoInteractions(fileStorage, messageRepository, eventPublisher);
    }

    @Test
    void givenATextOverTheLimit_whenSend_thenBadRequestButTheLimitCountsCharactersNotBytes() {
        givenWave();
        givenSaveAssignsId(9L);
        givenRecipients(List.of(), List.of());

        assertThatThrownBy(() -> service.send(1L, "a".repeat(WaveChatService.MAX_TEXT_CHARS + 1), null, manager))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("1000");
        verifyNoInteractions(messageRepository);

        // Mil emojis son dos mil unidades de UTF-16 y mucho más de mil bytes, pero son mil caracteres: caben
        String emojis = "😀".repeat(WaveChatService.MAX_TEXT_CHARS);
        assertThat(service.send(1L, emojis, null, manager).getBody()).isEqualTo(emojis);
    }

    @Test
    void givenADriverWithoutAShiftInThatWave_whenSend_thenForbiddenAndNothingIsStoredOrSent() {
        givenWave();
        givenDriverHasShift(ana, false);

        assertThatThrownBy(() -> service.send(1L, "hola", photo(), ana)).isInstanceOf(AccessDeniedException.class);

        verifyNoInteractions(fileStorage, messageRepository, eventPublisher);
    }

    @Test
    void givenAFileThatIsNotAnImage_whenSend_thenItIsRejectedAndNothingIsSaved() {
        givenWave();
        MockMultipartFile notAnImage = new MockMultipartFile("photo", "x.pdf", "application/pdf", new byte[]{1, 2, 3});
        when(fileStorage.storeFile(notAnImage)).thenThrow(new InvalidFileException("Formato de archivo no permitido"));

        assertThatThrownBy(() -> service.send(1L, "mirad", notAnImage, manager))
                .isInstanceOf(InvalidFileException.class);

        verifyNoInteractions(messageRepository, eventPublisher);
    }

    @Test
    void givenTheMessageCannotBeSaved_whenSendWithAPhoto_thenThePhotoIsRemovedFromDisk() {
        givenWave();
        MockMultipartFile photo = photo();
        when(fileStorage.storeFile(photo)).thenReturn("abc.jpg");
        when(messageRepository.save(any(WaveMessage.class))).thenThrow(new IllegalStateException("base de datos caída"));

        assertThatThrownBy(() -> service.send(1L, null, photo, manager)).isInstanceOf(IllegalStateException.class);

        verify(fileStorage).delete("abc.jpg");
        verifyNoInteractions(eventPublisher);
    }

    @Test
    void givenNobodyElseToNotify_whenSend_thenNoEventIsPublished() {
        givenWave();
        givenSaveAssignsId(9L);
        givenRecipients(List.of(), List.of(5L));

        assertThat(service.send(1L, "¿hay alguien?", null, manager).getId()).isEqualTo(9L);

        verify(eventPublisher, never()).publishEvent(any(Object.class));
    }

    // ---------------------------------------------------------------- fotos

    @Test
    void givenADriverOfTheWave_whenGetPhoto_thenTheStoredFileIsReturned() {
        givenWave();
        givenDriverHasShift(ana, true);
        WaveMessage withPhoto = message(10L, manager, null);
        withPhoto.setPhotoFile("abc.jpg");
        when(messageRepository.findByIdAndWaveId(10L, 1L)).thenReturn(Optional.of(withPhoto));
        FileStorageService.StoredFile stored =
                new FileStorageService.StoredFile(new ByteArrayResource(new byte[]{1}), MediaType.IMAGE_JPEG);
        when(fileStorage.load("abc.jpg")).thenReturn(stored);

        assertThat(service.getPhoto(1L, 10L, ana)).isSameAs(stored);
    }

    @Test
    void givenAMessageOfAnotherWaveOrWithoutPhoto_whenGetPhoto_thenNotFound() {
        givenWave();
        when(messageRepository.findByIdAndWaveId(77L, 1L)).thenReturn(Optional.empty());
        WaveMessage onlyText = message(10L, manager, "hola");
        when(messageRepository.findByIdAndWaveId(10L, 1L)).thenReturn(Optional.of(onlyText));

        assertThatThrownBy(() -> service.getPhoto(1L, 77L, manager)).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.getPhoto(1L, 10L, manager)).isInstanceOf(ResourceNotFoundException.class);

        verifyNoInteractions(fileStorage);
    }

    @Test
    void givenAPhotoFromAnotherDay_whenGetPhoto_thenOnlyManagersAndAdminsSeeIt() {
        givenWave();
        givenDriverHasShift(ana, true);
        WaveMessage yesterday = message(10L, manager, null);
        yesterday.setPhotoFile("abc.jpg");
        yesterday.setWorkDate(LocalDate.now().minusDays(1));
        when(messageRepository.findByIdAndWaveId(10L, 1L)).thenReturn(Optional.of(yesterday));
        FileStorageService.StoredFile stored =
                new FileStorageService.StoredFile(new ByteArrayResource(new byte[]{1}), MediaType.IMAGE_JPEG);
        when(fileStorage.load("abc.jpg")).thenReturn(stored);

        assertThatThrownBy(() -> service.getPhoto(1L, 10L, ana)).isInstanceOf(AccessDeniedException.class);
        assertThat(service.getPhoto(1L, 10L, admin)).isSameAs(stored);
    }

    @Test
    void givenADriverWithoutAShiftInThatWave_whenGetPhoto_thenForbidden() {
        givenWave();
        givenDriverHasShift(ana, false);

        assertThatThrownBy(() -> service.getPhoto(1L, 10L, ana)).isInstanceOf(AccessDeniedException.class);

        verifyNoInteractions(messageRepository, fileStorage);
    }

    // ---------------------------------------------------------------- marcar como leido

    @Test
    void givenAnUnknownWave_whenMarkRead_thenNotFoundAndNothingIsWritten() {
        when(waveRepository.findById(9L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.markRead(9L, 5L, ana)).isInstanceOf(ResourceNotFoundException.class);

        verifyNoInteractions(readRepository);
    }

    @Test
    void givenADriverWithoutAShiftInThatWaveToday_whenMarkRead_thenForbiddenAndNothingIsWritten() {
        givenWave();
        givenDriverHasShift(ana, false);

        assertThatThrownBy(() -> service.markRead(1L, 5L, ana)).isInstanceOf(AccessDeniedException.class);

        verifyNoInteractions(readRepository);
    }

    @Test
    void givenNoMessagesToday_whenMarkRead_thenNothingIsWritten() {
        givenWave();
        givenDriverHasShift(ana, true);
        when(messageRepository.findNewestId(1L, LocalDate.now())).thenReturn(null);

        service.markRead(1L, 5L, ana);

        verifyNoInteractions(readRepository);
    }

    @Test
    void givenAMessageTheAppHasShown_whenMarkRead_thenTheMarkerGoesUpToIt() {
        givenWave();
        givenDriverHasShift(ana, true);
        when(messageRepository.findNewestId(1L, LocalDate.now())).thenReturn(12L);

        service.markRead(1L, 11L, ana);

        verify(readRepository).markRead(eq(2L), eq(1L), eq(11L), any(LocalDateTime.class));
    }

    @Test
    void givenAnIdBeyondTheNewestMessage_whenMarkRead_thenItIsCutToTheNewest() {
        givenWave();
        givenDriverHasShift(ana, true);
        when(messageRepository.findNewestId(1L, LocalDate.now())).thenReturn(12L);

        service.markRead(1L, 500L, ana);

        verify(readRepository).markRead(eq(2L), eq(1L), eq(12L), any(LocalDateTime.class));
    }

    @Test
    void givenAManager_whenMarkRead_thenNoShiftIsNeeded() {
        givenWave();
        when(messageRepository.findNewestId(1L, LocalDate.now())).thenReturn(12L);

        service.markRead(1L, 12L, manager);

        verify(readRepository).markRead(eq(5L), eq(1L), eq(12L), any(LocalDateTime.class));
        verifyNoInteractions(shiftRepository);
    }

    // ---------------------------------------------------------------- mensajes sin leer

    @Test
    void givenADriverWithoutAShiftToday_whenGetUnread_thenNothingAndNoCounting() {
        when(shiftRepository.findByWorkDateAndDriverId(any(LocalDate.class), eq(2L))).thenReturn(Optional.empty());

        assertThat(service.getUnread(ana)).isEmpty();

        verifyNoInteractions(readRepository);
    }

    @Test
    void givenADriverOfAWave_whenGetUnread_thenOnlyThatWaveCounts() {
        when(shiftRepository.findByWorkDateAndDriverId(any(LocalDate.class), eq(2L)))
                .thenReturn(Optional.of(shift(ana, 1L)));
        when(readRepository.countUnread(eq(2L), any(LocalDate.class)))
                .thenReturn(List.of(unreadRow(1L, 3L), unreadRow(2L, 7L)));

        assertThat(service.getUnread(ana)).containsExactly(Map.entry(1L, 3L));
    }

    @Test
    void givenAManager_whenGetUnread_thenEveryWaveWithUnreadMessagesCountsAndNoShiftIsLooked() {
        when(readRepository.countUnread(eq(5L), any(LocalDate.class)))
                .thenReturn(List.of(unreadRow(1L, 3L), unreadRow(2L, 7L), unreadRow(3L, 0L)));

        assertThat(service.getUnread(manager)).containsExactly(Map.entry(1L, 3L), Map.entry(2L, 7L));

        verifyNoInteractions(shiftRepository);
    }

    @Test
    void givenNothingUnread_whenGetUnread_thenAnEmptyMap() {
        when(readRepository.countUnread(eq(1L), any(LocalDate.class))).thenReturn(List.of());

        assertThat(service.getUnread(admin)).isEmpty();
    }

    // ---------------------------------------------------------------- retencion

    @Test
    void givenOldMessagesWithPhotos_whenDeleteMessagesBefore_thenTheRowsAndTheirPhotosAreDeleted() {
        LocalDate cutoff = LocalDate.now().minusDays(30);
        when(messageRepository.findPhotoFilesBefore(cutoff)).thenReturn(List.of("a.jpg", "b.png"));
        when(messageRepository.deleteOlderThan(cutoff)).thenReturn(5);

        assertThat(service.deleteMessagesBefore(cutoff)).isEqualTo(5);

        verify(fileStorage).delete("a.jpg");
        verify(fileStorage).delete("b.png");
    }

    @Test
    void givenNothingOld_whenDeleteMessagesBefore_thenNoPhotoIsTouched() {
        LocalDate cutoff = LocalDate.now().minusDays(30);
        when(messageRepository.findPhotoFilesBefore(cutoff)).thenReturn(List.of());
        when(messageRepository.deleteOlderThan(cutoff)).thenReturn(0);

        assertThat(service.deleteMessagesBefore(cutoff)).isZero();

        verifyNoInteractions(fileStorage);
    }

    // ---------------------------------------------------------------- datos de prueba

    private void givenWave() {
        Wave wave = new Wave();
        wave.setId(1L);
        wave.setName("Ola 1");
        wave.setStartTime(LocalTime.of(9, 30));
        when(waveRepository.findById(1L)).thenReturn(Optional.of(wave));
    }

    private void givenDriverHasShift(User driver, boolean hasShift) {
        when(shiftRepository.existsByWorkDateAndDriverIdAndWaveId(any(LocalDate.class), eq(driver.getId()), eq(1L)))
                .thenReturn(hasShift);
    }

    private void givenSaveAssignsId(Long id) {
        when(messageRepository.save(any(WaveMessage.class))).thenAnswer(invocation -> {
            WaveMessage message = invocation.getArgument(0);
            message.setId(id);
            return message;
        });
    }

    private void givenRecipients(List<Long> driverIds, List<Long> staffIds) {
        when(shiftRepository.findDriverIdsByWorkDateAndWaveId(any(LocalDate.class), eq(1L))).thenReturn(driverIds);
        when(userRepository.findIdsByRoleIn(anyCollection())).thenReturn(staffIds);
    }

    private static Object[] unreadRow(Long waveId, Long count) {
        return new Object[]{waveId, count};
    }

    private static ShiftAssignment shift(User driver, Long waveId) {
        Wave wave = new Wave();
        wave.setId(waveId);
        ShiftAssignment shift = new ShiftAssignment();
        shift.setDriver(driver);
        shift.setWave(wave);
        return shift;
    }

    private static WaveMessage message(Long id, User sender, String body) {
        WaveMessage message = new WaveMessage();
        message.setId(id);
        message.setWaveId(1L);
        message.setWorkDate(LocalDate.now());
        message.setSender(sender);
        message.setBody(body);
        message.setSentAt(LocalDateTime.now());
        return message;
    }

    private static MockMultipartFile photo() {
        return new MockMultipartFile("photo", "x.jpg", "image/jpeg", new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF});
    }

    private static User user(Long id, String username, User.Role role) {
        User user = new User();
        user.setId(id);
        user.setUsername(username);
        user.setPassword("not-a-real-hash");
        user.setRole(role);
        return user;
    }
}
