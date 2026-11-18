package com.guanghe.backend.conversation;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "zhixiaohe.conversation", name = {"enabled", "recovery-enabled"},
        havingValue = "true", matchIfMissing = true)
public class ConversationRunWatchdog {
    private final ConversationRunPersistenceService persistence;
    private final ConversationEventStreamService events;
    private final ConversationProperties properties;

    public ConversationRunWatchdog(ConversationRunPersistenceService persistence,
                                  ConversationEventStreamService events, ConversationProperties properties) {
        this.persistence = persistence;
        this.events = events;
        this.properties = properties;
    }

    @Scheduled(initialDelayString = "${CONVERSATION_RECOVERY_POLL_DELAY_MS:5000}",
            fixedDelayString = "${CONVERSATION_RECOVERY_POLL_DELAY_MS:5000}")
    public void expireOrphanedRuns() {
        if (!properties.isRecoveryEnabled()) return;
        OffsetDateTime cutoff = OffsetDateTime.now(ZoneOffset.UTC).minus(properties.getRecoveryGrace());
        for (int count = 0; count < 20; count++) {
            ConversationEventRecord event = persistence.expireOneRun(cutoff).orElse(null);
            if (event == null) return;
            events.publish(event);
        }
    }
}
