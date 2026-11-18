package com.guanghe.backend.geo;

import com.guanghe.backend.auth.security.AuthenticatedAccount;
import com.guanghe.backend.common.api.ApiResponse;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@ConditionalOnProperty(prefix = "zhixiaohe.paid-task", name = "enabled", havingValue = "true")
public class GeoReportInterpretationController {
    private final GeoReportInterpretationService service;
    public GeoReportInterpretationController(GeoReportInterpretationService service) { this.service = service; }

    @PostMapping("/api/v1/geo/reports/{reportId}/reinterpretation")
    public ApiResponse<GeoTaskDtos.ReportInterpretation> reinterpretation(
            @AuthenticationPrincipal AuthenticatedAccount account, @PathVariable @Positive long reportId) {
        return ApiResponse.success(service.request(account, reportId));
    }
}
