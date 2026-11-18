package com.guanghe.backend.asset;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AssetIngestionPropertiesTests {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(AssetIngestionConfiguration.class);

    @Test
    void applicationStartupFailsClearlyWhenWorkerIsEnabledWithoutAssetIngestion() {
        contextRunner.withPropertyValues(
                        "zhixiaohe.asset.enabled=false",
                        "zhixiaohe.asset.worker-enabled=true")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasRootCauseMessage(
                            "ASSET_INGESTION_WORKER_ENABLED requires ASSET_INGESTION_ENABLED=true");
                });
    }

    @Test
    void testBypassIsRejectedOutsideTestAndObjectKeysAreJavaOwned() {
        AssetIngestionProperties properties = configured();
        properties.setEnvironment("production");
        properties.setSafetyMode(AssetIngestionProperties.SafetyMode.TEST_BYPASS);

        assertThatThrownBy(properties::validate).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("only allowed in environment=test");

        properties.setEnvironment("test");
        properties.validate();
        assertThat(properties.objectKey(41, 99))
                .isEqualTo("zhixiaohe/test/users/41/assets/99/original.png");
    }

    @Test
    void notReviewedModeRecordsItsOwnStatusAndRetainsTestAssetAccess() {
        AssetIngestionProperties properties = configured();
        properties.setEnvironment("production");
        properties.setSafetyMode(AssetIngestionProperties.SafetyMode.NOT_REVIEWED);
        properties.validate();
        assertThat(properties.acceptsNewAssetStatus("NOT_REVIEWED")).isTrue();
        assertThat(properties.acceptsNewAssetStatus("APPROVED")).isFalse();
        assertThat(properties.acceptsNewAssetStatus("TEST_BYPASS")).isFalse();
        assertThat(properties.allowsAssetUse("NOT_REVIEWED")).isTrue();
        assertThat(properties.allowsAssetUse("TEST_BYPASS")).isFalse();

        properties.setEnvironment("test");
        properties.setTestBypassDownloadEnabled(true);
        properties.validate();
        assertThat(properties.allowsAssetUse("TEST_BYPASS")).isTrue();
        assertThat(properties.acceptsNewAssetStatus("TEST_BYPASS")).isFalse();

        properties.setEnvironment("production");
        assertThatThrownBy(properties::validate).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("TEST_BYPASS asset downloads require test environment");
    }

    @Test
    void explicitNotReviewedConfigurationProvidesAnAuditablePolicy() {
        contextRunner.withPropertyValues("zhixiaohe.asset.safety-mode=NOT_REVIEWED")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    ContentSafetyPolicy policy = context.getBean(ContentSafetyPolicy.class);
                    assertThat(policy.mode()).isEqualTo("NOT_REVIEWED");
                    assertThat(policy.inspectUpload(41, 99, null))
                            .isEqualTo(new ContentSafetyPolicy.SafetyDecision(true, "NOT_REVIEWED"));
                });
    }

    @Test
    void isolatedOssGateRejectsAnyPrefixOutsideItsRandomTestNamespace() {
        OssAssetIsolationGateIntegrationTests.requireSafeGatePrefix(
                "zhixiaohe/test/gates/123e4567-e89b-12d3-a456-426614174000/");
        assertThatThrownBy(() -> OssAssetIsolationGateIntegrationTests.requireSafeGatePrefix(
                "zhixiaohe/test/users/41/assets/"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> OssAssetIsolationGateIntegrationTests.requireSafeGatePrefix(
                "zhixiaohe/production/gates/123e4567-e89b-12d3-a456-426614174000/"))
                .isInstanceOf(IllegalStateException.class);
    }

    private AssetIngestionProperties configured() {
        AssetIngestionProperties properties = new AssetIngestionProperties();
        properties.setEnabled(true);
        properties.setProviderAllowedHosts(List.of("*.aliyuncs.com"));
        properties.getOss().setEndpoint("https://oss.example.com");
        properties.getOss().setRegion("test-region");
        properties.getOss().setBucket("test-bucket");
        properties.getOss().setAccessKeyId("placeholder");
        properties.getOss().setAccessKeySecret("placeholder");
        return properties;
    }
}
