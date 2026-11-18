package com.guanghe.backend.geo;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.guanghe.backend.auth.account.AccountRole;
import com.guanghe.backend.auth.account.AccountStatus;
import com.guanghe.backend.auth.security.AuthenticatedAccount;
import com.guanghe.backend.catalog.product.ProductIdentityAssetService;
import com.guanghe.backend.catalog.product.ProductIdentityAssetRecord;
import com.guanghe.backend.common.api.BusinessException;
import com.guanghe.backend.common.api.ErrorCode;
import com.guanghe.backend.conversation.context.AuthorizedCatalogContextFactory;
import com.guanghe.backend.conversation.context.InternalRunContextDtos;
import com.guanghe.backend.integration.internal.client.PythonCallResult;
import com.guanghe.backend.integration.internal.client.PythonInternalClient;
import com.guanghe.backend.task.PaidTaskProperties;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GeoCreativePlanServiceTests {
    private final ObjectMapper json = new ObjectMapper();
    private final GeoTaskMapper reports = mock(GeoTaskMapper.class);
    private final AuthorizedCatalogContextFactory catalog = mock(AuthorizedCatalogContextFactory.class);
    private final ProductIdentityAssetService assets = mock(ProductIdentityAssetService.class);
    private final PythonInternalClient python = mock(PythonInternalClient.class);
    private final AuthenticatedAccount user = new AuthenticatedAccount(41, "user", AccountRole.USER,
            1, AccountStatus.ACTIVE, null, null, null);
    private GeoCreativePlanService service;
    private GeoTaskRecords.Report report;
    private GeoTaskRecords.Run run;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        ObjectProvider<PythonInternalClient> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(python);
        PaidTaskProperties tasks = new PaidTaskProperties();
        tasks.setTokenSecret("local-geo-plan-test-secret-at-least-32-bytes");
        service = new GeoCreativePlanService(reports, catalog, assets, provider, tasks, json);
        report = new GeoTaskRecords.Report(301, 302, 303, 41, 304, "SUCCEEDED",
                "{\"findings\":[\"缺少真实使用场景\"],\"recommendations\":[{\"priority\":\"P1\",\"title\":\"补充场景\",\"rationale\":\"部分样本缺少介绍\"}],\"ignoreMe\":\"" + "x".repeat(1000) + "\"}",
                "{}", "成功样本显示介绍不足", "geo-synthesis-v1", 2, null, null, 2, 0);
        when(reports.findReport(301, 41)).thenReturn(Optional.of(report));
        run = mock(GeoTaskRecords.Run.class);
        when(run.status()).thenReturn("SUCCEEDED");
        when(run.expectedUnits()).thenReturn(2);
        when(run.succeededUnits()).thenReturn(2);
        when(run.productId()).thenReturn(102L);
        when(run.spaceId()).thenReturn(101L);
        when(run.id()).thenReturn(302L);
        when(reports.findRun(302, 41)).thenReturn(Optional.of(run));
        InternalRunContextDtos.Product product = new InternalRunContextDtos.Product("102", "101", "咖啡机",
                List.of(), "家电", "A1", List.of("操作便捷"), Map.of("功率", "100W"),
                List.of("居家用户"), List.of("早晨冲泡"), List.of("不能承诺治疗"), "11", List.of("11"));
        when(catalog.create(41, 101L, 102L)).thenReturn(
                new AuthorizedCatalogContextFactory.Context(null, product, List.of()));
        when(assets.requireReadyIdentityAssets(41, 102, 101))
                .thenReturn(List.of(mock(ProductIdentityAssetRecord.class)));
        GeoTaskRecords.Observation observation = mock(GeoTaskRecords.Observation.class);
        when(observation.unitKey()).thenReturn("unit-1");
        when(observation.questionTextSnapshot()).thenReturn("适合什么场景？");
        when(observation.platformCode()).thenReturn("DOUBAO");
        when(observation.answerText()).thenReturn("适合居家使用");
        when(reports.findSuccessfulEvidence(302, 41, 210)).thenReturn(List.of(observation));
    }

    @Test
    void sendsOnlyBoundedOwnedEvidenceAndValidatesReturnedPlan() {
        ObjectNode model = modelPlan("unit-1");
        when(python.planGeoVisual(any())).thenReturn(PythonCallResult.available(200, model));
        var response = service.plan(user, 301,
                new GeoCreativePlanDtos.Request("VIDEO", "102", "301:recommendation:1", "突出早晨使用"));
        assertThat(response.reportId()).isEqualTo("301");
        assertThat(response.plan().path("recommendationId").asText()).isEqualTo("301:recommendation:1");
        assertThat(response.plan().path("proof").asText()).matches("^v1\\.[A-Za-z0-9_-]{43}$");
        assertThat(response.evidence()).hasSize(1);
        ArgumentCaptor<ObjectNode> packet = ArgumentCaptor.forClass(ObjectNode.class);
        verify(python).planGeoVisual(packet.capture());
        assertThat(packet.getValue().path("report").has("ignoreMe")).isFalse();
        assertThat(packet.getValue().path("evidence").get(0).path("key").asText()).isEqualTo("unit-1");
        assertThat(packet.getValue().path("productFacts").path("name").asText()).isEqualTo("咖啡机");
        assertThat(packet.getValue().path("prohibitedClaims").get(0).asText()).isEqualTo("不能承诺治疗");
        assertError(() -> service.plan(user, 301,
                new GeoCreativePlanDtos.Request("VIDEO", "102", "301:recommendation:1", null)),
                ErrorCode.RATE_LIMITED);
    }

    @Test
    void rejectsDeterministicReportBeforeAnyModelCall() {
        report = new GeoTaskRecords.Report(301, 302, 303, 41, 304, "SUCCEEDED", "{}", "{}",
                "摘要", "geo-deterministic-v1", 2, null, null, 2, 0);
        when(reports.findReport(301, 41)).thenReturn(Optional.of(report));
        assertError(() -> service.plan(user, 301,
                new GeoCreativePlanDtos.Request("IMAGE", "102", null, null)), ErrorCode.VALIDATION_FAILED);
        verify(python, never()).planGeoVisual(any());
    }

    @Test
    void rejectsInventedEvidenceFromModelWithoutReturningItToBrowser() {
        when(python.planGeoVisual(any())).thenReturn(PythonCallResult.available(200, modelPlan("invented")));
        assertError(() -> service.plan(user, 301,
                new GeoCreativePlanDtos.Request("VIDEO", "102", null, null)), ErrorCode.PROVIDER_UNAVAILABLE);
    }

    @Test
    void replacesInventedCountsAndMissingReferenceClaimsWithSignedBusinessFacts() {
        when(run.status()).thenReturn("PARTIAL");
        when(run.expectedUnits()).thenReturn(21);
        when(run.succeededUnits()).thenReturn(10);
        when(run.failedUnits()).thenReturn(11);
        ObjectNode model = modelPlan("unit-1");
        model.putArray("limitations").add("18 个计划样本只有 9 个成功，缺少实拍参考图");
        when(python.planGeoVisual(any())).thenReturn(PythonCallResult.available(200, model));

        var response = service.plan(user, 301,
                new GeoCreativePlanDtos.Request("VIDEO", "102", null, null));

        assertThat(response.plan().path("limitations")).hasSize(4);
        String limits = response.plan().path("limitations").toString();
        assertThat(limits).contains("计划 21 条，成功 10 条，失败 11 条，缺失 0 条",
                "取用 1 条成功回答节选，方案引用其中 1 条", "已有 1 张可用身份参考图",
                "未进行图片内容识别", "不得新增未经商品资料证实", "测试或演示资料仅用于测试展示")
                .doesNotContain("18 个", "9 个", "缺少实拍参考图");
        assertThat(model.path("limitations").get(0).asText()).startsWith("18 个");
        var product = catalog.create(41, 101L, 102L).product();
        PaidTaskProperties tasks = new PaidTaskProperties();
        tasks.setTokenSecret("local-geo-plan-test-secret-at-least-32-bytes");
        var proof = new GeoCreativePlanProof(tasks, json);
        proof.verify((ObjectNode) response.plan(), 41, 101, 102, product);
        ObjectNode tampered = response.plan().deepCopy();
        tampered.putArray("limitations").add("没有参考图");
        assertError(() -> proof.verify(tampered, 41, 101, 102, product), ErrorCode.VALIDATION_FAILED);
    }

    @Test
    void recommendationRelevantEvidenceAfterFirstEightIsSelected() {
        java.util.ArrayList<GeoTaskRecords.Observation> observations = new java.util.ArrayList<>();
        for (int index = 0; index < 8; index++) {
            GeoTaskRecords.Observation early = mock(GeoTaskRecords.Observation.class);
            when(early.unitKey()).thenReturn("unit-" + index);
            when(early.questionTextSnapshot()).thenReturn("价格怎么样？");
            when(early.platformCode()).thenReturn("DOUBAO");
            when(early.answerText()).thenReturn("价格需要查询");
            observations.add(early);
        }
        GeoTaskRecords.Observation late = mock(GeoTaskRecords.Observation.class);
        when(late.unitKey()).thenReturn("unit-209");
        when(late.questionTextSnapshot()).thenReturn("商品适合何种使用场景？");
        when(late.platformCode()).thenReturn("KIMI");
        when(late.answerText()).thenReturn("场景描述较少");
        observations.add(late);
        when(reports.findSuccessfulEvidence(302, 41, 210)).thenReturn(observations);
        when(python.planGeoVisual(any())).thenReturn(PythonCallResult.available(200, modelPlan("unit-209")));

        var response = service.plan(user, 301,
                new GeoCreativePlanDtos.Request("VIDEO", "102", "301:recommendation:1", null));

        assertThat(response.evidence()).hasSize(8);
        assertThat(response.evidence().getFirst().key()).isEqualTo("unit-209");
        ArgumentCaptor<ObjectNode> packet = ArgumentCaptor.forClass(ObjectNode.class);
        verify(python).planGeoVisual(packet.capture());
        assertThat(packet.getValue().path("evidence").get(0).path("key").asText()).isEqualTo("unit-209");
    }

    private ObjectNode modelPlan(String key) {
        ObjectNode plan = json.createObjectNode();
        plan.put("version", "geo-visual-plan-v1");
        plan.put("taskType", "VIDEO");
        plan.put("opportunity", "场景介绍不足");
        plan.put("audience", "居家用户");
        plan.put("message", "真实展示冲泡");
        plan.putArray("evidenceKeys").add(key);
        plan.put("scene", "早晨的厨房内展示咖啡机");
        plan.put("visualFocus", "商品主体清楚可见");
        plan.putArray("shotBeats").add("建立厨房场景").add("展示冲泡瞬间");
        plan.putArray("limitations").add("仅基于当前样本");
        return plan;
    }

    private void assertError(Runnable action, ErrorCode code) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(BusinessException.class,
                exception -> assertThat(exception.errorCode()).isEqualTo(code));
    }
}
