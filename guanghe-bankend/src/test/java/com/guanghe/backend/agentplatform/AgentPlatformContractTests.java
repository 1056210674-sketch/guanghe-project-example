package com.guanghe.backend.agentplatform;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.parser.OpenAPIV3Parser;
import io.swagger.v3.parser.core.models.ParseOptions;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class AgentPlatformContractTests {
    @Test void pathsHaveExactOperationsAndBearerErrorResponses() {
        var api=parse();
        var operations=Map.of("GET /api/v1/agents","listAgents","GET /api/v1/agent-installations","listAgentInstallations",
                "POST /api/v1/agent-installations","addAgentInstallation","PATCH /api/v1/agent-installations/{id}","updateAgentInstallation",
                "DELETE /api/v1/agent-installations/{id}","removeAgentInstallation","GET /api/v1/projects","listProjects","POST /api/v1/projects","createProject");
        operations.forEach((key,id)-> {
            String[] parts=key.split(" ");
            var operation=api.getPaths().get(parts[1]).readOperationsMap().entrySet().stream()
                    .filter(entry->entry.getKey().name().equals(parts[0])).findFirst().orElseThrow().getValue();
            assertThat(operation.getOperationId()).isEqualTo(id);
            assertThat(operation.getSecurity()).anyMatch(value->value.containsKey("bearerAuth"));
            assertThat(operation.getResponses()).containsKeys("401","403",key.equals("POST /api/v1/projects")?"201":"200");
            assertThat(operation.getParameters()==null?List.of():operation.getParameters()).noneMatch(value->"Idempotency-Key".equals(value.getName()));
        });
    }
    @Test void schemasKeepVersionIdentityAndProjectsGenericWithoutImportAuthority() {
        var schemas=parse().getComponents().getSchemas();
        assertThat(schemas.get("AddAgentInstallationRequest").getProperties()).containsOnlyKeys("agentKey");
        assertThat(schemas.get("CreateProjectRequest").getProperties()).containsOnlyKeys("name");
        assertThat(schemas.get("UpdateAgentInstallationRequest").getRequired()).containsExactlyInAnyOrder("version","enabled");
        for(var name:List.of("AddAgentInstallationRequest","UpdateAgentInstallationRequest","CreateProjectRequest"))
            assertThat(schemas.get(name).getAdditionalProperties()).isEqualTo(false);
        assertThat(schemas.get("AgentIdentity").getRequired()).containsExactlyInAnyOrder("installationId","agentKey","agentVersion","name","projectId","capabilities");
        assertThat(((Schema<?>)schemas.get("AgentIdentity").getProperties().get("capabilities")).getItems().getMaxLength()).isEqualTo(64);
        assertThat(((Schema<?>)schemas.get("AgentDefinitionView").getProperties().get("status")).getEnum()).extracting(Object::toString).containsExactly("PUBLISHED","DISABLED","WITHDRAWN");
        assertThat(((Schema<?>)schemas.get("ProjectView").getProperties().get("status")).getEnum()).extracting(Object::toString).containsExactly("ACTIVE","ARCHIVED");
    }
    @Test void legacyIdentitySerializesExplicitNullsAndIdsStayStrings() throws Exception {
        var json=new ObjectMapper();
        var legacy=json.valueToTree(new AgentPlatformDtos.AgentIdentity(null,"geo","1.0.0","GEO",null,List.of("chat")));
        assertThat(legacy.has("installationId")).isTrue(); assertThat(legacy.get("installationId").isNull()).isTrue();
        assertThat(legacy.get("projectId").isNull()).isTrue();
        var frozen=json.valueToTree(new AgentPlatformDtos.AgentIdentity("1900012345678901100","geo","1.0.0","GEO","1900012345678901101",List.of("chat")));
        assertThat(frozen.get("installationId").isTextual()).isTrue(); assertThat(frozen.get("projectId").isTextual()).isTrue();
        assertThatThrownBy(()->json.readValue("{\"agentKey\":\"geo\",\"script\":\"x\"}",AgentPlatformDtos.AddInstallationRequest.class)).isInstanceOf(Exception.class);
    }
    @Test void explicitGeoTargetSettingsKeepNameSeparateFromProjectDisplayNameAndRejectInjectedAuthority() {
        var api = parse();
        var path = api.getPaths().get("/api/v1/projects/{projectId}/geo-target");
        assertThat(path.getGet().getOperationId()).isEqualTo("getProjectGeoTarget");
        assertThat(path.getPatch().getOperationId()).isEqualTo("updateProjectGeoTarget");
        for (var operation : List.of(path.getGet(), path.getPatch())) {
            assertThat(operation.getSecurity()).anyMatch(value -> value.containsKey("bearerAuth"));
            assertThat(operation.getResponses()).containsKeys("200", "401", "403", "404");
        }
        assertThat(path.getPatch().getResponses()).containsKeys("409", "422");
        var request = api.getComponents().getSchemas().get("UpdateProjectGeoTargetRequest");
        assertThat(request.getAdditionalProperties()).isEqualTo(false);
        assertThat(request.getRequired()).containsExactlyInAnyOrder("version", "name", "aliases");
        assertThat(request.getProperties()).containsOnlyKeys("version", "name", "aliases");
        assertThat(((Schema<?>) request.getProperties().get("name")).getMinLength()).isEqualTo(2);
        assertThat(((Schema<?>) request.getProperties().get("name")).getMaxLength()).isEqualTo(100);
        var aliases = (Schema<?>) request.getProperties().get("aliases");
        assertThat(aliases.getMaxItems()).isEqualTo(50);
        assertThat(aliases.getUniqueItems()).isTrue();
        assertThat(aliases.getItems().getMinLength()).isEqualTo(2);
        var view = api.getComponents().getSchemas().get("ProjectGeoTarget");
        assertThat(view.getRequired()).containsExactlyInAnyOrder("projectId", "version", "name", "aliases", "configured", "updatedAt");
        assertThat(((Schema<?>) view.getProperties().get("name")).getTypes()).containsExactlyInAnyOrder("string", "null");
        assertThat(view.getProperties()).doesNotContainKeys("userId", "brandProfile", "projectName", "sourceUrl");
    }
    private static OpenAPI parse() {
        var options=new ParseOptions(); options.setResolve(true); options.setResolveFully(true);
        var result=new OpenAPIV3Parser().readLocation(AgentPlatformContractTests.class.getResource("/contracts/openapi/browser-openapi.yaml").toString(),null,options);
        assertThat(result.getMessages()).isEmpty(); return result.getOpenAPI();
    }
}
