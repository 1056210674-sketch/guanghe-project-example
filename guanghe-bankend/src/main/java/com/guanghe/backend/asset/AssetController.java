package com.guanghe.backend.asset;

import com.guanghe.backend.auth.security.AuthenticatedAccount;
import com.guanghe.backend.catalog.PageResponse;
import com.guanghe.backend.common.api.ApiResponse;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.http.MediaType;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.multipart.MultipartFile;

@Validated
@RestController
@ConditionalOnProperty(prefix = "zhixiaohe.asset", name = "enabled", havingValue = "true")
public class AssetController {
    private final AssetService service;
    private final AssetUploadService uploads;

    public AssetController(AssetService service, AssetUploadService uploads) {
        this.service = service;
        this.uploads = uploads;
    }

    @PostMapping(value = "/api/v1/assets/images", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<AssetDtos.UploadResponse>> uploadImage(
            @AuthenticationPrincipal AuthenticatedAccount account,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestParam("spaceId") @Positive long spaceId,
            @RequestParam(value = "name", required = false) String name,
            @RequestPart("file") MultipartFile file) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(uploads.upload(account, spaceId, idempotencyKey, name, file)));
    }

    @GetMapping("/api/v1/assets")
    public ApiResponse<PageResponse<AssetDtos.Summary>> list(
            @AuthenticationPrincipal AuthenticatedAccount account,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) @Positive Long spaceId,
            @RequestParam(required = false) @Positive Long productId,
            @RequestParam(required = false) String sourceType) {
        return ApiResponse.success(service.list(account, page, size, type, spaceId, productId, sourceType));
    }

    @GetMapping("/api/v1/assets/{assetId}")
    public ApiResponse<AssetDtos.Response> get(@AuthenticationPrincipal AuthenticatedAccount account,
                                               @PathVariable @Positive long assetId) {
        return ApiResponse.success(service.get(account, assetId));
    }

    @GetMapping("/api/v1/assets/{assetId}/download-url")
    public ApiResponse<AssetDtos.DownloadUrlResponse> downloadUrl(
            @AuthenticationPrincipal AuthenticatedAccount account,
            @PathVariable @Positive long assetId) {
        return ApiResponse.success(service.downloadUrl(account, assetId));
    }
}
