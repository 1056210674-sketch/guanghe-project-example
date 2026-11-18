package com.guanghe.backend.integration.internal.actuator;

import com.guanghe.backend.integration.internal.config.InternalCommunicationProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.info.Info;
import org.springframework.boot.actuate.info.InfoContributor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.info.GitProperties;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "zhixiaohe.internal.enabled", havingValue = "true", matchIfMissing = true)
public class JavaM1InfoContributor implements InfoContributor {

    private final InternalCommunicationProperties properties;
    private final ObjectProvider<GitProperties> gitPropertiesProvider;

    public JavaM1InfoContributor(
            InternalCommunicationProperties properties,
            ObjectProvider<GitProperties> gitPropertiesProvider
    ) {
        this.properties = properties;
        this.gitPropertiesProvider = gitPropertiesProvider;
    }

    @Override
    public void contribute(Info.Builder builder) {
        GitProperties gitProperties = gitPropertiesProvider.getIfAvailable();
        String gitCommit = gitProperties == null
                ? "UNAVAILABLE"
                : gitProperties.get("commit.id.full");
        builder.withDetail("gitCommit", gitCommit == null ? "UNAVAILABLE" : gitCommit);
        builder.withDetail("javaM1Ready", properties.isCodeCapabilitiesReady());
        builder.withDetail("codeCapabilitiesReady", properties.isCodeCapabilitiesReady());
        builder.withDetail("testServerDeployed", properties.isTestServerDeployed());
        builder.withDetail("m0GateStatus", properties.getM0GateStatus().name());
    }
}
