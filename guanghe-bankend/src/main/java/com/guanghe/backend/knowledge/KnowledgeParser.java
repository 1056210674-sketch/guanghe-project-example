package com.guanghe.backend.knowledge;

import com.guanghe.backend.common.api.BusinessException;
import com.guanghe.backend.common.api.ErrorCode;
import java.io.IOException;
import java.io.Writer;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Text-only parser. Content is data: neither HTML, Markdown nor document instructions are executed. */
@Component
@ConditionalOnProperty(prefix = "zhixiaohe.agent-platform", name = "enabled", havingValue = "true", matchIfMissing = true)
public class KnowledgeParser {
    private final KnowledgeProperties properties;
    public KnowledgeParser(KnowledgeProperties properties) {
        properties.validate();
        this.properties = properties;
    }

    public Parsed pasted(String content) {
        if (content == null || content.isBlank() || content.length() > properties.getMaxTextCharacters()
                || content.indexOf('\0') >= 0) throw failure("KNOWLEDGE_TEXT_INVALID");
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        return new Parsed("PASTED_TEXT", "text/plain", bytes.length, sha256(bytes), null,
                paragraphs(content, null, 0));
    }

    public String sourceType(String name) {
        String lower = name == null ? "" : name.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".txt")) return "TXT";
        if (lower.endsWith(".md") || lower.endsWith(".markdown")) return "MARKDOWN";
        if (lower.endsWith(".pdf")) return "PDF";
        throw failure("KNOWLEDGE_FILE_UNSUPPORTED");
    }

    public Parsed file(Path path, String name, String declaredContentType) throws IOException {
        String source = sourceType(name);
        long size = Files.size(path);
        if (size < 1 || size > properties.getMaxFileBytes()) throw failure("KNOWLEDGE_FILE_TOO_LARGE");
        long deadline = System.nanoTime() + properties.getParseTimeout().toNanos();
        byte[] bytes = Files.readAllBytes(path);
        String sha = sha256(bytes);
        boolean pdfMagic = bytes.length >= 5 && bytes[0] == '%' && bytes[1] == 'P' && bytes[2] == 'D'
                && bytes[3] == 'F' && bytes[4] == '-';
        String declared = declaredContentType == null ? "" : declaredContentType.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        if ("PDF".equals(source)) {
            if (!pdfMagic || !(declared.isBlank() || "application/pdf".equals(declared)
                    || "application/octet-stream".equals(declared))) throw failure("KNOWLEDGE_FILE_TYPE_MISMATCH");
            return pdf(path, size, sha, deadline);
        }
        if (pdfMagic || !(declared.isBlank() || "text/plain".equals(declared) || "text/markdown".equals(declared)
                || "application/octet-stream".equals(declared))) throw failure("KNOWLEDGE_FILE_TYPE_MISMATCH");
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException exception) {
            throw failure("KNOWLEDGE_UTF8_REQUIRED");
        }
        if (text.startsWith("\uFEFF")) text = text.substring(1);
        checkDeadline(deadline);
        Parsed parsed = pasted(text);
        return new Parsed(source, "MARKDOWN".equals(source) ? "text/markdown" : "text/plain",
                size, sha, null, parsed.paragraphs());
    }

    private Parsed pdf(Path path, long size, String sha, long deadline) throws IOException {
        List<KnowledgeDtos.Paragraph> result = new ArrayList<>();
        try (PDDocument document = Loader.loadPDF(path.toFile())) {
            checkDeadline(deadline);
            if (document.isEncrypted() || !document.getCurrentAccessPermission().canExtractContent()) {
                throw failure("KNOWLEDGE_PDF_PROTECTED");
            }
            int pages = document.getNumberOfPages();
            if (pages < 1 || pages > properties.getMaxPdfPages()) throw failure("KNOWLEDGE_PDF_PAGE_LIMIT");
            int characters = 0;
            PDFTextStripper stripper = new PDFTextStripper();
            for (int page = 1; page <= pages; page++) {
                checkDeadline(deadline);
                stripper.setStartPage(page);
                stripper.setEndPage(page);
                BoundedTextWriter writer = new BoundedTextWriter(properties.getMaxTextCharacters() - characters, deadline);
                stripper.writeText(document, writer);
                String pageText = writer.text();
                characters += pageText.length();
                if (!pageText.isBlank()) result.addAll(paragraphs(pageText, page, result.size()));
                if (result.size() > 200) throw failure("KNOWLEDGE_PARAGRAPH_LIMIT");
            }
            if (result.isEmpty()) throw failure("KNOWLEDGE_PDF_NO_EXTRACTABLE_TEXT");
            return new Parsed("PDF", "application/pdf", size, sha, pages, result);
        } catch (InvalidPasswordException exception) {
            throw failure("KNOWLEDGE_PDF_PROTECTED");
        }
    }

    private List<KnowledgeDtos.Paragraph> paragraphs(String raw, Integer page, int offset) {
        String text = raw.replace("\r\n", "\n").replace('\r', '\n').trim();
        if (text.isBlank()) return List.of();
        List<KnowledgeDtos.Paragraph> result = new ArrayList<>();
        // Fixed, bounded chunks retain every character and reliable page/chunk locators.
        for (int start = 0; start < text.length();) {
            int end = Math.min(text.length(), start + 1000);
            if (end < text.length() && Character.isHighSurrogate(text.charAt(end - 1))) end--;
            String chunk = text.substring(start, end);
            result.add(new KnowledgeDtos.Paragraph(offset + result.size() + 1, page, chunk));
            start = end;
        }
        if (result.size() + offset > 200) throw failure("KNOWLEDGE_PARAGRAPH_LIMIT");
        return List.copyOf(result);
    }

    private static void checkDeadline(long deadline) {
        if (System.nanoTime() > deadline || Thread.currentThread().isInterrupted()) throw failure("KNOWLEDGE_PARSE_TIMEOUT");
    }
    public static String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException exception) { throw new IllegalStateException("SHA-256 unavailable", exception); }
    }
    public static BusinessException failure(String code) {
        return new BusinessException(ErrorCode.VALIDATION_FAILED, "资料无法解析，请使用纯文本、UTF-8 TXT、Markdown 或可提取文字的 PDF。",
                Map.of("reasonCode", code));
    }
    public record Parsed(String sourceType, String contentType, long sizeBytes, String sha256,
            Integer pageCount, List<KnowledgeDtos.Paragraph> paragraphs) {
        public Parsed { paragraphs = List.copyOf(paragraphs); }
        @Override public String toString() { return "Parsed[type=" + sourceType + ", characters="
                + paragraphs.stream().mapToInt(value -> value.text().length()).sum() + "]"; }
    }
    private static final class BoundedTextWriter extends Writer {
        private final int maximum;
        private final long deadline;
        private final StringBuilder result = new StringBuilder();
        BoundedTextWriter(int maximum, long deadline) { this.maximum = maximum; this.deadline = deadline; }
        @Override public void write(char[] value, int offset, int length) {
            checkDeadline(deadline);
            if ((long) result.length() + length > maximum) throw failure("KNOWLEDGE_TEXT_LIMIT");
            result.append(value, offset, length);
        }
        @Override public void flush() { checkDeadline(deadline); }
        @Override public void close() { }
        String text() { return result.toString(); }
    }
}
