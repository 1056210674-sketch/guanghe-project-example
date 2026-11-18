package com.guanghe.backend.knowledge;

import com.guanghe.backend.auth.security.AuthenticatedAccount;
import com.guanghe.backend.catalog.PageResponse;
import com.guanghe.backend.common.api.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@Validated
@RestController
@ConditionalOnProperty(prefix = "zhixiaohe.agent-platform", name = "enabled", havingValue = "true", matchIfMissing = true)
public class KnowledgeController {
    private final KnowledgeService service;
    private AgentMemoryService memory;
    @org.springframework.beans.factory.annotation.Autowired
    void setMemory(AgentMemoryService value) { memory=value; }
    public KnowledgeController(KnowledgeService service) { this.service = service; }

    @GetMapping("/api/v1/memory/entries")
    public ApiResponse<List<MemoryDtos.Entry>> memories(@AuthenticationPrincipal AuthenticatedAccount account,
            @RequestParam String scope,@RequestParam(required=false) @Positive Long projectId,
            @RequestParam(defaultValue="false") boolean includeDeleted) {
        return ApiResponse.success(memory.list(account,scope,projectId,includeDeleted));
    }
    @PostMapping("/api/v1/memory/entries")
    public ResponseEntity<ApiResponse<MemoryDtos.Entry>> createMemory(@AuthenticationPrincipal AuthenticatedAccount account,
            @Valid @RequestBody MemoryDtos.Create request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(memory.create(account,request)));
    }
    @PutMapping("/api/v1/memory/entries/{id}")
    public ApiResponse<MemoryDtos.Entry> updateMemory(@AuthenticationPrincipal AuthenticatedAccount account,
            @PathVariable @Positive long id,@Valid @RequestBody MemoryDtos.Update request) {
        return ApiResponse.success(memory.update(account,id,request));
    }
    @DeleteMapping("/api/v1/memory/entries/{id}")
    public ApiResponse<Void> deleteMemory(@AuthenticationPrincipal AuthenticatedAccount account,
            @PathVariable @Positive long id,@RequestParam @Min(1) int version) {
        memory.delete(account,id,version);return ApiResponse.success(null);
    }

    @GetMapping("/api/v1/projects/{projectId}/knowledge-documents")
    public ApiResponse<PageResponse<KnowledgeDtos.DocumentSummary>> documents(@AuthenticationPrincipal AuthenticatedAccount account,
            @PathVariable @Positive long projectId, @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return ApiResponse.success(service.documents(account, projectId, page, size));
    }
    @PostMapping("/api/v1/projects/{projectId}/knowledge-documents")
    public ResponseEntity<ApiResponse<KnowledgeDtos.DocumentDetail>> createDocument(@AuthenticationPrincipal AuthenticatedAccount account,
            @PathVariable @Positive long projectId, @Valid @RequestBody KnowledgeDtos.CreateDocumentRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(service.createDocument(account, projectId, request)));
    }
    @GetMapping("/api/v1/projects/{projectId}/knowledge-documents/{documentId}")
    public ApiResponse<KnowledgeDtos.DocumentDetail> document(@AuthenticationPrincipal AuthenticatedAccount account,
            @PathVariable @Positive long projectId, @PathVariable @Positive long documentId) {
        return ApiResponse.success(service.document(account, projectId, documentId));
    }
    @PutMapping("/api/v1/projects/{projectId}/knowledge-documents/{documentId}")
    public ApiResponse<KnowledgeDtos.DocumentDetail> updateDocument(@AuthenticationPrincipal AuthenticatedAccount account,
            @PathVariable @Positive long projectId, @PathVariable @Positive long documentId,
            @Valid @RequestBody KnowledgeDtos.UpdateDocumentRequest request) {
        return ApiResponse.success(service.updateDocument(account, projectId, documentId, request));
    }
    @DeleteMapping("/api/v1/projects/{projectId}/knowledge-documents/{documentId}")
    public ApiResponse<Void> deleteDocument(@AuthenticationPrincipal AuthenticatedAccount account,
            @PathVariable @Positive long projectId, @PathVariable @Positive long documentId, @RequestParam @Min(1) int version) {
        service.deleteDocument(account, projectId, documentId, version);
        return ApiResponse.success(null);
    }
    @PostMapping(value = "/api/v1/projects/{projectId}/knowledge-documents/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<KnowledgeDtos.DocumentDetail>> upload(@AuthenticationPrincipal AuthenticatedAccount account,
            @PathVariable @Positive long projectId, @RequestParam(required = false) String name, @RequestPart("file") MultipartFile file) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(service.upload(account, projectId, name, file)));
    }
    @GetMapping("/api/v1/memory/preferences")
    public ApiResponse<List<KnowledgeDtos.PreferenceView>> preferences(@AuthenticationPrincipal AuthenticatedAccount account,
            @RequestParam(required = false) @Positive Long projectId) {
        return ApiResponse.success(service.preferences(account, projectId));
    }
    @PostMapping("/api/v1/memory/preferences")
    public ResponseEntity<ApiResponse<KnowledgeDtos.PreferenceView>> createPreference(@AuthenticationPrincipal AuthenticatedAccount account,
            @Valid @RequestBody KnowledgeDtos.CreatePreferenceRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(service.createPreference(account, request)));
    }
    @PutMapping("/api/v1/memory/preferences/{id}")
    public ApiResponse<KnowledgeDtos.PreferenceView> updatePreference(@AuthenticationPrincipal AuthenticatedAccount account,
            @PathVariable @Positive long id, @Valid @RequestBody KnowledgeDtos.UpdatePreferenceRequest request) {
        return ApiResponse.success(service.updatePreference(account, id, request));
    }
    @DeleteMapping("/api/v1/memory/preferences/{id}")
    public ApiResponse<Void> deletePreference(@AuthenticationPrincipal AuthenticatedAccount account,
            @PathVariable @Positive long id, @RequestParam @Min(1) int version) {
        service.deletePreference(account, id, version);
        return ApiResponse.success(null);
    }
    @GetMapping("/api/v1/projects/{projectId}/business-history")
    public ApiResponse<KnowledgeDtos.BusinessHistory> history(@AuthenticationPrincipal AuthenticatedAccount account,
            @PathVariable @Positive long projectId) {
        return ApiResponse.success(service.businessHistory(account, projectId));
    }
}
