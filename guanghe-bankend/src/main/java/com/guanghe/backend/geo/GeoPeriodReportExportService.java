package com.guanghe.backend.geo;

import com.guanghe.backend.auth.security.AuthenticatedAccount;
import com.guanghe.backend.common.api.BusinessException;
import com.guanghe.backend.common.api.ErrorCode;
import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionURI;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDBorderStyleDictionary;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/** Offline rendering of the same immutable evidence shown by the authenticated preview. */
@Service
@ConditionalOnProperty(prefix = "zhixiaohe.paid-task", name = "enabled", havingValue = "true")
public class GeoPeriodReportExportService {
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm").withZone(ZONE);
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("MM/dd").withZone(ZONE);
    private static final Color INK = new Color(30, 42, 54);
    private static final Color MUTED = new Color(101, 116, 130);
    private static final Color ACCENT = new Color(32, 110, 117);
    private static final Color LIGHT = new Color(240, 246, 246);
    private static final Color[] SERIES = {ACCENT, new Color(62, 99, 180), new Color(167, 99, 48),
            new Color(143, 81, 158), new Color(176, 68, 81), new Color(83, 124, 58), new Color(58, 121, 166)};
    private final GeoPeriodReportService reports;

    public GeoPeriodReportExportService(GeoPeriodReportService reports) { this.reports = reports; }

    public Export export(AuthenticatedAccount account, long id) {
        var report = reports.get(account, id);
        if (!List.of("READY", "SUMMARY_UNAVAILABLE").contains(report.status()))
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "报告正在生成，请完成后再导出", Map.of());
        return new Export("geo-period-report-" + report.id() + ".pdf", "application/pdf", pdf(report));
    }

    public record Export(String filename, String contentType, byte[] bytes) { }

    static byte[] pdf(GeoPeriodReportDtos.Detail report) {
        try (PDDocument document = new PDDocument();
             InputStream input = GeoPeriodReportExportService.class.getResourceAsStream("/fonts/NotoSansSC-Regular.ttf")) {
            if (input == null) throw new IOException("Bundled font unavailable");
            document.getDocumentInformation().setTitle(report.title());
            document.getDocumentInformation().setAuthor("光禾AI智能体框架");
            document.getDocumentInformation().setSubject("客户项目周期 GEO 优化成果报告");
            try (Layout layout = new Layout(document, PDType0Font.load(document, input), report)) {
                layout.render();
            }
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            document.save(output);
            return output.toByteArray();
        } catch (IOException failure) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "报告 PDF 生成失败，请稍后重试", Map.of());
        }
    }

    private static class Layout implements AutoCloseable {
        private static final float LEFT = 46, WIDTH = 503, BOTTOM = 53;
        private final PDDocument document;
        private final PDType0Font font;
        private final GeoPeriodReportDtos.Detail report;
        private PDPage page;
        private PDPageContentStream stream;
        private float y;
        private long characters;

        Layout(PDDocument document, PDType0Font font, GeoPeriodReportDtos.Detail report) throws IOException {
            this.document = document; this.font = font; this.report = report; nextPage();
        }

        void render() throws IOException {
            text("客户交付报告 / GEO", 10, ACCENT, 8);
            text(report.title(), 24, INK, 14);
            text(report.projectName() + "  |  " + report.startDate() + " 至 " + report.endDate(), 11, MUTED, 5);
            text("数据截至 " + date(report.snapshotAt()) + "（北京时间） · 版本 " + report.version(), 9, MUTED, 15);
            text("本报告分别呈现已完成的工作与观察到的变化；前后变化不等于发文带来的因果效果。", 10, MUTED, 14);
            if (report.empty()) text("所选期间暂无可用于统计的记录。暂无数据不代表可见度为零。", 12, ACCENT, 10);
            if ("SUMMARY_UNAVAILABLE".equals(report.status()))
                text("智能总结尚未生成：以下统计和图表来自已保存的数据快照，不含模型分析。", 11, new Color(155, 91, 26), 12);
            var m = report.metrics();
            cards(List.of("监测批次", "新建文章", "已发布媒体次数", "已发布文章", "已返回回链", "待处理投稿"),
                    List.of(m.monitorRuns(), m.createdArticles(), m.publishedPlacements(), m.publishedArticles(), m.publishedLinks(), m.pendingSubmissions()));
            heading("01  本期总结");
            if (report.summaryMarkdown() == null || report.summaryMarkdown().isBlank())
                text(report.empty() ? "暂未发现期间内的监测、文章或投稿记录。可调整日期范围后重新生成报告。"
                        : "本期统计见下方工作交付与监测图表。智能总结尚不可用，可在报告页面重试。", 10.5f, INK, 10);
            else markdown(report.summaryMarkdown());
            heading("02  工作交付与样本覆盖");
            bars("文章与投稿（各项为独立口径，不相加）", List.of("新建文章", "发布媒体次数", "发布文章", "待处理投稿", "失败或退稿"),
                    List.of(m.createdArticles(), m.publishedPlacements(), m.publishedArticles(), m.pendingSubmissions(), m.failedSubmissions()));
            text("新建文章不等于完成写作或发布；同一篇文章投向多家媒体，分别统计文章数和媒体发布次数。", 9, MUTED, 9);
            bars("监测样本覆盖", List.of("有效样本", "失败样本", "缺失样本"),
                    List.of(m.validSamples(), m.failedSamples(), m.missingSamples()));
            text("监测批次：完成 " + m.completedMonitorRuns() + " / 部分完成 " + m.partialMonitorRuns()
                    + " / 失败 " + m.failedMonitorRuns() + " / 进行中 " + m.runningMonitorRuns()
                    + "。计划样本 " + m.expectedSamples() + "；失败和缺失不按未提及计入可见度。", 9, MUTED, 10);
            // Keep the section heading with its first topic and chart, not alone at a page foot.
            ensure(report.topics().isEmpty() ? 90 : 385);
            heading("03  AI 平台可见度变化");
            if (report.topics().isEmpty()) text("所选范围内没有可展示的监测趋势。", 10.5f, MUTED, 12);
            for (var topic : report.topics()) {
                ensure(105);
                text(topic.topicName(), 14, INK, 8);
                text("口径：自然发现问题中的品牌名称提及率。只比较符合条件的共同样本，不代表推荐排名或市场份额。", 9, MUTED, 8);
                trend(topic);
                for (var p : topic.platforms()) {
                    ensure(94);
                    text(platformName(p.platformCode()) + "  |  基线 " + percent(p.baselineMentionRate())
                            + "  →  期末 " + percent(p.endMentionRate())
                            + "  |  变化 " + delta(p.deltaPercentagePoints()), 10.5f, INK, 4);
                    text("共同有效样本 " + p.commonSamples() + "；" + comparisonReason(p.comparisonStatus(), p.comparisonReason()), 9, MUTED, 3);
                    text("基线 " + date(p.baselineObservedAt()) + "（报告 " + safe(p.baselineReportId()) + "） / 期末 "
                            + date(p.endObservedAt()) + "（报告 " + safe(p.endReportId()) + "）", 8, MUTED, 8);
                }
            }
            heading("04  内容与发布记录");
            text("本期新建文章", 12, ACCENT, 8);
            if (report.articles().isEmpty()) text("本期没有新建文章记录。", 10, MUTED, 7);
            for (var article : report.articles()) {
                ensure(65);
                text(article.title(), 10.5f, INK, 2);
                text("创建 " + date(article.createdAt()) + " · 内容版本 " + article.version() + " · 文章 " + article.id(), 8.5f, MUTED, 8);
            }
            text("媒体投稿与回链", 12, ACCENT, 8);
            if (report.publications().isEmpty()) text("本期没有投稿或确认发布记录。", 10, MUTED, 7);
            for (var publication : report.publications()) {
                ensure(82);
                text(publication.articleTitle(), 11, INK, 2);
                text(publication.mediaName() + " · " + status(publication.status())
                        + " · 投稿 " + date(publication.createdAt()), 9, MUTED, 2);
                if (publication.confirmedPublishedAt() != null)
                    text("确认发布 " + date(publication.confirmedPublishedAt()) + "（系统首次确认时间）", 9, MUTED, 2);
                if (publication.publishedUrls().isEmpty())
                    text("PUBLISHED".equals(publication.status()) ? "已发布，上游暂未提供发布回链。" : "尚无已确认发布回链。", 9, MUTED, 7);
                else for (String url : publication.publishedUrls()) link(url);
                y -= 4;
            }
            heading("05  统计口径与证据限制");
            for (String limitation : report.limitations()) text("• " + limitation, 9.5f, MUTED, 5);
            text("日期按北京时间包含起止自然日；今天的报告是截至生成时刻的阶段快照。发布回链是上游返回的媒体页面，不代表已独立验证其永久有效。", 9.5f, MUTED, 5);
            text("本文件不含代理采购成本及内部利润。下载只使用保存的报告，不启动监测、生成文章或投稿。", 9.5f, MUTED, 5);
            text("报告编号 " + report.id() + " · 数据快照 " + date(report.snapshotAt()), 8, MUTED, 0);
        }

        private void nextPage() throws IOException {
            if (stream != null) stream.close();
            if (document.getNumberOfPages() >= 120) throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "报告内容过长，请缩小日期范围后导出", Map.of());
            page = new PDPage(PDRectangle.A4); document.addPage(page);
            stream = new PDPageContentStream(document, page); y = 779;
            write("光禾AI智能体框架  /  GEO 优化成果", LEFT, 809, 8.5f, ACCENT);
            line(LEFT, 798, LEFT + WIDTH, 798, new Color(220, 229, 232), .6f);
            write("客户周期报告 · 北京时间", LEFT, 29, 8, MUTED);
            write("第 " + document.getNumberOfPages() + " 页", 496, 29, 8, MUTED);
        }
        private void ensure(float height) throws IOException { if (y - height < BOTTOM) nextPage(); }
        private void heading(String heading) throws IOException {
            ensure(90); y -= 13; text(heading, 15, ACCENT, 13);
        }
        private void text(String value, float size, Color color, float after) throws IOException {
            String source = value == null ? "" : value;
            characters += source.length();
            if (characters > 600_000) throw new BusinessException(ErrorCode.VALIDATION_FAILED);
            for (String row : wrap(source, size, WIDTH)) {
                ensure(size * 1.65f); write(row, LEFT, y, size, color); y -= size * 1.65f;
            }
            y -= after;
        }
        private void markdown(String markdown) throws IOException {
            for (String paragraph : markdown.replace("\r", "").split("\n")) {
                String stripped = paragraph.strip();
                if (stripped.isEmpty()) { y -= 5; continue; }
                boolean heading = stripped.startsWith("#");
                stripped = stripped.replaceFirst("^#{1,6}\\s*", "").replace("**", "").replace("`", "");
                if (heading) ensure(65);
                text(stripped, heading ? 12 : 10.5f, heading ? ACCENT : INK, heading ? 6 : 4);
            }
        }
        private void cards(List<String> labels, List<Long> values) throws IOException {
            ensure(146);
            float w = (WIDTH - 20) / 3;
            for (int i = 0; i < labels.size(); i++) {
                float x = LEFT + i % 3 * (w + 10), top = y - (i / 3) * 69;
                rectangle(x, top - 59, w, 59, LIGHT);
                write(labels.get(i), x + 12, top - 17, 9, MUTED);
                write(Long.toString(values.get(i)), x + 12, top - 43, 21, ACCENT);
            }
            y -= 145;
        }
        private void bars(String title, List<String> labels, List<Long> values) throws IOException {
            float height = 40 + labels.size() * 28;
            ensure(height); text(title, 11.5f, INK, 6);
            long max = Math.max(1, values.stream().mapToLong(Long::longValue).max().orElse(1));
            for (int i = 0; i < labels.size(); i++) {
                write(labels.get(i), LEFT, y - 1, 9, MUTED);
                rectangle(LEFT + 108, y - 6, 330, 13, LIGHT);
                float width = 330f * values.get(i) / max;
                if (width > 0) rectangle(LEFT + 108, y - 6, width, 13, i == 0 ? ACCENT : SERIES[(i + 1) % SERIES.length]);
                write(Long.toString(values.get(i)), LEFT + 450, y - 1, 9, INK); y -= 28;
            }
            y -= 5;
        }
        private void trend(GeoPeriodReportDtos.Topic topic) throws IOException {
            List<GeoPeriodReportDtos.Point> points = topic.platforms().stream().flatMap(p -> p.points().stream()).toList();
            if (points.isEmpty()) { text("暂无趋势采样点。", 10, MUTED, 9); return; }
            long min = points.stream().mapToLong(p -> p.observedAt().toInstant().toEpochMilli()).min().orElse(0);
            long max = points.stream().mapToLong(p -> p.observedAt().toInstant().toEpochMilli()).max().orElse(min);
            float legendHeight = ((topic.platforms().size() + 3) / 4) * 18;
            ensure(212 + legendHeight);
            float left = LEFT + 36, bottom = y - 148, width = WIDTH - 50, height = 128;
            for (int tick = 0; tick <= 4; tick++) {
                float at = bottom + tick * height / 4;
                line(left, at, left + width, at, new Color(225, 232, 235), .5f);
                write(tick * 25 + "%", LEFT, at - 3, 8, MUTED);
            }
            for (int s = 0; s < topic.platforms().size(); s++) {
                var platform = topic.platforms().get(s); Color color = SERIES[s % SERIES.length];
                GeoPeriodReportDtos.Point previous = null;
                for (var p : platform.points()) {
                    if (p.mentionRate() == null || !Double.isFinite(p.mentionRate())) { previous = null; continue; }
                    float x = atX(p.observedAt(), min, max, left, width), at = bottom + bounded(p.mentionRate()) * height / 100;
                    if (previous != null && p.connectToPrevious())
                        line(atX(previous.observedAt(), min, max, left, width), bottom + bounded(previous.mentionRate()) * height / 100, x, at, color, 1.5f);
                    rectangle(x - 2, at - 2, 4, 4, color); previous = p;
                }
                float legendX = LEFT + s % 4 * 125, legendY = bottom - 36 - (s / 4) * 18;
                rectangle(legendX, legendY, 8, 8, color); write(platformName(platform.platformCode()), legendX + 13, legendY, 8.5f, MUTED);
            }
            write(DAY.format(java.time.Instant.ofEpochMilli(min)), left, bottom - 16, 8, MUTED);
            if (max != min) write(DAY.format(java.time.Instant.ofEpochMilli(max)), left + width - 30, bottom - 16, 8, MUTED);
            y = bottom - 45 - legendHeight;
            text("仅连接口径可比的采样点；空缺或断线表示数据缺失或不可比，不按零值补齐。", 8.5f, MUTED, 10);
        }
        private float atX(OffsetDateTime at, long min, long max, float left, float width) {
            return max == min ? left + width / 2 : left + width * (at.toInstant().toEpochMilli() - min) / (max - min);
        }
        private void link(String url) throws IOException {
            if (!safeUrl(url)) { text("回链格式不可用，请在投稿记录中核对。", 9, MUTED, 4); return; }
            for (String row : wrap(url, 8.5f, WIDTH)) {
                ensure(16); write(row, LEFT, y, 8.5f, ACCENT);
                PDAnnotationLink link = new PDAnnotationLink();
                link.setRectangle(new PDRectangle(LEFT, y - 2, Math.min(WIDTH, font.getStringWidth(row) / 1000 * 8.5f), 13));
                PDBorderStyleDictionary border = new PDBorderStyleDictionary(); border.setWidth(0); link.setBorderStyle(border);
                PDActionURI action = new PDActionURI(); action.setURI(url); link.setAction(action);
                page.getAnnotations().add(link); y -= 16;
            }
            y -= 4;
        }
        private List<String> wrap(String text, float size, float maxWidth) throws IOException {
            List<String> result = new ArrayList<>();
            for (String paragraph : text.replace("\r", "").split("\n", -1)) {
                StringBuilder pending = new StringBuilder(); float width = 0;
                for (int codepoint : paragraph.codePoints().toArray()) {
                    if (Character.isISOControl(codepoint)) continue;
                    String symbol = glyph(new String(Character.toChars(codepoint)));
                    float w = font.getStringWidth(symbol) / 1000 * size;
                    if (width + w > maxWidth && !pending.isEmpty()) { result.add(pending.toString()); pending.setLength(0); width = 0; }
                    pending.append(symbol); width += w;
                }
                result.add(pending.toString());
            }
            return result;
        }
        private String glyph(String text) throws IOException {
            try { font.encode(text); return text; } catch (IllegalArgumentException absent) { return "?"; }
        }
        private void write(String text, float x, float y, float size, Color color) throws IOException {
            StringBuilder safe = new StringBuilder();
            for (int c : text.codePoints().toArray()) if (!Character.isISOControl(c)) safe.append(glyph(new String(Character.toChars(c))));
            stream.beginText(); stream.setNonStrokingColor(color); stream.setFont(font, size);
            stream.newLineAtOffset(x, y); stream.showText(safe.toString()); stream.endText();
        }
        private void line(float x1, float y1, float x2, float y2, Color color, float width) throws IOException {
            stream.setStrokingColor(color); stream.setLineWidth(width); stream.moveTo(x1, y1); stream.lineTo(x2, y2); stream.stroke();
        }
        private void rectangle(float x, float y, float w, float h, Color color) throws IOException {
            stream.setNonStrokingColor(color); stream.addRect(x, y, w, h); stream.fill();
        }
        @Override public void close() throws IOException { if (stream != null) stream.close(); }
    }

    private static float bounded(Double value) { return (float) Math.max(0, Math.min(100, value)); }
    private static String safe(String value) { return value == null || value.isBlank() ? "暂无" : value; }
    private static String date(OffsetDateTime value) { return value == null ? "暂无" : TIME.format(value); }
    private static String percent(Double value) { return value == null ? "不可评估" : String.format(Locale.ROOT, "%.1f%%", value); }
    private static String delta(Double value) { return value == null ? "不可比较" : String.format(Locale.ROOT, "%+.1f 个百分点", value); }
    private static String comparisonReason(String status, String reason) {
        if ("COMPARABLE".equals(status)) return "同口径可比，仅比较共同有效样本";
        if (reason == null) return "暂无可比证据";
        return switch (reason) {
            case "BASELINE_UNAVAILABLE" -> "缺少有效基线";
            case "BASELINE_ONLY" -> "仅有一个有效监测时点";
            case "TARGET_CHANGED" -> "监测品牌或商品已变化";
            case "REGION_CHANGED" -> "监测地区已变化";
            case "SAMPLING_CHANGED" -> "模型或采样方式已变化";
            case "PLATFORM_NOT_SAMPLED" -> "前后时点未同时监测该平台";
            case "MISSING_SAMPLING_METADATA" -> "缺少采样口径记录";
            case "MISSING_QUESTION_METADATA" -> "缺少问题版本记录";
            case "NO_COMMON_SAMPLES" -> "没有共同有效样本";
            case "NO_COMMON_VALID_SAMPLES" -> "没有共同有效样本";
            case "PERIOD_TRUNCATED" -> "批次超出展示上限，不计算整个周期的首尾变化";
            case "EVIDENCE_LIMIT" -> "证据量超过本次分析上限";
            default -> "缺少同口径的有效证据";
        };
    }
    private static boolean safeUrl(String value) {
        try { URI uri = URI.create(value); return uri.getHost() != null && uri.getUserInfo() == null
                && List.of("https", "http").contains(uri.getScheme().toLowerCase(Locale.ROOT)); }
        catch (RuntimeException bad) { return false; }
    }
    private static String platformName(String code) {
        return switch (code) { case "DOUBAO" -> "豆包"; case "KIMI" -> "Kimi"; case "QWEN" -> "千问";
            case "WENXIN" -> "文心一言"; case "YUANBAO" -> "元宝"; case "ZHIPU" -> "智谱"; case "DEEPSEEK" -> "DeepSeek"; default -> code; };
    }
    private static String status(String value) {
        return switch (value) { case "PUBLISHED" -> "已发布"; case "REJECTED" -> "已退稿"; case "FAILED" -> "失败";
            case "SUBMIT_UNKNOWN", "RECONCILING" -> "结果核对中"; case "ACCEPTED" -> "已受理"; default -> "处理中"; };
    }
}
