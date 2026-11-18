package com.guanghe.backend.article;

import com.guanghe.backend.auth.security.AuthenticatedAccount;
import com.guanghe.backend.common.api.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@Validated
@RestController
@ConditionalOnProperty(prefix="zhixiaohe.paid-task",name="enabled",havingValue="true")
public class ArticleFullController {
    private final ArticleFullService service;
    public ArticleFullController(ArticleFullService service) { this.service=service; }
    @GetMapping("/api/v1/conversations/{conversationId}/article-authorizations")
    public ApiResponse<List<ArticleFullDtos.AuthorizationView>> grants(@AuthenticationPrincipal AuthenticatedAccount account,
            @PathVariable @Positive long conversationId) { return ApiResponse.success(service.grants(account,conversationId)); }
    @PostMapping("/api/v1/conversations/{conversationId}/article-authorizations")
    public ApiResponse<ArticleFullDtos.AuthorizationView> grant(@AuthenticationPrincipal AuthenticatedAccount account,
            @PathVariable @Positive long conversationId,@Valid @RequestBody ArticleFullDtos.AuthorizationCreate request) {
        return ApiResponse.success(service.authorize(account,conversationId,request));
    }
    @DeleteMapping("/api/v1/conversations/{conversationId}/article-authorizations/{grantId}")
    public ApiResponse<Void> revoke(@AuthenticationPrincipal AuthenticatedAccount account,
            @PathVariable @Positive long conversationId,@PathVariable @Positive long grantId,
            @RequestParam @Min(1) int version) { service.revoke(account,conversationId,grantId,version); return ApiResponse.success(null); }
    @PostMapping("/api/v1/conversations/{conversationId}/article-full-preparations")
    public ApiResponse<ArticleFullDtos.PreparationView> start(@AuthenticationPrincipal AuthenticatedAccount account,
            @PathVariable @Positive long conversationId,@RequestHeader("Idempotency-Key") String key,
            @Valid @RequestBody ArticleFullDtos.StartRequest request) {
        return ApiResponse.success(service.start(account,conversationId,key,request));
    }
    @GetMapping("/api/v1/article-full-preparations/{preparationId}")
    public ApiResponse<ArticleFullDtos.PreparationView> get(@AuthenticationPrincipal AuthenticatedAccount account,
            @PathVariable @Positive long preparationId) { return ApiResponse.success(service.get(account,preparationId)); }
    @GetMapping("/api/v1/article-full-preparations/{preparationId}/preview")
    public ApiResponse<ArticleFullDtos.PreviewView> preview(@AuthenticationPrincipal AuthenticatedAccount account,
            @PathVariable @Positive long preparationId) { return ApiResponse.success(service.preview(account,preparationId)); }
}
