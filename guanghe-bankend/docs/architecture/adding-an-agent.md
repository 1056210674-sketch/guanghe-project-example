# 新增智能体接入指南

适用于开发者增加内置智能体，不是用户自助 Builder。此文随 Java 仓库保存，先读 [当前架构](current-architecture.md)。不需要复制 GEO 控制器、聊天页或整个循环。

## 1. 选择扩展类型

- 提示词与工具组合不同：复用 `runtimeKind=native` 和 `plain/none` 策略。
- 仅输入文字摘要：复用 `runtimeKind=summary`，不得挂写工具。
- 确有新业务过程：新增行为模块/领域服务，但复用 RunManager、Harness、Gateway 和 Java 授权；不要在通用循环加 `if agentKey == ...`。
- 新外部副作用：先补 Java 服务、授权、任务、计价和幂等，再注册工具；不是在 Python 直接投稿或写业务库。

## 2. 声明受控执行注册

修改 Python `app/agents/runtime-catalog.json`。以下资料助手示例不会由本文自动安装到用户账号：

```json
{
  "agentKey": "research-helper",
  "agentVersion": "1.0.0",
  "catalogueAdapterKey": "research-helper",
  "runtimeKind": "native",
  "promptCode": "RESEARCH_HELPER_V1",
  "executionRelease": "research-helper-20261011-v1",
  "intent": "GENERAL",
  "contextPolicy": "plain",
  "continuationPolicy": "none",
  "explicitActions": [],
  "requiredCapabilities": ["chat"],
  "capabilities": ["chat", "resources.read"],
  "legacyCapabilityGrants": {},
  "allowedTools": ["context.resources.search", "context.resources.read"]
}
```

`capabilities` 是上限，不是授权；Java 安装/会话授权仍须允许。每个工具还必须满足清单 `tools` 的所有 `requiredCapabilities`。只读助手不要借 GEO prompt：新的提示词应明确只处理资料，不监测、不写记忆、不创建付费内容。

业务策略和工具能力是两个边界。`plain/none` 即使具备写文章或监测能力，也不会接入 GEO 专用的短句续接；不要为了复用一个工具就复制 GEO 的策略。明确的新请求仍由既有语义准入与 Java 用户原文授权共同约束。

在既有 Prompt Bundle 添加 `RESEARCH_HELPER_V1`，递增 Bundle 版本，并更新清单 `bundleVersion/bundleSha256`。保留精确历史文章版本白名单并加入当前版本；同步 Java Internal OpenAPI 和 Python 文章结果消费者，不能把枚举放宽为任意字符串。

将清单同步到 Java `src/main/resources/contracts/agents/runtime-catalog.json`。两份文件只允许行尾差异。改变共享 Bundle 会改变全局清单哈希，发布须协调两服务，不可混用新旧发布执行同一 Run。

## 3. 发布业务定义，不改历史定义

Java 追加 Flyway 迁移，向 `business.agent_definition_version` 插入 key/version、名称、说明、能力、`adapter_key`、可见性、状态、定义哈希和发布时间。实际字段/哈希构造参考 V047/V057，不修改它们本身。

- `agent_key/version/adapter_key` 必须与清单对应。
- 初始设 INTERNAL，验收后再按正式发布流程开放。
- 已发布内容不可变；变更发布新 version，不 UPDATE 旧内容或会话绑定。
- 本地迁移只用隔离数据库；不能直接运行共享服务器迁移。

用户通过既有 `/api/v1/agent-installations` 安装，通过聊天接口选择 installation。浏览器不能提交任意身份、清单路径、工具实现或执行发布号。Java 新 Run 冻结带 release/hash 的身份；新增 Agent 缺这两个字段时必须失败。

## 4. 界面与能力

当前监测/写文章/生图/视频快捷入口读取 capabilities，已有能力组合无需新增专用 ChatPage。按钮仅填请求或打开固定入口，不表示任务已执行。

全新交互才扩展公共组件/能力映射。头像/IP 可按身份展示，但不是权限判断。个人素材与客户项目是不同范围，异步上传、初始化存储或切换聊天后，不能把旧响应带进新项目。

用户目录可能同时包含新版与该用户已安装的旧版。只对目录展示按 key 取服务端返回的首条；解析安装必须用 `agentKey + agentVersion`，不能取同 key 首条或猜测语义版本排序。下架 `WITHDRAWN` 不允许新添加但保留已安装使用，禁用 `DISABLED` 才停止执行；客户端只作展示判断，Java 仍最终验权。

## 5. 必须验收的功能场景

| 场景 | 期望 |
| --- | --- |
| 第四 Agent 普通对话 | 共享执行器，正常流式输出与终态 |
| 调用未声明写工具 | Python 不执行，绕过 Python 调 Java 同样拒绝 |
| 只读/Chat-only | 不写品牌或记忆、不创建付费任务；不接收未授权知识/附件/报告 |
| 两用户、两项目、并发 Run | 数据与执行能力不串用 |
| 停用安装、撤销能力 | 旧快照不能继续写，兼容映射不能恢复撤销的能力 |
| 未知版本、错 hash、半缺失元数据 | 明确失败，不降级到 GEO 或第一个 Profile |
| Stop、失败、继续聊天 | 不重复外部提交、不伪造完成 |
| GEO 文章承接与创作能力 | 原有主链路可用，投稿仍最终确认 |
| 发布新版后已有安装 | 保留旧版真实状态和能力，目录不重复卡片、不重复安装 |
| plain/none 具有写作能力 | 明确新稿可通过准入，但不承接 GEO 专用短句续接 |
| 读取期间权限失效 | 正常业务拒绝不被嵌套事务包装成系统错误；技术错误及写入失败仍回滚 |

实际功能用例放在三个仓库既有 tests/src/test 下。第四 Agent 是隔离验收夹具，不为凑验收发布到 PUBLIC。

## 6. 检查与发布

Python 清单检查（只读，不调用模型）：

```powershell
uv run python scripts/verify_runtime_catalog.py
```

Java 三仓契约检查：

```powershell
.\scripts\run-cross-repo-stability-gate.ps1 -JdkHome 'D:\JAVA\jdk'
```

发布前审查属于本次交付的源码，不擅自提交别人未完成的工作，在干净检出中执行：

```powershell
.\scripts\run-cross-repo-stability-gate.ps1 -JdkHome 'D:\JAVA\jdk' -RequireCleanWorktree
.\scripts\build.ps1 -JdkHome 'D:\JAVA\jdk' -RequireCleanWorktree
```

另执行 Python 现行原生循环评估及 Worker 场景、Web 构建和交互验收。跨仓 Gate 不触发共享数据库、部署或付费调用，完整构建不等于生产链路验收。

契约漂移、版本错配或构建失败应保留结果并定位，不能跳过或换 Mock 后宣称真实模型可用。部署后核对运行态版本/Worker readiness，再用获授权的最小真实对话验质量；费用和对外发布仍单独确认。
