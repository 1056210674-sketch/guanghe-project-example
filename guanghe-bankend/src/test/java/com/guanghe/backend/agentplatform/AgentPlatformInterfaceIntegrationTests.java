package com.guanghe.backend.agentplatform;

import com.guanghe.backend.auth.account.AccountIdentity;
import com.guanghe.backend.auth.account.AccountMapper;
import com.guanghe.backend.auth.account.AccountRecord;
import com.guanghe.backend.auth.account.AccountRole;
import com.guanghe.backend.auth.account.AccountStatus;
import com.guanghe.backend.auth.ratelimit.LoginAttemptGuard;
import com.guanghe.backend.auth.security.JwtTokenService;
import com.guanghe.backend.adminplatform.AdminPlatformService;
import com.guanghe.backend.knowledge.KnowledgeService;
import com.guanghe.backend.geo.ProjectGeoTargetDtos;
import com.guanghe.backend.geo.ProjectGeoTargetService;
import com.guanghe.backend.common.api.BusinessException;
import com.guanghe.backend.common.api.ErrorCode;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc(print = org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint.NONE)
@ActiveProfiles("mock")
class AgentPlatformInterfaceIntegrationTests {
    private static final byte[] SECRET=new byte[32];
    static { new SecureRandom().nextBytes(SECRET); }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("zhixiaohe.auth.enabled",()->"true");
        registry.add("zhixiaohe.agent-platform.enabled",()->"true");
        registry.add("zhixiaohe.agent-platform.internal-agents-enabled",()->"true");
        registry.add("zhixiaohe.agent-platform.internal-agent-user-ids",()->"41");
        registry.add("zhixiaohe.auth.jwt.issuer",()->"agent-platform-test");
        registry.add("zhixiaohe.auth.jwt.access-token-ttl-seconds",()->"3600");
        registry.add("zhixiaohe.auth.jwt.secret-base64",()->Base64.getEncoder().encodeToString(SECRET));
    }
    @Autowired MockMvc mvc;
    @Autowired JwtTokenService tokens;
    @MockitoBean AgentPlatformMapper mapper;
    @MockitoBean AccountMapper accounts;
    @MockitoBean LoginAttemptGuard attempts;
    // This catalogue HTTP fixture has no database; independently tested adjacent
    // platform services must not require real Mapper/transaction infrastructure.
    @MockitoBean AdminPlatformService adminPlatform;
    @MockitoBean KnowledgeService knowledge;
    @MockitoBean ProjectGeoTargetService geoTargets;
    @MockitoBean com.guanghe.backend.conversation.context.ConversationCompactionService compaction;
    @MockitoBean com.guanghe.backend.knowledge.AgentMemoryService memory;
    @BeforeEach void setup() {
        when(accounts.findIdentityById(anyLong())).thenAnswer(i -> {
            long id=i.getArgument(0);
            return Optional.of(new AccountIdentity(id,"fixture",null,1,id==1?AccountRole.ADMIN:AccountRole.USER,AccountStatus.ACTIVE,null,null));
        });
        when(mapper.definitions()).thenReturn(List.of(AgentPlatformServiceTests.definition("geo","PUBLISHED"),
                AgentPlatformServiceTests.definition("summary","PUBLISHED")));
    }
    @Test void bearerAndRoleAreEnforcedOnEveryCollection() throws Exception {
        for(var path:List.of("/api/v1/agents","/api/v1/agent-installations","/api/v1/projects", "/api/v1/projects/71/geo-target")) {
            mvc.perform(get(path)).andExpect(status().isUnauthorized());
            mvc.perform(get(path).header("Authorization",bearer(1))).andExpect(status().isForbidden());
        }
        for (var path : List.of("/api/v1/admin/agent-definitions", "/api/v1/admin/geo-plans",
                "/api/v1/admin/platform-metadata", "/api/v1/admin/knowledge-documents",
                "/api/v1/admin/memory-preferences")) {
            mvc.perform(get(path)).andExpect(status().isUnauthorized());
            mvc.perform(get(path).header("Authorization", bearer(41))).andExpect(status().isForbidden());
        }
        verifyNoInteractions(adminPlatform);
    }
    @Test void publicGeoTargetRequiresUserOwnershipAndExplicitValidatedSettings() throws Exception {
        String path = "/api/v1/projects/71/geo-target";
        when(geoTargets.get(any(), eq(71L))).thenReturn(new ProjectGeoTargetDtos.View("71", 3, null,
                List.of(), false, AgentPlatformServiceTests.NOW));
        mvc.perform(get(path).header("Authorization", bearer(41))).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.projectId").value("71"))
                .andExpect(jsonPath("$.data.name").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.data.configured").value(false));
        String valid = "{\"version\":3,\"name\":\"小行星咖啡店\",\"aliases\":[]}";
        mvc.perform(patch(path).contentType(MediaType.APPLICATION_JSON).content(valid)).andExpect(status().isUnauthorized());
        mvc.perform(patch(path).header("Authorization", bearer(1)).contentType(MediaType.APPLICATION_JSON).content(valid))
                .andExpect(status().isForbidden());
        for (String invalid : List.of("{\"version\":3,\"name\":\"小行星咖啡店\"}",
                "{\"version\":0,\"name\":\"小行星咖啡店\",\"aliases\":[]}",
                "{\"version\":3,\"name\":null,\"aliases\":[]}",
                "{\"version\":3,\"name\":\"小行星咖啡店\",\"aliases\":[\"a\"]}",
                "{\"version\":3,\"name\":\"小行星咖啡店\",\"aliases\":[],\"userId\":42}"))
            mvc.perform(patch(path).header("Authorization", bearer(41)).contentType(MediaType.APPLICATION_JSON).content(invalid))
                    .andExpect(status().isUnprocessableEntity());
        verify(geoTargets, never()).update(any(), anyLong(), any());
        when(geoTargets.get(any(), eq(72L))).thenThrow(new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        mvc.perform(get("/api/v1/projects/72/geo-target").header("Authorization", bearer(41)))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }
    @Test void collectionsAreArraysAndInternalCatalogueIsOwnerRestricted() throws Exception {
        mvc.perform(get("/api/v1/agents").header("Authorization",bearer(41)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].capabilities").isArray()).andExpect(jsonPath("$.data[0].installedInstallationId").doesNotExist());
        mvc.perform(get("/api/v1/agents").header("Authorization",bearer(42)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(1));
        for(var path:List.of("/api/v1/agent-installations","/api/v1/projects"))
            mvc.perform(get(path).header("Authorization",bearer(41))).andExpect(status().isOk()).andExpect(jsonPath("$.data").isArray());
    }
    @Test void catalogueOnlyRequestsRejectIdentityCodeAndOwnerInjection() throws Exception {
        for(var field:List.of("userId","agentVersion","instructions","tools","script","endpoint"))
            mvc.perform(post("/api/v1/agent-installations").header("Authorization",bearer(41)).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"agentKey\":\"geo\",\""+field+"\":\"untrusted\"}"))
                    .andExpect(status().isUnprocessableEntity());
        mvc.perform(post("/api/v1/agent-installations").header("Authorization",bearer(42)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"agentKey\":\"summary\"}")).andExpect(status().isNotFound());
        verify(mapper,never()).insertInstallation(any());
    }
    @Test void projectNameOnlyCreates201WithStringIdAndNoBrand() throws Exception {
        when(mapper.insertProject(any())).thenReturn(1);
        mvc.perform(post("/api/v1/projects").header("Authorization",bearer(41)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"客户\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.data.id").isString())
                .andExpect(jsonPath("$.data.status").value("ACTIVE")).andExpect(jsonPath("$.data.brand").doesNotExist());
        mvc.perform(post("/api/v1/projects").header("Authorization",bearer(41)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\""+"x".repeat(101)+"\"}" )).andExpect(status().isUnprocessableEntity());
        mvc.perform(post("/api/v1/projects").header("Authorization",bearer(41)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"client\",\"userId\":42}")).andExpect(status().isUnprocessableEntity());
    }
    @Test void foreignMutationUsesOwned404AndPatchRequiresOptimisticVersion() throws Exception {
        mvc.perform(delete("/api/v1/agent-installations/51").header("Authorization",bearer(42))).andExpect(status().isNotFound());
        mvc.perform(patch("/api/v1/agent-installations/51").header("Authorization",bearer(41)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"enabled\":false}")).andExpect(status().isUnprocessableEntity());
        when(mapper.lockInstallation(41,51)).thenReturn(Optional.of(AgentPlatformServiceTests.installation(true,null)));
        mvc.perform(patch("/api/v1/agent-installations/51").header("Authorization",bearer(41)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":2,\"enabled\":false}")).andExpect(status().isConflict());
    }
    private String bearer(long id) {
        return "Bearer "+tokens.issue(new AccountRecord(id,"fixture","fixture",null,"a".repeat(32),"MD5_FIXED_SALT_V1",1,
                id==1?AccountRole.ADMIN:AccountRole.USER,AccountStatus.ACTIVE,null,null)).value();
    }
}
