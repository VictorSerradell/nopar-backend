package com.company.nopar.config;

import com.company.nopar.service.WaveChatService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WaveChatRetentionTest {

    @Mock
    private WaveChatService chatService;

    @Test
    void givenThirtyDays_whenTheNightlyJobRuns_thenMessagesBeforeThatDayAreDeleted() {
        LocalDate cutoff = LocalDate.now().minusDays(30);
        when(chatService.deleteMessagesBefore(cutoff)).thenReturn(12);

        new WaveChatRetention(chatService, 30).deleteOldMessages();

        verify(chatService).deleteMessagesBefore(cutoff);
    }

    @Test
    void givenZeroOrNegativeDays_whenTheNightlyJobRuns_thenNothingIsDeleted() {
        new WaveChatRetention(chatService, 0).deleteOldMessages();
        new WaveChatRetention(chatService, -5).deleteOldMessages();

        verifyNoInteractions(chatService);
    }
}
