package com.guanghe.backend.article;

import com.guanghe.backend.common.api.BusinessException;
import com.guanghe.backend.common.api.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.safety.Cleaner;
import org.jsoup.safety.Safelist;
import org.springframework.stereotype.Component;

@Component
public class ArticleHtmlSanitizer {
    private static final int MAX_BYTES = 524_288;
    private static final Safelist POLICY = Safelist.none()
            .addTags("p", "br", "h1", "h2", "h3", "h4", "h5", "h6", "ul", "ol", "li",
                    "strong", "em", "u", "s", "blockquote", "pre", "code", "table", "thead",
                    "tbody", "tr", "th", "td", "a", "figure", "figcaption", "span", "section")
            .addAttributes("a", "href", "title")
            .addProtocols("a", "href", "http", "https")
            .addAttributes("th", "colspan", "rowspan")
            .addAttributes("td", "colspan", "rowspan")
            .addAttributes("span", "data-asset-id")
            .addAttributes("figure", "data-asset-id")
            .addAttributes("section", "data-article-block-id")
            .addEnforcedAttribute("a", "rel", "nofollow noopener noreferrer");

    public Sanitized sanitize(String input) {
        String value = input == null ? "" : input;
        if (value.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        Document dirty = Jsoup.parseBodyFragment(value);
        dirty.outputSettings().prettyPrint(false);
        Document clean = new Cleaner(POLICY).clean(dirty);
        clean.outputSettings().prettyPrint(false);
        String html = clean.body().html();
        String text = clean.body().text();
        if (html.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES
                || text.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        Set<Long> assetIds = new HashSet<>();
        clean.select("[data-asset-id]").forEach(element -> {
            String raw = element.attr("data-asset-id");
            try {
                long id = Long.parseLong(raw);
                if (id <= 0) throw new NumberFormatException();
                assetIds.add(id);
            } catch (NumberFormatException exception) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED);
            }
        });
        return new Sanitized(html, text, Set.copyOf(assetIds));
    }

    public record Sanitized(String html, String text, Set<Long> assetIds) { }
}
