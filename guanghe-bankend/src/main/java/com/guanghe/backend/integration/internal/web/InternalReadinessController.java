package com.guanghe.backend.integration.internal.web;

import com.guanghe.backend.integration.internal.config.InternalCommunicationProperties;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/v1/health")
@ConditionalOnProperty(name = "zhixiaohe.internal.enabled", havingValue = "true", matchIfMissing = true)
public class InternalReadinessController {

    private final InternalCommunicationProperties properties;

    public InternalReadinessController(InternalCommunicationProperties properties) {
        this.properties = properties;
    }

    @GetMapping("/ready")
    Map<String, Object> readiness() {
        return Map.of(
                "status", properties.isCodeCapabilitiesReady() ? "UP" : "NOT_READY",
                "javaM1Ready", properties.isCodeCapabilitiesReady(),
                "codeCapabilitiesReady", properties.isCodeCapabilitiesReady(),
                "testServerDeployed", properties.isTestServerDeployed(),
                "m0GateStatus", properties.getM0GateStatus().name()
        );
    }
}
