package com.guanghe.backend.geo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.guanghe.backend.task.PaidTaskProperties;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import static org.assertj.core.api.Assertions.*;

/** Pin the separate diagnostic contract without relaxing ordinary GEO or browser authority. */
class GeoDiagnosticDeliveryContractTests {
    final ObjectMapper json=new ObjectMapper();
    @Test void extendedAnalysisBudgetKeepsPdfLeaseAndTwoCallEvidenceBoundaryUnchanged() {
        var started=java.time.OffsetDateTime.parse("2026-10-23T00:00:00Z");
        assertThat(GeoDiagnosticDeliveryService.ANALYSIS_TIMEOUT_SECONDS).isEqualTo(120);
        assertThat(GeoDiagnosticDeliveryService.deliveryLeaseExpiresAt("ANALYSIS_RUNNING",started)).isEqualTo(started.plusSeconds(150));
        assertThat(GeoDiagnosticDeliveryService.deliveryLeaseExpiresAt("PDF_RUNNING",started)).isEqualTo(started.plusSeconds(90));
        assertThat(GeoDiagnosticDeliveryService.validAnalysisLatency(60_001)).isTrue();
        assertThat(GeoDiagnosticDeliveryService.validAnalysisLatency(120_000)).isTrue();
        assertThat(GeoDiagnosticDeliveryService.validAnalysisLatency(120_001)).isFalse();
        assertThat(GeoDiagnosticDeliveryService.validAnalysisLatency(-1)).isFalse();
    }
    @Test void publicSubmissionAndRecoveryHaveOnlyExplicitProofFieldsAndExactVersionPrivateDownload() throws Exception {
        var browser=contract("browser-openapi.yaml");var schemas=browser.path("components").path("schemas");
        assertThat(fields(schemas.path("GeoDiagnosticSubmitRequest").path("properties"))).containsExactlyInAnyOrder("version","quoteId","inputHash","requestId");
        assertThat(schemas.path("GeoDiagnosticSubmitRequest").path("additionalProperties").asBoolean()).isFalse();
        assertThat(fields(schemas.path("GeoDiagnosticReportRetryRequest").path("properties"))).containsExactlyInAnyOrder("version","requestId");
        assertThat(schemas.path("GeoDiagnosticReportRetryRequest").path("additionalProperties").asBoolean()).isFalse();
        for(String name:List.of("GeoDiagnosticDelivery","GeoDiagnosticReportDetail"))assertThat(schemas.path(name).path("properties").path("reportVersion").path("const").asInt()).isEqualTo(1);
        var download=browser.path("paths").path("/api/v1/geo/diagnostics/{id}/report/download-url").path("get");
        assertThat(download.path("parameters").get(0).path("required").asBoolean()).isTrue();
        assertThat(download.path("parameters").get(0).path("schema").path("const").asInt()).isEqualTo(1);
        assertThat(browser.path("paths").path("/api/v1/geo/diagnostics").path("get").path("parameters").toString()).contains("submittedOnly");
        assertThatThrownBy(()->json.readValue("{\"version\":1,\"requestId\":\"00000000-0000-0000-0000-000000000001\",\"forceResample\":true}",GeoDiagnosticReportDtos.Retry.class)).isInstanceOf(Exception.class);
        assertThatThrownBy(()->json.readValue("{\"version\":1,\"requestId\":\"00000000-0000-0000-0000-000000000001\",\"quoteId\":\"00000000-0000-0000-0000-000000000002\",\"inputHash\":\""+"a".repeat(64)+"\",\"points\":0}",GeoDiagnosticReportDtos.Submit.class)).isInstanceOf(Exception.class);
    }
    @Test void diagnosticExecutionKeepsTwoCategoriesSingleSampleAndIndependentBudgetWithoutRelaxingMonitor() throws Exception {
        var schemas=contract("java-internal-openapi.yaml").path("components").path("schemas");
        assertThat(schemas.path("GeoTaskParameters").path("properties").path("mode").path("const").asText()).isEqualTo("MONITOR_RUN");
        assertThat(schemas.path("GeoQuestionSnapshot").path("properties").path("category").path("const").asText()).isEqualTo("BRAND_DISCOVERY");
        var diagnostic=schemas.path("GeoDiagnosticTaskParameters");assertThat(diagnostic.path("additionalProperties").asBoolean()).isFalse();
        assertThat(diagnostic.path("properties").path("mode").path("const").asText()).isEqualTo("DIAGNOSTIC_RUN");
        assertThat(diagnostic.path("properties").path("sampleMode").path("const").asText()).isEqualTo("STANDARD");
        var authorized=schemas.path("GeoDiagnosticAuthorizedContext").path("properties");
        assertThat(authorized.path("questions").path("items").path("properties").path("category").path("enum").toString()).isEqualTo("[\"BRAND_DISCOVERY\",\"BRAND_REPUTATION\"]");
        assertThat(authorized.path("topic").path("properties").path("productId").path("type").asText()).isEqualTo("null");
        assertThat(authorized.path("monitoringTarget").path("properties").path("name").path("maxLength").asInt()).isEqualTo(200);
        assertThat(new PaidTaskProperties().getGeoDiagnosticMaxProviderCalls()).isEqualTo(200);
        assertThat(schemas.path("RunResourceReadRequest").toString()+schemas.path("RunResourceSelectionRequest").toString()).doesNotContain("signedGetUrl");
        assertThat(schemas.toString()).contains("GEO_DIAGNOSTIC_REPORT");
    }
    @Test void lateDiagnosticAcknowledgementReportsActualTerminalTaskWithoutInventingOrdinaryReport() throws Exception {
        var schemas=contract("java-internal-openapi.yaml").path("components").path("schemas");
        var diagnostic=schemas.path("GeoDiagnosticCompletionAccepted").path("properties");
        assertThat(diagnostic.path("taskStatus").path("enum").toString()).isEqualTo("[\"RUNNING\",\"SUCCEEDED\",\"PARTIAL\",\"FAILED\",\"SYSTEM_TERMINATED\"]");
        assertThat(diagnostic.path("reportId").path("type").asText()).isEqualTo("null");assertThat(diagnostic.path("settledPoints").path("minimum").asInt()).isZero();
        var monitor=schemas.path("GeoMonitorCompletionAccepted").path("properties");assertThat(monitor.path("settledPoints").path("minimum").asInt()).isEqualTo(1);
        assertThat(monitor.path("taskStatus").path("enum").toString()).isEqualTo("[\"SUCCEEDED\",\"PARTIAL\"]");
        assertThat(monitor.path("reportId").path("$ref").asText()).endsWith("SnowflakeId");
    }
    @Test void entryAndWorkerFlagsDefaultOffAndDoNotUseSubmitFlagToStrandExistingDelivery() {
        assertThat(new PaidTaskProperties().isGeoDiagnosticSubmissionEnabled()).isFalse();
        for(Class<?> type:List.of(GeoDiagnosticDeliveryService.class,GeoDiagnosticDeliveryWorker.class)) {
            var condition=type.getAnnotation(ConditionalOnProperty.class);assertThat(condition.name()).containsExactly("enabled","geo-diagnostic-enabled");
            assertThat(condition.havingValue()).isEqualTo("true");
        }
    }
    @Test void derivedReportCaptionsRespectTwoHundredLimitWithoutChangingImmutableBrandFacts() {
        String brand="禾".repeat(200);var profile=new GeoDiagnosticDtos.Profile(brand,"https://example.com","确认的完整品牌事实",List.of(brand),"广州");
        var frozen=json.valueToTree(profile);assertThat(GeoDiagnosticDeliveryService.reportTitle(profile.brandName(),false)).hasSize(200).isEqualTo(brand);
        assertThat(GeoDiagnosticDeliveryService.reportTitle(profile.brandName(),true)).hasSize(200).isEqualTo(brand);
        assertThat(frozen.path("brandName").asText()).hasSize(200).isEqualTo(brand);assertThat(frozen.path("brandTerms").get(0).asText()).hasSize(200).isEqualTo(brand);
        assertThat(profile.brandName()).hasSize(200);assertThat(GeoDiagnosticDeliveryService.reportTitle("星河咖啡",false)).isEqualTo("星河咖啡 · GEO 诊断报告");
    }
    @Test void browserExposesAuthoritativeFullAnswerFlagsWhileLegacyUnknownRemainsNullAndFrozenEvidenceUntouched() throws Exception {
        var fixture=new GeoDiagnosticProjectionTests();var current=fixture.goodEvidence();String frozen=fixture.json.writeValueAsString(current);
        var samples=GeoDiagnosticDeliveryService.reportSamples(current,fixture.json);assertThat(samples).hasSize(6);
        assertThat(samples.getFirst().matchedBrandIds()).containsExactly("OWN");
        assertThat(samples.get(2).matchedBrandIds()).containsExactly("comp-1");
        assertThat(samples.getFirst().questionText()).contains("广州");assertThat(fixture.json.writeValueAsString(current)).isEqualTo(frozen);
        var legacy=current.deepCopy();for(var sample:legacy.path("samples"))((com.fasterxml.jackson.databind.node.ObjectNode)sample).remove("matchedBrandIds");
        String legacyFrozen=fixture.json.writeValueAsString(legacy);assertThat(GeoDiagnosticDeliveryService.reportSamples(legacy,fixture.json)).allSatisfy(sample->assertThat(sample.matchedBrandIds()).isNull());
        assertThat(fixture.json.writeValueAsString(legacy)).isEqualTo(legacyFrozen); // no backfill/input-hash rewrite on V1 recovery
        var unknown=current.deepCopy();for(var sample:unknown.path("samples"))((com.fasterxml.jackson.databind.node.ObjectNode)sample).putNull("matchedBrandIds");
        assertThat(GeoDiagnosticDeliveryService.reportSamples(unknown,fixture.json)).allSatisfy(sample->assertThat(sample.matchedBrandIds()).isNull());
        var checked=current.deepCopy();for(var sample:checked.path("samples"))((com.fasterxml.jackson.databind.node.ObjectNode)sample).putArray("matchedBrandIds");
        assertThat(GeoDiagnosticDeliveryService.reportSamples(checked,fixture.json)).allSatisfy(sample->{
            if(sample.status().equals("SUCCEEDED"))assertThat(sample.matchedBrandIds()).isEmpty();else assertThat(sample.matchedBrandIds()).isNull();});
        var schemas=contract("browser-openapi.yaml").path("components").path("schemas");
        assertThat(schemas.path("GeoDiagnosticSample").path("required")).isNotEmpty();
        assertThat(schemas.path("GeoDiagnosticSample").path("properties").path("matchedBrandIds").path("type").toString()).isEqualTo("[\"array\",\"null\"]");
        assertThat(schemas.path("GeoDiagnosticSample").path("required").toString()).doesNotContain("matchedBrandIds");
        assertThat(schemas.path("GeoDiagnosticSample").path("properties").path("answerExcerpt").path("maxLength").asInt()).isEqualTo(1600);
    }
    @Test void consultingBrowserFieldsAreIncrementalAndUseClosedEvidenceStructures() throws Exception {
        var schemas=contract("browser-openapi.yaml").path("components").path("schemas");
        assertThat(schemas.path("GeoDiagnosticAnalysis").path("required").toString()).doesNotContain("strategy");
        assertThat(schemas.path("GeoDiagnosticReportDetail").path("properties").has("competitors")).isTrue();
        assertThat(schemas.path("GeoDiagnosticRecommendation").path("properties").has("acceptance")).isTrue();
        for(String name:List.of("GeoDiagnosticStrategy","GeoDiagnosticCoreJudgment","GeoDiagnosticCognitionFinding","GeoDiagnosticKeywordLayer","GeoDiagnosticRoadmapStage"))
            assertThat(schemas.path(name).path("additionalProperties").asBoolean(true)).isFalse();
        assertThat(schemas.path("GeoDiagnosticStrategy").path("properties").path("version").path("const").asText()).isEqualTo("consulting-v1");
    }
    @Test void agentAnalysisV11KeepsLegacyUnknownFlagsNullableWithoutRelaxingTheClosedSample() throws Exception {
        var schemas=contract("agent-openapi.yaml").path("components").path("schemas");var sample=schemas.path("DiagnosticSample");
        assertThat(sample.path("additionalProperties").asBoolean()).isFalse();
        assertThat(sample.path("required").toString()).doesNotContain("matchedBrandIds");
        var variants=sample.path("properties").path("matchedBrandIds").path("anyOf");assertThat(variants).hasSize(2);
        assertThat(variants.get(0).path("type").asText()).isEqualTo("array");assertThat(variants.get(0).path("maxItems").asInt()).isEqualTo(6);
        assertThat(variants.get(1).path("type").asText()).isEqualTo("null");
        assertThat(schemas.path("DiagnosticAnalysisResponse").path("properties").path("promptVersion").path("const").asText()).isEqualTo("GEO_DIAGNOSTIC_ANALYSIS_V11");
        assertThat(sample.path("properties").path("answerExcerpt").toString()).contains("1600");
        assertThat(schemas.path("DiagnosticReport").path("properties").has("strategy")).isTrue();
    }
    private JsonNode contract(String filename)throws Exception {try(var stream=getClass().getResourceAsStream("/contracts/openapi/"+filename)){return new ObjectMapper(new YAMLFactory()).readTree(stream);}}
    private Set<String> fields(JsonNode value){Set<String> fields=new HashSet<>();value.fieldNames().forEachRemaining(fields::add);return fields;}
}
