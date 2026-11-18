package com.guanghe.backend.catalog.product;

import com.guanghe.backend.asset.AssetIngestionProperties;
import com.guanghe.backend.asset.AssetMapper;
import com.guanghe.backend.asset.AssetRecords;
import com.guanghe.backend.auth.account.AccountRole;
import com.guanghe.backend.auth.account.AccountStatus;
import com.guanghe.backend.auth.security.AuthenticatedAccount;
import com.guanghe.backend.common.api.BusinessException;
import com.guanghe.backend.common.api.ErrorCode;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProductIdentityAssetServiceTests {
    private ProductMapper products;
    private AssetMapper assets;
    private ProductIdentityAssetService service;

    @BeforeEach
    void setUp() {
        products = mock(ProductMapper.class);
        assets = mock(AssetMapper.class);
        AssetIngestionProperties properties = new AssetIngestionProperties();
        properties.setEnvironment("test");
        properties.setSafetyMode(AssetIngestionProperties.SafetyMode.TEST_BYPASS);
        properties.setTestBypassDownloadEnabled(true);
        service = new ProductIdentityAssetService(products, assets, properties);
        when(products.findByIdAndUserId(102, 41)).thenReturn(Optional.of(product()));
    }

    @Test
    void replacementIsVersionedAndAcceptsOnlyOwnedReadyImages() {
        when(assets.findAsset(11, 41)).thenReturn(Optional.of(asset(11)));
        when(assets.findAsset(12, 41)).thenReturn(Optional.of(asset(12)));
        when(products.updateIdentityVersion(eq(102L), eq(41L), eq(101L), eq(3), eq(11L),
                any(OffsetDateTime.class))).thenReturn(1);
        when(products.findIdentityAssets(102, 41, 101)).thenReturn(List.of(
                identity(11, "PRIMARY", 1), identity(12, "DETAIL", 2)));

        ProductDtos.IdentityAssetsResponse response = service.replace(user(), 102,
                new ProductDtos.ReplaceIdentityAssetsRequest(3, List.of(
                        new ProductDtos.IdentityAssetInput("11", "PRIMARY", 1),
                        new ProductDtos.IdentityAssetInput("12", "DETAIL", 2))));

        assertThat(response.version()).isEqualTo(4);
        assertThat(response.creativeReady()).isTrue();
        assertThat(response.items()).extracting(ProductDtos.IdentityAssetResponse::assetId)
                .containsExactly("11", "12");
        verify(products).deleteIdentityAssets(102, 41, 101);
        verify(products, org.mockito.Mockito.times(2)).insertIdentityAsset(
                anyLong(), anyLong(), anyLong(), anyLong(), any(), anyInt(), any());
    }

    @Test
    void anotherUsersOrUnreadyAssetFailsBeforeChangingTheProduct() {
        when(assets.findAsset(99, 41)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.replace(user(), 102,
                new ProductDtos.ReplaceIdentityAssetsRequest(3, List.of(
                        new ProductDtos.IdentityAssetInput("99", "PRIMARY", 1)))))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.errorCode()).isEqualTo(ErrorCode.REFERENCE_ASSET_NOT_OWNED));

        verify(products, never()).updateIdentityVersion(anyLong(), anyLong(), anyLong(),
                org.mockito.ArgumentMatchers.anyInt(), any(), any());
    }

    private ProductRecord product() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        return new ProductRecord(102, 41, 101, "美式咖啡", "美式咖啡", "咖啡", null,
                "[]", "{}", "[]", "[]", "[]", null, 3, now, now, 0);
    }

    private AssetRecords.Asset asset(long id) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        return new AssetRecords.Asset(id, 41, 101, null, "IMAGE", "UPLOADED", null,
                "reference.png", "zhixiaohe/test/users/41/assets/" + id + "/original.png",
                "image/png", 512, "a".repeat(64), 64, 64, null,
                "READY", "TEST_BYPASS", now, now);
    }

    private ProductIdentityAssetRecord identity(long id, String role, int sequence) {
        return new ProductIdentityAssetRecord(id, role, sequence, "READY", "TEST_BYPASS",
                "image/png", 512, "a".repeat(64), 64, 64,
                "zhixiaohe/test/users/41/assets/" + id + "/original.png");
    }

    private AuthenticatedAccount user() {
        return new AuthenticatedAccount(41, "user", AccountRole.USER, 1,
                AccountStatus.ACTIVE, null, null, null);
    }
}
