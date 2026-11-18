package com.guanghe.backend.integration.internal.actuator;

import com.guanghe.backend.integration.internal.config.InternalCommunicationProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.info.Info;
import org.springframework.boot.info.GitProperties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class JavaM1InfoContributorTests {

    @Test
    void missingGeneratedGitPropertiesHasASafeExplicitFallback() {
        InternalCommunicationProperties properties = new InternalCommunicationProperties();
        @SuppressWarnings("unchecked")
        ObjectProvider<GitProperties> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);

        Info.Builder builder = new Info.Builder();
        new JavaM1InfoContributor(properties, provider).contribute(builder);

        assertThat(builder.build().getDetails())
                .containsEntry("gitCommit", "UNAVAILABLE")
                .containsEntry("javaM1Ready", false)
                .containsEntry("codeCapabilitiesReady", false)
                .containsEntry("testServerDeployed", false)
                .containsEntry("m0GateStatus", "NOT_RUN")
                .doesNotContainKey("python");
    }
}
