package com.guanghe.backend.geo;

import com.guanghe.backend.auth.security.AuthenticatedAccount;
import com.guanghe.backend.common.api.BusinessException;
import com.guanghe.backend.common.api.ErrorCode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.List;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class GeoReportExportServiceTests {
    @Test
    void ownerFailureIsNotAnExportBypassAndUnsupportedFormatDoesNotReadData() {
        GeoTaskService source = mock(GeoTaskService.class);
        GeoReportExportService exporter = new GeoReportExportService(source);
        AuthenticatedAccount account = mock(AuthenticatedAccount.class);
        when(source.report(account, 41)).thenThrow(new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        assertThatThrownBy(() -> exporter.export(account, 41, "pdf")).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> exporter.export(account, 41, "html")).isInstanceOf(BusinessException.class);
        verify(source, times(1)).report(account, 41);
        verifyNoMoreInteractions(source);
    }

    @Test
    void deterministicReportNeverExportsInventedInterpretationAndUnknownVisibilityIsNotZero() {
        GeoTaskDtos.ReportDetail report = base();
        when(report.methodVersion()).thenReturn("geo-deterministic-v1");
        GeoTaskDtos.SynthesisResponse synthesis = mock(GeoTaskDtos.SynthesisResponse.class);
        when(synthesis.summary()).thenReturn("无证据但假装已优化");
        when(report.synthesis()).thenReturn(synthesis);
        when(report.visibility()).thenReturn(new GeoTaskDtos.VisibilityResponse("v1", "READY", null,
                new GeoTaskDtos.VisibilityTarget("BRAND", "91", "小行星咖啡店", List.of()),
                List.of(new GeoTaskDtos.VisibilityPlatform("DOUBAO", 3, 0, null, 3, 0, null)), List.of()));
        String md = GeoReportExportService.markdown(GeoReportExportService.blocks(report));
        assertThat(md).contains("豆包：不可评估", "智能解读尚未完成", "不计为未提及")
                .doesNotContain("无证据但假装已优化", "0.0%");
    }

    @Test
    void chinesePdfEmbedsFontWrapsLongUrlsAndPaginatesWithoutActiveContent() throws Exception {
        List<GeoReportExportService.Block> blocks = List.of(
                new GeoReportExportService.Block(1, "小行星咖啡店 - GEO 监测报告"),
                new GeoReportExportService.Block(0, "咖啡、曲奇与蛋糕。😀"),
                new GeoReportExportService.Block(2, "真实来源与回答"),
                new GeoReportExportService.Block(0, ("只有已核对的事实才能发布。https://example.invalid/"
                        + "long-source".repeat(20) + "\n").repeat(25)));
        byte[] pdf = GeoReportExportService.pdf(blocks);
        try (var document = Loader.loadPDF(pdf)) {
            assertThat(document.getNumberOfPages()).isGreaterThan(1);
            assertThat(document.getDocumentCatalog().getOpenAction()).isNull();
            String text = new PDFTextStripper().getText(document);
            assertThat(text).contains("小行星咖啡店", "咖啡、曲奇与蛋糕", "真实来源与回答", "第 2 页");
            assertThat(document.getPage(0).getResources().getFontNames()).isNotEmpty();
        }
        // Representative QA artifact is outside source and ignored; rendered separately before release.
        Path target = Path.of("target/pdf-qa/geo-report-chinese.pdf");
        Files.createDirectories(target.getParent()); Files.write(target, pdf);
    }

    private GeoTaskDtos.ReportDetail base() {
        GeoTaskDtos.ReportDetail report = mock(GeoTaskDtos.ReportDetail.class);
        when(report.id()).thenReturn("41");
        when(report.topic()).thenReturn(new GeoTaskDtos.TopicSnapshot("2", "3", null, "小行星咖啡店", "广州", 1));
        when(report.generatedAt()).thenReturn(OffsetDateTime.parse("2026-10-07T03:00:00Z"));
        when(report.observations()).thenReturn(List.of());
        return report;
    }
}
