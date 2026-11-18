package com.guanghe.backend.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.guanghe.backend.billing.notification.NotificationService;
import java.time.OffsetDateTime;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class TaskNotificationServiceTests {
    private TaskControlMapper tasks;
    private NotificationService inbox;
    private TaskNotificationService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        tasks = mock(TaskControlMapper.class);
        inbox = mock(NotificationService.class);
        ObjectProvider<TaskControlMapper> provider = mock(ObjectProvider.class);
        when(provider.getObject()).thenReturn(tasks);
        service = new TaskNotificationService(provider, inbox, new ObjectMapper());
    }

    @ParameterizedTest
    @CsvSource({"IMAGE,图片生成", "VIDEO,视频生成", "GEO,GEO 监测", "MEDIA,媒体投稿"})
    void announcesPersistedSuccessAndLinksToTaskWhenNoResultReferenceExists(String type, String name) {
        persisted(type, "SUCCEEDED", "{}");
        service.publishTerminal(91, 41);
        verify(inbox).publish(eq(41L), eq("SYSTEM"), eq(name + "已完成"),
                anyString(), eq("TASK"), eq(91L), eq("TASK_TERMINAL:91"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"QUEUED", "RUNNING", "RETRY_WAIT"})
    void intermediateProviderSuccessOrRetryIsNotAnInboxCompletion(String status) {
        persisted("IMAGE", status, "{\"asset\":{\"assetId\":\"92\"}}");
        service.publishTerminal(91, 41);
        verifyNoInteractions(inbox);
    }

    @Test
    void partialGeoReportLinksToSavedEvidenceWithoutClaimingFullSuccess() {
        persisted("GEO", "PARTIAL", "{\"reportId\":\"92\",\"providerUrl\":\"secret\"}");
        service.publishTerminal(91, 41);
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(inbox).publish(eq(41L), eq("SYSTEM"), eq("GEO 监测部分完成"),
                body.capture(), eq("GEO_REPORT"), eq(92L), eq("TASK_TERMINAL:91"));
        assertThat(body.getValue()).contains("未完成项").doesNotContain("secret", "providerUrl");
    }

    @Test
    void articleTitleStageDoesNotClaimTheWholeArticleIsFinished() {
        persisted("ARTICLE", "SUCCEEDED", "{\"articleId\":\"92\",\"mode\":\"TITLE\"}");
        service.publishTerminal(91, 41);
        verify(inbox).publish(eq(41L), eq("SYSTEM"), eq("文章标题生成已完成"),
                anyString(), eq("ARTICLE"), eq(92L), eq("TASK_TERMINAL:91"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"FAILED", "SYSTEM_TERMINATED", "SKIPPED"})
    void unsuccessfulTaskLinksToItsDiagnosticPageNotAnOldResult(String status) {
        persisted("GEO", status, "{\"reportId\":\"92\"}");
        service.publishTerminal(91, 41);
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(inbox).publish(eq(41L), eq("SYSTEM"), anyString(), body.capture(),
                eq("TASK"), eq(91L), eq("TASK_TERMINAL:91"));
        assertThat(body.getValue()).contains("不会自动创建新的付费任务");
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "null", "not-json", "{\"reportId\":\"https://invalid\"}",
            "{\"reportId\":\"9999999999999999999\"}", "{\"reportId\":\"-1\"}"})
    void invalidOrMissingLegacyReferenceKeepsTheSafeTaskLink(String result) {
        persisted("GEO", "SUCCEEDED", result);
        service.publishTerminal(91, 41);
        verify(inbox).publish(eq(41L), eq("SYSTEM"), anyString(), anyString(),
                eq("TASK"), eq(91L), eq("TASK_TERMINAL:91"));
    }

    @Test
    void unknownOrForeignTaskCannotEmitNotification() {
        when(tasks.findTask(91, 41)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.publishTerminal(91, 41)).isInstanceOf(java.util.NoSuchElementException.class);
        verifyNoInteractions(inbox);
    }

    @Test
    void notificationWriteFailurePropagatesSoBusinessTransactionCannotSilentlyCommit() throws Exception {
        persisted("IMAGE", "SUCCEEDED", "{}");
        doThrow(new IllegalStateException("store unavailable")).when(inbox).publish(
                eq(41L), anyString(), anyString(), anyString(), anyString(), eq(91L), anyString());
        assertThatThrownBy(() -> service.publishTerminal(91, 41)).isInstanceOf(IllegalStateException.class);
        assertThat(TaskNotificationService.class.getMethod("publishTerminal", long.class, long.class)
                .getAnnotation(Transactional.class).propagation()).isEqualTo(Propagation.MANDATORY);
    }

    private void persisted(String type, String status, String result) {
        OffsetDateTime now = OffsetDateTime.parse("2026-09-22T00:00:00Z");
        when(tasks.findTask(91, 41)).thenReturn(Optional.of(new TaskRecords.Task(
                91, 41, 101, null, 901, 801, type, status, "COMPLETED", 1, "{}", "hash",
                1, 7, 7, result, null, "TASK_CONFIRM", null, "hash", now, now, now, now, now)));
    }
}
