package com.guanghe.backend.agentplatform;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.guanghe.backend.common.api.BusinessException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class AgentRuntimeCatalogTests {
    private final AgentRuntimeCatalog catalog = new AgentRuntimeCatalog();

    @Test void controlledCatalogKeepsExactArticleVersionsAndNormalizesOnlyLineEndings() throws Exception {
        byte[] bytes = AgentRuntimeCatalogTests.class.getResourceAsStream("/contracts/agents/runtime-catalog.json").readAllBytes();
        var windows = new AgentRuntimeCatalog(new String(bytes, StandardCharsets.UTF_8).replace("\r\n", "\n")
                .replace("\n", "\r\n").getBytes(StandardCharsets.UTF_8));
        assertThat(windows.catalogHash()).isEqualTo(catalog.catalogHash());
        assertThat(catalog.acceptedArticlePromptVersions()).contains("agent-bundle-v1.48.3", "agent-bundle-v1.49.0", "agent-bundle-v1.50.0", "agent-bundle-v1.51.0", "agent-bundle-v1.52.0", "agent-bundle-v1.53.0", "agent-bundle-v1.54.0", "agent-bundle-v1.59.0", "agent-bundle-v1.60.0", "agent-bundle-v1.61.0", "agent-bundle-v1.62.0", "agent-bundle-v1.62.1")
                .doesNotContain("agent-bundle-v1.48.4", "agent-bundle-v1.50.1", "agent-bundle-v1.51.1", "agent-bundle-v1.53.1", "agent-bundle-v1.54.1", "agent-bundle-v1.59.1", "agent-bundle-v1.60.1", "agent-bundle-v1.61.1", "agent-bundle-v1.62.2", "anything");
    }

    @Test void fourthAgentReusesNativeAdapterAndCannotTurnReadPermissionIntoMemoryWrites() throws Exception {
        var fixture = readOnlyCatalog();
        var identity = fixture.freeze(new AgentPlatformDtos.AgentIdentity("71", "client-reader", "2.1.0",
                "Client reader", "21", List.of("chat", "resources.read", "memory.read")));
        assertThat(fixture.require(identity).runtimeKind()).isEqualTo("native");
        assertThat(fixture.require(identity).catalogueAdapterKey()).isEqualTo("geo");
        assertThat(fixture.permits(identity, "context.read")).isTrue();
        assertThat(fixture.permits(identity, "memory.read")).isTrue();
        assertThat(fixture.permits(identity, "memory.upsert")).isFalse();
        assertThat(fixture.permits(identity, "memory.forget")).isFalse();
        assertThat(fixture.permits(identity, "geo.monitor")).isFalse();
        assertThatThrownBy(() -> fixture.validateFrozen(new AgentPlatformDtos.AgentIdentity("71", "client-reader", "2.1.0",
                "Client reader", "21", List.of("chat", "resources.read", "memory.read")))).isInstanceOf(BusinessException.class);
    }

    @Test void newFrozenRunDoesNotRegainRevokedWriteCapabilitiesFromLegacyMappings() {
        var original = catalog.freeze(new AgentPlatformDtos.AgentIdentity("71", "geo", "1.0.0", "GEO", "21",
                List.of("chat", "resources.read")));
        assertThat(original.capabilities()).contains("memory.write", "memory.delete", "brand.write");
        var readOnly = new AgentPlatformDtos.AgentIdentity(original.installationId(), original.agentKey(), original.agentVersion(),
                original.name(), original.projectId(), List.of("chat", "resources.read"), original.executionRelease(), original.catalogHash());
        assertThat(catalog.validateFrozen(readOnly).capabilities()).containsExactly("chat", "resources.read");
        assertThat(catalog.permits(catalog.validateFrozen(readOnly), "memory.upsert")).isFalse();
    }

    @Test void unknownAgentVersionAndIncompatibleOrPartialReleaseAreRejected() {
        assertThatThrownBy(() -> catalog.require("geo", "9.0.0")).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> catalog.require("unregistered", "1.0.0")).isInstanceOf(BusinessException.class);
        var current = catalog.freeze(new AgentPlatformDtos.AgentIdentity("71", "geo", "1.0.0", "GEO", "21", List.of("chat")));
        for (var identity : List.of(
                new AgentPlatformDtos.AgentIdentity("71","geo","1.0.0","GEO","21",List.of("chat"),"unknown",current.catalogHash()),
                new AgentPlatformDtos.AgentIdentity("71","geo","1.0.0","GEO","21",List.of("chat"),current.executionRelease(),"0".repeat(64)),
                new AgentPlatformDtos.AgentIdentity("71","geo","1.0.0","GEO","21",List.of("chat"),null,current.catalogHash()))) {
            assertThatThrownBy(() -> catalog.validateFrozen(identity)).isInstanceOf(BusinessException.class);
        }
    }

    @Test void duplicateIdentitiesAndUnregisteredToolsFailStartup() throws Exception {
        var mapper = new ObjectMapper();
        ObjectNode root = (ObjectNode) mapper.readTree(AgentRuntimeCatalogTests.class.getResourceAsStream("/contracts/agents/runtime-catalog.json"));
        ((ArrayNode) root.path("agents")).add(root.path("agents").get(0).deepCopy());
        assertThatThrownBy(() -> new AgentRuntimeCatalog(mapper.writeValueAsBytes(root))).isInstanceOf(IllegalStateException.class);
        ((ArrayNode) root.path("agents")).remove(root.path("agents").size()-1);
        ((ArrayNode) root.path("agents").get(0).path("allowedTools")).add("shell.execute");
        assertThatThrownBy(() -> new AgentRuntimeCatalog(mapper.writeValueAsBytes(root))).isInstanceOf(IllegalStateException.class);
    }

    static AgentRuntimeCatalog readOnlyCatalog() throws Exception {
        var mapper = new ObjectMapper();
        ObjectNode root = (ObjectNode) mapper.readTree(AgentRuntimeCatalogTests.class.getResourceAsStream("/contracts/agents/runtime-catalog.json"));
        ObjectNode entry = ((ObjectNode) root.path("agents").get(0)).deepCopy();
        entry.put("agentKey", "client-reader").put("agentVersion", "2.1.0");
        entry.putArray("capabilities").add("chat").add("resources.read").add("memory.read");
        entry.putObject("legacyCapabilityGrants");
        entry.putArray("allowedTools").add("context.read").add("memory.read").add("memory.upsert").add("memory.forget");
        ((ArrayNode) root.path("agents")).add(entry);
        return new AgentRuntimeCatalog(mapper.writeValueAsBytes(root));
    }
}
