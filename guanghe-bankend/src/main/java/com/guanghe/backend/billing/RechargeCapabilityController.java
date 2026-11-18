package com.guanghe.backend.billing;

import com.guanghe.backend.common.api.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The recharge capability probe.
 *
 * <p>Intentionally not gated by {@code zhixiaohe.billing.enabled}, unlike the endpoints that create
 * and settle orders. Those need a database; this one does not. Keeping it always present is what
 * lets the payer page render "暂不可充值" with the specific missing prerequisite instead of a broken
 * or absent control.
 */
@RestController
public class RechargeCapabilityController {

    private final RechargeCapabilityService capability;

    public RechargeCapabilityController(RechargeCapabilityService capability) {
        this.capability = capability;
    }

    @GetMapping("/api/v1/billing/recharge-capability")
    public ApiResponse<BillingDtos.RechargeCapabilityResponse> capability() {
        return ApiResponse.success(capability.current());
    }
}
