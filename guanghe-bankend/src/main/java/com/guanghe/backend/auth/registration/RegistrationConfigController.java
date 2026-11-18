package com.guanghe.backend.auth.registration;

import com.guanghe.backend.common.api.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/public")
public class RegistrationConfigController {

    private final RegistrationProperties properties;

    public RegistrationConfigController(RegistrationProperties properties) {
        this.properties = properties;
    }

    @GetMapping("/registration-config")
    ApiResponse<RegistrationConfigResponse> registrationConfig() {
        return ApiResponse.success(new RegistrationConfigResponse(
                false,
                new AgreementReference(properties.termsVersion(), properties.termsUrl()),
                new AgreementReference(properties.privacyVersion(), properties.privacyUrl()),
                properties.smsCooldownSeconds()
        ));
    }

    record RegistrationConfigResponse(
            boolean registrationEnabled,
            AgreementReference terms,
            AgreementReference privacy,
            int smsCooldownSeconds
    ) {
    }

    record AgreementReference(String version, String url) {
    }
}
