package com.guanghe.backend.geo;

import com.guanghe.backend.auth.security.AuthenticatedAccount;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@ConditionalOnProperty(prefix = "zhixiaohe.paid-task", name = "enabled", havingValue = "true")
public class GeoReportExportController {
    private final GeoReportExportService service;
    public GeoReportExportController(GeoReportExportService service) { this.service = service; }

    @GetMapping("/api/v1/geo/reports/{reportId}/export")
    public ResponseEntity<byte[]> export(@AuthenticationPrincipal AuthenticatedAccount account,
                                         @PathVariable @Positive long reportId,
                                         @RequestParam(defaultValue = "md") String format) {
        GeoReportExportService.Export result = service.export(account, reportId, format);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + result.filename() + "\"")
                .header("X-Content-Type-Options", "nosniff")
                .contentType(MediaType.parseMediaType(result.contentType())).body(result.bytes());
    }
}
