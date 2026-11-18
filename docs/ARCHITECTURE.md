# 光禾AI智能体框架 · 架构导读

## 一条对话如何执行

1. Web 选择智能体及可选项目，通过 Java Browser API 发送消息。
2. Java 验证账号与项目归属，冻结 Run 上下文、智能体身份和工具能力。
3. Java 经签名内部协议调用 Python。Python 检查运行清单，进入共享的有界循环。
4. 模型通过统一网关产生回复或工具请求；工具执行器核对格式、权限上限和预算。
5. 业务读写回到 Java 再次校验。需要后台执行的工作通过现有任务机制与 RabbitMQ 交给 Worker。
6. Worker 回报执行结果；Java 验收、保存业务状态并处理结算。Web 根据服务端事件和快照展示状态。

收到提议、受理请求、模型完成、后台任务完成和交付成果就绪是不同事实，不能在前端或模型输出中互相替代。

## 源码阅读顺序

| 想看什么 | 建议入口 |
| --- | --- |
| 页面与路由 | `guanghe-web/src/routes/AppRoutes.tsx` |
| 聊天交互 | `guanghe-web/src/pages/chat`、`guanghe-web/src/features/chat` |
| 浏览器协议 | `guanghe-bankend/src/main/resources/contracts/openapi/browser-openapi.yaml` |
| 业务控制 | `guanghe-bankend/src/main/java/com/guanghe/backend/conversation`、`task` |
| Java 内部通信 | `guanghe-bankend/src/main/java/com/guanghe/backend/integration/internal` |
| Python 装配 | `guanghe-agent/app/api/main.py`、`app/common/runtime.py` |
| Agent 身份与权限 | `guanghe-agent/app/agents/runtime-catalog.json`、`catalog.py` |
| Run 生命周期 | `guanghe-agent/app/agent/run_manager.py` |
| 预算与工具限制 | `guanghe-agent/app/harness/runtime.py` |
| 模型网关 | `guanghe-agent/app/model_gateway` |
| 任务执行 | `guanghe-agent/app/workers` |
| 数据结构 | `guanghe-bankend/src/main/resources/db/migration` |

## 数据与权限边界

- 账号隔离业务所有权，项目隔离不同客户的品牌事实和成果。
- Java 是业务 PostgreSQL 的唯一写入方；Python 不持有浏览器 JWT，不决定积分和任务业务终态。
- Redis、消息和 Agent 内存均不能替代数据库真相。
- Agent capability 与工具白名单是执行权限上限，业务操作仍需 Java 校验。
- 付费任务使用业务准入、预算、确认与幂等规则；模型提议本身不会形成扣费授权。

## 扩展原则

修改现有业务优先扩展既有模块。文字模型走统一 Gateway；Java 调用复用签名 Internal Client；长任务复用 Worker 和队列契约。不要为单个 Provider 新建一套运行框架。

新增 Agent 先阅读 [三端接入指南](../guanghe-bankend/docs/architecture/adding-an-agent.md)。运行清单、Prompt 版本、Java 镜像、API 契约和测试应同步检查。

更详细的原项目边界见 [当前架构](../guanghe-bankend/docs/architecture/current-architecture.md) 与 [运行时边界](../guanghe-bankend/docs/runtime-boundaries.md)。这些历史文档保留原开发阶段记录，不表示本公开版本已经部署。
