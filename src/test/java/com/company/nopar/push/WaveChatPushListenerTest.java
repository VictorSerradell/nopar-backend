package com.company.nopar.push;

import com.company.nopar.service.WaveChatMessageEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.scheduling.annotation.Async;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

@ExtendWith(MockitoExtension.class)
class WaveChatPushListenerTest {

    private final WaveChatMessageEvent event = new WaveChatMessageEvent(
            9L, 1L, "Ola 1", LocalDate.of(2026, 11, 24), "ana", "Llego en 5 minutos", false, List.of(3L, 5L));

    @Mock
    private PushService pushService;

    @InjectMocks
    private WaveChatPushListener listener;

    @Test
    void givenAChatMessage_whenTheEventArrives_thenOnlyTheOtherMembersAreNotified() {
        listener.onMessage(event);

        verify(pushService).notifyUsers(eq(List.of(3L, 5L)), argThat(notification ->
                "Ola 1".equals(notification.title())
                        && "ana: Llego en 5 minutos".equals(notification.body())
                        && "chat".equals(notification.data().get("type"))
                        && "1".equals(notification.data().get("waveId"))));
        verifyNoMoreInteractions(pushService);
    }

    @Test
    void givenTheSendingFails_whenTheEventArrives_thenItDoesNotPropagate() {
        doThrow(new IllegalStateException("sin red")).when(pushService).notifyUsers(any(), any());

        assertThatCode(() -> listener.onMessage(event)).doesNotThrowAnyException();
    }

    @Test
    void theListenerRunsInTheBackgroundAndOnlyAfterTheMessageIsCommitted() throws Exception {
        var method = WaveChatPushListener.class.getMethod("onMessage", WaveChatMessageEvent.class);

        assertThat(method.getAnnotation(Async.class)).isNotNull();
        assertThat(method.getAnnotation(TransactionalEventListener.class).phase())
                .isEqualTo(TransactionPhase.AFTER_COMMIT);
    }
}
