package com.guanghe.backend.catalog.product;

import com.guanghe.backend.auth.security.AuthenticatedAccount;
import com.guanghe.backend.catalog.PageResponse;
import com.guanghe.backend.common.api.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@ConditionalOnProperty(prefix = "zhixiaohe.catalog", name = "enabled", havingValue = "true", matchIfMissing = true)
public class ProductController {

    private final ProductService service;
    private final ProductIdentityAssetService identityAssets;

    public ProductController(ProductService service,
                             org.springframework.beans.factory.ObjectProvider<ProductIdentityAssetService> identityAssets) {
        this.service = service;
        this.identityAssets = identityAssets.getIfAvailable();
    }

    @GetMapping("/api/v1/spaces/{spaceId}/products")
    public ApiResponse<PageResponse<ProductDtos.Summary>> list(
            @AuthenticationPrincipal AuthenticatedAccount account,
            @PathVariable @Positive long spaceId,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size
    ) {
        return ApiResponse.success(service.list(account, spaceId, page, size));
    }

    @PostMapping("/api/v1/spaces/{spaceId}/products")
    public ResponseEntity<ApiResponse<ProductDtos.Response>> create(
            @AuthenticationPrincipal AuthenticatedAccount account,
            @PathVariable @Positive long spaceId,
            @Valid @RequestBody ProductDtos.CreateRequest request
    ) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(service.create(account, spaceId, request)));
    }

    @GetMapping("/api/v1/products/{productId}")
    public ApiResponse<ProductDtos.Response> get(
            @AuthenticationPrincipal AuthenticatedAccount account,
            @PathVariable @Positive long productId
    ) {
        return ApiResponse.success(service.get(account, productId));
    }

    @PatchMapping("/api/v1/products/{productId}")
    public ApiResponse<ProductDtos.Response> update(
            @AuthenticationPrincipal AuthenticatedAccount account,
            @PathVariable @Positive long productId,
            @Valid @RequestBody ProductDtos.UpdateRequest request
    ) {
        return ApiResponse.success(service.update(account, productId, request));
    }

    @DeleteMapping("/api/v1/products/{productId}")
    public ApiResponse<Void> delete(
            @AuthenticationPrincipal AuthenticatedAccount account,
            @PathVariable @Positive long productId
    ) {
        service.delete(account, productId);
        return ApiResponse.success(null);
    }

    @PutMapping("/api/v1/products/{productId}/aliases")
    public ApiResponse<ProductDtos.Response> replaceAliases(
            @AuthenticationPrincipal AuthenticatedAccount account,
            @PathVariable @Positive long productId,
            @Valid @RequestBody ProductDtos.ReplaceAliasesRequest request
    ) {
        return ApiResponse.success(service.replaceAliases(account, productId, request));
    }

    @GetMapping("/api/v1/products/{productId}/identity-assets")
    public ApiResponse<ProductDtos.IdentityAssetsResponse> getIdentityAssets(
            @AuthenticationPrincipal AuthenticatedAccount account,
            @PathVariable @Positive long productId
    ) {
        return ApiResponse.success(requireIdentityAssets().get(account, productId));
    }

    @PutMapping("/api/v1/products/{productId}/identity-assets")
    public ApiResponse<ProductDtos.IdentityAssetsResponse> replaceIdentityAssets(
            @AuthenticationPrincipal AuthenticatedAccount account,
            @PathVariable @Positive long productId,
            @Valid @RequestBody ProductDtos.ReplaceIdentityAssetsRequest request
    ) {
        return ApiResponse.success(requireIdentityAssets().replace(account, productId, request));
    }

    private ProductIdentityAssetService requireIdentityAssets() {
        if (identityAssets == null) {
            throw new com.guanghe.backend.common.api.BusinessException(
                    com.guanghe.backend.common.api.ErrorCode.PROVIDER_NOT_CONFIGURED);
        }
        return identityAssets;
    }
}
