# 多智能体架构基础改造验收记录

日期：2026-10-11。状态：本地第一阶段改造及所列验证通过，未部署；不是线上验收结论。

## 范围与结论

保留 Java 业务控制面、Python Agent 执行面的两层结构。产品只有 USER/ADMIN，用户下面的客户项目是资料边界，不新增代理组织、员工邀请或租户成员模型。复用已有身份认证、HMAC、Run/任务、队列、Worker、积分、资产、投稿与 Trace，不另建执行框架或数据库写入通道。

本轮落地：

- Java/Python 使用同一受控运行清单，统一 Agent 版本、Prompt、执行策略、能力上限与工具白名单。
- Python 的共享原生循环与 GEO 专用行为拆分；测试专用第四个 Agent 经实际 RunManager/循环运行，不复制聊天页或 GEO 循环。
- 新 Run 冻结执行 release 和清单哈希，版本不符明确失败；旧无元数据兼容只对登记的三个内置 1.0.0 身份开放。
- 工具动作、资料读取、记忆与摘要、旧工作流入口都核对冻结权限与当前权限，前端隐藏按钮不是安全边界。
- 项目范围在消息受理、快照、搜索、读取和旧结果回放中核对；读取能力不自动授予记忆写入和付费任务能力。
- 前端常用入口按能力而非 GEO/creative 身份判断，切换项目和账号后丢弃旧异步响应。
- 跨仓 Gate 增加清单/Bundle 一致性与脏源码发布拒绝；开发检查通过不等于允许部署。
- 新同事从 [当前架构](current-architecture.md) 和 [新增智能体指南](adding-an-agent.md) 开始接手。

旧混合工作流入口现须显式具备其全部能力。当前三个内置 Agent 不具备该组合，不能借 GEO 身份发起跨领域工作流；原生 GEO/创作直接工具链仍保留。具备显式注册能力的第四 Agent 夹具验证合法工作流路径，但不会发布到用户目录。

## 代码与契约

- Java 运行清单：`src/main/resources/contracts/agents/runtime-catalog.json`；解析与授权：`agentplatform/AgentRuntimeCatalog.java`。
- Python 清单源与行为模块：`guanghe-agent/app/agents/`；共享循环：`app/agent/loop.py`；装配：`app/agent/run_manager.py`。
- Web 公共能力展示：`src/features/agent-platform/capabilities.ts`、聊天公共操作组件与输入能力选择。
- Internal/Browser OpenAPI 身份投影增加 `executionRelease/catalogHash`，Python 消费者与 Web 生成类型同步。原 6 个身份字段仍必填；新增字段兼容旧快照可省略，提供时不能是 null，成对及内容校验由运行时执行。浏览器不能指定自己的授权身份。
- 不修改已应用 Flyway，不新增迁移、不清库、不改共享数据。执行元数据存入既有 Run JSON 快照。`business_space` 是保留的物理兼容表名，不恢复经营空间产品入口。
- 当前全局清单哈希改变会使旧在途快照与新发布不兼容；联合发布要停止接收新 Run、处理在途任务后协调更新两端，不能滚动混用同一 Run。

## 验证记录

| 检查 | 最终结果 |
| --- | --- |
| Java `scripts/build.ps1 -JdkHome 'D:\JAVA\jdk'` | 完整 clean verify 与 JAR 重新打包通过；1524 项，1494 通过、0 失败、0 错误、30 跳过，159 份 Surefire 报告 |
| Java 核心扩展/授权九类 | 100 项通过，包含在上述总数内 |
| 三仓 `scripts/run-cross-repo-stability-gate.ps1 -JdkHome 'D:\JAVA\jdk'` | PASS；Java OpenAPI 13 项通过、Java/Python 消费契约 PASS、Web 生成类型 PASS、清单/Bundle 哈希一致 |
| Python `uv run python -m evals.run_agent_evals --mode local` | 18 项通过：原生 RunManager 功能旅程与启动校验；不是付费模型评测 |
| Python 契约/身份/资料补全相关选集 | 99/99 通过，86.52 秒；包含上面 18 项，不重复累计；相关 Ruff/Pyright 检查通过 |
| Web 五文件功能交互组合 | 41/41 通过，2026-10-11 21:56:13 启动，35.45 秒 |
| Web `npm.cmd run build` 与 `npm.cmd run check:api` | 通过；主代理在收尾时重新构建，未部署 |
| 发布前 `verify_runtime_catalog.py --require-clean` | 按预期拒绝三个 DIRTY 仓库，未绕过 |

Java clean 构建于 22:00:23 完成，耗时 3 分 8 秒。30 项跳过涉及显式启用的数据库、OSS、Redis、RabbitMQ、远程服务等 Gate，没有以跳过结果宣称这些环境通过。

Web 命令：

```powershell
npm.cmd test -- src/test/agent-capability-composer.integration.test.tsx src/test/chat-composer-safety.integration.test.tsx src/test/asset-center.integration.test.tsx src/test/project-first-chat.integration.test.tsx src/test/project-library-simplification.integration.test.tsx
```

Python 最终选集命令：

```powershell
uv run pytest -q tests/unit/test_brand_intake.py tests/contract/test_java_internal_consumer_contract.py tests/unit/test_runtime_catalog_journey.py tests/unit/test_agent_identity_runtime.py
```

同时验证了“工具出现在目录里不等于用户已授权”：资料不足或只是讨论时，文章语义预检拒绝写入；GEO 不能伪造调用生图工具。修正的是仍假设关键词隐藏文章工具、旧 Graph 路由和旧 Gateway 接口的测试，没有恢复旧关键词路由或放宽真实工具权限。

全部本地功能验证使用现有业务实现与确定性接口/模型替身。Web 是实际 React 页面/路由的 JSDOM 交互，HTTP 接口被替换；不证明真实浏览器视觉、模型文案质量、真实供应商或服务器状态。没有运行 Python/Web 整仓全部历史测试，不能将所列选集当作全量通过。

本轮同时修正了已有工作区中落后的测试装配和精确契约断言，例如文章写入回执、项目准入、GEO 采样进度、记忆投影与 SSE 代理规则。没有删除安全断言或通过关闭检查掩盖失败，也不能将整份 `git diff HEAD` 归为本轮变更：三个仓库在开始时就含有大量未提交实现。

## 源码与发布状态

| 仓库 | 当前 HEAD | 工作区 |
| --- | --- | --- |
| Java | `b9e131768f80976b54dac88c420a9eb5a275dadf` | DIRTY，含此前未提交实现 |
| Python | `1e381c86950f9f13f8cd2729b0825aedb1d4e523` | DIRTY，含此前未提交实现 |
| Web | `c819e29c637318e804540f2c55f55e2b4a865f7a` | DIRTY，含此前未提交实现 |

- Catalogue LF SHA256：`430951b9597d14bce0942a1f2c5dc0c04a7596ab4236cdfc1152f56c84059b78`。
- Bundle：`agent-bundle-v1.48.3`，规范化 SHA256：`d136cb4dfd38431dbe90e90870e4202ffc530a05dfa1643f8e9eb56eb48f7ec9`。
- 本地完整 JAR SHA256：`201191767c01e0a03023c6d28d272841e3e6c301695c425c1e3836510897f768`；Web `dist/index.html` SHA256：`14bdea18448785b1978e738fa22f6ecac227a4dade18ce7b40d531dae54f9ad6`。只是本地构建标识，不是线上发布标识。
- 没有 commit、push、服务器部署、共享数据库变更、真实模型调用、监测或投稿，零付费 API 消耗。
- 发布模式 `verify_runtime_catalog.py --require-clean` 对当前三个脏仓均拒绝，这是预期保护，不应绕过。

## 尚未完成与下一验收边界

1. 用户自定义 Agent 的私有配置/版本表、设计器和发布流程尚未实现；系统清单不会开放给普通用户编辑。后续用已登记执行内核承载用户配置，不为每个用户配置部署代码。
2. 这不是所有历史模块的彻底清理。仍需按领域渐进拆分大型业务处理器、清理确实不再使用的兼容路径，不批量删除尚被任务引用的代码。
3. 未运行共享/外部基础设施 Gate，未进行浏览器视觉或真实模型对话验收。本地断言通过不代表客户文案质量或线上全链路已验收。
4. 整理属于本批的未提交源码并形成可追溯联合发布后，再做最小真实对话：客户 A/B 隔离、GEO 方案与文章承接、创作工具及长对话继续；不得自动重跑历史监测或投稿。
