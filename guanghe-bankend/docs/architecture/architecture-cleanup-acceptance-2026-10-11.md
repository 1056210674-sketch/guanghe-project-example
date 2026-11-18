# 当前架构清理验收记录

日期：2026-10-11。状态：本地改造、构建和所列功能检查完成；未部署，不是线上验收结论。

本记录续接 [基础改造记录](architecture-foundation-acceptance-2026-10-11.md)，不替换其历史证据。用户最新范围优先：用户自建智能体是长期目标，本轮不做，也不再把它计入当前交付缺口。

## 实施范围

保持 Java 业务控制面、Python Agent 执行面；产品只有 USER/ADMIN，不引入组织、员工或成员权限。数据按用户归属隔离，客户项目承载各自品牌事实、聊天和资产。

| 发现的问题 | 本轮处理 |
| --- | --- |
| Java 动作入口与资料入口各自维护实时授权，容易漂移 | 提取 `conversation/RunToolAuthorizationService`，两个现有入口共用账号、Run、安装、冻结权限与当前权限交集检查；事务和回执处理仍各自保留 |
| 等待 Run/安装/定义/会话锁可能跨过截止时间 | 授权在取得锁后重新读取时间；资料入口取得额外会话锁后再次检查，不用等待前的时间放行 |
| 正常权限拒绝被嵌套事务包装成系统异常 | 只为 5 个授权/读取方法明确 `noRollbackFor=BusinessException`，保留技术异常与写操作的回滚策略 |
| GEO 的短句续接与通用工具执行混在一起 | 将证据解析及 Run 内一次性状态移入 `app/agents/geo_continuation.py`；通用执行器仍负责唯一工具执行、预算、去重和未知提交保护 |
| 有文章/监测能力的其他 Agent 可能误获 GEO 续接 | 分别要求已有 `geo-article` / `geo-brand` 策略及对应工具授权；`plain/none` 不获得这些领域规则，但明确新稿仍走正常语义准入 |
| 前端部分入口按 Agent 名称/key 找版本 | 公用 `installedAgent.ts` 按 `agentKey + agentVersion` 精确匹配，聊天、选择器、库和资产一致 |
| 服务端只返回最新版，合法旧安装没有可匹配定义 | 目录返回最新版与当前用户已安装的精确旧版；安装 ID 不挂到新版，前端只对库卡片去重，不裁掉权限匹配所需旧版 |
| 新同事不知道在哪扩展、哪些规则不能复制 | 更新 [当前架构](current-architecture.md)、[接入指南](adding-an-agent.md)、Python `app/agents/README.md`，说明职责、策略、能力和版本规则 |

新增模块都有当前真实消费者，不是为 Builder 预建框架。没有新执行循环、动态类加载、在线 Prompt 配置或私有 Agent 表。

## 保留的边界

- Java 授权顺序保持账号 → Run → 安装/定义；历史回执不绕过当前权限。资源入口仍检查 protocol feature、附件限制、当前活动 Run、项目及来源；动作入口仍检查原用户消息、计价和幂等。
- 授权公共服务不新增事务注解。动作失败仍回滚；资料入口的受控失败回执仍按原策略保存。
- 只读授权链的事务调整限于 `RunContextService.get`、`AgentPlatformService.assertContextReadAllowed/assertTaskConfirmationAllowed`、`KnowledgeService.revalidateFrozenContext` 和 `AgentMemoryService.context`。这些方法不写业务数据，预期拒绝不污染外层资源事务；FOR SHARE 方法仍保持原非 readOnly 设置，未全局改变异常回滚规则。
- 文章续接新读取先撤销旧证据，尝试在异步调用前消耗；已有任务仅展示，未知写入/已有回执/其他写入不恢复执行。监测仍要求明确提交，不能把“好/继续”当作付费监测授权。
- `WITHDRAWN` 不允许新添加，已安装版本可继续交互使用；`DISABLED`、缺失定义或不可见版本不能借新版恢复。既有自动监测调度只允许 PUBLISHED 的独立政策不变。Java 仍独立校验运行清单和必要能力。
- Browser OpenAPI 仅补充目录集合语义和顺序说明，没有新增字段；Web 类型按生成脚本更新，本轮增量仅注释变化。

## 数据库与旧代码

检查了现有 Agent 定义/安装/会话绑定、项目记忆及回执的迁移和访问代码。复合归属约束已覆盖当前多用户模型；本轮没有需要新增表的需求，不修改已应用迁移，不清库或改共享数据。`business_space` 保留为历史物理表名，不恢复产品上的经营空间。

生产聊天已通过 RunManager 装配 NativeAgentLoop；旧 `CommerceAssistantGraph` 仍被显式 legacy 评测/直接测试引用，未在本轮假称彻底删除。`graphs/domains.py` 仍被固定监测入口复用，WORKFLOW Worker 仍承担历史任务恢复、租约和结算，不能连同旧图目录一起删除。大型领域服务后续可按真实边界拆分，本轮不做机械搬文件。

## 验证记录

| 检查 | 结果 |
| --- | --- |
| Java `scripts/build.ps1 -JdkHome 'D:\JAVA\jdk'` | 最终完整 clean verify、JAR 重打包通过；1555 项，1525 通过，0 失败/错误、30 跳过；160 份报告，23:09:47 完成，耗时 1 分 31 秒 |
| Java 真实嵌套事务代理补充用例 | 12/12，已包含在完整构建；5 个读取/授权层 × search/read 正常拒绝，以及技术异常、写入外层回滚 |
| Python 续接/监测/语义准入/身份八文件选集 | 325/325，14.48 秒；修改文件 Ruff、format、Pyright 通过 |
| Python `uv run python -m evals.run_agent_evals --mode local` | 18/18，6.99 秒；实际原生 RunManager/循环确定性功能旅程，与上面选集有重叠，不重复累计 |
| Web 五文件功能交互选集 | 49/49，22:43:26 开始，49.71 秒；实际 React 页面/路由，JSDOM 和 HTTP fixture |
| Web generate:api / check:api / build / 相关 ESLint | 全部通过；只对本轮涉及文件记录 lint 结论 |
| 三仓 `scripts/run-cross-repo-stability-gate.ps1 -JdkHome 'D:\JAVA\jdk'` | PASS；Java OpenAPI 13 项、Java/Python 任务上下文契约、Web 生成类型、清单/Bundle 哈希一致性全部通过 |

跨仓 Gate 同时标明 `SOURCE_RELEASE_READINESS=NOT_READY_DIRTY_SOURCE`，即开发检查通过，但当前脏工作区不满足正式发布条件；未绕过这一保护。

Python 选集：

```powershell
uv run pytest -q tests/unit/test_article_continuation.py tests/unit/test_article_continuation_runtime.py tests/unit/test_monitor_continuation.py tests/unit/test_native_monitor_authorization.py tests/unit/test_article_action_review.py tests/unit/test_brand_intake.py tests/unit/test_runtime_catalog_journey.py tests/unit/test_agent_identity_runtime.py
```

Web 选集：

```powershell
npm.cmd test -- src/test/agent-capability-composer.integration.test.tsx src/test/chat-composer-safety.integration.test.tsx src/test/asset-center.integration.test.tsx src/test/project-first-chat.integration.test.tsx src/test/project-library-simplification.integration.test.tsx
```

中间失败未隐去：首次 Java 全量装配有 3 项错误，修复接口 fixture 缺少新授权服务所需的 AccountMapper mock；第二次新版本目录的 3 个参数案例漏算原有 `geo.read → web.search` 兼容授权，修正精确断言并额外断言新版文章/资料能力不泄漏。未改生产兼容规则来迎合测试。Python 两个领域隔离负例先复现错误，再通过策略校验修复。Web 新交互断言适配真实请求省略空字段及 Ant 按钮可访问名称，未放宽用户侧执行判断。

后续独立复核发现嵌套事务风险，使用真实服务、`TransactionInterceptor`、`JdbcTransactionManager` 和模拟 JDBC 连接在本地复现：新增 12 例中的 10 个预期业务拒绝原先均变为 `UnexpectedRollbackException`。限定修复 5 个只读授权方法后，同 12 例全通过；另两例证明技术失败和实际 RunAction 写入外层仍回滚。这不是外部数据库或 PostgreSQL 锁竞争验收；最终再次完整构建，结果为上表 1555 项。

这些检查不证明真实模型文案质量或供应商可用性。30 个 Java 跳过项涉及显式启用的数据库、Redis、RabbitMQ、OSS、远程等环境；未运行共享数据库或真实浏览器验收。未运行 Python/Web 整仓全部历史测试。

## 源码与发布状态

| 仓库 | HEAD | 状态 |
| --- | --- | --- |
| Java | `b9e131768f80976b54dac88c420a9eb5a275dadf` | DIRTY，含之前未提交实现 |
| Python | `1e381c86950f9f13f8cd2729b0825aedb1d4e523` | DIRTY，含之前未提交实现 |
| Web | `c819e29c637318e804540f2c55f55e2b4a865f7a` | DIRTY，含之前未提交实现 |

- 清单 LF SHA256：`430951b9597d14bce0942a1f2c5dc0c04a7596ab4236cdfc1152f56c84059b78`，本轮未改清单。
- Bundle：`agent-bundle-v1.48.3`，LF SHA256 `d136cb4dfd38431dbe90e90870e4202ffc530a05dfa1643f8e9eb56eb48f7ec9`，本轮未改 Prompt。
- 本地完整 JAR SHA256：`4587756c890e49ca68fbe9b14675e16004a8824f20bfb1c264f03f77068850c5`。
- 本地 Web `dist/index.html` SHA256：`3f30cd3cd64049d5f8712ea2852d1f93393a9180de49bfb664c313d125865fb9`。
- 没有提交、推送、部署、共享数据库变更、真实模型调用、监测或投稿，零付费 API 消耗。以上为本地产物标识，不是线上发布号。

本次已经进入脏工作区，不能把整份 `git diff HEAD` 归为本轮改造。发布前仍须整理既有与本批源码，在可追溯的干净检出中做三仓联合构建；不得绕过 release 模式的 dirty 检查或局部替换 JAR class。上线后的真实对话、跨客户项目隔离和历史任务恢复需单独验收。
