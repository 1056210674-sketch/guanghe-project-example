package com.guanghe.backend.geo;

import com.guanghe.backend.auth.security.AuthenticatedAccount;
import com.guanghe.backend.common.api.BusinessException;
import com.guanghe.backend.common.api.ErrorCode;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
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
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/** Pure, offline formatting of one authorized persisted report snapshot. Never fetches URLs. */
@Service
@ConditionalOnProperty(prefix = "zhixiaohe.paid-task", name = "enabled", havingValue = "true")
public class GeoReportExportService {
    private static final int MAX_CHARACTERS = 2_000_000;
    private static final int MAX_PAGES = 600;
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss")
            .withZone(ZoneId.of("Asia/Shanghai"));
    private final GeoTaskService reports;

    public GeoReportExportService(GeoTaskService reports) { this.reports = reports; }

    public Export export(AuthenticatedAccount account, long reportId, String format) {
        if (!List.of("md", "pdf").contains(format)) throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        GeoTaskDtos.ReportDetail report = reports.report(account, reportId);
        List<Block> blocks = blocks(report);
        String markdown = markdown(blocks);
        return new Export("geo-report-" + report.id() + "." + format,
                "md".equals(format) ? "text/markdown;charset=UTF-8" : "application/pdf",
                "md".equals(format) ? markdown.getBytes(StandardCharsets.UTF_8) : pdf(blocks));
    }

    public record Export(String filename, String contentType, byte[] bytes) { }
    record Block(int level, String text) { }

    static List<Block> blocks(GeoTaskDtos.ReportDetail report) {
        List<Block> result = new ArrayList<>();
        result.add(new Block(1, report.topic().name() + " - GEO 监测报告"));
        result.add(new Block(0, "报告编号：" + report.id() + "  |  监测时间："
                + DATE.format(report.generatedAt()) + "（北京时间）"));
        result.add(new Block(0, "本文件仅描述本次已保存的回答样本。不代表市场份额、推荐排名或优化效果。"));
        result.add(new Block(2, "各平台 AI 可见度"));
        GeoTaskDtos.VisibilityResponse visibility = report.visibility();
        if (visibility == null || visibility.target() == null) {
            result.add(new Block(0, "目标尚不可识别，暂不评估可见度。"));
        } else {
            result.add(new Block(0, "监测目标：" + visibility.target().name()));
            for (GeoTaskDtos.VisibilityPlatform platform : visibility.platforms()) {
                String rate = platform.mentionRate() == null ? "不可评估"
                        : String.format(Locale.ROOT, "%.1f%%", platform.mentionRate());
                result.add(new Block(0, platformName(platform.platformCode()) + "：" + rate
                        + "  |  提及 " + value(platform.mentionedSamples()) + " / 有效 " + platform.validSamples()
                        + "  |  失败 " + platform.failedSamples() + " / 缺失 " + platform.missingSamples()));
            }
        }
        result.add(new Block(0, "名称提及率以有效回答为分母。失败、缺失与不可识别样本不计为未提及。"));
        result.add(new Block(2, "监测结论与下一步"));
        boolean interpreted = !"geo-deterministic-v1".equals(report.methodVersion());
        if (interpreted && report.synthesis() != null) {
            result.add(new Block(0, report.synthesis().summary()));
            section(result, "回答中的观察", report.synthesis().findings());
            section(result, "采样与证据限制", report.synthesis().coverageGaps());
            section(result, "竞品提及信号（非市场份额）", report.synthesis().competitorSignals());
            if (!report.synthesis().recommendations().isEmpty()) {
                result.add(new Block(2, "优化建议"));
                for (GeoTaskDtos.RecommendationResponse recommendation : report.synthesis().recommendations()) {
                    result.add(new Block(3, recommendation.title()));
                    result.add(new Block(0, recommendation.rationale()));
                }
            }
            section(result, "内容准备方向", report.synthesis().contentBlueprint());
        } else {
            result.add(new Block(0, "智能解读尚未完成；已取得的回答和目标提及结果仍可查看。"
                    + "这不代表目标未被提及。"));
        }
        result.add(new Block(2, "样本范围"));
        result.add(new Block(0, "计划 " + report.expectedUnits() + "；有效 " + report.succeededUnits()
                + "；失败 " + report.failedUnits() + "；缺失 " + report.missingUnits()));
        result.add(new Block(2, "平台回答与来源证据"));
        for (GeoTaskDtos.ObservationResponse observation : report.observations()) {
            result.add(new Block(3, platformName(observation.platformCode()) + " - " + observation.questionText()));
            result.add(new Block(0, "采样轮次：" + observation.sampleNo() + "  |  "
                    + ("SUCCEEDED".equals(observation.status()) ? "已取得回答" : "采集失败，不能评估提及")));
            if ("SUCCEEDED".equals(observation.status())) {
                result.add(new Block(0, observation.answerText()));
                if (observation.citations().isEmpty()) result.add(new Block(0, "没有可核验的引用来源。"));
                for (GeoTaskDtos.CitationResponse citation : observation.citations()) {
                    result.add(new Block(0, "来源 " + citation.sequence() + "：" + value(citation.title())
                            + "\n" + value(citation.url()) + "\n" + value(citation.snippet())));
                }
            } else {
                result.add(new Block(0, "未取得有效回答；不将该样本计为未提及。"));
            }
        }
        result.add(new Block(2, "方法与费用"));
        result.add(new Block(0, "方法版本：" + report.methodVersion() + "；本次结算 " + report.settledPoints()
                + " 积分，释放 " + report.releasedPoints() + " 积分。导出不重新执行监测或模型解读。"));
        long characters = result.stream().mapToLong(block -> value(block.text()).length()).sum();
        if (characters > MAX_CHARACTERS) throw tooLarge();
        return List.copyOf(result);
    }

    private static void section(List<Block> blocks, String heading, List<String> items) {
        if (items == null || items.isEmpty()) return;
        blocks.add(new Block(2, heading));
        items.forEach(item -> blocks.add(new Block(0, "- " + item)));
    }

    static String markdown(List<Block> blocks) {
        StringBuilder result = new StringBuilder();
        for (Block block : blocks) {
            if (block.level() > 0) result.append("#".repeat(block.level())).append(' ');
            result.append(value(block.text()).replace("\r", "")).append("\n\n");
        }
        return result.toString();
    }

    static byte[] pdf(List<Block> blocks) {
        try (PDDocument document = new PDDocument();
             InputStream fontInput = GeoReportExportService.class.getResourceAsStream("/fonts/NotoSansSC-Regular.ttf")) {
            if (fontInput == null) throw new IOException("Bundled font unavailable");
            PDType0Font font = PDType0Font.load(document, fontInput);
            try (Layout layout = new Layout(document, font)) {
                for (Block block : blocks) layout.block(block);
            }
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            document.save(output);
            return output.toByteArray();
        } catch (IOException exception) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "报告导出暂时不可用，请稍后重试", Map.of());
        }
    }

    private static class Layout implements AutoCloseable {
        private final PDDocument document;
        private final PDType0Font font;
        private PDPageContentStream stream;
        private float y;
        Layout(PDDocument document, PDType0Font font) throws IOException {
            this.document = document; this.font = font; page();
        }
        private void page() throws IOException {
            if (stream != null) stream.close();
            if (document.getNumberOfPages() >= MAX_PAGES) throw tooLarge();
            PDPage page = new PDPage(PDRectangle.A4);
            document.addPage(page);
            stream = new PDPageContentStream(document, page);
            y = 785;
            line("光禾AI智能体框架 · GEO 监测报告", 9, 812);
            line("第 " + document.getNumberOfPages() + " 页", 9, 27);
        }
        void block(Block block) throws IOException {
            float size = switch (block.level()) { case 1 -> 19; case 2 -> 14; case 3 -> 11.5f; default -> 10.5f; };
            float leading = size * 1.65f;
            if (block.level() > 0 && y < 110) page();
            for (String paragraph : value(block.text()).replace("\r", "").split("\n", -1)) {
                StringBuilder pending = new StringBuilder();
                float width = 0;
                for (int codepoint : paragraph.codePoints().toArray()) {
                    if (Character.isISOControl(codepoint)) continue;
                    String symbol = new String(Character.toChars(codepoint));
                    try { font.encode(symbol); } catch (IllegalArgumentException invalidGlyph) { symbol = "(?)"; }
                    float characterWidth = font.getStringWidth(symbol) / 1000 * size;
                    if (width + characterWidth > 495 && !pending.isEmpty()) {
                        renderLine(pending.toString(), size, leading); pending.setLength(0); width = 0;
                    }
                    pending.append(symbol); width += characterWidth;
                }
                renderLine(pending.toString(), size, leading);
            }
            y -= block.level() > 0 ? 8 : 5;
        }
        private void renderLine(String text, float size, float leading) throws IOException {
            if (y - leading < 48) page();
            line(text, size, y); y -= leading;
        }
        private void line(String text, float size, float at) throws IOException {
            stream.beginText(); stream.setFont(font, size); stream.newLineAtOffset(50, at);
            stream.showText(text); stream.endText();
        }
        @Override public void close() throws IOException { if (stream != null) stream.close(); }
    }

    private static BusinessException tooLarge() {
        return new BusinessException(ErrorCode.VALIDATION_FAILED,
                "该报告内容超过导出上限，请减少监测范围或分批导出", Map.of("reason", "REPORT_EXPORT_LIMIT"));
    }
    private static String value(Object value) { return value == null ? "-" : value.toString(); }
    private static String platformName(String code) {
        return switch (code) {
            case "DOUBAO" -> "豆包"; case "QWEN" -> "通义千问"; case "WENXIN" -> "文心一言";
            case "YUANBAO" -> "腾讯元宝"; case "ZHIPU" -> "智谱"; case "KIMI" -> "Kimi";
            default -> "DeepSeek";
        };
    }
}
