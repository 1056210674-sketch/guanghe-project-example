package com.guanghe.backend.article;

import com.guanghe.backend.auth.security.AuthenticatedAccount;
import com.guanghe.backend.common.api.ApiResponse;
import com.guanghe.backend.media.MediaDtos;
import com.guanghe.backend.media.MediaSubmissionService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@ConditionalOnProperty(prefix = "zhixiaohe.media", name = "enabled", havingValue = "true")
public class ArticlePreparationMediaController {
    private final MediaSubmissionService service;
    public ArticlePreparationMediaController(MediaSubmissionService service) { this.service = service; }

    @GetMapping("/api/v1/articles/{articleId}/preparation-media-recommendations")
    public ApiResponse<MediaDtos.RecommendationSet> recommendations(
            @AuthenticationPrincipal AuthenticatedAccount account,
            @PathVariable @Positive long articleId,
            @RequestParam(defaultValue = "5") @Min(1) @Max(5) int limit) {
        return ApiResponse.success(service.preparationRecommendations(account, articleId, limit));
    }
}
