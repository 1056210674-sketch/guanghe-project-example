package com.guanghe.backend.asset;

import com.aliyun.oss.OSS;
import com.aliyun.oss.ClientBuilderConfiguration;
import com.aliyun.oss.OSSClientBuilder;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.actuate.info.Info;
import org.springframework.boot.actuate.info.InfoContributor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AssetIngestionProperties.class)
public class AssetIngestionConfiguration {
    public AssetIngestionConfiguration(AssetIngestionProperties properties) {
        properties.validate();
    }

    @Bean(destroyMethod = "shutdown")
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "zhixiaohe.asset", name = "enabled", havingValue = "true")
    OSS assetOssClient(AssetIngestionProperties properties) {
        ClientBuilderConfiguration client = new ClientBuilderConfiguration();
        client.setConnectionTimeout(Math.toIntExact(properties.getConnectTimeout().toMillis()));
        client.setSocketTimeout(Math.toIntExact(properties.getReadTimeout().toMillis()));
        client.setRequestTimeoutEnabled(true);
        client.setRequestTimeout(Math.toIntExact(properties.getTotalTimeout().toMillis()));
        return new OSSClientBuilder().build(properties.getOss().getEndpoint(),
                properties.getOss().getAccessKeyId(), properties.getOss().getAccessKeySecret(), client);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "zhixiaohe.asset", name = "enabled", havingValue = "true")
    ObjectStorage assetObjectStorage(OSS client, AssetIngestionProperties properties) {
        return new AlibabaOssObjectStorage(client, properties);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "zhixiaohe.asset", name = "enabled", havingValue = "true")
    ProviderArtifactDownloader providerArtifactDownloader(AssetIngestionProperties properties) {
        return new ProviderArtifactDownloader(properties);
    }

    @Bean
    @ConditionalOnProperty(prefix = "zhixiaohe.asset", name = "safety-mode", havingValue = "TEST_BYPASS")
    ContentSafetyPolicy testBypassContentSafetyPolicy() {
        return new TestBypassContentSafetyPolicy();
    }

    @Bean
    @ConditionalOnProperty(prefix = "zhixiaohe.asset", name = "safety-mode", havingValue = "NOT_REVIEWED")
    ContentSafetyPolicy notReviewedContentSafetyPolicy() {
        return new NotReviewedContentSafetyPolicy();
    }

    @Bean("assetIngestionHealthIndicator")
    @ConditionalOnProperty(prefix = "zhixiaohe.asset", name = "enabled", havingValue = "true")
    HealthIndicator assetIngestionHealthIndicator(AssetIngestionProperties properties,
                                                   ContentSafetyPolicy safetyPolicy) {
        return () -> Health.up()
                .withDetail("storage", "OSS_CONFIGURED")
                .withDetail("contentSafetyMode", safetyPolicy.mode())
                .withDetail("workerEnabled", properties.isWorkerEnabled())
                .build();
    }

    @Bean
    @ConditionalOnProperty(prefix = "zhixiaohe.asset", name = "enabled", havingValue = "true")
    InfoContributor assetIngestionInfoContributor(AssetIngestionProperties properties,
                                                   ContentSafetyPolicy safetyPolicy) {
        return (Info.Builder builder) -> builder.withDetail("assetIngestion", java.util.Map.of(
                "enabled", true,
                "workerEnabled", properties.isWorkerEnabled(),
                "contentSafetyMode", safetyPolicy.mode(),
                "permanentStorage", "OSS"
        ));
    }
}
