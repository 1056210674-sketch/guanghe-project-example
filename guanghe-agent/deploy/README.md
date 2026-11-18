# Deployment

API 与 Worker 已使用独立入口：`guanghe-agent-api` 和 `guanghe-agent-worker`。本地默认端口
分别为 8000 与 8010，二者拥有独立 lifespan 和健康检查；Worker 收到 SIGTERM 后先停止
本地拉取，再在配置的宽限期内排空在途任务。

测试环境的 IMAGE 与 VIDEO Worker 是两个独立进程。IMAGE 继续监听
`127.0.0.1:8010` 并消费 `zhixiaohe.agent.image`；VIDEO 使用
[`zhixiaohe-agent-video-worker-test.service`](zhixiaohe-agent-video-worker-test.service)、
监听 `127.0.0.1:8011` 并消费 `zhixiaohe.agent.video`。VIDEO unit 只读取
公共 API 环境文件和 `/etc/zhixiaohe/python-video-worker-test.env` 的 Worker 专用覆盖；后者
必须为 `root:root / 0600`，且不得与
IMAGE Worker 混用 workerId、consumerName、taskType、端口或队列。

测试环境 systemd 单元位于
[`zhixiaohe-agent-test.service`](zhixiaohe-agent-test.service)。它只通过受限的
`/etc/zhixiaohe/python-test.env` 读取配置和凭据，服务监听地址必须在该文件中固定为
`127.0.0.1:8000`。仓库、systemd 单元、部署日志和命令行参数均不得包含密钥。

部署前必须完成全部本地质量门并将目标提交推送到远端。测试服务器为目标提交创建只读语义
的独立 release，完成依赖同步后再原子切换 `current`；API、IMAGE Worker 和 VIDEO Worker
必须全部从 `current` 运行，不能以环境变量中的提交号掩盖可变 `repo` 代码。标准更新流程为：

```bash
cd /opt/zhixiaohe/python/repo
git fetch origin
git worktree add --detach /opt/zhixiaohe/python/releases/<target-commit> <target-commit>
cd /opt/zhixiaohe/python/releases/<target-commit>
uv sync --frozen --python 3.12
sudo install -o root -g root -m 0644 \
  deploy/zhixiaohe-agent-test.service \
  /etc/systemd/system/zhixiaohe-agent-test.service
ln -sfn /opt/zhixiaohe/python/releases/<target-commit> /opt/zhixiaohe/python/current.next
mv -Tf /opt/zhixiaohe/python/current.next /opt/zhixiaohe/python/current
sudo systemctl daemon-reload
sudo systemctl restart zhixiaohe-agent-test
sudo systemctl status zhixiaohe-agent-test --no-pager
```

`GUANGHE_AGENT_GIT_COMMIT` 必须在受限 EnvironmentFile 中设置为正在运行的完整提交 ID；
`PYTHON_M1_READY` 在 Java/Python 双向 HMAC、Redis 重放防护和跨服务 Trace Gate 全部通过前
不得设为 `true`。EnvironmentFile 建议权限为 `root:root 0600`（由 systemd 读取），禁止输出其内容。

部署后依次验证 loopback readiness、带签名 capabilities、Java→Python POST、Python→Java
带 Query 反向探针、篡改/重放/过期/Redis 故障以及跨服务 Trace。反向代理不得代理
`/internal/v1/health/ready`；该路径还会同时校验 socket 对端和 Host 均为 loopback。
Java 直接调用 Uvicorn loopback 入口时，JDK `HttpClient` 必须固定使用 HTTP/1.1；不得尝试
明文 HTTP/2 升级（h2c），否则 POST 原始 Body 无法作为双方一致的签名输入。

回滚时先在开发机创建并推送一个恢复到上次已验证内容的回滚提交，再为这个新提交创建
release 并切换 `current`；禁止让可变 `repo` 目录直接承担运行时：

```bash
cd /opt/zhixiaohe/python/repo
git fetch origin
git worktree add --detach /opt/zhixiaohe/python/releases/<rollback-commit> <rollback-commit>
# 同步依赖并按上述步骤原子切换 current
sudo systemctl restart zhixiaohe-agent-test
```

不要直接让服务器分支倒退，也不要在测试服务器执行 `reset --hard` 或覆盖未跟踪文件。
生产配置不得使用
`WORKER_CONTROL_PLANE=LOCAL` 或 `WORKER_PROVIDER=MOCK`；当前代码会对此返回
`NOT_READY`。真实 Rabbit Consumer 与尚未冻结的 Java 业务 Context/Claim 字段不在此单元中
猜测或配置。

## OpenTelemetry 测试基础

API 和各 Worker 复用 `app.observability.tracing` 的唯一 SDK 装配入口。默认关闭；测试环境
启用时使用 `http/protobuf` 向 loopback Collector 的 `/v1/traces` 发送，采样率固定为 1。
缺少 Endpoint 时自动保持 NOOP，Collector 故障只影响异步导出，不参与业务 readiness。
不同进程的 `service.name` 分别带 `api`、`image-worker`、`video-worker`、`geo-worker`、
`article-worker` 或 `workflow-worker` 后缀；`service.version` 是实际 Git Commit。

仓库提供的 [`otel-collector-test.yaml`](otel-collector-test.yaml) 只适用于官方
`otelcol-contrib`：OTLP receiver 的 gRPC/HTTP 只监听 `127.0.0.1:4317` 和
`127.0.0.1:4318`，先删除高风险 HTTP、消息正文和
URL Query 属性，再经过 memory limiter 与 batch，最终写入仅 Collector 用户可读、可轮换的
本地诊断文件。安装前必须核对官方发行包和 SHA256；版本不支持这些组件时不得自行编写兼容
Collector。服务用户不得复用 API/Worker 用户，输出目录权限必须为 `0700`，输出文件为
`0600`。Collector 配置文件必须保持 `root:root / 0600`；systemd 通过 `LoadCredential`
向非 root Collector 进程提供只读副本，不放宽源文件权限。

[`otel-collector-langfuse-test.yaml`](otel-collector-langfuse-test.yaml) 是可选远端导出配置：它
保留同一受限本地文件 pipeline，并通过独立 `otlphttp/langfuse` pipeline 发送 metadata-only
Span。Collector 的区域 Endpoint 和 Basic Authorization 只允许写入
`/etc/zhixiaohe/langfuse-otel.env`，文件必须为 `root:root / 0600`；应用服务不读取该文件，
仓库中的 [`langfuse-otel.env.example`](langfuse-otel.env.example) 只有占位符。没有凭据时继续
安装基础 Collector 配置，应用将 `langfuseExport` 报告为 `NOT_CONFIGURED`，不得将本地文件
写入成功冒充为 Langfuse 远端 Gate 通过。

测试环境固定使用官方 `otelcol-contrib 0.139.0` Linux amd64 发行包，来源为
`open-telemetry/opentelemetry-collector-releases` 的 `v0.139.0` Release；归档 SHA256 为
`7d4356adf690b1a08f5d536331dc932ee28d31fd80801b6e960cd6b5618f86db`。升级版本时必须先用
新版本二进制执行配置校验，并在独立提交中更新版本和校验值。

应用环境变量仅包含以下非秘密配置；OTLP Endpoint 不允许嵌入用户名、密码、Query 或
Fragment：

```text
GUANGHE_AGENT_OTEL_ENABLED=true
GUANGHE_AGENT_OTEL_OTLP_ENDPOINT=http://127.0.0.1:4318/v1/traces
GUANGHE_AGENT_OTEL_OTLP_PROTOCOL=http/protobuf
GUANGHE_AGENT_OTEL_SAMPLE_RATIO=1
GUANGHE_AGENT_LANGFUSE_EXPORT_ENABLED=true
GUANGHE_AGENT_LANGFUSE_EXPORT_COLLECTOR_CONFIGURED=false
```

`LANGFUSE_EXPORT_COLLECTOR_CONFIGURED=true` 只表示部署者已将远端 exporter 和凭据装配到
Collector，不代表远端一定已摄取。远端 Gate 必须用一个可检索 Trace 单独验证。测试环境采样率
固定为 1；production 应用默认 head sampling 为 0.1。head sampling 会在错误发生前决定是否
采样，因此无法保证保留所有错误；错误全留需要后续 Collector tail sampling 设计与容量 Gate。

部署 Collector 时使用
[`zhixiaohe-otel-collector-test.service`](zhixiaohe-otel-collector-test.service)，并继续保留
systemd 的非 root、loopback、只读文件系统和专用可写目录约束。本地文件输出不能冒充任何
远端 Backend Gate。

## OTel Metrics 与 loopback Prometheus

API 和所有 Worker 继续复用 `app.observability.tracing` 的唯一 SDK 装配入口，通过 OTLP HTTP
`/v1/metrics` 将低基数指标发送到同一 Collector。Collector 的 Prometheus exporter 仅监听
`127.0.0.1:9464`，不得由 Nginx 或公网代理；导出故障不改变任何业务 readiness。
`guanghe.worker.inflight` 是进程本地 UpDownCounter，进程重启时归零；其他 Counter 重启也会
reset，查询必须使用 `rate` 或 `increase`，不能把它们当作 Java 业务审计或 RabbitMQ 队列真相。

告警模板位于 [`prometheus-alert-rules.yaml`](prometheus-alert-rules.yaml)。没有外部 Prometheus
或 Grafana 时，只执行 Collector loopback scrape Gate，外部监控状态保持 `PENDING`。

```text
GUANGHE_AGENT_OTEL_METRICS_ENABLED=true
GUANGHE_AGENT_OTEL_METRICS_OTLP_ENDPOINT=http://127.0.0.1:4318/v1/metrics
GUANGHE_AGENT_OTEL_METRICS_EXPORT_INTERVAL_MS=5000
```
