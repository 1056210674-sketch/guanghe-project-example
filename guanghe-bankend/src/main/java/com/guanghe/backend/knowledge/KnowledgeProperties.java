package com.guanghe.backend.knowledge;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("zhixiaohe.knowledge")
public class KnowledgeProperties {
    private long maxFileBytes = 20L * 1024 * 1024;
    private int maxTextCharacters = 100000;
    private int maxPdfPages = 100;
    private Duration parseTimeout = Duration.ofSeconds(10);
    private int maxDocumentsPerProject = 200;
    private int maxPreferencesPerScope = 50;
    private int maxContextCharacters = 12000;
    private int maxContextDocuments = 12;
    private boolean uploadRecoveryEnabled = true;

    public long getMaxFileBytes() { return maxFileBytes; }
    public void setMaxFileBytes(long value) { maxFileBytes = value; }
    public int getMaxTextCharacters() { return maxTextCharacters; }
    public void setMaxTextCharacters(int value) { maxTextCharacters = value; }
    public int getMaxPdfPages() { return maxPdfPages; }
    public void setMaxPdfPages(int value) { maxPdfPages = value; }
    public Duration getParseTimeout() { return parseTimeout; }
    public void setParseTimeout(Duration value) { parseTimeout = value; }
    public int getMaxDocumentsPerProject() { return maxDocumentsPerProject; }
    public void setMaxDocumentsPerProject(int value) { maxDocumentsPerProject = value; }
    public int getMaxPreferencesPerScope() { return maxPreferencesPerScope; }
    public void setMaxPreferencesPerScope(int value) { maxPreferencesPerScope = value; }
    public int getMaxContextCharacters() { return maxContextCharacters; }
    public void setMaxContextCharacters(int value) { maxContextCharacters = value; }
    public int getMaxContextDocuments() { return maxContextDocuments; }
    public void setMaxContextDocuments(int value) { maxContextDocuments = value; }
    public boolean isUploadRecoveryEnabled() { return uploadRecoveryEnabled; }
    public void setUploadRecoveryEnabled(boolean value) { uploadRecoveryEnabled = value; }
    public void validate() {
        if (maxFileBytes < 1 || maxFileBytes > 20L * 1024 * 1024
                || maxTextCharacters < 100 || maxTextCharacters > 100000
                || maxPdfPages < 1 || maxPdfPages > 100
                || parseTimeout == null || parseTimeout.isNegative() || parseTimeout.isZero()
                || parseTimeout.compareTo(Duration.ofSeconds(30)) > 0
                || maxDocumentsPerProject < 1 || maxDocumentsPerProject > 200
                || maxPreferencesPerScope < 1 || maxPreferencesPerScope > 50
                || maxContextCharacters < 100 || maxContextCharacters > 12000
                || maxContextDocuments < 1 || maxContextDocuments > 12) {
            throw new IllegalStateException("Invalid bounded knowledge settings");
        }
    }
}
