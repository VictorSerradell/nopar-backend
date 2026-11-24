package com.company.nopar.push;

import com.company.nopar.service.AnnouncementPublishedEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.scheduling.annotation.Async;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class AnnouncementPushListenerTest {

    private final AnnouncementPublishedEvent event = new AnnouncementPublishedEvent(9L, "Lockers", "Recordad cerrar la puerta");

    @Mock
    private PushService pushService;

    @InjectMocks
    private AnnouncementPushListener listener;

    @Test
    void givenAPublishedAnnouncement_whenTheEventArrives_thenTheDriversAreNotifiedWithItsTitleBodyAndId() {
        listener.onAnnouncementPublished(event);

        verify(pushService).notifyDrivers(argThat(notification ->
                "Lockers".equals(notification.title())
                        && "Recordad cerrar la puerta".equals(notification.body())
                        && "9".equals(notification.data().get("announcementId"))));
    }

    @Test
    void givenTheSendingFails_whenTheEventArrives_thenItDoesNotPropagate() {
        doThrow(new IllegalStateException("sin red")).when(pushService).notifyDrivers(any());

        assertThatCode(() -> listener.onAnnouncementPublished(event)).doesNotThrowAnyException();
    }

    @Test
    void theListenerRunsInTheBackgroundAndOnlyAfterTheAnnouncementIsCommitted() throws Exception {
        var method = AnnouncementPushListener.class.getMethod("onAnnouncementPublished", AnnouncementPublishedEvent.class);

        assertThat(method.getAnnotation(Async.class)).isNotNull();
        assertThat(method.getAnnotation(TransactionalEventListener.class).phase())
                .isEqualTo(TransactionPhase.AFTER_COMMIT);
    }
}
