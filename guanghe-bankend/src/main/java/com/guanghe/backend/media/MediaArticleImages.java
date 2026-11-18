package com.guanghe.backend.media;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.guanghe.backend.article.ArticleRecords;
import com.guanghe.backend.asset.AssetIngestionProperties;
import com.guanghe.backend.asset.AssetMapper;
import com.guanghe.backend.asset.AssetRecords;
import com.guanghe.backend.asset.ObjectStorage;
import com.guanghe.backend.common.api.BusinessException;
import com.guanghe.backend.common.api.ErrorCode;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** The media-only bridge from an owned immutable article snapshot to supplier HTML. */
@Component
@ConditionalOnProperty(prefix = "zhixiaohe.media", name = "enabled", havingValue = "true")
public class MediaArticleImages {
    private final AssetMapper assets;
    private final ObjectProvider<ObjectStorage> storage;
    private final AssetIngestionProperties assetProperties;
    private final MediaProperties properties;
    private final ObjectMapper json;
    private final MediaMapper media;
    private static final java.security.SecureRandom RANDOM = new java.security.SecureRandom();

    public MediaArticleImages(AssetMapper assets, ObjectProvider<ObjectStorage> storage,
            AssetIngestionProperties assetProperties, MediaProperties properties, ObjectMapper json, MediaMapper media) {
        this.assets = assets;
        this.storage = storage;
        this.assetProperties = assetProperties;
        this.properties = properties;
        this.json = json;
        this.media = media;
    }

    boolean supports(MediaRecords.Resource resource) {
        return properties.imageContractConfigured() && storage.getIfAvailable() != null
                && MediaImagePricing.resolve(properties, resource, json).isPresent();
    }

    void requireCompatible(MediaRecords.Resource resource, int count) {
        if (count > 0 && (!supports(resource) || count > properties.getImageMaxCount())) throw unavailable();
    }

    ObjectNode contract() {
        return json.createObjectNode().put("version", properties.getImageContractVersion())
                .put("delivery", "HTML_STABLE_PUBLIC_COPY")
                .put("maxCount", properties.getImageMaxCount()).put("maxBytes", properties.getImageMaxBytes());
    }

    /** Allocate only when an explicit publication confirmation creates its immutable image pin. */
    String publicationKey(String contentType) {
        if (!Set.of("image/png", "image/jpeg").contains(contentType)) throw unavailable();
        byte[] random = new byte[32];
        RANDOM.nextBytes(random);
        return "media-public/" + assetProperties.getEnvironment() + "/"
                + java.util.HexFormat.of().formatHex(random) + ("image/png".equals(contentType) ? ".png" : ".jpg");
    }

    /** No URL is created before confirmation. Object keys and hashes remain in server-only task snapshots. */
    ArrayNode freeze(ArticleRecords.SubmissionSnapshot article) {
        JsonNode manifest = read(article.assetManifestJson());
        Document document = Jsoup.parseBodyFragment(article.bodyHtml() == null ? "" : article.bodyHtml());
        if (!manifest.isArray() || manifest.size() > 10 || !document.select("img").isEmpty()) throw unavailable();
        var figures = document.select("[data-asset-id]");
        if (manifest.isEmpty() && figures.isEmpty()) return json.createArrayNode();
        if (!properties.imageContractConfigured() || storage.getIfAvailable() == null
                || manifest.size() > properties.getImageMaxCount()) throw unavailable();
        Set<String> placed = new HashSet<>();
        for (Element figure : figures) {
            if (!"figure".equals(figure.tagName()) || !placed.add(figure.attr("data-asset-id"))) throw unavailable();
        }
        ArrayNode frozen = json.createArrayNode();
        Set<String> unique = new HashSet<>();
        for (JsonNode item : manifest) {
            String id = item.path("assetId").asText();
            if (!id.matches("[1-9][0-9]{0,18}") || !unique.add(id) || !placed.contains(id)) throw unavailable();
            AssetRecords.Asset asset;
            try {
                asset = assets.findAsset(Long.parseLong(id), article.userId()).orElseThrow(this::unavailable);
            } catch (NumberFormatException exception) { throw unavailable(); }
            if (asset.spaceId() != article.spaceId() || !"IMAGE".equals(asset.assetType())
                    || !"READY".equals(asset.status()) || !assetProperties.allowsAssetUse(asset.safetyStatus())
                    || !Set.of("image/png", "image/jpeg").contains(asset.contentType())
                    || asset.sizeBytes() < 1 || asset.sizeBytes() > properties.getImageMaxBytes()
                    || asset.sha256() == null || !asset.sha256().matches("[0-9a-f]{64}")
                    || asset.objectKey() == null || !asset.objectKey().startsWith("zhixiaohe/")) throw unavailable();
            frozen.addObject().put("assetId", id).put("objectKey", asset.objectKey())
                    .put("sha256", asset.sha256()).put("sizeBytes", asset.sizeBytes())
                    .put("contentType", asset.contentType());
        }
        if (!unique.equals(placed)) throw unavailable();
        return frozen;
    }

    void revalidate(ArticleRecords.SubmissionSnapshot article, JsonNode taskSnapshot) {
        ArrayNode current = freeze(article);
        if (current.isEmpty()) {
            if (taskSnapshot.has("articleImages") && !taskSnapshot.path("articleImages").isEmpty()) throw unavailable();
            return;
        }
        if (taskSnapshot.path("articleVersion").asInt(-1) != article.articleVersion()
                || !article.bodyHash().equals(taskSnapshot.path("articleBodyHash").asText())
                || !current.equals(taskSnapshot.path("articleImages"))
                || !contract().equals(taskSnapshot.path("imageContract"))) {
            throw new BusinessException(ErrorCode.MEDIA_QUOTE_CHANGED);
        }
    }

    /** Called exactly once within the existing claimed submission; failures here cannot create an order. */
    String render(ArticleRecords.SubmissionSnapshot article, JsonNode taskSnapshot, long submissionId) {
        try {
            revalidate(article, taskSnapshot);
            JsonNode frozen = taskSnapshot.path("articleImages");
            if (frozen.isMissingNode() || frozen.isEmpty()) return article.bodyHtml();
            ObjectStorage objects = storage.getIfAvailable();
            if (objects == null) throw unavailable();
            var pins = media.publicationImages(submissionId, article.userId(), article.spaceId(), article.id());
            if (pins.size() != frozen.size()) throw unavailable();
            Map<String, MediaMapper.PublicationImage> byAsset = new HashMap<>();
            for (var pin : pins) byAsset.put(Long.toString(pin.assetId()), pin);
            Map<String, String> urls = new HashMap<>();
            for (JsonNode image : frozen) {
                var pin = byAsset.get(image.path("assetId").asText());
                if (pin == null || !image.path("objectKey").asText().equals(pin.objectKey())
                        || !image.path("sha256").asText().equals(pin.sha256())
                        || image.path("sizeBytes").asLong() != pin.sizeBytes()
                        || !image.path("contentType").asText().equals(pin.contentType())
                        || pin.publicationKey() == null) throw unavailable();
                Path directory = Files.createTempDirectory("zhixiaohe-media-image-");
                Path verified = directory.resolve("verified-image");
                try {
                    objects.readVerified(image.path("objectKey").asText(), verified,
                            image.path("sizeBytes").asLong(), image.path("sha256").asText());
                    URI url = objects.publishImageCopy(pin.publicationKey(), verified, pin.contentType(),
                            pin.sizeBytes(), pin.sha256());
                    String host = url.getHost();
                    // Only this platform's public OSS endpoint, never browser-provided URLs or internal hosts.
                    URI endpoint = URI.create(assetProperties.getOss().getEndpoint());
                    String expectedHost = assetProperties.getOss().getBucket() + "." + endpoint.getHost();
                    if (!"https".equalsIgnoreCase(url.getScheme()) || host == null
                            || !host.equalsIgnoreCase(expectedHost) || !host.endsWith(".aliyuncs.com")
                            || host.contains("-internal.") || url.getUserInfo() != null || url.getFragment() != null
                            || (url.getPort() != -1 && url.getPort() != 443)) throw unavailable();
                    urls.put(image.path("assetId").asText(), url.toASCIIString());
                } finally {
                    Files.deleteIfExists(verified);
                    Files.deleteIfExists(directory);
                }
            }
            Document document = Jsoup.parseBodyFragment(article.bodyHtml());
            document.outputSettings().prettyPrint(false);
            for (Element figure : document.select("figure[data-asset-id]")) {
                String url = urls.get(figure.attr("data-asset-id"));
                if (url == null) throw unavailable();
                Element caption = figure.selectFirst("figcaption");
                Element paragraph = new Element("p");
                paragraph.appendElement("img").attr("src", url)
                        .attr("alt", caption == null ? "文章配图" : caption.text()).attr("width", "100%");
                figure.before(paragraph);
                if (caption != null && !caption.text().isBlank()) figure.before(new Element("p").text(caption.text()));
                figure.remove();
            }
            // Remove application-only identifiers, retaining actual headings, paragraphs, lists and tables.
            document.select("[data-article-block-id]").removeAttr("data-article-block-id");
            return document.body().html();
        } catch (Exception exception) {
            // No URL, object key or original exception is logged; supplier.submit has not run yet.
            throw new MediaProviderException("MEDIA_IMAGE_PREPARATION_FAILED", false);
        }
    }

    private JsonNode read(String value) {
        try { return json.readTree(value); }
        catch (Exception exception) { throw unavailable(); }
    }
    private BusinessException unavailable() { return new BusinessException(ErrorCode.MEDIA_IMAGE_UNSUPPORTED); }
}
