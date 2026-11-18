package com.guanghe.backend.asset;

import com.aliyun.oss.OSS;
import com.guanghe.backend.auth.account.AccountRole;
import com.guanghe.backend.auth.account.AccountStatus;
import com.guanghe.backend.auth.security.AuthenticatedAccount;
import com.guanghe.backend.catalog.PageResponse;
import com.guanghe.backend.catalog.space.BusinessSpaceService;
import com.guanghe.backend.catalog.product.ProductMapper;
import com.guanghe.backend.catalog.product.ProductIdentityAssetService;
import com.guanghe.backend.conversation.ConversationMapper;
import com.guanghe.backend.task.TaskControlMapper;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("mock")
class AssetInterfaceIntegrationTests {
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("zhixiaohe.asset.enabled", () -> "true");
        registry.add("zhixiaohe.asset.environment", () -> "test");
        registry.add("zhixiaohe.asset.provider-allowed-hosts", () -> "cdn.example.com");
        registry.add("zhixiaohe.asset.safety-mode", () -> "TEST_BYPASS");
        registry.add("zhixiaohe.asset.oss.endpoint", () -> "https://oss.example.com");
        registry.add("zhixiaohe.asset.oss.region", () -> "test-region");
        registry.add("zhixiaohe.asset.oss.bucket", () -> "test-bucket");
        registry.add("zhixiaohe.asset.oss.access-key-id", () -> "test-placeholder");
        registry.add("zhixiaohe.asset.oss.access-key-secret", () -> "test-placeholder");
    }

    @Autowired MockMvc mvc;
    @MockitoBean AssetService service;
    @MockitoBean AssetMapper mapper;
    @MockitoBean TaskControlMapper taskControlMapper;
    @MockitoBean ConversationMapper conversationMapper;
    @MockitoBean OSS oss;
    @MockitoBean ObjectStorage storage;
    @MockitoBean ProviderArtifactDownloader downloader;
    @MockitoBean BusinessSpaceService spaces;
    @MockitoBean PlatformTransactionManager transactionManager;
    @MockitoBean ProductMapper productMapper;
    @MockitoBean ProductIdentityAssetService identityAssets;
    @MockitoBean AssetUploadService uploads;

    @Test
    void browserMultipartTextFieldsBindWithoutPartMessageConverters() throws Exception {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        long spaceId = 1900012345678901100L;
        AssetDtos.UploadResponse uploaded = new AssetDtos.UploadResponse(
                "1900012345678901800", "IMAGE", "UPLOADED", "READY", "TEST_BYPASS",
                "库迪咖啡白底图.png", Long.toString(spaceId), "image/png", 8,
                "a".repeat(64), 16, 16, now);
        when(uploads.upload(any(), anyLong(), anyString(), any(), any())).thenReturn(uploaded);

        MockMultipartFile image = new MockMultipartFile(
                "file", "coffee.png", "image/png", new byte[]{1, 2, 3, 4});
        mvc.perform(multipart("/api/v1/assets/images")
                        .file(image)
                        .param("spaceId", Long.toString(spaceId))
                        .param("name", "库迪咖啡白底图.png")
                        .header("Idempotency-Key", "7f1480cc-b247-4fb1-8b9d-c2dc69ca8b51")
                        .with(user(AccountRole.USER)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.id").value(uploaded.id()))
                .andExpect(jsonPath("$.data.name").value(uploaded.name()));

        verify(uploads).upload(any(), org.mockito.ArgumentMatchers.eq(spaceId),
                org.mockito.ArgumentMatchers.eq("7f1480cc-b247-4fb1-8b9d-c2dc69ca8b51"),
                org.mockito.ArgumentMatchers.eq("库迪咖啡白底图.png"), any());
    }

    @Test
    void userReadsStringIdsWhileAdminIsForbiddenAndMalformedIdIs422() throws Exception {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        AssetDtos.Summary summary = new AssetDtos.Summary("1900012345678901800", "IMAGE", "GENERATED",
                "READY", "TEST_BYPASS", "图片", "1900012345678901100", "1900012345678901200",
                "image/png", 88, "a".repeat(64), 16, 12, "1900012345678901700", now, now);
        AssetDtos.Response detail = new AssetDtos.Response(summary.id(), summary.assetType(),
                summary.sourceType(), summary.status(), summary.safetyStatus(), summary.name(),
                summary.spaceId(), summary.primaryProductId(), List.of(summary.primaryProductId()),
                summary.contentType(), summary.sizeBytes(), summary.sha256(), summary.width(),
                summary.height(), null, summary.sourceTaskId(), now, now);
        when(service.list(any(), anyInt(), anyInt(), any(), any(), any(), any()))
                .thenReturn(PageResponse.of(List.of(summary), 1, 20, 1));
        when(service.get(any(), anyLong())).thenReturn(detail);

        mvc.perform(get("/api/v1/assets").with(user(AccountRole.USER)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].id").isString())
                .andExpect(jsonPath("$.data.items[0].objectKey").doesNotExist());
        mvc.perform(get("/api/v1/assets/1900012345678901800").with(user(AccountRole.USER)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.productIds[0]").isString())
                .andExpect(jsonPath("$.data.providerUrl").doesNotExist());
        mvc.perform(get("/api/v1/assets").with(user(AccountRole.ADMIN)))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/assets/not-a-number").with(user(AccountRole.USER)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    private org.springframework.test.web.servlet.request.RequestPostProcessor user(AccountRole role) {
        AuthenticatedAccount principal = new AuthenticatedAccount(41, "actor", role, 1,
                AccountStatus.ACTIVE, null, null, null);
        return authentication(new UsernamePasswordAuthenticationToken(principal, null,
                List.of(new SimpleGrantedAuthority("ROLE_" + role.name()))));
    }
}
