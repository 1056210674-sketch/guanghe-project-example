package com.guanghe.backend.billing;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * M8 commercial control-plane settings.
 *
 * <p><strong>The recharge rate below is a non-production default.</strong> It exists so the control
 * plane can be exercised; the real rate must be frozen by the product owner before any live
 * payment Gate, exactly like the existing paid-task test prices. It is never presented as a
 * production price.
 */
@ConfigurationProperties("zhixiaohe.billing")
public class BillingProperties {
    private boolean enabled = true;

    /** How long a recharge order stays payable before it expires. */
    private Duration rechargeOrderTtl = Duration.ofMinutes(30);

    /** Smallest accepted recharge, in fen. Guards against 0 and negative amounts. */
    private long minRechargeAmountCents = 100;

    /** Largest accepted recharge, in fen. */
    private long maxRechargeAmountCents = 500_000;

    /**
     * Non-production conversion rate: points granted per whole yuan.
     *
     * <p>Applied once at order creation and snapshotted onto the order, so a later configuration
     * change can never rewrite the points an existing order promised.
     */
    private int pointsPerYuan = 100;

    /** How long a manual-adjustment confirmation token stays valid. */
    private Duration confirmationTokenTtl = Duration.ofMinutes(10);

    /** Upper bound on a single operator credit, as a blast-radius guard. */
    private int manualAdjustmentMaxPoints = 100_000;

    /** Description shown to the payer. Must not contain user or order identifiers. */
    private String rechargeDescription = "光禾AI智能体框架积分充值";

    /** Points for a given amount, floored to whole yuan. */
    public int pointsFor(long amountCents) {
        long wholeYuan = amountCents / 100;
        long points = wholeYuan * pointsPerYuan;
        return (int) Math.min(points, Integer.MAX_VALUE);
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Duration getRechargeOrderTtl() {
        return rechargeOrderTtl;
    }

    public void setRechargeOrderTtl(Duration rechargeOrderTtl) {
        this.rechargeOrderTtl = rechargeOrderTtl;
    }

    public long getMinRechargeAmountCents() {
        return minRechargeAmountCents;
    }

    public void setMinRechargeAmountCents(long minRechargeAmountCents) {
        this.minRechargeAmountCents = minRechargeAmountCents;
    }

    public long getMaxRechargeAmountCents() {
        return maxRechargeAmountCents;
    }

    public void setMaxRechargeAmountCents(long maxRechargeAmountCents) {
        this.maxRechargeAmountCents = maxRechargeAmountCents;
    }

    public int getPointsPerYuan() {
        return pointsPerYuan;
    }

    public void setPointsPerYuan(int pointsPerYuan) {
        this.pointsPerYuan = pointsPerYuan;
    }

    public Duration getConfirmationTokenTtl() {
        return confirmationTokenTtl;
    }

    public void setConfirmationTokenTtl(Duration confirmationTokenTtl) {
        this.confirmationTokenTtl = confirmationTokenTtl;
    }

    public int getManualAdjustmentMaxPoints() {
        return manualAdjustmentMaxPoints;
    }

    public void setManualAdjustmentMaxPoints(int manualAdjustmentMaxPoints) {
        this.manualAdjustmentMaxPoints = manualAdjustmentMaxPoints;
    }

    public String getRechargeDescription() {
        return rechargeDescription;
    }

    public void setRechargeDescription(String rechargeDescription) {
        this.rechargeDescription = rechargeDescription;
    }
}
