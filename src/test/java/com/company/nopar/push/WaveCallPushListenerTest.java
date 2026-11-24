package com.company.nopar.push;

import com.company.nopar.service.WaveCalledEvent;
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
class WaveCallPushListenerTest {

    private final WaveCalledEvent event = new WaveCalledEvent(
            7L, 1L, "Ola 1", LocalDate.of(2026, 11, 24), "Subid", List.of(2L, 3L));

    @Mock
    private PushService pushService;

    @InjectMocks
    private WaveCallPushListener listener;

    @Test
    void givenAWaveCall_whenTheEventArrives_thenOnlyThoseDriversAreNotified() {
        listener.onWaveCalled(event);

        verify(pushService).notifyUsers(eq(List.of(2L, 3L)), argThat(notification ->
                "Ola 1: Subid".equals(notification.title())
                        && "wave_call".equals(notification.data().get("type"))
                        && "7".equals(notification.data().get("waveCallId"))));
        verifyNoMoreInteractions(pushService);
    }

    @Test
    void givenTheSendingFails_whenTheEventArrives_thenItDoesNotPropagate() {
        doThrow(new IllegalStateException("sin red")).when(pushService).notifyUsers(any(), any());

        assertThatCode(() -> listener.onWaveCalled(event)).doesNotThrowAnyException();
    }

    @Test
    void theListenerRunsInTheBackgroundAndOnlyAfterTheCallIsCommitted() throws Exception {
        var method = WaveCallPushListener.class.getMethod("onWaveCalled", WaveCalledEvent.class);

        assertThat(method.getAnnotation(Async.class)).isNotNull();
        assertThat(method.getAnnotation(TransactionalEventListener.class).phase())
                .isEqualTo(TransactionPhase.AFTER_COMMIT);
    }
}
