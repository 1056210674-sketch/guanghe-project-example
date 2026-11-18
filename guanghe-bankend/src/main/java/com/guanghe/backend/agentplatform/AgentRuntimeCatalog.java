package com.guanghe.backend.agentplatform;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.guanghe.backend.common.api.BusinessException;
import com.guanghe.backend.common.api.ErrorCode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.springframework.stereotype.Component;

/** Controlled release metadata shared byte-for-byte with the Python execution plane. */
@Component
public final class AgentRuntimeCatalog {
    private static final String RESOURCE = "/contracts/agents/runtime-catalog.json";
    private static final Set<String> LEGACY_IDENTITIES = Set.of("geo/1.0.0", "creative/1.0.0", "summary/1.0.0");
    private final Manifest manifest;
    private final Map<String, Entry> entries;
    private final String hash;

    public AgentRuntimeCatalog() { this(resourceBytes()); }

    public AgentRuntimeCatalog(byte[] source) {
        try {
            if (source.length > 262_144) throw new IllegalArgumentException("Runtime catalogue is too large");
            byte[] normalized = new String(source, StandardCharsets.UTF_8).replace("\r\n", "\n")
                    .getBytes(StandardCharsets.UTF_8);
            var mapper = new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                    .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                    .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
            manifest = mapper.readValue(normalized, Manifest.class);
            if (manifest.schemaVersion() != 1 || manifest.agents() == null || manifest.agents().isEmpty() || manifest.agents().size()>256
                    || manifest.tools() == null || manifest.tools().isEmpty()
                    || manifest.acceptedArticlePromptVersions() == null || manifest.acceptedArticlePromptVersions().isEmpty()
                    || !manifest.acceptedArticlePromptVersions().contains(manifest.bundleVersion())
                    || manifest.workerProfileCode() == null || !manifest.workerProfileCode().matches("^[A-Z][A-Z0-9_]{0,63}$")
                    || manifest.bundleSha256() == null || !manifest.bundleSha256().matches("^[0-9a-f]{64}$")) {
                throw new IllegalArgumentException("Invalid runtime catalogue header");
            }
            validateNames(manifest.acceptedArticlePromptVersions(), "^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$");
            manifest.tools().forEach((name, tool) -> {
                if (name == null || !name.matches("^[a-z][a-z0-9_.-]{0,63}$") || tool == null)
                    throw new IllegalArgumentException("Invalid tool registration");
                validateNames(tool.requiredCapabilities(), "^[a-z][a-z0-9_.-]{0,63}$");
                if (tool.requiredCapabilities().isEmpty() || tool.requiredCapabilities().size()>32)
                    throw new IllegalArgumentException("Tool permissions are required and bounded");
            });
            Map<String, Entry> registered = new LinkedHashMap<>();
            for (Entry entry : manifest.agents()) {
                if (entry.agentKey() == null || !entry.agentKey().matches("^[a-z][a-z0-9-]{0,63}$")
                        || entry.agentVersion() == null || !entry.agentVersion().matches("^[0-9]+\\.[0-9]+\\.[0-9]+$")
                        || entry.catalogueAdapterKey() == null || !entry.catalogueAdapterKey().matches("^[a-z][a-z0-9-]{0,63}$")
                        || !Set.of("native", "summary").contains(entry.runtimeKind())
                        || !Set.of("plain", "geo-brand").contains(entry.contextPolicy())
                        || !Set.of("none", "geo-article").contains(entry.continuationPolicy())
                        || !Set.of("GEO", "GENERAL").contains(entry.intent())
                        || entry.promptCode() == null || !entry.promptCode().matches("^[A-Z][A-Z0-9_]{0,63}$")
                        || entry.executionRelease() == null || !entry.executionRelease().matches("^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$")
                        || entry.legacyCapabilityGrants() == null)
                    throw new IllegalArgumentException("Invalid runtime registration");
                validateNames(entry.capabilities(), "^[a-z][a-z0-9_.-]{0,63}$");
                validateNames(entry.requiredCapabilities(), "^[a-z][a-z0-9_.-]{0,63}$");
                validateNames(entry.allowedTools(), "^[a-z][a-z0-9_.-]{0,63}$");
                validateNames(entry.explicitActions(), "^[A-Z][A-Z0-9_]{0,63}$");
                if (entry.capabilities().size()>32 || entry.requiredCapabilities().size()>32 || entry.allowedTools().size()>64
                        || !entry.requiredCapabilities().contains("chat") || !entry.capabilities().containsAll(entry.requiredCapabilities())
                        || !manifest.tools().keySet().containsAll(entry.allowedTools())
                        || !Set.of("MONITOR_RUN", "ARTICLE_FULL_V1").containsAll(entry.explicitActions())
                        || !entry.legacyCapabilityGrants().isEmpty() && !LEGACY_IDENTITIES.contains(key(entry.agentKey(),entry.agentVersion()))
                        || "summary".equals(entry.runtimeKind()) && (!entry.allowedTools().isEmpty() || !entry.explicitActions().isEmpty()
                            || !"plain".equals(entry.contextPolicy()) || !"none".equals(entry.continuationPolicy())
                            || !entry.requiredCapabilities().contains("text.summarize")))
                    throw new IllegalArgumentException("Invalid runtime permissions");
                entry.legacyCapabilityGrants().forEach((capability, grants) -> {
                    validateNames(List.of(capability), "^[a-z][a-z0-9_.-]{0,63}$");
                    validateNames(grants, "^[a-z][a-z0-9_.-]{0,63}$");
                    if (!entry.capabilities().containsAll(grants)) throw new IllegalArgumentException("Legacy grants exceed ceiling");
                });
                if (registered.put(key(entry.agentKey(), entry.agentVersion()), entry) != null)
                    throw new IllegalArgumentException("Duplicate runtime identity");
            }
            entries = Map.copyOf(registered);
            hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(normalized));
        } catch (Exception failure) {
            throw new IllegalStateException("Invalid controlled agent runtime catalogue", failure);
        }
    }

    public Optional<Entry> find(String agentKey, String version) { return Optional.ofNullable(entries.get(key(agentKey, version))); }
    public Entry require(String agentKey, String version) { return find(agentKey, version).orElseThrow(this::unavailable); }
    public Entry require(AgentPlatformDtos.AgentIdentity identity) { return require(identity.agentKey(), identity.agentVersion()); }
    public boolean supports(AgentPlatformRecords.Definition definition) {
        return find(definition.agentKey(), definition.version())
                .map(value -> value.catalogueAdapterKey().equals(definition.adapterKey())).orElse(false);
    }
    public String catalogHash() { return hash; }
    public Set<String> acceptedArticlePromptVersions() { return Set.copyOf(manifest.acceptedArticlePromptVersions()); }

    /** Historic DB definitions stay immutable; compatibility grants belong to one registered release only. */
    public List<String> effectiveCapabilities(String key, String version, Collection<String> granted) {
        var entry = require(key, version);
        Set<String> expanded = new LinkedHashSet<>(granted);
        entry.legacyCapabilityGrants().forEach((source, additions) -> { if (granted.contains(source)) expanded.addAll(additions); });
        return entry.capabilities().stream().filter(expanded::contains).toList();
    }

    public AgentPlatformDtos.AgentIdentity freeze(AgentPlatformDtos.AgentIdentity identity) {
        var entry = require(identity);
        var capabilities = effectiveCapabilities(identity.agentKey(), identity.agentVersion(), identity.capabilities());
        if (!capabilities.containsAll(entry.requiredCapabilities())) throw unavailable();
        return new AgentPlatformDtos.AgentIdentity(identity.installationId(), identity.agentKey(), identity.agentVersion(),
                identity.name(), identity.projectId(), capabilities, entry.executionRelease(), hash);
    }

    public AgentPlatformDtos.AgentIdentity validateFrozen(AgentPlatformDtos.AgentIdentity identity) {
        var entry = require(identity);
        if (identity.executionRelease() == null && identity.catalogHash() == null) {
            if (!LEGACY_IDENTITIES.contains(key(identity.agentKey(),identity.agentVersion()))) throw unavailable();
            return freeze(identity);
        }
        if (!entry.executionRelease().equals(identity.executionRelease()) || !hash.equals(identity.catalogHash())
                || !entry.capabilities().containsAll(identity.capabilities())
                || !identity.capabilities().containsAll(entry.requiredCapabilities())) throw unavailable();
        return identity;
    }

    public boolean permits(AgentPlatformDtos.AgentIdentity identity, String toolName) {
        var entry = require(identity);
        var tool = manifest.tools().get(toolName);
        return tool != null && identity.capabilities().containsAll(entry.requiredCapabilities())
                && entry.allowedTools().contains(toolName)
                && identity.capabilities().containsAll(tool.requiredCapabilities());
    }

    /** Frozen Run authority may only shrink when checked against the current installation. */
    public AgentPlatformDtos.AgentIdentity authorize(AgentPlatformDtos.AgentIdentity snapshot,
            AgentPlatformDtos.AgentIdentity current) {
        if (snapshot == null || current == null) throw unavailable();
        var frozen = validateFrozen(snapshot);
        var entry = require(current);
        if (!Objects.equals(frozen.installationId(), current.installationId())
                || !frozen.agentKey().equals(current.agentKey()) || !frozen.agentVersion().equals(current.agentVersion())
                || !Objects.equals(frozen.projectId(), current.projectId())) throw new BusinessException(ErrorCode.FORBIDDEN);
        // Current is already resolved by AgentPlatformService. Do not legacy-expand it again.
        return new AgentPlatformDtos.AgentIdentity(current.installationId(), current.agentKey(), current.agentVersion(),
                current.name(), current.projectId(), frozen.capabilities().stream()
                        .filter(current.capabilities()::contains).filter(entry.capabilities()::contains).toList(),
                frozen.executionRelease(), frozen.catalogHash());
    }

    private static String key(String key, String version) { return key + "/" + version; }
    private static void validateNames(List<String> values, String pattern) {
        if (values == null || values.size() > 128 || values.stream().anyMatch(value -> value == null || !value.matches(pattern))
                || new HashSet<>(values).size() != values.size()) throw new IllegalArgumentException("Invalid catalogue values");
    }
    private static byte[] resourceBytes() {
        try (var input = AgentRuntimeCatalog.class.getResourceAsStream(RESOURCE)) {
            if (input == null) throw new IllegalStateException("Controlled runtime catalogue is missing");
            return input.readAllBytes();
        } catch (IOException failure) { throw new IllegalStateException("Cannot read runtime catalogue", failure); }
    }
    private BusinessException unavailable() { return new BusinessException(ErrorCode.PROVIDER_NOT_CONFIGURED); }

    public record Tool(List<String> requiredCapabilities) { public Tool { requiredCapabilities = List.copyOf(requiredCapabilities); } }
    public record Entry(String agentKey, String agentVersion, String catalogueAdapterKey, String runtimeKind,
            String promptCode, String executionRelease, String intent, String contextPolicy, String continuationPolicy,
            List<String> explicitActions, List<String> requiredCapabilities, List<String> capabilities,
            Map<String, List<String>> legacyCapabilityGrants, List<String> allowedTools) {
        public Entry {
            explicitActions = List.copyOf(explicitActions); requiredCapabilities = List.copyOf(requiredCapabilities);
            capabilities = List.copyOf(capabilities); allowedTools = List.copyOf(allowedTools);
            var copy = new LinkedHashMap<String, List<String>>();
            legacyCapabilityGrants.forEach((key, value) -> copy.put(key, List.copyOf(value)));
            legacyCapabilityGrants = Map.copyOf(copy);
        }
    }
    private record Manifest(int schemaVersion, String bundleVersion, String bundleSha256, String workerProfileCode,
            List<String> acceptedArticlePromptVersions, Map<String, Tool> tools, List<Entry> agents) { }
}
