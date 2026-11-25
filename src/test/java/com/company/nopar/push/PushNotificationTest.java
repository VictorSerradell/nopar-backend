package com.company.nopar.push;

import com.company.nopar.service.ShiftChangedEvent;
import com.company.nopar.service.WaveCalledEvent;
import com.company.nopar.service.WaveChatMessageEvent;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PushNotificationTest {

    @Test
    void givenAnAnnouncement_whenForAnnouncement_thenTitleBodyAndTheDataToOpenIt() {
        PushNotification notification = PushNotification.forAnnouncement(5L, "Lockers", "Recordad cerrar la puerta");

        assertThat(notification.title()).isEqualTo("Lockers");
        assertThat(notification.body()).isEqualTo("Recordad cerrar la puerta");
        assertThat(notification.data()).isEqualTo(Map.of("type", "announcement", "announcementId", "5"));
    }

    @Test
    void givenALongMultilineText_whenForAnnouncement_thenOneLineCutWithAnEllipsis() {
        String body = ("Primera línea\n\n   segunda línea " + "x".repeat(300));

        PushNotification notification = PushNotification.forAnnouncement(5L, "Aviso", body);

        assertThat(notification.body()).startsWith("Primera línea segunda línea x").endsWith("…");
        assertThat(notification.body().codePointCount(0, notification.body().length()))
                .isEqualTo(PushNotification.MAX_BODY_CHARS);
    }

    @Test
    void givenATextOfExactlyTheLimit_whenAbbreviate_thenItIsUntouched() {
        String text = "y".repeat(PushNotification.MAX_BODY_CHARS);

        assertThat(PushNotification.abbreviate(text, PushNotification.MAX_BODY_CHARS)).isEqualTo(text);
    }

    @Test
    void givenCharactersOutsideTheBasicPlane_whenAbbreviate_thenTheyAreNeverCutInHalf() {
        String emoji = new String(Character.toChars(0x1F600));

        String result = PushNotification.abbreviate(emoji.repeat(200), 140);

        assertThat(result).isEqualTo(emoji.repeat(139) + "…");
    }

    @Test
    void givenNoBody_whenForAnnouncement_thenEmptyBody() {
        assertThat(PushNotification.forAnnouncement(5L, "Aviso", null).body()).isEmpty();
    }

    @Test
    void givenNoId_whenForAnnouncement_thenTheDataOnlyHasTheType() {
        assertThat(PushNotification.forAnnouncement(null, "Aviso", "Texto").data())
                .isEqualTo(Map.of("type", "announcement"));
    }

    @Test
    void givenNoTitle_whenCreate_thenFails() {
        assertThatThrownBy(() -> new PushNotification(null, "Texto", null)).isInstanceOf(NullPointerException.class);
    }

    // 25 de noviembre de 2026 es miércoles
    private static ShiftChangedEvent shiftEvent(ShiftChangedEvent.Change change, Long shiftId) {
        return new ShiftChangedEvent(change, shiftId, 2L, LocalDate.of(2026, 11, 25), "Ola 1", LocalTime.of(9, 30),
                "1111AAA");
    }

    @Test
    void givenANewShift_whenForShift_thenTheDayWaveAndVanAndTheDataToOpenTheSchedule() {
        PushNotification notification = PushNotification.forShift(shiftEvent(ShiftChangedEvent.Change.ASSIGNED, 4L));

        assertThat(notification.title()).isEqualTo("Nuevo turno");
        assertThat(notification.body()).isEqualTo("Miércoles 25 de noviembre · Ola 1 (09:30) · furgoneta 1111AAA");
        assertThat(notification.data()).isEqualTo(
                Map.of("type", "shift", "change", "assigned", "workDate", "2026-11-25", "shiftId", "4"));
    }

    @Test
    void givenAModifiedShift_whenForShift_thenItSaysSoWithTheNewDetails() {
        PushNotification notification = PushNotification.forShift(shiftEvent(ShiftChangedEvent.Change.UPDATED, 4L));

        assertThat(notification.title()).isEqualTo("Turno modificado");
        assertThat(notification.body()).isEqualTo("Miércoles 25 de noviembre · Ola 1 (09:30) · furgoneta 1111AAA");
        assertThat(notification.data()).containsEntry("change", "updated");
    }

    @Test
    void givenACancelledShift_whenForShift_thenItNamesTheDayAndWaveButNotTheVan() {
        PushNotification notification = PushNotification.forShift(shiftEvent(ShiftChangedEvent.Change.CANCELLED, 4L));

        assertThat(notification.title()).isEqualTo("Turno cancelado");
        assertThat(notification.body()).isEqualTo("Miércoles 25 de noviembre · Ola 1 (09:30)");
        assertThat(notification.data()).containsEntry("change", "cancelled");
    }

    @Test
    void givenNoShiftId_whenForShift_thenTheDataOmitsIt() {
        PushNotification notification = PushNotification.forShift(shiftEvent(ShiftChangedEvent.Change.ASSIGNED, null));

        assertThat(notification.data()).doesNotContainKey("shiftId").containsEntry("type", "shift");
    }

    @Test
    void givenAWaveCall_whenForWaveCall_thenTheTitleSaysWaveAndOrderAndTheDataToOpenIt() {
        WaveCalledEvent event = new WaveCalledEvent(
                7L, 1L, "Ola 1", LocalDate.of(2026, 11, 24), "Subid", List.of(2L, 3L));

        PushNotification notification = PushNotification.forWaveCall(event);

        assertThat(notification.title()).isEqualTo("Ola 1: Subid");
        assertThat(notification.body()).isEqualTo("Abre la app y pulsa «Enterado»");
        assertThat(notification.data()).isEqualTo(
                Map.of("type", "wave_call", "waveCallId", "7", "waveId", "1", "workDate", "2026-11-24"));
    }

    @Test
    void givenAWaveCall_whenAndroidChannelId_thenItUsesTheWaveCallChannel() {
        WaveCalledEvent event = new WaveCalledEvent(
                7L, 1L, "Ola 1", LocalDate.of(2026, 11, 24), "Subid", List.of(2L));

        assertThat(PushNotification.forWaveCall(event).androidChannelId()).isEqualTo("nopar_wave_calls");
    }

    @Test
    void givenATextMessage_whenForChatMessage_thenTheWaveIsTheTitleAndTheBodySaysWhoWrote() {
        PushNotification notification = PushNotification.forChatMessage(chatEvent("Llego en 5 minutos", false));

        assertThat(notification.title()).isEqualTo("Ola 1");
        assertThat(notification.body()).isEqualTo("ana: Llego en 5 minutos");
        assertThat(notification.data()).isEqualTo(
                Map.of("type", "chat", "waveId", "1", "waveName", "Ola 1", "workDate", "2026-11-24", "messageId", "9"));
    }

    @Test
    void givenAPhotoMessage_whenForChatMessage_thenTheBodyMentionsThePhoto() {
        assertThat(PushNotification.forChatMessage(chatEvent(null, true)).body()).isEqualTo("ana: 📷 Foto");
        assertThat(PushNotification.forChatMessage(chatEvent("  ", true)).body()).isEqualTo("ana: 📷 Foto");
        assertThat(PushNotification.forChatMessage(chatEvent("Mirad este golpe", true)).body())
                .isEqualTo("ana: 📷 Mirad este golpe");
    }

    @Test
    void givenALongMessage_whenForChatMessage_thenTheBodyIsOneLineCutAtTheLimit() {
        String body = PushNotification.forChatMessage(chatEvent("línea uno\n" + "x".repeat(500), false)).body();

        assertThat(body).doesNotContain("\n").endsWith("…");
        assertThat(body.codePointCount(0, body.length())).isEqualTo(PushNotification.MAX_BODY_CHARS);
    }

    @Test
    void givenAChatMessage_whenAndroidChannelId_thenNoneSoItSoundsInTheDefaultChannel() {
        assertThat(PushNotification.forChatMessage(chatEvent("hola", false)).androidChannelId()).isNull();
    }

    private static WaveChatMessageEvent chatEvent(String text, boolean hasPhoto) {
        return new WaveChatMessageEvent(
                9L, 1L, "Ola 1", LocalDate.of(2026, 11, 24), "ana", text, hasPhoto, List.of(3L));
    }

    @Test
    void givenAnnouncementsAndShifts_whenAndroidChannelId_thenNoneSoAndroidUsesTheDefaultOne() {
        assertThat(PushNotification.forAnnouncement(5L, "Lockers", "Texto").androidChannelId()).isNull();
        assertThat(new PushNotification("Titulo", "Cuerpo", null).androidChannelId()).isNull();
    }
}
