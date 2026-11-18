package com.guanghe.backend.media;

import com.guanghe.backend.auth.security.AuthenticatedAccount;
import com.guanghe.backend.catalog.PageResponse;
import com.guanghe.backend.common.api.ApiResponse;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The operator console's media routes.
 *
 * <p>Read-only in this version: every mapping here is a {@code GET}, and there is no route that
 * submits, replays a callback or triggers a catalogue sync. The browser does not present those
 * operations as available actions; resource updates use the scheduled synchronization path.
 *
 * <p>No {@code @ConditionalOnProperty}: see {@link MediaAdminService} for why the tenant-facing
 * {@code zhixiaohe.media.enabled} switch must not govern the back office. Authorisation is handled
 * twice over — {@code SecurityConfiguration} restricts {@code /api/v1/admin/**} to {@code ROLE_ADMIN},
 * and the service repeats the check before it reads another account's rows.
 */
@Validated
@RestController
@RequestMapping("/api/v1/admin/media")
public class MediaAdminController {
    private final MediaAdminService service;

    public MediaAdminController(MediaAdminService service) { this.service = service; }

    @GetMapping("/overview")
    public ApiResponse<MediaAdminDtos.MediaOverview> overview(
            @AuthenticationPrincipal AuthenticatedAccount account) {
        return ApiResponse.success(service.overview(account));
    }

    @GetMapping("/resources")
    public ApiResponse<PageResponse<MediaAdminDtos.AdminResource>> resources(
            @AuthenticationPrincipal AuthenticatedAccount account,
            @RequestParam(required = false) @Size(max = 200) String search,
            @RequestParam(required = false) @Size(max = 32) String modelId,
            @RequestParam(required = false) @Size(max = 64) String type,
            @RequestParam(required = false) @Size(max = 32) String status,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return ApiResponse.success(service.resources(account, search, modelId, type, status, page, size));
    }

    @GetMapping("/resources/{resourceId}")
    public ApiResponse<MediaAdminDtos.AdminResource> resource(
            @AuthenticationPrincipal AuthenticatedAccount account,
            @PathVariable @Positive long resourceId) {
        return ApiResponse.success(service.resource(account, resourceId));
    }

    @GetMapping("/submissions")
    public ApiResponse<PageResponse<MediaAdminDtos.AdminSubmissionSummary>> submissions(
            @AuthenticationPrincipal AuthenticatedAccount account,
            @RequestParam(required = false) @Size(max = 32) String status,
            @RequestParam(required = false) @Size(max = 16) String group,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return ApiResponse.success(service.submissions(account, status, group, page, size));
    }

    @GetMapping("/submissions/{submissionId}")
    public ApiResponse<MediaAdminDtos.AdminSubmissionDetail> submission(
            @AuthenticationPrincipal AuthenticatedAccount account,
            @PathVariable @Positive long submissionId) {
        return ApiResponse.success(service.submission(account, submissionId));
    }

    @GetMapping("/callbacks")
    public ApiResponse<PageResponse<MediaAdminDtos.AdminCallback>> callbacks(
            @AuthenticationPrincipal AuthenticatedAccount account,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return ApiResponse.success(service.callbacks(account, page, size));
    }

    @GetMapping("/callbacks/{callbackId}")
    public ApiResponse<MediaAdminDtos.AdminCallback> callback(
            @AuthenticationPrincipal AuthenticatedAccount account,
            @PathVariable @Positive long callbackId) {
        return ApiResponse.success(service.callback(account, callbackId));
    }

    @GetMapping("/sync-states")
    public ApiResponse<List<MediaAdminDtos.AdminSyncState>> syncStates(
            @AuthenticationPrincipal AuthenticatedAccount account) {
        return ApiResponse.success(service.syncStates(account));
    }
}
