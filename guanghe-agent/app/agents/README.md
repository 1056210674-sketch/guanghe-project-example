# Agent 扩展入口

这里保存代码审查后的运行清单与业务行为。普通用户不能通过 Prompt、URL 或上传代码改变这里的注册。

- `runtime-catalog.json`：Agent 定义版本、执行 release、Prompt、runtime kind、上下文/承接策略、能力上限和工具白名单。
- `catalog.py`：严格解析、内容哈希、Bundle/Profile 校验；Java 镜像内容须一致。
- `behavior.py`：共享循环行为扩展点；默认不注入 GEO 品牌或自动承接文章。
- `geo.py`：GEO 专有品牌投影与 Java 授权文章承接。
- `geo_continuation.py`：逐 Run 的 GEO 文章/监测续接证据、消息/项目匹配和单次消费状态；不执行工具或调用 Java。文章要求 `continuationPolicy=geo-article`，监测要求 `contextPolicy=geo-brand`，并同时满足对应工具权限；不能仅凭具备文章能力就继承 GEO 短确认。共享 `tools/native.py` 仍统一承担工具执行、预算、去重和真实回执；创作智能体不装配 GEO 续接状态。
- `../agent/run_manager.py`：按清单装配既有 `NativeAgentLoop` 或摘要适配器，不按新 Agent key 写分支。

只改提示词/工具组合的 Agent：添加受控 Prompt 和清单项、同步 Java 镜像并追加 Java 定义迁移；无需复制 Loop/聊天页/鉴权/积分实现。新条目 `legacyCapabilityGrants` 必须为空。新能力要先有 Java 业务授权、任务/价格/幂等边界，再开放模型工具。

`executionRelease/catalogHash` 由 Java 冻结到每个 Run；新身份缺少或不匹配则拒绝。只对明确列出的三个旧 1.0.0 身份接受无 metadata 兼容，禁止将其扩展成所有 Agent 的后门。工具同时需要在 allowedTools 中并满足全部 requiredCapabilities，`resources.read` 不代表能写记忆。

变更 Bundle 时更新版本及规范化 LF SHA256，并同步当前文章版本精确白名单/OpenAPI/消费者。运行：

```powershell
uv run python scripts/verify_runtime_catalog.py
uv run python -m evals.run_agent_evals --mode local
```

第二条默认验证真实 RunManager/原生循环的固定功能旅程，网关/Java 使用确定性替身，不证明真实模型质量。测试专用第四 Agent 见 `tests/unit/test_runtime_catalog_journey.py`，不发布到用户目录。

完整新同事指南保存在 Java 仓库 `docs/architecture/adding-an-agent.md`。用户自建智能体为明确延期的长期目标，本轮不新增设计器、私有定义表或在线配置发布；当前指南仅供开发者新增受控内置 Agent。
