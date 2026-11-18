package com.guanghe.backend.conversation.context;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.guanghe.backend.catalog.competitor.CompetitorMapper;
import com.guanghe.backend.catalog.competitor.CompetitorStatus;
import com.guanghe.backend.catalog.competitor.CompetitorType;
import com.guanghe.backend.catalog.product.ProductMapper;
import com.guanghe.backend.catalog.space.BusinessSpaceMapper;
import com.guanghe.backend.common.api.BusinessException;
import com.guanghe.backend.common.api.ErrorCode;
import com.guanghe.backend.conversation.AgentRunRecord;
import com.guanghe.backend.conversation.ConversationMapper;
import com.guanghe.backend.auth.account.*;
import com.guanghe.backend.agentplatform.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RunContextServiceTests {

    private static final UUID RUN_ID = UUID.randomUUID();
    private static final String CONTEXT_REF = "opaque-context-reference";
    private static final long USER_ID = 41;
    private static final long SPACE_ID = 2001;
    private static final long PRODUCT_ID = 3001;

    private ConversationMapper conversationMapper;
    private ObjectMapper objectMapper;
    private RunContextService service;
    private AccountMapper accounts;
    private AgentPlatformService platform;

    @BeforeEach
    void setUp() {
        conversationMapper = mock(ConversationMapper.class);
        objectMapper = new ObjectMapper().findAndRegisterModules();
        service = new RunContextService(conversationMapper, objectMapper);
        accounts=mock(AccountMapper.class);
        platform=mock(AgentPlatformService.class);
        service.setAccounts(accounts);
        service.setAgentPlatform(platform);
        when(accounts.findIdentityById(USER_ID)).thenReturn(Optional.of(new AccountIdentity(USER_ID,"test",null,1,
                AccountRole.USER,AccountStatus.ACTIVE,"test",null)));
        when(platform.assertContextReadAllowed(USER_ID,9001,true)).thenReturn(new AgentRuntimeCatalog().freeze(
                new AgentPlatformDtos.AgentIdentity(null,"geo","1.0.0","GEO",null,List.of("chat","resources.read","geo.read"))));
    }

    @Test
    void returnsThePersistedWhitelistSnapshotWithoutReadingMutableCatalogState() throws Exception {
        String snapshotJson = objectMapper.writeValueAsString(snapshot("创建时空间", "创建时商品", "创建时竞品"));
        when(conversationMapper.findRun(RUN_ID)).thenReturn(Optional.of(run(
                USER_ID, CONTEXT_REF, OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(2),
                "RUNNING", snapshotJson
        )));

        InternalRunContextDtos.RunContext result = service.get(RUN_ID, CONTEXT_REF);
        JsonNode json = objectMapper.valueToTree(result);

        assertThat(result.space().name()).isEqualTo("创建时空间");
        assertThat(result.product().name()).isEqualTo("创建时商品");
        assertThat(result.competitors()).extracting(InternalRunContextDtos.Competitor::name)
                .containsExactly("创建时竞品");
        assertThat(result.space().logoAssetId()).isNull();
        assertThat(result.product().primaryAssetId()).isNull();
        assertThat(result.attachmentAssetIds()).isEmpty();
        assertThat(json.has("userId")).isFalse();
        assertThat(json.toString()).doesNotContain("contextRef", "password", "tokenVersion", "username");
        List<String> fields = new ArrayList<>();
        json.fieldNames().forEachRemaining(fields::add);
        assertThat(fields).containsExactlyInAnyOrder(
                "runId", "conversationId", "contextVersion", "space", "product",
                "competitors", "attachmentAssetIds", "conversationMemory", "taskWorkspace",
                "loadedToolGroups", "contextBudget", "taskPreparationConstraints", "geoInsightContext"
        );
    }

    @Test
    void contextReaderHasNoDependencyThatCanReloadMutableCatalogTables() {
        List<Class<?>> dependencyTypes = java.util.Arrays.stream(RunContextService.class.getDeclaredFields())
                .map(java.lang.reflect.Field::getType)
                .toList();

        assertThat(dependencyTypes).doesNotContain(
                BusinessSpaceMapper.class, ProductMapper.class, CompetitorMapper.class
        );
    }

    @Test void disabledAccountOrInstallationCannotReadAlreadyFrozenContext() throws Exception {
        String saved=objectMapper.writeValueAsString(snapshot("空间","商品","竞品"));
        when(conversationMapper.findRun(RUN_ID)).thenReturn(Optional.of(run(USER_ID,CONTEXT_REF,
                OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(2),"RUNNING",saved)));
        when(accounts.findIdentityById(USER_ID)).thenReturn(Optional.empty());
        assertUnavailable(()->service.get(RUN_ID,CONTEXT_REF));
        when(accounts.findIdentityById(USER_ID)).thenReturn(Optional.of(new AccountIdentity(USER_ID,"test",null,1,
                AccountRole.USER,AccountStatus.ACTIVE,"test",null)));
        when(platform.assertContextReadAllowed(USER_ID,9001,true)).thenThrow(new BusinessException(ErrorCode.FORBIDDEN));
        assertThatThrownBy(()->service.get(RUN_ID,CONTEXT_REF)).isInstanceOfSatisfying(BusinessException.class,
                error->assertThat(error.errorCode()).isEqualTo(ErrorCode.FORBIDDEN));
    }

    @Test void revokedFrozenCapabilityRejectsWholeContextBeforeKnowledgeRetrieval() throws Exception {
        var node=(com.fasterxml.jackson.databind.node.ObjectNode)objectMapper.valueToTree(snapshot("空间","商品","竞品"));
        var frozen=new AgentRuntimeCatalog().freeze(new AgentPlatformDtos.AgentIdentity("71","geo","1.0.0","GEO",null,
                List.of("chat","resources.read","geo.read")));
        node.set("agentIdentity",objectMapper.valueToTree(frozen));
        when(conversationMapper.findRun(RUN_ID)).thenReturn(Optional.of(run(USER_ID,CONTEXT_REF,
                OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(2),"RUNNING",node.toString())));
        when(platform.assertContextReadAllowed(USER_ID,9001,false)).thenReturn(
                new AgentPlatformDtos.AgentIdentity("71","geo","1.0.0","GEO",null,List.of("chat")));
        assertUnavailable(()->service.get(RUN_ID,CONTEXT_REF));
    }

    @Test
    void mismatchedExpiredTerminalAndMismatchedSnapshotBindingsFailClosed() throws Exception {
        String validSnapshot = objectMapper.writeValueAsString(snapshot("空间", "商品", "竞品"));
        when(conversationMapper.findRun(RUN_ID)).thenReturn(Optional.of(run(
                USER_ID, CONTEXT_REF, OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(2),
                "RUNNING", validSnapshot
        )));
        assertUnavailable(() -> service.get(RUN_ID, "another-user-reference"));

        when(conversationMapper.findRun(RUN_ID)).thenReturn(Optional.of(run(
                USER_ID, CONTEXT_REF, OffsetDateTime.now(ZoneOffset.UTC).minusSeconds(1),
                "RUNNING", validSnapshot
        )));
        assertUnavailable(() -> service.get(RUN_ID, CONTEXT_REF));

        when(conversationMapper.findRun(RUN_ID)).thenReturn(Optional.of(run(
                USER_ID, CONTEXT_REF, OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(2),
                "COMPLETED", validSnapshot
        )));
        assertUnavailable(() -> service.get(RUN_ID, CONTEXT_REF));

        InternalRunContextDtos.RunContext wrongBinding = new InternalRunContextDtos.RunContext(
                UUID.randomUUID().toString(), "9001", 1, null, null, List.of(), List.of()
        );
        when(conversationMapper.findRun(RUN_ID)).thenReturn(Optional.of(run(
                USER_ID, CONTEXT_REF, OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(2),
                "RUNNING", objectMapper.writeValueAsString(wrongBinding)
        )));
        assertUnavailable(() -> service.get(RUN_ID, CONTEXT_REF));
    }

    private InternalRunContextDtos.RunContext snapshot(String spaceName, String productName, String competitorName) {
        return new InternalRunContextDtos.RunContext(
                RUN_ID.toString(), "9001", 1,
                new InternalRunContextDtos.Space(
                        Long.toString(SPACE_ID), spaceName,
                        new InternalRunContextDtos.Brand(
                                "品牌", List.of("品牌别名"), "说明", List.of("专业"),
                                List.of("家庭"), List.of("省电"), List.of("绝对化")
                        ), null
                ),
                new InternalRunContextDtos.Product(
                        Long.toString(PRODUCT_ID), Long.toString(SPACE_ID), productName,
                        List.of("商品别名"), "家电", "A1", List.of("卖点"),
                        Map.of("功率", "1000W"), List.of("家庭"), List.of("厨房"),
                        List.of("禁语"), null
                ),
                List.of(new InternalRunContextDtos.Competitor(
                        "4001", Long.toString(SPACE_ID), competitorName, List.of("竞品别名"),
                        CompetitorType.DIRECT, "说明", CompetitorStatus.ACTIVE
                )),
                List.of()
        );
    }

    private void assertUnavailable(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOfSatisfying(BusinessException.class,
                error -> assertThat(error.errorCode()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND));
    }

    private AgentRunRecord run(
            long userId,
            String contextRef,
            OffsetDateTime expiresAt,
            String status,
            String snapshotJson
    ) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        return new AgentRunRecord(
                RUN_ID, 9001, userId, 8001, 8002L, SPACE_ID, PRODUCT_ID, "[]", status, 1,
                snapshotJson, hash(contextRef), expiresAt, null, 0, "{}", null, null, null,
                now, null, now, now
        );
    }

    private String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
    }
}
