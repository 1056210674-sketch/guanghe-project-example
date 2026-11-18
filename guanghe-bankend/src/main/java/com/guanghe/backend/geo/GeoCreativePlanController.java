package com.guanghe.backend.geo;

import com.guanghe.backend.auth.security.AuthenticatedAccount;
import com.guanghe.backend.common.api.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/v1/geo/reports")
@ConditionalOnExpression("'${zhixiaohe.paid-task.enabled:false}' == 'true'"
        + " and '${zhixiaohe.asset.enabled:false}' == 'true'")
public class GeoCreativePlanController {
    private final GeoCreativePlanService service;

    public GeoCreativePlanController(GeoCreativePlanService service) { this.service = service; }

    @PostMapping("/{reportId}/creative-plans")
    public ApiResponse<GeoCreativePlanDtos.Response> create(
            @AuthenticationPrincipal AuthenticatedAccount account,
            @PathVariable @Positive long reportId,
            @Valid @RequestBody GeoCreativePlanDtos.Request request) {
        return ApiResponse.success(service.plan(account, reportId, request));
    }
}
