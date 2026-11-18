package com.guanghe.backend.media;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

interface MediaProviderAdapter {
    ResourcePage resources(String modelId, String resourceId, int page, int limit);
    SubmitResult submit(SubmitCommand command);
    OrderResult order(String modelId, String upstreamOrderId);
    JsonNode walletBalance();
    JsonNode decodeCallback(String response, String sign, String appKey);

    record ResourcePage(long total, List<JsonNode> items) { }
    record SubmitCommand(String modelId, String resourceId, String customerOrderId,
                         String upstreamPrice, String title, String bodyHtml, String remark) { }
    record SubmitResult(String upstreamOrderId, String upstreamOrderNo, JsonNode raw) { }
    record OrderResult(String upstreamStatus, JsonNode raw) { }
}
