package com.company.nopar.service;

import com.company.nopar.entity.ShiftAssignment;
import com.company.nopar.entity.User;
import com.company.nopar.entity.Vehicle;
import com.company.nopar.entity.Wave;
import com.company.nopar.entity.WaveCall;
import com.company.nopar.entity.WaveCallRecipient;
import com.company.nopar.exception.InvalidStateException;
import com.company.nopar.exception.ResourceNotFoundException;
import com.company.nopar.repository.ShiftAssignmentRepository;
import com.company.nopar.repository.WaveCallRecipientRepository;
import com.company.nopar.repository.WaveCallRepository;
import com.company.nopar.repository.WaveRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.access.AccessDeniedException;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
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
class WaveCallServiceTest {

    @Mock
    private WaveRepository waveRepository;

    @Mock
    private ShiftAssignmentRepository shiftRepository;

    @Mock
    private WaveCallRepository callRepository;

    @Mock
    private WaveCallRecipientRepository recipientRepository;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @InjectMocks
    private WaveCallService service;

    // ---------------------------------------------------------------- llamar a una ola

    @Test
    void givenDriversWithAShiftToday_whenCallWave_thenSavesTheCallWithItsRecipientsAndPublishesTheEvent() {
        User manager = user(5L, "encargado1", User.Role.MANAGER);
        Wave wave = wave(1L, "Ola 1");
        givenShiftsToday(wave, shift(wave, 2L, "ana", "1111AAA"), shift(wave, 3L, "bea", "2222BBB"));
        givenSaveAssignsId(9L);

        WaveCall call = service.callWave(1L, "  Subid ya  ", manager);

        assertThat(call.getId()).isEqualTo(9L);
        assertThat(call.getWave()).isSameAs(wave);
        assertThat(call.getWorkDate()).isEqualTo(LocalDate.now());
        assertThat(call.getMessage()).isEqualTo("Subid ya");
        assertThat(call.getCalledBy()).isSameAs(manager);
        // la orden vale una hora desde que se da (la ventana por defecto)
        assertThat(call.getExpiresAt()).isEqualTo(call.getCalledAt().plusHours(1));
        assertThat(call.getRecipients()).extracting(r -> r.getDriver().getUsername(), WaveCallRecipient::getVehicleLicensePlate)
                .containsExactly(tuple("ana", "1111AAA"),
                        tuple("bea", "2222BBB"));
        assertThat(call.getRecipients()).allSatisfy(r -> {
            assertThat(r.getWaveCall()).isSameAs(call);
            assertThat(r.isAcknowledged()).isFalse();
        });

        ArgumentCaptor<WaveCalledEvent> event = ArgumentCaptor.forClass(WaveCalledEvent.class);
        verify(eventPublisher).publishEvent(event.capture());
        assertThat(event.getValue()).isEqualTo(
                new WaveCalledEvent(9L, 1L, "Ola 1", LocalDate.now(), "Subid ya", List.of(2L, 3L)));
    }

    @Test
    void givenAConfiguredWindow_whenCallWave_thenTheOrderExpiresAfterIt() {
        Wave wave = wave(1L, "Ola 1");
        givenShiftsToday(wave, shift(wave, 2L, "ana", "1111AAA"));
        givenSaveAssignsId(9L);
        service.setAcknowledgeWindowMinutes(15);

        WaveCall call = service.callWave(1L, "Subid", user(5L, "encargado1", User.Role.MANAGER));

        assertThat(call.getExpiresAt()).isEqualTo(call.getCalledAt().plusMinutes(15));
    }

    @Test
    void givenAWindowThatIsNotPositive_whenConfigure_thenFailsSoTheAppDoesNotStartWithOrdersThatNeverWork() {
        assertThatThrownBy(() -> service.setAcknowledgeWindowMinutes(0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("acknowledge-window-minutes");
        assertThatThrownBy(() -> service.setAcknowledgeWindowMinutes(-5))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void givenNoMessage_whenCallWave_thenTheOrderIsSubid() {
        Wave wave = wave(1L, "Ola 1");
        givenShiftsToday(wave, shift(wave, 2L, "ana", "1111AAA"));
        givenSaveAssignsId(9L);

        assertThat(service.callWave(1L, null, user(5L, "encargado1", User.Role.MANAGER)).getMessage())
                .isEqualTo("Subid");
        assertThat(service.callWave(1L, "   ", user(5L, "encargado1", User.Role.MANAGER)).getMessage())
                .isEqualTo(WaveCallService.DEFAULT_MESSAGE);
    }

    @Test
    void givenNobodyHasAShiftInTheWaveToday_whenCallWave_thenConflictAndNothingIsSavedOrSent() {
        Wave wave = wave(1L, "Ola 1");
        givenShiftsToday(wave);

        assertThatThrownBy(() -> service.callWave(1L, "Subid", user(5L, "encargado1", User.Role.MANAGER)))
                .isInstanceOf(InvalidStateException.class)
                .hasMessageContaining("Ola 1");

        verify(callRepository, never()).saveAndFlush(any());
        verifyNoInteractions(eventPublisher);
    }

    @Test
    void givenAnUnknownWave_whenCallWave_thenNotFound() {
        when(waveRepository.findById(1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.callWave(1L, "Subid", user(5L, "encargado1", User.Role.MANAGER)))
                .isInstanceOf(ResourceNotFoundException.class);

        verifyNoInteractions(shiftRepository, callRepository, eventPublisher);
    }

    // ---------------------------------------------------------------- consultas

    @Test
    void givenAnUnknownCall_whenGetCall_thenNotFound() {
        when(callRepository.findWithDetailsById(7L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getCall(7L)).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void givenACall_whenGetCall_thenItIsReturned() {
        WaveCall call = new WaveCall();
        when(callRepository.findWithDetailsById(7L)).thenReturn(Optional.of(call));

        assertThat(service.getCall(7L)).isSameAs(call);
    }

    @Test
    void whenGetCallsOfDriver_thenAsksForThatDriversCallsOfTheDay() {
        LocalDate day = LocalDate.of(2026, 11, 24);
        WaveCallRecipient recipient = new WaveCallRecipient();
        when(recipientRepository.findOfDriverOnDate(2L, day)).thenReturn(List.of(recipient));

        assertThat(service.getCallsOfDriver(user(2L, "ana", User.Role.DRIVER), day)).containsExactly(recipient);
    }

    // ---------------------------------------------------------------- Enterado

    @Test
    void givenARecipient_whenAcknowledge_thenTheConfirmationTimeIsStored() {
        WaveCallRecipient recipient = recipient(null, LocalDateTime.now().plusMinutes(30));
        when(callRepository.existsById(7L)).thenReturn(true);
        when(recipientRepository.findOfDriver(7L, 2L)).thenReturn(Optional.of(recipient));

        LocalDateTime before = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS);
        WaveCallRecipient result = service.acknowledge(7L, user(2L, "ana", User.Role.DRIVER));

        assertThat(result).isSameAs(recipient);
        assertThat(result.isAcknowledged()).isTrue();
        assertThat(result.getAcknowledgedAt()).isBetween(before, LocalDateTime.now());
    }

    @Test
    void givenAlreadyAcknowledged_whenAcknowledgeAgain_thenTheFirstTimeIsKept() {
        LocalDateTime first = LocalDateTime.of(2026, 11, 24, 9, 26);
        WaveCallRecipient recipient = recipient(first, LocalDateTime.now().plusMinutes(30));
        when(callRepository.existsById(7L)).thenReturn(true);
        when(recipientRepository.findOfDriver(7L, 2L)).thenReturn(Optional.of(recipient));

        WaveCallRecipient result = service.acknowledge(7L, user(2L, "ana", User.Role.DRIVER));

        assertThat(result.getAcknowledgedAt()).isEqualTo(first);
    }

    @Test
    void givenTheOrderHasExpired_whenAcknowledge_thenConflictAndNothingIsStored() {
        WaveCallRecipient recipient = recipient(null, LocalDateTime.now().minusMinutes(1));
        when(callRepository.existsById(7L)).thenReturn(true);
        when(recipientRepository.findOfDriver(7L, 2L)).thenReturn(Optional.of(recipient));

        assertThatThrownBy(() -> service.acknowledge(7L, user(2L, "ana", User.Role.DRIVER)))
                .isInstanceOf(InvalidStateException.class)
                .hasMessageContaining("Ola 1")
                .hasMessageContaining("caducó");

        assertThat(recipient.isAcknowledged()).isFalse();
    }

    @Test
    void givenAlreadyAcknowledgedInTimeAndTheOrderExpiredSince_whenAcknowledgeAgain_thenTheFirstConfirmationIsReturnedWithoutError() {
        LocalDateTime first = LocalDateTime.now().minusMinutes(50);
        WaveCallRecipient recipient = recipient(first, LocalDateTime.now().minusMinutes(1));
        when(callRepository.existsById(7L)).thenReturn(true);
        when(recipientRepository.findOfDriver(7L, 2L)).thenReturn(Optional.of(recipient));

        assertThat(service.acknowledge(7L, user(2L, "ana", User.Role.DRIVER)).getAcknowledgedAt()).isEqualTo(first);
    }

    @Test
    void givenADriverWhoWasNotCalled_whenAcknowledge_thenAccessDenied() {
        when(callRepository.existsById(7L)).thenReturn(true);
        when(recipientRepository.findOfDriver(7L, 4L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.acknowledge(7L, user(4L, "cris", User.Role.DRIVER)))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void givenAnUnknownCall_whenAcknowledge_thenNotFoundBeforeLookingForTheRecipient() {
        when(callRepository.existsById(7L)).thenReturn(false);

        assertThatThrownBy(() -> service.acknowledge(7L, user(2L, "ana", User.Role.DRIVER)))
                .isInstanceOf(ResourceNotFoundException.class);

        verifyNoInteractions(recipientRepository);
    }

    // ---------------------------------------------------------------- volver a llamar a los que faltan

    @Test
    void givenSomeDidNotConfirm_whenRecall_thenTheNewCallKeepsTheConfirmationsAndOnlyNotifiesWhoIsMissing() {
        User manager = user(5L, "encargado1", User.Role.MANAGER);
        Wave wave = wave(1L, "Ola 1");
        LocalDateTime anaConfirmedAt = LocalDateTime.now().minusMinutes(8);
        WaveCall original = existingCall(7L, wave, LocalDate.now(), LocalDateTime.now().minusMinutes(5));
        addRecipient(original, 2L, "ana", "1111AAA", anaConfirmedAt);
        addRecipient(original, 3L, "bea", "2222BBB", null);
        addRecipient(original, 4L, "cris", "3333CCC", null);
        givenTheLatestCallOfTheWaveIs(original);
        when(shiftRepository.findByWorkDateAndWaveId(any(LocalDate.class), eq(1L))).thenReturn(List.of(
                shift(wave, 2L, "ana", "1111AAA"), shift(wave, 3L, "bea", "2222BBB"), shift(wave, 4L, "cris", "3333CCC")));
        givenSaveAssignsId(9L);

        WaveCall again = service.recall(7L, manager);

        assertThat(again.getId()).isEqualTo(9L);
        assertThat(again.getWave()).isSameAs(wave);
        assertThat(again.getMessage()).isEqualTo("Subid");
        assertThat(again.getCalledBy()).isSameAs(manager);
        assertThat(again.getExpiresAt()).isEqualTo(again.getCalledAt().plusHours(1));
        // la llamada nueva es completa: Ana ya había confirmado y conserva su hora; Bea y Cris faltan
        assertThat(again.getRecipients()).extracting(r -> r.getDriver().getUsername(), WaveCallRecipient::getAcknowledgedAt)
                .containsExactly(tuple("ana", anaConfirmedAt), tuple("bea", null), tuple("cris", null));
        // y solo se avisa a quienes faltan
        ArgumentCaptor<WaveCalledEvent> event = ArgumentCaptor.forClass(WaveCalledEvent.class);
        verify(eventPublisher).publishEvent(event.capture());
        assertThat(event.getValue()).isEqualTo(
                new WaveCalledEvent(9L, 1L, "Ola 1", LocalDate.now(), "Subid", List.of(3L, 4L)));
    }

    @Test
    void givenTheOriginalIsStillOpen_whenRecall_thenItIsClosedAtOnceSoNobodyKeepsTwoPendingOrders() {
        Wave wave = wave(1L, "Ola 1");
        WaveCall original = existingCall(7L, wave, LocalDate.now(), LocalDateTime.now().plusMinutes(40));
        addRecipient(original, 2L, "ana", "1111AAA", null);
        givenTheLatestCallOfTheWaveIs(original);
        when(shiftRepository.findByWorkDateAndWaveId(any(LocalDate.class), eq(1L)))
                .thenReturn(List.of(shift(wave, 2L, "ana", "1111AAA")));
        givenSaveAssignsId(9L);

        WaveCall again = service.recall(7L, user(5L, "encargado1", User.Role.MANAGER));

        assertThat(original.getExpiresAt()).isEqualTo(again.getCalledAt());
        assertThat(original.isOpenAt(again.getCalledAt())).isFalse();
    }

    @Test
    void givenAnExpiredOriginal_whenRecall_thenItsExpiryIsLeftAsItWas() {
        Wave wave = wave(1L, "Ola 1");
        LocalDateTime expired = LocalDateTime.now().minusMinutes(5);
        WaveCall original = existingCall(7L, wave, LocalDate.now(), expired);
        addRecipient(original, 2L, "ana", "1111AAA", null);
        givenTheLatestCallOfTheWaveIs(original);
        when(shiftRepository.findByWorkDateAndWaveId(any(LocalDate.class), eq(1L)))
                .thenReturn(List.of(shift(wave, 2L, "ana", "1111AAA")));
        givenSaveAssignsId(9L);

        service.recall(7L, user(5L, "encargado1", User.Role.MANAGER));

        assertThat(original.getExpiresAt()).isEqualTo(expired);
    }

    @Test
    void givenADriverJoinedTheWaveAfterTheCall_whenRecall_thenTheNewcomerIsCalledWithTheCurrentVan() {
        Wave wave = wave(1L, "Ola 1");
        WaveCall original = existingCall(7L, wave, LocalDate.now(), LocalDateTime.now().minusMinutes(5));
        addRecipient(original, 2L, "ana", "1111AAA", LocalDateTime.now().minusMinutes(8));
        givenTheLatestCallOfTheWaveIs(original);
        when(shiftRepository.findByWorkDateAndWaveId(any(LocalDate.class), eq(1L))).thenReturn(List.of(
                shift(wave, 2L, "ana", "1111AAA"), shift(wave, 5L, "dani", "5555EEE")));
        givenSaveAssignsId(9L);

        WaveCall again = service.recall(7L, user(5L, "encargado1", User.Role.MANAGER));

        assertThat(again.getRecipients()).extracting(r -> r.getDriver().getUsername(), WaveCallRecipient::getVehicleLicensePlate,
                        WaveCallRecipient::isAcknowledged)
                .containsExactly(tuple("ana", "1111AAA", true), tuple("dani", "5555EEE", false));
        ArgumentCaptor<WaveCalledEvent> event = ArgumentCaptor.forClass(WaveCalledEvent.class);
        verify(eventPublisher).publishEvent(event.capture());
        assertThat(event.getValue().driverIds()).containsExactly(5L);
    }

    @Test
    void givenAnUnconfirmedDriverLeftTheWave_whenRecall_thenHeIsNotCalled() {
        Wave wave = wave(1L, "Ola 1");
        WaveCall original = existingCall(7L, wave, LocalDate.now(), LocalDateTime.now().minusMinutes(5));
        addRecipient(original, 2L, "ana", "1111AAA", null);
        addRecipient(original, 3L, "bea", "2222BBB", null);
        givenTheLatestCallOfTheWaveIs(original);
        when(shiftRepository.findByWorkDateAndWaveId(any(LocalDate.class), eq(1L)))
                .thenReturn(List.of(shift(wave, 3L, "bea", "2222BBB")));
        givenSaveAssignsId(9L);

        WaveCall again = service.recall(7L, user(5L, "encargado1", User.Role.MANAGER));

        assertThat(again.getRecipients()).extracting(r -> r.getDriver().getUsername()).containsExactly("bea");
    }

    @Test
    void givenEveryoneConfirmed_whenRecall_thenConflictAndNothingIsSavedOrSent() {
        Wave wave = wave(1L, "Ola 1");
        WaveCall original = existingCall(7L, wave, LocalDate.now(), LocalDateTime.now().minusMinutes(5));
        addRecipient(original, 2L, "ana", "1111AAA", LocalDateTime.now().minusMinutes(8));
        givenTheLatestCallOfTheWaveIs(original);
        when(shiftRepository.findByWorkDateAndWaveId(any(LocalDate.class), eq(1L)))
                .thenReturn(List.of(shift(wave, 2L, "ana", "1111AAA")));

        assertThatThrownBy(() -> service.recall(7L, user(5L, "encargado1", User.Role.MANAGER)))
                .isInstanceOf(InvalidStateException.class)
                .hasMessageContaining("no falta nadie");

        verify(callRepository, never()).saveAndFlush(any());
        verifyNoInteractions(eventPublisher);
    }

    @Test
    void givenACallFromAnotherDay_whenRecall_thenConflict() {
        WaveCall original = existingCall(7L, wave(1L, "Ola 1"), LocalDate.now().minusDays(1), LocalDateTime.now().minusDays(1));
        when(callRepository.findWithDetailsById(7L)).thenReturn(Optional.of(original));

        assertThatThrownBy(() -> service.recall(7L, user(5L, "encargado1", User.Role.MANAGER)))
                .isInstanceOf(InvalidStateException.class)
                .hasMessageContaining("de hoy");

        verify(callRepository, never()).saveAndFlush(any());
    }

    @Test
    void givenANewerCallToTheSameWave_whenRecall_thenConflictTellingToRepeatThatOne() {
        Wave wave = wave(1L, "Ola 1");
        WaveCall original = existingCall(7L, wave, LocalDate.now(), LocalDateTime.now().minusMinutes(5));
        addRecipient(original, 2L, "ana", "1111AAA", null);
        WaveCall newer = existingCall(8L, wave, LocalDate.now(), LocalDateTime.now().plusMinutes(30));
        when(callRepository.findWithDetailsById(7L)).thenReturn(Optional.of(original));
        when(callRepository.findFirstByWaveIdAndWorkDateOrderByCalledAtDescIdDesc(1L, LocalDate.now()))
                .thenReturn(Optional.of(newer));

        assertThatThrownBy(() -> service.recall(7L, user(5L, "encargado1", User.Role.MANAGER)))
                .isInstanceOf(InvalidStateException.class)
                .hasMessageContaining("más reciente");

        verify(callRepository, never()).saveAndFlush(any());
        verifyNoInteractions(eventPublisher);
    }

    @Test
    void givenAnUnknownCall_whenRecall_thenNotFound() {
        when(callRepository.findWithDetailsById(7L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.recall(7L, user(5L, "encargado1", User.Role.MANAGER)))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // ---------------------------------------------------------------- cuántos faltan por llamada

    @Test
    void givenTwoWaves_whenGetRecallableCounts_thenOnlyTheLatestCallOfEachWaveWithSomeoneMissingCounts() {
        Wave wave1 = wave(1L, "Ola 1");
        Wave wave2 = wave(2L, "Ola 2");
        LocalDate today = LocalDate.now();
        // la más reciente primero: la 9 es la última de la Ola 1; la 8 es una anterior de la Ola 1; la 7, de la Ola 2
        WaveCall latestOfWave1 = existingCall(9L, wave1, today, LocalDateTime.now().plusMinutes(30));
        addRecipient(latestOfWave1, 2L, "ana", "1111AAA", LocalDateTime.now().minusMinutes(1));
        addRecipient(latestOfWave1, 3L, "bea", "2222BBB", null);
        WaveCall olderOfWave1 = existingCall(8L, wave1, today, LocalDateTime.now().minusHours(1));
        addRecipient(olderOfWave1, 2L, "ana", "1111AAA", null);
        WaveCall onlyCallOfWave2 = existingCall(7L, wave2, today, LocalDateTime.now().plusMinutes(10));
        addRecipient(onlyCallOfWave2, 6L, "eli", "6666FFF", LocalDateTime.now().minusMinutes(2));
        when(callRepository.findWithDetailsByWorkDate(today))
                .thenReturn(List.of(latestOfWave1, olderOfWave1, onlyCallOfWave2));
        // Cris entró en la Ola 1 después; en la Ola 2 solo está Eli, que ya confirmó
        when(shiftRepository.findInRange(today, today)).thenReturn(List.of(
                shift(wave1, 2L, "ana", "1111AAA"), shift(wave1, 3L, "bea", "2222BBB"), shift(wave1, 4L, "cris", "3333CCC"),
                shift(wave2, 6L, "eli", "6666FFF")));

        Map<Long, Integer> counts = service.getRecallableCounts(today);

        // la 9 llamaría a Bea (no confirmó) y a Cris (nuevo); la 8 no es la última; la 7 no tiene a nadie pendiente
        assertThat(counts).containsExactly(Map.entry(9L, 2));
    }

    @Test
    void givenADayThatIsNotToday_whenGetRecallableCounts_thenNothingCanBeRepeatedAndNothingIsQueried() {
        assertThat(service.getRecallableCounts(LocalDate.now().minusDays(1))).isEmpty();

        verifyNoInteractions(callRepository, shiftRepository);
    }

    @Test
    void givenNoCallsToday_whenGetRecallableCounts_thenEmptyWithoutLoadingTheSchedule() {
        when(callRepository.findWithDetailsByWorkDate(LocalDate.now())).thenReturn(List.of());

        assertThat(service.getRecallableCounts(LocalDate.now())).isEmpty();

        verifyNoInteractions(shiftRepository);
    }

    // ---------------------------------------------------------------- auxiliares

    private void givenTheLatestCallOfTheWaveIs(WaveCall call) {
        when(callRepository.findWithDetailsById(call.getId())).thenReturn(Optional.of(call));
        when(callRepository.findFirstByWaveIdAndWorkDateOrderByCalledAtDescIdDesc(call.getWave().getId(), LocalDate.now()))
                .thenReturn(Optional.of(call));
    }

    /** Una llamada ya guardada a [wave] con el mensaje "Subid", sin destinatarios (se añaden con addRecipient). */
    private static WaveCall existingCall(Long id, Wave wave, LocalDate workDate, LocalDateTime expiresAt) {
        WaveCall call = new WaveCall();
        call.setId(id);
        call.setWave(wave);
        call.setWorkDate(workDate);
        call.setMessage("Subid");
        call.setCalledBy(user(5L, "encargado1", User.Role.MANAGER));
        call.setCalledAt(LocalDateTime.now().minusMinutes(10));
        call.setExpiresAt(expiresAt);
        return call;
    }

    private static void addRecipient(WaveCall call, Long driverId, String username, String plate,
                                     LocalDateTime acknowledgedAt) {
        WaveCallRecipient recipient = new WaveCallRecipient();
        recipient.setWaveCall(call);
        recipient.setDriver(user(driverId, username, User.Role.DRIVER));
        recipient.setVehicleLicensePlate(plate);
        recipient.setAcknowledgedAt(acknowledgedAt);
        call.getRecipients().add(recipient);
    }

    private void givenShiftsToday(Wave wave, ShiftAssignment... shifts) {
        when(waveRepository.findById(wave.getId())).thenReturn(Optional.of(wave));
        when(shiftRepository.findByWorkDateAndWaveId(any(LocalDate.class), eq(wave.getId())))
                .thenReturn(List.of(shifts));
    }

    private void givenSaveAssignsId(Long id) {
        when(callRepository.saveAndFlush(any(WaveCall.class))).thenAnswer(invocation -> {
            WaveCall call = invocation.getArgument(0);
            call.setId(id);
            return call;
        });
    }

    /** Un destinatario de una llamada a "Ola 1" que vale hasta expiresAt. */
    private static WaveCallRecipient recipient(LocalDateTime acknowledgedAt, LocalDateTime expiresAt) {
        WaveCall call = new WaveCall();
        call.setId(7L);
        call.setWave(wave(1L, "Ola 1"));
        call.setExpiresAt(expiresAt);
        WaveCallRecipient recipient = new WaveCallRecipient();
        recipient.setWaveCall(call);
        recipient.setAcknowledgedAt(acknowledgedAt);
        return recipient;
    }

    private static Wave wave(Long id, String name) {
        Wave wave = new Wave();
        wave.setId(id);
        wave.setName(name);
        wave.setStartTime(LocalTime.of(9, 30));
        return wave;
    }

    private static ShiftAssignment shift(Wave wave, Long driverId, String username, String plate) {
        Vehicle van = new Vehicle();
        van.setLicensePlate(plate);
        ShiftAssignment shift = new ShiftAssignment();
        shift.setWave(wave);
        shift.setDriver(user(driverId, username, User.Role.DRIVER));
        shift.setVehicle(van);
        shift.setWorkDate(LocalDate.now());
        return shift;
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
