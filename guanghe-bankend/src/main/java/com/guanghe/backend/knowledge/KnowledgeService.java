package com.guanghe.backend.knowledge;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.guanghe.backend.agentplatform.AgentPlatformMapper;
import com.guanghe.backend.asset.AssetIngestionException;
import com.guanghe.backend.asset.AssetIngestionProperties;
import com.guanghe.backend.asset.ObjectStorage;
import com.guanghe.backend.auth.security.AuthenticatedAccount;
import com.guanghe.backend.catalog.CatalogAccess;
import com.guanghe.backend.catalog.PageResponse;
import com.guanghe.backend.common.api.BusinessException;
import com.guanghe.backend.common.api.ErrorCode;
import com.guanghe.backend.common.id.SnowflakeIdGenerator;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.HashMap;
import java.util.Objects;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.scheduling.annotation.Scheduled;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Service
@ConditionalOnProperty(prefix = "zhixiaohe.agent-platform", name = "enabled", havingValue = "true", matchIfMissing = true)
public class KnowledgeService {
    private static final TypeReference<List<KnowledgeDtos.Paragraph>> PARAGRAPHS = new TypeReference<>() { };
    private static final Logger LOG = LoggerFactory.getLogger(KnowledgeService.class);
    private final KnowledgeMapper mapper;
    private final AgentPlatformMapper projects;
    private final SnowflakeIdGenerator ids;
    private final ObjectMapper json;
    private final KnowledgeParser parser;
    private final KnowledgeProperties properties;
    private final ObjectProvider<ObjectStorage> storageProvider;
    private final AssetIngestionProperties assetProperties;
    private final TransactionTemplate transactions;
    private AgentMemoryService memoryService;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    void setMemoryService(AgentMemoryService value) { memoryService=value; }

    public KnowledgeService(KnowledgeMapper mapper, AgentPlatformMapper projects, SnowflakeIdGenerator ids,
            ObjectMapper json, KnowledgeParser parser, KnowledgeProperties properties,
            ObjectProvider<ObjectStorage> storageProvider, AssetIngestionProperties assetProperties,
            PlatformTransactionManager transactionManager) {
        properties.validate();
        this.mapper = mapper;
        this.projects = projects;
        this.ids = ids;
        this.json = json;
        this.parser = parser;
        this.properties = properties;
        this.storageProvider = storageProvider;
        this.assetProperties = assetProperties;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    @Transactional(readOnly = true)
    public PageResponse<KnowledgeDtos.DocumentSummary> documents(AuthenticatedAccount account, long projectId, int page, int size) {
        long userId = CatalogAccess.requireUser(account);
        requireProject(userId, projectId, false);
        if (page < 1 || size < 1 || size > 100) throw invalid("KNOWLEDGE_PAGE_INVALID");
        return PageResponse.of(mapper.documentPage(userId, projectId, (long) (page - 1) * size, size).stream()
                .map(this::summary).toList(), page, size, mapper.documentCount(userId, projectId));
    }

    @Transactional(readOnly = true)
    public KnowledgeDtos.DocumentDetail document(AuthenticatedAccount account, long projectId, long id) {
        long userId = CatalogAccess.requireUser(account);
        requireProject(userId, projectId, false);
        return detail(ownedDocument(userId, projectId, id));
    }

    @Transactional
    public KnowledgeDtos.DocumentDetail createDocument(AuthenticatedAccount account, long projectId,
            KnowledgeDtos.CreateDocumentRequest request) {
        long userId = CatalogAccess.requireUser(account);
        requireProject(userId, projectId, true);
        if (request == null) throw invalid("KNOWLEDGE_TEXT_INVALID");
        requireDocumentCapacity(userId, projectId);
        String name = name(request.name());
        String url = sourceUrl(request.sourceUrl());
        var parsed = parser.pasted(request.text());
        OffsetDateTime now = now();
        long id = ids.nextId();
        String source = url == null ? "PASTED_TEXT" : "LINK_PASTED_TEXT";
        var record = new KnowledgeRecords.Document(id, userId, projectId, name, source, "READY", 1, null, now, now, null);
        insertDocument(record);
        insertVersion(version(record, parsed, url, null));
        return detail(projection(record, parsed, url));
    }

    @Transactional
    public KnowledgeDtos.DocumentDetail updateDocument(AuthenticatedAccount account, long projectId, long id,
            KnowledgeDtos.UpdateDocumentRequest request) {
        long userId = CatalogAccess.requireUser(account);
        requireProject(userId, projectId, true);
        var existing = mapper.lockDocument(userId, projectId, id).orElseThrow(this::notFound);
        if (request == null || request.version() == null || request.version() != existing.currentVersion()) throw conflict();
        if (!List.of("READY", "FAILED").contains(existing.status()) || existing.currentVersion() >= 100) {
            throw new BusinessException(ErrorCode.RESOURCE_IN_USE);
        }
        var parsed = parser.pasted(request.text());
        String url = sourceUrl(request.sourceUrl());
        if ("FAILED".equals(existing.status()) && List.of("TXT", "MARKDOWN", "PDF").contains(existing.sourceType())) {
            // A failed/interrupted PUT may have left its deterministic original object before DB completion.
            storage().delete(objectKey(userId, projectId, id, existing.sourceType()));
        }
        var record = new KnowledgeRecords.Document(id, userId, projectId, name(request.name()),
                url == null ? "PASTED_TEXT" : "LINK_PASTED_TEXT", "READY", existing.currentVersion() + 1,
                null, existing.createdAt(), now(), null);
        insertVersion(version(record, parsed, url, null));
        if (mapper.updateDocument(record, existing.currentVersion()) != 1) throw conflict();
        return detail(projection(record, parsed, url));
    }

    @Transactional
    public void deleteDocument(AuthenticatedAccount account, long projectId, long id, int version) {
        long userId = CatalogAccess.requireUser(account);
        // Archived projects are readable, and users can still remove their private data.
        requireProject(userId, projectId, false);
        var current = mapper.lockDocument(userId, projectId, id).orElseThrow(this::notFound);
        if (current.currentVersion() != version) throw conflict();
        if ("UPLOADING".equals(current.status()) || "PARSING".equals(current.status())) {
            throw new BusinessException(ErrorCode.RESOURCE_IN_USE);
        }
        List<String> keys = new ArrayList<>(mapper.documentObjectKeys(userId, projectId, id));
        if (List.of("TXT", "MARKDOWN", "PDF").contains(current.sourceType())) {
            String originalKey = objectKey(userId, projectId, id, current.sourceType());
            if (!keys.contains(originalKey)) keys.add(originalKey);
        }
        if (!keys.isEmpty()) {
            ObjectStorage storage = storage();
            // Do not claim successful deletion if original object cleanup is unavailable.
            for (String key : keys) storage.delete(key);
        }
        OffsetDateTime now = now();
        var deleted = new KnowledgeRecords.Document(id, userId, projectId, current.name(), current.sourceType(),
                "DELETED", current.currentVersion() + 1, null, current.createdAt(), now, now);
        mapper.redactDocumentVersions(userId, projectId, id);
        if (mapper.updateDocument(deleted, version) != 1) throw conflict();
    }

    /** Copy/store/parse outside database transactions. State transitions are durable and source-bound. */
    public KnowledgeDtos.DocumentDetail upload(AuthenticatedAccount account, long projectId, String requestedName, MultipartFile file) {
        long userId = CatalogAccess.requireUser(account);
        if (file == null || file.isEmpty() || file.getSize() > properties.getMaxFileBytes()) throw invalid("KNOWLEDGE_FILE_TOO_LARGE");
        String fileName = name(file.getOriginalFilename());
        String source = parser.sourceType(fileName);
        String displayedName = name(requestedName == null || requestedName.isBlank() ? fileName : requestedName);
        ObjectStorage storage = storage();
        long id = ids.nextId();
        OffsetDateTime started = now();
        var parsing = new KnowledgeRecords.Document(id, userId, projectId, displayedName, source,
                "PARSING", 1, null, started, started, null);
        transactions.executeWithoutResult(ignored -> {
            requireProject(userId, projectId, true);
            requireDocumentCapacity(userId, projectId);
            insertDocument(parsing);
        });
        Path directory = null;
        ObjectStorage.StoredObject stored = null;
        try {
            directory = Files.createTempDirectory("zhixiaohe-knowledge-");
            Path target = directory.resolve("original.bin");
            copy(file, target);
            KnowledgeParser.Parsed parsed = parser.file(target, fileName, file.getContentType());
            // Existing ObjectStorage uses the same configured bucket, credentials and verified digest.
            String objectKey = objectKey(userId, projectId, id, source);
            stored = storage.storeOrConfirm(objectKey, target, parsed.contentType(), parsed.sizeBytes(), parsed.sha256());
            ObjectStorage.StoredObject object = stored;
            KnowledgeRecords.Document ready = transactions.execute(ignored -> {
                requireProject(userId, projectId, true);
                var current = mapper.lockDocument(userId, projectId, id).orElseThrow(this::notFound);
                if (!"PARSING".equals(current.status()) || current.currentVersion() != 1) throw conflict();
                var record = new KnowledgeRecords.Document(id, userId, projectId, displayedName, source,
                        "READY", 1, null, started, now(), null);
                insertVersion(version(record, parsed, null, object.objectKey()));
                if (mapper.updateDocument(record, 1) != 1) throw conflict();
                return record;
            });
            return detail(projection(Objects.requireNonNull(ready), parsed, null));
        } catch (BusinessException exception) {
            var failed = failUpload(parsing, failureCode(exception));
            cleanupObject(storage, stored);
            return failed;
        } catch (IOException | AssetIngestionException exception) {
            var failed = failUpload(parsing, "KNOWLEDGE_UPLOAD_UNAVAILABLE");
            cleanupObject(storage, stored);
            if (stored == null && exception instanceof AssetIngestionException ingestion && ingestion.objectCreatedByCaller()) {
                storage.delete(objectKey(userId, projectId, id, source));
            }
            return failed;
        } catch (RuntimeException exception) {
            // No provider body, private content, object key or local file path enters errors.
            var failed = failUpload(parsing, "KNOWLEDGE_PARSE_FAILED");
            cleanupObject(storage, stored);
            return failed;
        } finally {
            if (directory != null) {
                try { Files.deleteIfExists(directory.resolve("original.bin")); Files.deleteIfExists(directory); }
                catch (IOException ignored) { /* Local ephemeral cleanup never reopens parsing or executes content. */ }
            }
        }
    }

    private KnowledgeDtos.DocumentDetail failUpload(KnowledgeRecords.Document original, String code) {
        var failed = new KnowledgeRecords.Document(original.id(), original.userId(), original.projectId(), original.name(),
                original.sourceType(), "FAILED", 1, code, original.createdAt(), now(), null);
        transactions.executeWithoutResult(ignored -> {
            var locked = mapper.lockDocument(original.userId(), original.projectId(), original.id()).orElseThrow(this::notFound);
            if (!"PARSING".equals(locked.status()) || mapper.updateDocument(failed, 1) != 1) throw conflict();
        });
        return detail(new KnowledgeRecords.DocumentProjection(failed.id(), failed.userId(), failed.projectId(), failed.name(),
                failed.sourceType(), failed.status(), 1, failed.failureCode(), failed.createdAt(), failed.updatedAt(), "",
                "text/plain", 0, null, "[]", null));
    }

    /** Interrupted uploads are explicitly failed, never silently retried or left parsing forever. */
    @Scheduled(fixedDelayString = "${zhixiaohe.knowledge.upload-recovery-poll-delay-ms:60000}")
    public void recoverStaleUploads() {
        if (!properties.isUploadRecoveryEnabled()) return;
        OffsetDateTime cutoff = now().minusMinutes(5);
        for (var candidate : mapper.staleUploads(cutoff)) {
            try {
                transactions.executeWithoutResult(ignored -> {
                    var current = mapper.lockDocument(candidate.userId(), candidate.projectId(), candidate.id()).orElse(null);
                    if (current == null || !List.of("UPLOADING", "PARSING").contains(current.status())
                            || !current.updatedAt().isBefore(cutoff)) return;
                    var failed = new KnowledgeRecords.Document(current.id(), current.userId(), current.projectId(), current.name(),
                            current.sourceType(), "FAILED", current.currentVersion(), "KNOWLEDGE_UPLOAD_INTERRUPTED",
                            current.createdAt(), now(), null);
                    if (mapper.updateDocument(failed, current.currentVersion()) != 1) throw conflict();
                });
            } catch (RuntimeException exception) {
                LOG.warn("knowledge_upload_recovery_unavailable");
            }
        }
    }

    @Transactional(readOnly = true)
    public List<KnowledgeDtos.PreferenceView> preferences(AuthenticatedAccount account, Long projectId) {
        long userId = CatalogAccess.requireUser(account);
        if (projectId != null) requireProject(userId, projectId, false);
        return mapper.preferences(userId, projectId).stream().map(this::preference).toList();
    }

    @Transactional
    public KnowledgeDtos.PreferenceView createPreference(AuthenticatedAccount account, KnowledgeDtos.CreatePreferenceRequest request) {
        long userId = CatalogAccess.requireUser(account);
        if (request == null) throw invalid("PREFERENCE_INVALID");
        Long projectId = id(request.projectId());
        requirePreferenceWrite(userId, projectId);
        return preference(insertPreference(userId, projectId, preferenceContent(request.content()), "USER_INPUT", null));
    }

    /** Only call for an explicit user's remember/future preference request; never for extracted document instructions. */
    @Transactional
    public KnowledgeDtos.PreferenceView rememberExpressedPreference(long userId, Long projectId, long sourceMessageId, String exactUserTextFragment) {
        requirePreferenceWrite(userId, projectId);
        String content = preferenceContent(exactUserTextFragment);
        String original = mapper.originalUserText(userId, sourceMessageId).orElseThrow(this::notFound);
        if (!original.contains(content) || !explicitRemember(original)) throw invalid("PREFERENCE_EXPLICIT_SOURCE_REQUIRED");
        var replay = mapper.preferenceFromMessage(userId, sourceMessageId, hash(content)).orElse(null);
        if (replay != null) {
            // A deleted memory never revives when the same old source message is replayed.
            if (replay.deletedAt() != null) return null;
            if (!Objects.equals(replay.projectId(), projectId)) throw conflict();
            return preference(replay);
        }
        return preference(insertPreference(userId, projectId, content, "USER_MESSAGE", sourceMessageId));
    }

    @Transactional
    public KnowledgeDtos.PreferenceView updatePreference(AuthenticatedAccount account, long id, KnowledgeDtos.UpdatePreferenceRequest request) {
        long userId = CatalogAccess.requireUser(account);
        lockPreferenceAccount(userId);
        var current = mapper.lockPreference(userId, id).orElseThrow(this::notFound);
        if (current.projectId() != null) requireProject(userId, current.projectId(), true);
        if (request == null || request.version() == null || current.version() != request.version()) throw conflict();
        String content = preferenceContent(request.content());
        OffsetDateTime updatedAt = now();
        try {
            if (mapper.updatePreference(userId, id, request.version(), content, hash(content), updatedAt) != 1) throw conflict();
        } catch (DuplicateKeyException exception) { throw new BusinessException(ErrorCode.RESOURCE_NAME_CONFLICT); }
        return preference(new KnowledgeRecords.Preference(id, userId, current.projectId(), content, hash(content),
                "USER_INPUT", null, current.version() + 1, current.createdAt(), updatedAt, null));
    }

    @Transactional
    public void deletePreference(AuthenticatedAccount account, long id, int version) {
        long userId = CatalogAccess.requireUser(account);
        lockPreferenceAccount(userId);
        var current = mapper.lockPreference(userId, id).orElseThrow(this::notFound);
        if (current.version() != version) throw conflict();
        if (mapper.deletePreference(userId, id, version, now()) != 1) throw conflict();
    }

    @Transactional(readOnly = true)
    public KnowledgeDtos.BusinessHistory businessHistory(AuthenticatedAccount account, long projectId) {
        long userId = CatalogAccess.requireUser(account);
        requireProject(userId, projectId, false);
        return history(userId, projectId);
    }

    /** Fresh, scoped retrieval. No in-memory index/cache can make a deleted source reappear. */
    @Transactional(readOnly = true)
    public KnowledgeDtos.KnowledgeContext context(long userId, Long projectId, String query) {
        var memory=memoryService==null?null:memoryService.context(userId,projectId);
        // Legacy free-text preferences remain editable in the library, never silently promoted
        // to account-wide facts. Only explicitly re-saved scoped memory is authoritative.
        List<KnowledgeDtos.PreferenceView> preferences = new ArrayList<>();
        if (projectId != null) {
            requireProject(userId, projectId, false);
        }
        int remaining = properties.getMaxContextCharacters();
        List<KnowledgeDtos.PreferenceView> selectedPreferences = new ArrayList<>();
        for (var item : preferences) {
            if (selectedPreferences.size() >= 100 || item.content().length() > remaining) continue;
            selectedPreferences.add(item);
            remaining -= item.content().length();
        }
        if (projectId == null) return new KnowledgeDtos.KnowledgeContext(List.of(), selectedPreferences, null,
                selectedPreferences.size() != preferences.size(), 0, properties.getMaxContextCharacters() - remaining,memory);
        int sourceCount = Math.toIntExact(mapper.readyDocumentCount(userId, projectId));
        List<KnowledgeRecords.DocumentProjection> sources = mapper.contextDocuments(userId, projectId, searchTerms(query),
                properties.getMaxContextDocuments());
        List<String> terms = searchTerms(query);
        List<KnowledgeRecords.DocumentProjection> ordered = new ArrayList<>(sources);
        ordered.sort(Comparator.comparingInt((KnowledgeRecords.DocumentProjection value) -> score(value, terms)).reversed()
                .thenComparing(KnowledgeRecords.DocumentProjection::updatedAt, Comparator.reverseOrder())
                .thenComparing(Comparator.comparingLong(KnowledgeRecords.DocumentProjection::id).reversed()));
        List<KnowledgeDtos.ContextDocument> documents = new ArrayList<>();
        boolean truncated = selectedPreferences.size() != preferences.size() || sourceCount > sources.size();
        for (var source : ordered) {
            if(mapper.legacyConversationDocument(userId,Long.toString(source.id()))) continue;
            if (documents.size() >= properties.getMaxContextDocuments() || remaining <= 0) { truncated = true; break; }
            List<KnowledgeDtos.Paragraph> paragraphs = readParagraphs(source.paragraphsJson());
            List<KnowledgeDtos.Paragraph> selected = new ArrayList<>();
            List<KnowledgeDtos.Paragraph> ranked = new ArrayList<>(paragraphs);
            ranked.sort(Comparator.comparingInt((KnowledgeDtos.Paragraph value) -> textScore(value.text(), terms)).reversed()
                    .thenComparingInt(KnowledgeDtos.Paragraph::index));
            for (var paragraph : ranked) {
                if (paragraph.text().length() > remaining) { truncated = true; continue; }
                selected.add(paragraph);
                remaining -= paragraph.text().length();
            }
            selected.sort(Comparator.comparingInt(KnowledgeDtos.Paragraph::index));
            if (selected.size() != paragraphs.size()) truncated = true;
            if (!selected.isEmpty()) documents.add(new KnowledgeDtos.ContextDocument(Long.toString(source.id()), source.currentVersion(),
                    source.name(), source.sha256(), source.sourceType(), source.sourceUrl(), selected));
        }
        return new KnowledgeDtos.KnowledgeContext(documents, selectedPreferences, history(userId, projectId), truncated,
                sourceCount, properties.getMaxContextCharacters() - remaining,memory);
    }

    /** Revalidate frozen references before new execution; old sources are not replaced by newer versions. */
    @Transactional(readOnly = true)
    public boolean referencesCurrent(long userId, long projectId, List<KnowledgeDtos.ContextDocument> references) {
        var project = projects.project(userId, projectId).orElseThrow(this::notFound);
        if (!"ACTIVE".equals(project.status())) return false;
        if (references == null || references.size() > properties.getMaxContextDocuments()) return false;
        for (var reference : references) {
            if(mapper.legacyConversationDocument(userId,reference.id())) return false;
            Long documentId = id(reference.id());
            if (documentId == null) return false;
            var document = mapper.document(userId, projectId, documentId).orElse(null);
            if (document == null || !"READY".equals(document.status()) || document.currentVersion() != reference.version()
                    || !document.sha256().equals(reference.sha256())) return false;
        }
        return true;
    }

    /** A queued snapshot cannot regain access after deletion/correction; no newer text is substituted. */
    @Transactional(readOnly = true, noRollbackFor = BusinessException.class)
    public KnowledgeDtos.KnowledgeContext revalidateFrozenContext(long userId, Long projectId, KnowledgeDtos.KnowledgeContext snapshot) {
        if (snapshot == null) return null;
        boolean projectActive = projectId != null && projects.project(userId, projectId)
                .filter(project -> "ACTIVE".equals(project.status())).isPresent();
        List<KnowledgeDtos.ContextDocument> documents = new ArrayList<>();
        boolean changed = false;
        for (var reference : snapshot.documents()) {
            if(mapper.legacyConversationDocument(userId,reference.id())) { changed=true;continue; }
            if (!projectActive) { changed = true; continue; }
            Long documentId;
            try { documentId = id(reference.id()); }
            catch (BusinessException exception) { changed = true; continue; }
            var current = documentId == null ? null : mapper.document(userId, projectId, documentId).orElse(null);
            if (current == null || !"READY".equals(current.status()) || current.currentVersion() != reference.version()
                    || !current.sha256().equals(reference.sha256())) { changed = true; continue; }
            var allowed = readParagraphs(current.paragraphsJson());
            if (!allowed.containsAll(reference.paragraphs())) { changed = true; continue; }
            documents.add(reference);
        }
        Map<String, KnowledgeDtos.PreferenceView> currentPreferences = new HashMap<>();
        List<KnowledgeDtos.PreferenceView> preferences = new ArrayList<>();
        for (var frozen : snapshot.preferences()) {
            var current = currentPreferences.get(frozen.id());
            if (current == null || frozen.version() != current.version() || !Objects.equals(frozen.projectId(), current.projectId())
                    || !frozen.content().equals(current.content())) { changed = true; continue; }
            preferences.add(frozen);
        }
        var businessHistory = projectActive && snapshot.businessHistory() != null
                && Long.toString(projectId).equals(snapshot.businessHistory().projectId()) ? snapshot.businessHistory() : null;
        if (businessHistory != snapshot.businessHistory()) changed = true;
        int remaining = properties.getMaxContextCharacters();
        List<KnowledgeDtos.PreferenceView> boundedPreferences = new ArrayList<>();
        for (var preference : preferences) {
            if (boundedPreferences.size() >= 100 || preference.content().length() > remaining) { changed = true; continue; }
            boundedPreferences.add(preference);
            remaining -= preference.content().length();
        }
        List<KnowledgeDtos.ContextDocument> boundedDocuments = new ArrayList<>();
        for (var document : documents) {
            int length = document.paragraphs().stream().mapToInt(value -> value.text().length()).sum();
            if (boundedDocuments.size() >= properties.getMaxContextDocuments() || length > remaining) { changed = true; continue; }
            boundedDocuments.add(document);
            remaining -= length;
        }
        return new KnowledgeDtos.KnowledgeContext(boundedDocuments, boundedPreferences, businessHistory, snapshot.truncated() || changed,
                snapshot.sourceCount(), properties.getMaxContextCharacters() - remaining,
                memoryService==null?null:memoryService.context(userId,projectActive?projectId:null));
    }

    private void requireProject(long userId, long projectId, boolean write) {
        if (write) {
            String status = mapper.lockProjectForWrite(userId, projectId).orElseThrow(this::notFound);
            if (!"ACTIVE".equals(status)) throw new BusinessException(ErrorCode.RESOURCE_IN_USE,
                    "项目已归档，请先恢复项目。", Map.of("reasonCode", "PROJECT_ARCHIVED"));
        } else if (projects.project(userId, projectId).isEmpty()) throw notFound();
    }
    private void requirePreferenceWrite(long userId, Long projectId) {
        lockPreferenceAccount(userId);
        if (projectId != null) requireProject(userId, projectId, true);
    }
    private void lockPreferenceAccount(long userId) {
        if (mapper.lockAccountForPreferenceWrite(userId).isEmpty()) throw new BusinessException(ErrorCode.FORBIDDEN);
    }
    private void requireDocumentCapacity(long userId, long projectId) {
        if (mapper.documentCount(userId, projectId) >= properties.getMaxDocumentsPerProject()) throw invalid("KNOWLEDGE_PROJECT_LIMIT");
    }
    private KnowledgeRecords.DocumentProjection ownedDocument(long userId, long projectId, long id) {
        return mapper.document(userId, projectId, id).orElseThrow(this::notFound);
    }
    private KnowledgeRecords.Preference insertPreference(long userId, Long projectId, String content, String source, Long messageId) {
        String hash = hash(content);
        var duplicate = mapper.duplicatePreference(userId, projectId, hash).orElse(null);
        if (duplicate != null) return duplicate;
        if (mapper.preferenceCount(userId, projectId) >= properties.getMaxPreferencesPerScope()) throw invalid("PREFERENCE_SCOPE_LIMIT");
        OffsetDateTime now = now();
        var record = new KnowledgeRecords.Preference(ids.nextId(), userId, projectId, content, hash, source, messageId, 1, now, now, null);
        if (mapper.insertPreference(record) != 1) throw conflict();
        return record;
    }
    private KnowledgeDtos.PreferenceView preference(KnowledgeRecords.Preference record) {
        return new KnowledgeDtos.PreferenceView(Long.toString(record.id()), record.projectId() == null ? null : Long.toString(record.projectId()),
                record.projectId() == null ? "PERSONAL" : "PROJECT", record.content(), record.sourceType(),
                record.sourceMessageId() == null ? null : Long.toString(record.sourceMessageId()), record.version(), record.createdAt(), record.updatedAt());
    }
    private KnowledgeDtos.BusinessHistory history(long userId, long projectId) {
        var counts = mapper.businessHistory(userId, projectId);
        if (counts == null) throw new BusinessException(ErrorCode.INTERNAL_ERROR);
        return new KnowledgeDtos.BusinessHistory(Long.toString(projectId), counts.writtenArticleCount(), counts.publishedArticleCount(),
                counts.publishedPlacementCount(), now());
    }
    private KnowledgeRecords.DocumentVersion version(KnowledgeRecords.Document record, KnowledgeParser.Parsed parsed, String url, String key) {
        return new KnowledgeRecords.DocumentVersion(record.id(), record.userId(), record.projectId(), record.currentVersion(),
                parsed.sha256(), parsed.contentType(), parsed.sizeBytes(), url, key, writeParagraphs(parsed.paragraphs()), parsed.pageCount(), now());
    }
    private KnowledgeRecords.DocumentProjection projection(KnowledgeRecords.Document record, KnowledgeParser.Parsed parsed, String url) {
        return new KnowledgeRecords.DocumentProjection(record.id(), record.userId(), record.projectId(), record.name(), record.sourceType(), record.status(),
                record.currentVersion(), record.failureCode(), record.createdAt(), record.updatedAt(), parsed.sha256(), parsed.contentType(), parsed.sizeBytes(),
                url, writeParagraphs(parsed.paragraphs()), parsed.pageCount());
    }
    private KnowledgeDtos.DocumentSummary summary(KnowledgeRecords.DocumentProjection record) {
        return new KnowledgeDtos.DocumentSummary(Long.toString(record.id()), Long.toString(record.projectId()), record.name(), record.sourceType(), record.status(),
                record.currentVersion(), record.sha256(), record.contentType(), record.sizeBytes(), readParagraphs(record.paragraphsJson()).size(),
                record.pageCount(), record.sourceUrl(), record.failureCode(), record.createdAt(), record.updatedAt());
    }
    private KnowledgeDtos.DocumentDetail detail(KnowledgeRecords.DocumentProjection record) {
        var summary = summary(record);
        return new KnowledgeDtos.DocumentDetail(summary.id(), summary.projectId(), summary.name(), summary.sourceType(), summary.status(), summary.version(),
                summary.sha256(), summary.contentType(), summary.sizeBytes(), summary.paragraphCount(), summary.pageCount(), summary.sourceUrl(), summary.failureCode(),
                summary.createdAt(), summary.updatedAt(), readParagraphs(record.paragraphsJson()), false);
    }
    private List<KnowledgeDtos.Paragraph> readParagraphs(String value) {
        try { return json.readValue(value, PARAGRAPHS); }
        catch (IOException exception) { throw new BusinessException(ErrorCode.INTERNAL_ERROR); }
    }
    private String writeParagraphs(List<KnowledgeDtos.Paragraph> value) {
        try { return json.writeValueAsString(value); }
        catch (IOException exception) { throw new BusinessException(ErrorCode.INTERNAL_ERROR); }
    }
    private void insertDocument(KnowledgeRecords.Document record) { if (mapper.insertDocument(record) != 1) throw conflict(); }
    private void insertVersion(KnowledgeRecords.DocumentVersion record) { if (mapper.insertVersion(record) != 1) throw conflict(); }
    private String name(String value) {
        if (value == null) throw invalid("KNOWLEDGE_NAME_INVALID");
        String safe = value.replace('\\', '/');
        safe = safe.substring(safe.lastIndexOf('/') + 1).replaceAll("[\\r\\n\\t]", " ").trim();
        if (safe.isBlank() || safe.length() > 255 || safe.indexOf('\0') >= 0) throw invalid("KNOWLEDGE_NAME_INVALID");
        return safe;
    }
    private String preferenceContent(String value) {
        if (value == null || value.isBlank() || value.length() > 1000 || value.indexOf('\0') >= 0) throw invalid("PREFERENCE_INVALID");
        return value.trim();
    }
    private String sourceUrl(String value) {
        if (value == null || value.isBlank()) return null;
        if (value.length() > 2048 || value.chars().anyMatch(Character::isWhitespace)) throw invalid("KNOWLEDGE_URL_INVALID");
        try {
            URI uri = URI.create(value);
            if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    || uri.getHost() == null || uri.getUserInfo() != null || uri.getFragment() != null) throw invalid("KNOWLEDGE_URL_INVALID");
            String normalized = uri.toASCIIString();
            normalized = uri.getScheme().toLowerCase(Locale.ROOT) + normalized.substring(normalized.indexOf(':'));
            if (normalized.length() > 2048) throw invalid("KNOWLEDGE_URL_INVALID");
            return normalized;
        } catch (IllegalArgumentException exception) { throw invalid("KNOWLEDGE_URL_INVALID"); }
    }
    private Long id(String value) {
        if (value == null) return null;
        try { if (!value.matches("^[1-9][0-9]{0,18}$")) throw new NumberFormatException(); return Long.parseLong(value); }
        catch (NumberFormatException exception) { throw invalid("KNOWLEDGE_ID_INVALID"); }
    }
    private String hash(String value) { return KnowledgeParser.sha256(value.getBytes(StandardCharsets.UTF_8)); }
    private ObjectStorage storage() {
        ObjectStorage value = storageProvider.getIfAvailable();
        if (value == null) throw new BusinessException(ErrorCode.PROVIDER_NOT_CONFIGURED);
        return value;
    }
    private void copy(MultipartFile file, Path target) throws IOException {
        long total = 0;
        try (InputStream input = file.getInputStream(); OutputStream output = Files.newOutputStream(target, StandardOpenOption.CREATE_NEW)) {
            byte[] buffer = new byte[65536];
            int read;
            while ((read = input.read(buffer)) != -1) {
                total += read;
                if (total > properties.getMaxFileBytes()) throw invalid("KNOWLEDGE_FILE_TOO_LARGE");
                output.write(buffer, 0, read);
            }
        }
        if (total == 0) throw invalid("KNOWLEDGE_FILE_EMPTY");
    }
    private void cleanupObject(ObjectStorage storage, ObjectStorage.StoredObject stored) {
        if (stored != null && stored.createdByCaller()) storage.delete(stored.objectKey());
    }
    private String objectKey(long userId, long projectId, long id, String source) {
        String extension = "PDF".equals(source) ? ".pdf" : "MARKDOWN".equals(source) ? ".md" : ".txt";
        return "zhixiaohe/" + assetProperties.getEnvironment() + "/users/" + userId + "/knowledge/"
                + projectId + "/" + id + "/original" + extension;
    }
    private String failureCode(BusinessException exception) {
        Object code = exception.details().get("reasonCode");
        return code instanceof String value && value.matches("^[A-Z][A-Z0-9_]{0,63}$") ? value : "KNOWLEDGE_PARSE_FAILED";
    }
    private boolean explicitRemember(String value) {
        String normalized = value.toLowerCase(Locale.ROOT);
        return normalized.contains("记住") || normalized.contains("以后") || normalized.contains("今后")
                || normalized.contains("remember") || normalized.contains("from now on");
    }
    private List<String> searchTerms(String query) {
        if (query == null || query.isBlank()) return List.of();
        String value = query.toLowerCase(Locale.ROOT);
        if (value.length() > 200) value = value.substring(0, 200);
        List<String> result = new ArrayList<>();
        for (String token : value.split("[\\s\\p{Punct}，。？！、；：]+")) {
            if (token.length() >= 2) {
                result.add(token);
                if (token.length() > 4) for (int i = 0; i + 2 <= token.length() && result.size() < 32; i += 2) result.add(token.substring(i, i + 2));
            }
            if (result.size() >= 32) break;
        }
        return List.copyOf(result);
    }
    private int score(KnowledgeRecords.DocumentProjection record, List<String> terms) {
        return textScore(record.name(), terms) * 3 + textScore(record.paragraphsJson(), terms);
    }
    private int textScore(String value, List<String> terms) {
        String lower = value.toLowerCase(Locale.ROOT);
        return (int) terms.stream().filter(lower::contains).count();
    }
    private OffsetDateTime now() {
        // PostgreSQL stores microseconds; mutation responses must match a subsequent read.
        return OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(java.time.temporal.ChronoUnit.MICROS);
    }
    private BusinessException invalid(String code) { return KnowledgeParser.failure(code); }
    private BusinessException notFound() { return new BusinessException(ErrorCode.RESOURCE_NOT_FOUND); }
    private BusinessException conflict() { return new BusinessException(ErrorCode.RESOURCE_VERSION_CONFLICT); }
}
