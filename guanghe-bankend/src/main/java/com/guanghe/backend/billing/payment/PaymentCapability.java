package com.guanghe.backend.billing.payment;

import java.util.List;

/**
 * Whether recharge is usable right now, and why not when it is not.
 *
 * <p>This is what the Browser API reports so that the Web client can render an honest
 * "capability unavailable" state instead of a disabled button pretending to be a finished feature.
 * A {@code false} value must never be presented to a user as "waiting for payment success".
 *
 * @param provider            provider code, e.g. {@code WECHAT_NATIVE}
 * @param configured          merchant credentials are present
 * @param payable             orders may be created and pushed to the provider
 * @param callbackVerifiable  inbound callbacks can be cryptographically verified
 * @param status              machine-readable status, surfaced verbatim to the client
 * @param missingRequirements names of absent prerequisites; never contains values
 */
public record PaymentCapability(String provider, boolean configured, boolean payable,
                                boolean callbackVerifiable, String status,
                                List<String> missingRequirements) {

    /** No provider is configured: the only honest state in this environment. */
    public static final String NOT_CONFIGURED = "PAYMENT_PROVIDER_NOT_CONFIGURED";
    /** Configured but the live integration is not enabled for this build. */
    public static final String NOT_IMPLEMENTED = "PAYMENT_PROVIDER_NOT_IMPLEMENTED";
    public static final String READY = "PAYMENT_PROVIDER_READY";

    public static PaymentCapability notConfigured(String provider, List<String> missing) {
        return new PaymentCapability(provider, false, false, false, NOT_CONFIGURED, missing);
    }
}
