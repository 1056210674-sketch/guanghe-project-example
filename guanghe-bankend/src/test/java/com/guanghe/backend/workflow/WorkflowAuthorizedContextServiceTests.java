package com.guanghe.backend.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.guanghe.backend.catalog.competitor.CompetitorStatus;
import com.guanghe.backend.catalog.competitor.CompetitorType;
import com.guanghe.backend.common.api.BusinessException;
import com.guanghe.backend.common.api.ErrorCode;
import com.guanghe.backend.conversation.context.InternalRunContextDtos;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorkflowAuthorizedContextServiceTests {
    private static final UUID RUN_ID = UUID.fromString("11111111-2222-4333-8444-555555555555");
    private static final OffsetDateTime NOW = OffsetDateTime.of(
            2026, 9, 10, 8, 0, 0, 0, ZoneOffset.UTC);
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private final WorkflowAuthorizedContextService service = new WorkflowAuthorizedContextService(json);

    @Test
    void freezesImmutableWhitelistAndFailsClosedForGapTamperOrCrossScope() throws Exception {
        InternalRunContextDtos.RunContext source = context(3, 2, false);
        WorkflowAuthorizedContextService.FrozenContext frozen = service.freeze(
                source, RUN_ID, 501, 601, 701L);

        ((ObjectNode) source.conversationMemory().messages().getLast().content())
                .put("text", "changed later");
        InternalRunContextDtos.RunContext loaded = service.load(
                frozen.json(), frozen.hash(), frozen.schemaVersion(), false);

        assertThat(loaded.space().name()).isEqualTo("授权经营空间");
        assertThat(loaded.product().name()).isEqualTo("授权商品");
        assertThat(loaded.competitors()).hasSize(2).allMatch(item -> item.status() == CompetitorStatus.ACTIVE);
        assertThat(loaded.conversationMemory().messages()).extracting(
                InternalRunContextDtos.MemoryMessage::sequence).containsExactly(1L, 2L, 3L);
        assertThat(loaded.conversationMemory().messages().getLast().content().path("text").asText())
                .isEqualTo("current request");
        assertThat(frozen.json()).doesNotContain("executionToken", "providerUrl",
                "eyJheaderlong.payloadlong.signaturelong");
        assertThat(frozen.hash()).matches("^[0-9a-f]{64}$");
        assertThat(frozen.projectedBytes()).isLessThanOrEqualTo(
                WorkflowAuthorizedContextService.MAX_AUTHORIZED_CONTEXT_BYTES);

        assertUnavailable(() -> service.load(frozen.json(), "0".repeat(64), 1, false));
        assertUnavailable(() -> service.load(null, null, null, true));
        assertThatThrownBy(() -> service.freeze(source, RUN_ID, 501, 602, 701L))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));
    }

    @Test
    void trimsOldHistoryThenLowPriorityCompetitorDataWithinWholeSnapshotBudget() {
        InternalRunContextDtos.RunContext source = context(18, 36, true);
        WorkflowAuthorizedContextService.FrozenContext frozen = service.freeze(
                source, RUN_ID, 501, 601, 701L);
        InternalRunContextDtos.RunContext loaded = service.load(
                frozen.json(), frozen.hash(), frozen.schemaVersion(), false);

        assertThat(loaded.contextBudget().truncated()).isTrue();
        assertThat(loaded.conversationMemory().messages().getLast().content().path("text").asText())
                .startsWith("current request");
        assertThat(loaded.conversationMemory().messages().stream()
                .filter(message -> message.sequence() == 18).count()).isOne();
        assertThat(loaded.conversationMemory().messages().size()).isLessThan(18);
        assertThat(loaded.competitors().size()).isLessThanOrEqualTo(36);
        assertThat(loaded.taskWorkspace().activeScope().competitorIds())
                .containsExactlyElementsOf(loaded.competitors().stream()
                        .map(InternalRunContextDtos.Competitor::id).toList());
        assertThat(frozen.projectedBytes()).isLessThanOrEqualTo(
                WorkflowAuthorizedContextService.MAX_AUTHORIZED_CONTEXT_BYTES);
    }

    @Test
    void trimmingPreservesFrozenRuntimeIdentityAndProtocolAndRejectsUnknownRelease() throws Exception {
        var catalog = new com.guanghe.backend.agentplatform.AgentRuntimeCatalog();
        var identity = catalog.freeze(new com.guanghe.backend.agentplatform.AgentPlatformDtos.AgentIdentity(
                "91", "geo", "1.0.0", "GEO", "601", List.of("chat", "resources.read")));
        ObjectNode payload = json.valueToTree(context(18, 36, true));
        payload.set("agentIdentity", json.valueToTree(identity));
        payload.set("protocolFeatures", json.valueToTree(new InternalRunContextDtos.ProtocolFeatures(true, true)));
        payload.put("articlePreparationMode", "ARTICLE_FULL_V1");
        var source = json.treeToValue(payload, InternalRunContextDtos.RunContext.class);
        var frozen = service.freeze(source, RUN_ID, 501, 601, 701L);
        var loaded = service.load(frozen.json(), frozen.hash(), frozen.schemaVersion(), false);
        assertThat(loaded.contextBudget().truncated()).isTrue();
        assertThat(loaded.agentIdentity()).isEqualTo(identity);
        assertThat(loaded.protocolFeatures()).isEqualTo(source.protocolFeatures());
        assertThat(loaded.articlePreparationMode()).isEqualTo("ARTICLE_FULL_V1");

        ((ObjectNode) payload.path("agentIdentity")).put("executionRelease", "unknown-release");
        var unknown = json.treeToValue(payload, InternalRunContextDtos.RunContext.class);
        assertThatThrownBy(() -> service.freeze(unknown, RUN_ID, 501, 601, 701L))
                .isInstanceOfSatisfying(BusinessException.class,
                        failure -> assertThat(failure.errorCode()).isEqualTo(ErrorCode.PROVIDER_NOT_CONFIGURED));
    }

    private InternalRunContextDtos.RunContext context(int messageCount, int competitorCount,
                                                       boolean large) {
        List<InternalRunContextDtos.MemoryMessage> messages = new ArrayList<>();
        for (int index = 1; index <= messageCount; index++) {
            ObjectNode content = json.createObjectNode();
            String text = index == messageCount ? "current request" : "history " + index;
            if (large) text += "x".repeat(15_500);
            content.put("text", text);
            if (index == 1) {
                content.put("executionToken", "must-not-survive");
                content.put("providerUrl", "https://provider.example/result?signature=secret");
                content.put("note", "eyJheaderlong.payloadlong.signaturelong");
            }
            messages.add(new InternalRunContextDtos.MemoryMessage(Long.toString(1000 + index), index,
                    index % 2 == 0 ? "ASSISTANT" : "USER", content, NOW.plusSeconds(index), null));
        }
        List<InternalRunContextDtos.Competitor> competitors = new ArrayList<>();
        for (int index = 1; index <= competitorCount; index++) {
            int competitorIndex = index;
            List<String> aliases = large
                    ? java.util.stream.IntStream.range(0, 30)
                    .mapToObj(value -> "alias-" + competitorIndex + "-" + value + "z".repeat(80)).toList()
                    : List.of("alias-" + index);
            competitors.add(new InternalRunContextDtos.Competitor(Long.toString(8000 + index), "601",
                    "竞品" + index, aliases, CompetitorType.DIRECT,
                    large ? "d".repeat(1800) : "对比描述", CompetitorStatus.ACTIVE));
        }
        var brand = new InternalRunContextDtos.Brand("品牌", List.of("品牌别名"), "品牌描述",
                List.of("专业"), List.of("目标人群"), List.of("卖点"), List.of("禁用表述"));
        var space = new InternalRunContextDtos.Space("601", "授权经营空间", brand, null);
        var product = new InternalRunContextDtos.Product("701", "601", "授权商品",
                List.of("商品别名"), "分类", "型号", List.of("商品卖点"), Map.of("材质", "安全"),
                List.of("人群"), List.of("场景"), List.of("禁止声称"), null);
        List<String> competitorIds = competitors.stream()
                .map(InternalRunContextDtos.Competitor::id).toList();
        var memory = new InternalRunContextDtos.ConversationMemory(messages, null,
                messageCount, 0, "POSTGRESQL");
        ObjectNode constraints = json.createObjectNode().put("color", "blue");
        var workspace = new InternalRunContextDtos.TaskWorkspace("current request", "IMAGE",
                new InternalRunContextDtos.ActiveScope("601", "701", competitorIds), constraints,
                List.of(), null, "9101", null, List.of(), List.of(), 1);
        var budget = new InternalRunContextDtos.ContextBudget(128_000, 16_000, 8_000,
                4_000, 100_000, large ? 120_000 : 100, false,
                "TOKEN_BUDGET_REPLY_CHAIN_THEN_RECENCY");
        return new InternalRunContextDtos.RunContext(RUN_ID.toString(), "501", 1,
                space, product, competitors, List.of(), memory, workspace,
                List.of("TASK_PREPARATION"), budget);
    }

    private void assertUnavailable(org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action).isInstanceOfSatisfying(BusinessException.class,
                error -> assertThat(error.errorCode()).isEqualTo(ErrorCode.WORKFLOW_CONTEXT_UNAVAILABLE));
    }
}
