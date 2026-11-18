package com.guanghe.backend.agentplatform;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.parser.OpenAPIV3Parser;
import io.swagger.v3.parser.core.models.ParseOptions;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class MonitorExecutionContractTests {
    @Test void browserOperationsAreOwnedNormalSettingsCrudAndExactResponses() {
        var api=parse("browser-openapi.yaml");
        var operations=Map.of(
            "GET /api/v1/execution-budget-policy","getExecutionBudgetPolicy",
            "PUT /api/v1/execution-budget-policy","putExecutionBudgetPolicy",
            "POST /api/v1/conversations/{conversationId}/geo-monitor-action-preview","previewGeoMonitorAction",
            "GET /api/v1/conversations/{conversationId}/geo-monitor-authorizations","listGeoMonitorAuthorizations",
            "POST /api/v1/conversations/{conversationId}/geo-monitor-authorizations","createGeoMonitorAuthorization",
            "PUT /api/v1/conversations/{conversationId}/geo-monitor-authorizations/{grantId}","updateGeoMonitorAuthorization",
            "POST /api/v1/conversations/{conversationId}/geo-monitor-authorizations/{grantId}/revoke","revokeGeoMonitorAuthorization",
            "GET /api/v1/conversations/{conversationId}/runs/{runId}/geo-monitor-admission","getGeoMonitorAdmission");
        operations.forEach((key,id)->{
            var parts=key.split(" ");var op=api.getPaths().get(parts[1]).readOperationsMap().entrySet().stream()
                    .filter(entry->entry.getKey().name().equals(parts[0])).findFirst().orElseThrow().getValue();
            assertThat(op.getOperationId()).isEqualTo(id);assertThat(op.getSecurity()).anyMatch(value->value.containsKey("bearerAuth"));
            assertThat(op.getResponses()).containsKeys("200","401","403","422");
            assertThat(op.getParameters()==null?List.of():op.getParameters()).noneMatch(value->"Idempotency-Key".equals(value.getName()));
        });
    }
    @Test void intentAndGrantNeverAcceptInternalAuthorityAndBudgetHasNoDefault() {
        var schemas=parse("browser-openapi.yaml").getComponents().getSchemas();
        assertThat(schemas.get("MonitorExecutionIntent").getProperties()).containsOnlyKeys("action","grantId","grantVersion","range","pricingVersionId","quotedPoints");
        assertThat(schemas.get("BudgetPolicyPut").getRequired()).containsExactlyInAnyOrder("version","enabled","maxPointsPerAction","maxPointsPerDay");
        for(String name:List.of("MonitorExecutionIntent","BudgetPolicyPut","MonitorRangeInput","FrozenMonitorRange","MonitorAuthorizationCreate","MonitorAuthorizationPut")) {
            var schema=schemas.get(name);
            // The 3.1 parser can represent a boolean schema as Schema(false), not Boolean(false).
            // Validate the literal closed authority rather than weakening this assertion.
            assertThat(rawSchemas("browser-openapi.yaml").path(name).path("additionalProperties").isBoolean()).isTrue();
            assertThat(rawSchemas("browser-openapi.yaml").path(name).path("additionalProperties").booleanValue()).isFalse();
            assertThat(schema.getProperties()).doesNotContainKeys("userId","authorized","installationId","agentKey","projectId");
            schema.getProperties().values().forEach(property->assertThat(((Schema<?>)property).getDefault()).isNull());
        }
        var reasons=schemas.get("WaitReason").getEnum().stream().map(Object::toString).toList();
        assertThat(reasons).containsExactlyInAnyOrder(java.util.Arrays.stream(MonitorWaitReason.values()).map(Enum::name).toArray(String[]::new));
    }
    @Test void internalOnlyReceivesExactMonitorParametersAndQuestionFactsNotBudgetsOrGrants() {
        var schemas=parse("java-internal-openapi.yaml").getComponents().getSchemas();
        var context=schemas.get("MonitorActionContext");
        assertThat(context.getRequired()).containsExactlyInAnyOrder("action","spaceId","productId","parameters","questions");
        assertThat(context.getProperties()).containsOnlyKeys("action","spaceId","productId","parameters","questions");
        assertThat(rawSchemas("java-internal-openapi.yaml").path("MonitorActionContext").path("additionalProperties").isBoolean()).isTrue();
        assertThat(rawSchemas("java-internal-openapi.yaml").path("MonitorActionContext").path("additionalProperties").booleanValue()).isFalse();
        assertThat(schemas.get("GeoTaskParameters").getRequired()).containsExactlyInAnyOrder("mode","topicId","questionIds","platformCodes","sampleMode","region");
        assertThat(schemas.get("GeoQuestionSnapshot").getProperties()).containsOnlyKeys("id","category","text");
        assertThat(((Schema<?>)schemas.get("GeoQuestionSnapshot").getProperties().get("text")).getMaxLength()).isEqualTo(1000);
        assertThat(schemas.get("AuthorizedRunContext").getRequired()).doesNotContain("monitorActionContext","requestMessageId");
    }
    private OpenAPI parse(String file) {
        var options=new ParseOptions();options.setResolve(true);options.setResolveFully(true);
        var result=new OpenAPIV3Parser().readLocation(getClass().getResource("/contracts/openapi/"+file).toString(),null,options);
        assertThat(result.getMessages()).isEmpty();return result.getOpenAPI();
    }
    private JsonNode rawSchemas(String file) {
        try(var stream=getClass().getResourceAsStream("/contracts/openapi/"+file)) {
            return new ObjectMapper(new YAMLFactory()).readTree(stream).path("components").path("schemas");
        } catch(Exception error) { throw new AssertionError("Cannot read canonical schemas",error); }
    }
}
