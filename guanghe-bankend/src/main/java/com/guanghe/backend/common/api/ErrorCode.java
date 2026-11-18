package com.guanghe.backend.common.api;

import org.springframework.http.HttpStatus;

public enum ErrorCode {
    VALIDATION_FAILED(HttpStatus.UNPROCESSABLE_ENTITY, "请求参数校验失败"),
    INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "账号或密码错误"),
    UNAUTHENTICATED(HttpStatus.UNAUTHORIZED, "登录状态无效或已过期"),
    AUTHENTICATION_DEPENDENCY_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "认证服务暂时不可用，请稍后重试"),
    ACCOUNT_DISABLED(HttpStatus.FORBIDDEN, "账号已停用"),
    FORBIDDEN(HttpStatus.FORBIDDEN, "无权执行此操作"),
    RESOURCE_NOT_FOUND(HttpStatus.NOT_FOUND, "资源不存在"),
    RESOURCE_NAME_CONFLICT(HttpStatus.CONFLICT, "名称已存在"),
    RESOURCE_ALIAS_CONFLICT(HttpStatus.CONFLICT, "别名已被占用"),
    RESOURCE_IN_USE(HttpStatus.CONFLICT, "资源正在使用中"),
    RESOURCE_VERSION_CONFLICT(HttpStatus.CONFLICT, "资源版本已变化，请刷新后重试"),
    KNOWLEDGE_SOURCE_CHANGED(HttpStatus.CONFLICT, "本篇文章引用的资料已发生变化，请复核资料后重新生成"),
    IDEMPOTENCY_KEY_REUSED(HttpStatus.CONFLICT, "幂等键已用于不同请求"),
    USERNAME_ALREADY_EXISTS(HttpStatus.CONFLICT, "账号已存在"),
    PHONE_ALREADY_EXISTS(HttpStatus.CONFLICT, "手机号已注册"),
    ACCOUNT_STATE_CONFLICT(HttpStatus.CONFLICT, "账号当前状态不允许此操作"),
    ACTIVATION_TOKEN_INVALID(HttpStatus.UNAUTHORIZED, "首次激活凭证无效或已失效"),
    AGREEMENT_VERSION_MISMATCH(HttpStatus.CONFLICT, "协议版本已更新，请重新确认"),
    PASSWORD_POLICY_VIOLATION(HttpStatus.UNPROCESSABLE_ENTITY, "新密码不符合安全要求"),
    SMS_CODE_INVALID(HttpStatus.UNPROCESSABLE_ENTITY, "验证码无效"),
    SMS_CODE_EXPIRED(HttpStatus.GONE, "验证码已过期"),
    RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS, "请求过于频繁"),
    CHAT_CONCURRENCY_LIMIT(HttpStatus.TOO_MANY_REQUESTS, "已有回复正在处理中，请等待完成或停止后再试"),
    CHAT_MINUTE_LIMIT(HttpStatus.TOO_MANY_REQUESTS, "提问太快了，请稍等一分钟后再试"),
    CHAT_DAILY_LIMIT(HttpStatus.TOO_MANY_REQUESTS, "今日免费问答额度已用完，明天可继续使用"),
    CHAT_VISION_DAILY_LIMIT(HttpStatus.TOO_MANY_REQUESTS, "今日免费看图额度已用完，可继续文字问答，明天可再次看图"),
    DRAFT_EXPIRED(HttpStatus.GONE, "任务草稿已过期"),
    TASK_CONFIRMATION_EXPIRED(HttpStatus.GONE, "任务确认已过期"),
    TASK_CONFIRMATION_MISMATCH(HttpStatus.CONFLICT, "任务输入或价格版本已变化"),
    INSUFFICIENT_POINTS(HttpStatus.CONFLICT, "可用积分不足"),
    TASK_ALREADY_CLAIMED(HttpStatus.CONFLICT, "任务已被其他执行代次领取"),
    TASK_STATE_CONFLICT(HttpStatus.CONFLICT, "当前任务状态不允许此操作"),
    TASK_NOT_CANCELLABLE(HttpStatus.CONFLICT, "已确认付费任务不允许取消"),
    ASSET_NOT_READY(HttpStatus.CONFLICT, "资产尚未就绪"),
    ASSET_IN_USE(HttpStatus.CONFLICT, "资产正在被引用"),
    PRODUCT_IDENTITY_ASSET_REQUIRED(HttpStatus.UNPROCESSABLE_ENTITY, "商品缺少可用的主图或白底图"),
    REFERENCE_ASSET_NOT_READY(HttpStatus.CONFLICT, "参考图片仍在处理或已不可用"),
    REFERENCE_ASSET_NOT_OWNED(HttpStatus.FORBIDDEN, "参考图片不属于当前用户或无权读取"),
    REFERENCE_IMAGE_MODE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "参考图创作能力暂不可用"),
    IMAGE_TO_VIDEO_MODE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "图生视频能力暂不可用"),
    CREATIVE_SOURCE_REQUIRED(HttpStatus.UNPROCESSABLE_ENTITY, "请选择商品，或上传本次要参考的图片"),
    UPLOAD_SESSION_EXPIRED(HttpStatus.GONE, "上传会话已过期"),
    MEDIA_QUOTE_CHANGED(HttpStatus.CONFLICT, "媒体报价已变化"),
    MEDIA_IMAGE_UNSUPPORTED(HttpStatus.UNPROCESSABLE_ENTITY, "当前媒体尚未核验图片发布规则，或图片不满足已核验限制；稿件与图片已保留，尚未投稿"),
    MEDIA_LINK_UNSUPPORTED(HttpStatus.UNPROCESSABLE_ENTITY, "所选媒体不支持文章中的外链"),
    MEDIA_SUBMISSION_UNKNOWN(HttpStatus.SERVICE_UNAVAILABLE, "媒体上游受理结果正在核对"),
    PROVIDER_NOT_CONFIGURED(HttpStatus.SERVICE_UNAVAILABLE, "服务能力尚未配置"),
    WORKFLOW_EXECUTION_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "工作流执行能力尚未配置"),
    WORKFLOW_CONTEXT_UNAVAILABLE(HttpStatus.CONFLICT, "工作流授权上下文不可用，请重新生成计划"),
    PROVIDER_RATE_LIMITED(HttpStatus.SERVICE_UNAVAILABLE, "上游服务限流"),
    PROVIDER_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "上游服务暂时不可用"),
    PROVIDER_RESULT_UNKNOWN(HttpStatus.SERVICE_UNAVAILABLE, "上游执行结果正在核对"),
    CONTENT_REJECTED(HttpStatus.UNPROCESSABLE_ENTITY, "内容未通过安全检查"),
    INTERNAL_SIGNATURE_INVALID(HttpStatus.UNAUTHORIZED, "内部请求签名无效"),
    INTERNAL_REPLAY_DETECTED(HttpStatus.UNAUTHORIZED, "内部请求已被处理"),
    INTERNAL_AUTHENTICATION_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "内部认证服务暂时不可用"),
    SSE_REPLAY_UNAVAILABLE(HttpStatus.CONFLICT, "事件已超出续传窗口"),
    // --- M8 commercial: recharge ---
    PAYMENT_PROVIDER_NOT_CONFIGURED(HttpStatus.SERVICE_UNAVAILABLE, "支付能力尚未配置，暂时无法充值"),
    PAYMENT_PROVIDER_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "支付渠道暂时不可用，请稍后重试"),
    PAYMENT_CALLBACK_INVALID(HttpStatus.UNAUTHORIZED, "支付回调校验失败"),
    PAYMENT_AMOUNT_MISMATCH(HttpStatus.CONFLICT, "支付金额与订单不一致"),
    RECHARGE_ORDER_STATE_CONFLICT(HttpStatus.CONFLICT, "当前充值订单状态不允许此操作"),
    RECHARGE_ORDER_EXPIRED(HttpStatus.GONE, "充值订单已过期"),
    RECHARGE_AMOUNT_OUT_OF_RANGE(HttpStatus.UNPROCESSABLE_ENTITY, "充值金额超出允许范围"),
    // --- M8 commercial: manual adjustment ---
    CONFIRMATION_TOKEN_INVALID(HttpStatus.CONFLICT, "二次确认凭证无效或已失效"),
    ADJUSTMENT_ALREADY_APPLIED(HttpStatus.CONFLICT, "该人工调账已执行"),
    // --- M8 commercial: invoice ---
    INVOICE_DELIVERY_NOT_CONFIGURED(HttpStatus.SERVICE_UNAVAILABLE, "发票投递能力尚未配置"),
    INVOICE_DELIVERY_NOT_IMPLEMENTED(HttpStatus.SERVICE_UNAVAILABLE, "发票投递能力尚未启用"),
    INVOICE_STATE_CONFLICT(HttpStatus.CONFLICT, "当前发票状态不允许此操作"),
    INVOICE_REQUEST_INVALID(HttpStatus.UNPROCESSABLE_ENTITY, "发票申请信息校验失败"),
    INVOICE_APPLICATION_NOT_FOUND(HttpStatus.NOT_FOUND, "发票申请不存在"),
    // --- M8 commercial: mail transport (invoice delivery and mail notification share it) ---
    MAIL_DELIVERY_NOT_CONFIGURED(HttpStatus.SERVICE_UNAVAILABLE, "邮件投递能力尚未配置"),
    MAIL_DELIVERY_NOT_IMPLEMENTED(HttpStatus.SERVICE_UNAVAILABLE, "邮件投递能力尚未启用"),
    // --- M8 commercial: notification, feedback and bookkeeping ---
    NOTIFICATION_NOT_FOUND(HttpStatus.NOT_FOUND, "通知不存在"),
    FEEDBACK_NOT_FOUND(HttpStatus.NOT_FOUND, "反馈不存在"),
    FEEDBACK_STATE_CONFLICT(HttpStatus.CONFLICT, "当前反馈状态不允许此操作"),
    // --- M8 commercial: pricing and rules ---
    PRICING_VERSION_NOT_FOUND(HttpStatus.NOT_FOUND, "定价版本不存在"),
    PRICING_VERSION_NOT_CONFIGURED(HttpStatus.SERVICE_UNAVAILABLE, "尚未发布正式定价版本"),
    // --- M8 commercial: pricing ---
    PRICING_VERSION_CONFLICT(HttpStatus.CONFLICT, "定价版本状态不允许此操作"),
    PRICING_VERSION_IN_USE(HttpStatus.CONFLICT, "定价版本已被任务引用，不能停用"),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "系统暂时不可用，请稍后重试");

    private final HttpStatus status;
    private final String defaultMessage;

    ErrorCode(HttpStatus status, String defaultMessage) {
        this.status = status;
        this.defaultMessage = defaultMessage;
    }

    public HttpStatus status() {
        return status;
    }

    public String defaultMessage() {
        return defaultMessage;
    }
}
