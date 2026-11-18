package com.guanghe.backend.conversation;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ConversationRunWatchdogTests {
    @Test
    void honorsGraceAndOnlyPublishesCommittedRecoveryEvents() {
        var persistence = mock(ConversationRunPersistenceService.class);
        var events = mock(ConversationEventStreamService.class);
        var properties = new ConversationProperties();
        var watchdog = new ConversationRunWatchdog(persistence, events, properties);
        var terminal = mock(ConversationEventRecord.class);
        when(persistence.expireOneRun(any())).thenReturn(Optional.of(terminal), Optional.empty());
        OffsetDateTime before = OffsetDateTime.now(ZoneOffset.UTC).minus(properties.getRecoveryGrace());
        watchdog.expireOrphanedRuns();
        var cutoff = ArgumentCaptor.forClass(OffsetDateTime.class);
        verify(persistence, times(2)).expireOneRun(cutoff.capture());
        assertThat(cutoff.getAllValues()).allMatch(value -> !value.isBefore(before)
                && !value.isAfter(OffsetDateTime.now(ZoneOffset.UTC).minus(properties.getRecoveryGrace())));
        verify(events).publish(terminal);
    }

    @Test
    void boundsEachSweepAndCanBeDisabled() {
        var persistence = mock(ConversationRunPersistenceService.class);
        var events = mock(ConversationEventStreamService.class);
        var properties = new ConversationProperties();
        var watchdog = new ConversationRunWatchdog(persistence, events, properties);
        when(persistence.expireOneRun(any())).thenReturn(Optional.of(mock(ConversationEventRecord.class)));
        watchdog.expireOrphanedRuns();
        verify(persistence, times(20)).expireOneRun(any());
        verify(events, times(20)).publish(any());
        clearInvocations(persistence, events);
        properties.setRecoveryEnabled(false);
        watchdog.expireOrphanedRuns();
        verifyNoInteractions(persistence, events);
    }
}
