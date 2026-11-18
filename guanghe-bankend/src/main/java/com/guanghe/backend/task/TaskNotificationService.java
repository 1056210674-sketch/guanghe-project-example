package com.guanghe.backend.task;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.guanghe.backend.billing.notification.NotificationService;
import java.util.Set;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** One durable inbox entry for a settled task, shared by all four completion paths. */
@Service
public class TaskNotificationService {
    private static final Set<String> TERMINAL = Set.of(
            "SUCCEEDED", "PARTIAL", "FAILED", "SKIPPED", "SYSTEM_TERMINATED");
    private final ObjectProvider<TaskControlMapper> tasks;
    private final NotificationService notifications;
    private final ObjectMapper json;

    public TaskNotificationService(ObjectProvider<TaskControlMapper> tasks,
                                   NotificationService notifications, ObjectMapper json) {
        this.tasks = tasks;
        this.notifications = notifications;
        this.json = json;
    }

    /**
     * Called after settlement, inside the same transaction. Read back the persisted state instead
     * of trusting the pre-update task or a Worker response. Inbox failure rolls back settlement;
     * the existing completion retry and inbox unique key then recover without duplicate notices.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void publishTerminal(long taskId, long userId) {
        TaskRecords.Task task = tasks.getObject().findTask(taskId, userId).orElseThrow();
        if (!TERMINAL.contains(task.status())) return;
        JsonNode result = result(task.resultSummaryJson());
        if("GEO_DIAGNOSTIC_SUBMIT".equals(task.operationScope())) {
            if(!result.path("diagnosticReportReady").asBoolean())return;
            publishDiagnosticDelivery(task.id(),task.userId(),positiveId(result.path("diagnosticId")),result.path("hasNoData").asBoolean());
            return;
        }
        String name = switch (task.taskType()) {
            case "IMAGE" -> "图片生成";
            case "VIDEO" -> "视频生成";
            case "GEO" -> "GEO 监测";
            case "MEDIA" -> "媒体投稿";
            case "ARTICLE" -> switch (result.path("mode").asText()) {
                case "TITLE" -> "文章标题生成";
                case "OUTLINE" -> "文章提纲生成";
                case "BODY" -> "文章正文生成";
                case "REWRITE" -> "文章改写";
                default -> "文章生成阶段";
            };
            default -> "任务";
        };
        String outcome = switch (task.status()) {
            case "SUCCEEDED" -> "已完成";
            case "PARTIAL" -> "部分完成";
            case "SKIPPED" -> "未执行";
            case "SYSTEM_TERMINATED" -> "已终止";
            default -> "未成功";
        };
        String body = "任务 " + task.id() + " 的" + name + outcome + "。"
                + switch (task.status()) {
                    case "SUCCEEDED" -> "结果已保存，可查看结果并继续后续操作。";
                    case "PARTIAL" -> "已有部分结果保存，请查看已完成内容、未完成项和积分结算。";
                    default -> "请查看任务详情中的处理结果和积分结算。不会自动创建新的付费任务。";
                };
        String targetType = "TASK";
        long targetId = task.id();
        if ("SUCCEEDED".equals(task.status()) || "PARTIAL".equals(task.status())) {
            Long resultId = positiveId(result.path("GEO".equals(task.taskType()) ? "reportId" : "articleId"));
            if (resultId != null && Set.of("GEO", "ARTICLE").contains(task.taskType())) {
                targetType = "GEO".equals(task.taskType()) ? "GEO_REPORT" : "ARTICLE";
                targetId = resultId;
            }
        }
        notifications.publish(task.userId(), NotificationService.CATEGORY_SYSTEM, name + outcome,
                body, targetType, targetId, "TASK_TERMINAL:" + task.id());
    }

    @Transactional(propagation=Propagation.MANDATORY)
    public void publishDiagnosticDelivery(long taskId,long userId,Long diagnosticId,boolean monitoringFailed) {
        TaskRecords.Task task=tasks.getObject().findTask(taskId,userId).orElseThrow();
        if(!"GEO_DIAGNOSTIC_SUBMIT".equals(task.operationScope())||diagnosticId==null)throw new IllegalArgumentException("Invalid diagnostic notification identity");
        notifications.publish(userId,NotificationService.CATEGORY_SYSTEM,monitoringFailed?"诊断采集未完成，说明 PDF 已生成":"GEO 诊断报告已生成",
            monitoringFailed?"监测未获得完整有效结果，已保存实际采集记录和说明 PDF。失败或缺失不代表可见度为零；不会自动重新监测或再次扣费。"
                :"诊断分析与 PDF 已保存，可回到诊断卡片查看报告、完整采样原文和下载 PDF。", "TASK",taskId,"DIAGNOSTIC_READY:"+taskId);
    }

    @Transactional(propagation=Propagation.MANDATORY)
    public void publishDiagnosticFailure(long taskId,long userId,long diagnosticId) {
        TaskRecords.Task task=tasks.getObject().findTask(taskId,userId).orElseThrow();
        if(!"GEO_DIAGNOSTIC_SUBMIT".equals(task.operationScope())||!"FAILED".equals(task.status()))throw new IllegalArgumentException("Invalid diagnostic failure identity");
        notifications.publish(userId,NotificationService.CATEGORY_SYSTEM,"诊断报告交付未完成",
            "诊断报告恢复次数已用完。实际采样原文和已结算记录仍可查看；不会重新监测、重复扣费或自动创建新任务。", "TASK",taskId,"DIAGNOSTIC_DELIVERY_FAILED:"+taskId);
    }

    private JsonNode result(String value) {
        if (value == null) return json.createObjectNode();
        try {
            JsonNode parsed = json.readTree(value);
            return parsed != null && parsed.isObject() ? parsed : json.createObjectNode();
        } catch (JsonProcessingException exception) {
            // Legacy data gaps must not invent a result link or leak the raw payload.
            return json.createObjectNode();
        }
    }

    private static Long positiveId(JsonNode node) {
        String value = node.asText("");
        if (!value.matches("[1-9][0-9]{0,18}")) return null;
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException exception) {
            return null;
        }
    }
}
