package com.company.nopar.push;

import org.junit.jupiter.api.Test;

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
}
