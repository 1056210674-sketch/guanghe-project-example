package com.guanghe.backend.integration.contract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ContractResourceTests {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void rabbitV1ContractKeepsIdsAsStringsAndPayloadOutOfMessage() throws Exception {
        JsonNode schema = read("/contracts/rabbit/task-execution-requested-v1.schema.json");

        assertThat(schema.at("/properties/schemaVersion/const").intValue()).isEqualTo(1);
        assertThat(schema.at("/properties/eventType/const").textValue())
                .isEqualTo("TASK_EXECUTION_REQUESTED");
        assertThat(schema.at("/$defs/id/type").textValue()).isEqualTo("string");
        assertThat(schema.at("/properties/prompt").isMissingNode()).isTrue();
        assertThat(schema.at("/properties/parameters").isMissingNode()).isTrue();
    }

    @Test
    void sseV1ContractContainsDocumentedRecoveryEvents() throws Exception {
        JsonNode schema = read("/contracts/sse/conversation-event-v1.schema.json");

        assertThat(schema.at("/$defs/id/type").textValue()).isEqualTo("string");
        List<String> eventTypes = new ArrayList<>();
        schema.at("/properties/eventType/enum").forEach(value -> eventTypes.add(value.textValue()));
        assertThat(eventTypes)
                .contains("message.delta", "agent.progress", "task.completed", "task.failed", "heartbeat");
        JsonNode progress = schema.at("/$defs/agentProgressPayload");
        assertThat(progress.path("additionalProperties").booleanValue()).isFalse();
        assertThat(progress.path("required").size()).isEqualTo(8);
        assertThat(progress.at("/properties/ordinal/maximum").intValue()).isEqualTo(32);
        JsonNode backgroundTask = schema.at("/$defs/backgroundTaskPayload");
        assertThat(backgroundTask.path("required")).extracting(JsonNode::textValue)
                .containsExactly("taskId", "deliveryKind", "status");
        assertThat(backgroundTask.at("/properties/deliveryKind/const").textValue())
                .isEqualTo("BACKGROUND_TASK");
        JsonNode delta = schema.at("/$defs/messageDeltaPayload");
        assertThat(delta.path("additionalProperties").booleanValue()).isFalse();
        assertThat(delta.path("required")).extracting(JsonNode::textValue).containsExactly("messageId", "delta");
        assertThat(delta.at("/properties/replace/type").textValue()).isEqualTo("boolean");
        assertThat(delta.at("/properties/replace/default").booleanValue()).isFalse();
        assertThat(delta.at("/properties/delta/maxLength").intValue()).isEqualTo(16000);
        assertThat(delta.at("/allOf/0/if/required")).extracting(JsonNode::textValue).containsExactly("replace");
        assertThat(delta.at("/allOf/0/if/properties/replace/const").booleanValue()).isTrue();
        assertThat(delta.at("/allOf/0/else/properties/delta/minLength").intValue()).isEqualTo(1);
    }

    private JsonNode read(String resource) throws Exception {
        try (InputStream input = getClass().getResourceAsStream(resource)) {
            assertThat(input).as(resource).isNotNull();
            return objectMapper.readTree(input);
        }
    }
}
