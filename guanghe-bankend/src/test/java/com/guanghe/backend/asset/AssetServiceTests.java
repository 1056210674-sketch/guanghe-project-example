package com.guanghe.backend.asset;

import com.guanghe.backend.auth.account.AccountRole;
import com.guanghe.backend.auth.account.AccountStatus;
import com.guanghe.backend.auth.security.AuthenticatedAccount;
import com.guanghe.backend.common.api.BusinessException;
import com.guanghe.backend.common.api.ErrorCode;
import java.net.URI;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AssetServiceTests {
    private AssetMapper mapper;
    private ObjectStorage storage;
    private AssetIngestionProperties properties;
    private AssetService service;

    @BeforeEach
    void setUp() {
        mapper = mock(AssetMapper.class);
        storage = mock(ObjectStorage.class);
        properties = new AssetIngestionProperties();
        properties.setEnvironment("test");
        properties.setTestBypassDownloadEnabled(true);
        properties.setSafetyMode(AssetIngestionProperties.SafetyMode.TEST_BYPASS);
        service = new AssetService(mapper, storage, properties);
    }

    @Test
    void userCanListAndReadOnlyOwnedAssetsWithoutObjectKey() {
        AssetRecords.Asset asset = asset(41, "READY", "TEST_BYPASS");
        when(mapper.countAssets(41, "IMAGE", 101L, 102L, "GENERATED")).thenReturn(1L);
        when(mapper.findAssetPage(41, "IMAGE", 101L, 102L, "GENERATED", 0, 20))
                .thenReturn(List.of(asset));
        when(mapper.findAsset(asset.id(), 41)).thenReturn(Optional.of(asset));
        when(mapper.findProductIds(asset.id(), 41)).thenReturn(List.of(102L));

        var page = service.list(user(41, AccountRole.USER), 1, 20, "IMAGE", 101L, 102L, "GENERATED");
        var detail = service.get(user(41, AccountRole.USER), asset.id());

        assertThat(page.items()).hasSize(1);
        assertThat(detail.id()).isEqualTo(Long.toString(asset.id()));
        assertThat(detail.productIds()).containsExactly("102");
    }

    @Test
    void adminIsForbiddenAndAnotherUserSeesNotFound() {
        assertCode(() -> service.get(user(1, AccountRole.ADMIN), 6002), ErrorCode.FORBIDDEN);
        when(mapper.findAsset(6002, 42)).thenReturn(Optional.empty());
        assertCode(() -> service.get(user(42, AccountRole.USER), 6002), ErrorCode.RESOURCE_NOT_FOUND);
    }

    @Test
    void downloadUrlRequiresReadyAndApprovedOrExplicitTestBypass() {
        AssetRecords.Asset ready = asset(41, "READY", "TEST_BYPASS");
        when(mapper.findAsset(ready.id(), 41)).thenReturn(Optional.of(ready));
        when(storage.signedGetUrl(anyString(), any())).thenReturn(URI.create("https://bucket.example/signed"));

        var response = service.downloadUrl(user(41, AccountRole.USER), ready.id());
        assertThat(response.url()).startsWith("https://");
        verify(storage).signedGetUrl(ready.objectKey(), response.expiresAt());

        properties.setTestBypassDownloadEnabled(false);
        assertCode(() -> service.downloadUrl(user(41, AccountRole.USER), ready.id()), ErrorCode.ASSET_NOT_READY);
        when(mapper.findAsset(6003, 41)).thenReturn(Optional.of(asset(41, "SCANNING", "PENDING", 6003)));
        assertCode(() -> service.downloadUrl(user(41, AccountRole.USER), 6003), ErrorCode.ASSET_NOT_READY);
    }

    @Test
    void explicitNotReviewedModeAllowsDownloadsAndPreservesLegacyTestAssets() {
        AssetRecords.Asset unreviewed = asset(41, "READY", "NOT_REVIEWED");
        when(mapper.findAsset(unreviewed.id(), 41)).thenReturn(Optional.of(unreviewed));
        when(storage.signedGetUrl(anyString(), any())).thenReturn(URI.create("https://bucket.example/signed"));

        properties.setEnvironment("production");
        properties.setSafetyMode(AssetIngestionProperties.SafetyMode.NOT_REVIEWED);
        properties.setTestBypassDownloadEnabled(false);
        assertThat(service.downloadUrl(user(41, AccountRole.USER), unreviewed.id()).url())
                .startsWith("https://");

        properties.setSafetyMode(AssetIngestionProperties.SafetyMode.REAL);
        assertCode(() -> service.downloadUrl(user(41, AccountRole.USER), unreviewed.id()),
                ErrorCode.ASSET_NOT_READY);

        properties.setEnvironment("test");
        properties.setSafetyMode(AssetIngestionProperties.SafetyMode.NOT_REVIEWED);
        properties.setTestBypassDownloadEnabled(true);
        when(mapper.findAsset(6003, 41)).thenReturn(Optional.of(asset(41, "READY", "TEST_BYPASS", 6003)));
        assertThat(service.downloadUrl(user(41, AccountRole.USER), 6003).url()).startsWith("https://");
    }

    private void assertCode(org.assertj.core.api.ThrowableAssert.ThrowingCallable action, ErrorCode code) {
        assertThatThrownBy(action).isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).errorCode()).isEqualTo(code);
    }

    private AssetRecords.Asset asset(long userId, String status, String safety) {
        return asset(userId, status, safety, 6002);
    }

    private AssetRecords.Asset asset(long userId, String status, String safety, long id) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        return new AssetRecords.Asset(id, userId, 101, 102L, "IMAGE", "GENERATED", 9002L,
                "AI生成图片.png", "zhixiaohe/test/users/" + userId + "/assets/" + id + "/original.png",
                "image/png", 88, "a".repeat(64), 32, 24, null, status, safety, now, now);
    }

    private AuthenticatedAccount user(long id, AccountRole role) {
        return new AuthenticatedAccount(id, "user", role, 1, AccountStatus.ACTIVE, null, null, null);
    }
}
