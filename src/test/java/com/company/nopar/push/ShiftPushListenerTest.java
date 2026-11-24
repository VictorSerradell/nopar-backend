package com.company.nopar.push;

import com.company.nopar.service.ShiftChangedEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.scheduling.annotation.Async;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.LocalDate;
import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

@ExtendWith(MockitoExtension.class)
class ShiftPushListenerTest {

    private final ShiftChangedEvent event = new ShiftChangedEvent(ShiftChangedEvent.Change.ASSIGNED, 4L, 2L,
            LocalDate.of(2026, 11, 25), "Ola 1", LocalTime.of(9, 30), "1111AAA");

    @Mock
    private PushService pushService;

    @InjectMocks
    private ShiftPushListener listener;

    @Test
    void givenAShiftChange_whenTheEventArrives_thenOnlyThatDriverIsNotified() {
        listener.onShiftChanged(event);

        verify(pushService).notifyUser(eq(2L), argThat(notification ->
                "Nuevo turno".equals(notification.title())
                        && "4".equals(notification.data().get("shiftId"))));
        verifyNoMoreInteractions(pushService);
    }

    @Test
    void givenTheSendingFails_whenTheEventArrives_thenItDoesNotPropagate() {
        doThrow(new IllegalStateException("sin red")).when(pushService).notifyUser(any(), any());

        assertThatCode(() -> listener.onShiftChanged(event)).doesNotThrowAnyException();
    }

    @Test
    void theListenerRunsInTheBackgroundAndOnlyAfterTheShiftIsCommitted() throws Exception {
        var method = ShiftPushListener.class.getMethod("onShiftChanged", ShiftChangedEvent.class);

        assertThat(method.getAnnotation(Async.class)).isNotNull();
        assertThat(method.getAnnotation(TransactionalEventListener.class).phase())
                .isEqualTo(TransactionPhase.AFTER_COMMIT);
    }
}
