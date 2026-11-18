# 光禾AI智能体框架 · 本地开发指南

本指南区分 Python 独立探索与完整业务联调。所有命令在你自己的开发环境执行。外部服务账号与调用费用由使用者自行配置；仓库没有提供公共测试账号或托管服务凭据。

## 环境要求

| 工具 | 版本或用途 |
| --- | --- |
| Git | 获取源码与记录修改 |
| Node.js | 22.22.2 或更新的 22.x；锁文件包含对 Node 版本要求较高的测试依赖 |
| npm | 10.9.x，仓库声明的 packageManager 为 10.9.8 |
| JDK | 21，Java 自带 Maven Wrapper |
| Python | 3.12，Agent 项目限制为 `>=3.12,<3.13` |
| uv | 同步 Python 锁定依赖与执行开发命令 |

完整运行需要 PostgreSQL、Redis 和 RabbitMQ。对象存储、真实模型、搜索、图片、视频及媒体投稿按功能配置。具体连接与认证参数以各模块主配置为准。

## 路线 A：先探索 Python API

在仓库根目录打开 PowerShell：

```powershell
Set-Location guanghe-agent
uv sync --python 3.12
$env:GUANGHE_AGENT_ENVIRONMENT = 'local'
$env:GUANGHE_AGENT_MODEL_GATEWAY = 'LOCAL'
$env:GUANGHE_AGENT_RUN_CONTEXT_PROVIDER = 'LOCAL'
uv run guanghe-agent-api
```

可访问入口：

| 地址 | 用途 |
| --- | --- |
| `http://127.0.0.1:8000/health` | 存活检查 |
| `http://127.0.0.1:8000/dev/agent` | 本地 Agent 开发控制台 |
| `http://127.0.0.1:8000/docs` | FastAPI 文档 |

如需探索 Worker，在另一个终端进入 `guanghe-agent`，显式设置本地模式：

```powershell
$env:GUANGHE_AGENT_ENVIRONMENT = 'local'
$env:GUANGHE_AGENT_WORKER_BROKER = 'LOCAL'
$env:GUANGHE_AGENT_WORKER_CONTROL_PLANE = 'LOCAL'
$env:GUANGHE_AGENT_WORKER_PROVIDER = 'MOCK'
uv run guanghe-agent-worker
```

Worker 默认监听 `127.0.0.1:8010`。本地模式用于验证编排与协议，不产生真实图片、视频或线上投稿结果；它也不能代替完整 Web 与 Java 的账号和业务流程。

`.env.example` 是变量目录，包含尖括号占位符，不能整份原样加载。应用配置采用环境变量；只设置当前模式需要的变量即可。

## 路线 B：完整 Web / Java / Python 联调

### 1. 准备隔离的基础设施

创建自己的开发数据库、数据库角色、Redis 和 RabbitMQ 账号，不指向他人的共享环境。Java 运行账号负责 DML；Flyway 迁移账号负责 DDL，两类账号分开配置。

数据库结构位于 [Flyway 迁移目录](../guanghe-bankend/src/main/resources/db/migration)。请完整阅读迁移内容，并通过 Flyway 对新的开发数据库应用迁移。不要改写已执行迁移或把测试初始化脚本当作生产初始化方案。

### 2. 配置 Java

主配置：[application.yaml](../guanghe-bankend/src/main/resources/application.yaml)。本地模板：[application-local.yaml.example](../guanghe-bankend/src/main/resources/application-local.yaml.example)。重要变量包括：

| 配置组 | 变量 |
| --- | --- |
| 业务数据库 | `APP_DB_URL`、`APP_DB_USERNAME`、`APP_DB_PASSWORD` |
| 迁移数据库 | `SPRING_FLYWAY_ENABLED`、`FLYWAY_DB_URL`、`FLYWAY_DB_USERNAME`、`FLYWAY_DB_PASSWORD` |
| 认证 | `JWT_SECRET_BASE64`；必须由使用者生成足够强度的随机密钥 |
| Redis | `REDIS_HOST`、`REDIS_PORT`、`REDIS_USERNAME`、`REDIS_PASSWORD`，以及相应 TLS 配置 |
| RabbitMQ | `RABBITMQ_HOST`、`RABBITMQ_PORT`、`RABBITMQ_VHOST`、`RABBITMQ_USERNAME`、`RABBITMQ_PASSWORD` |
| Python 地址 | `AGENT_BASE_URL`，本地通常为 `http://127.0.0.1:8000` |

Internal HMAC 的完整 Java 变量名见主配置的 `zhixiaohe.internal` 部分。保留的 `zhixiaohe` 等标识用于内部配置兼容，不是公开产品名称。

Windows 构建：

```powershell
Set-Location guanghe-bankend
.\scripts\build.ps1 -JdkHome 'C:\path\to\jdk-21'
```

构建入口执行 `clean verify` 并检查测试报告。它需要可用的 Maven 仓库访问；外部 Gate 默认不因此自动启用。

在已准备环境变量与数据库后，可以启动构建产物（仍在 Java 模块目录）：

```powershell
& 'C:\path\to\jdk-21\bin\java.exe' -jar target/guanghe-backend-0.0.1-SNAPSHOT.jar
```

Java 默认监听 `127.0.0.1:8080`。账号开通、首次登录、协议与积分均由现有 Java 业务实现管理；仓库不附带通用默认管理员口令。管理员配置和各功能的启用条件请继续查看 [Java 模块文档](../guanghe-bankend/README.md)。

### 3. 接入 Python 与 Worker

完整环境必须使用 Java 授权上下文。按 [Agent 变量模板](../guanghe-agent/.env.example) 与 [部署说明](../guanghe-agent/deploy/README.md) 配置：

- `GUANGHE_AGENT_RUN_CONTEXT_PROVIDER=JAVA`。
- `GUANGHE_AGENT_JAVA_INTERNAL_BASE_URL` 指向 Java。
- 两端一致的 Internal HMAC key ID、Secret 和 Nonce 存储。
- 显式选择文字模型 Gateway、模型名、Endpoint 与凭据。
- 需要真实长任务时，配置 RabbitMQ、`JAVA` Worker 控制面与适当 Provider。

Java 声明消息拓扑，Python Worker 按约定消费对应队列。不要直接把本地 Stub 带入生产，也不要向浏览器暴露内部签名服务。

本项目不假设光禾AI存在无需授权的统一公开 API。接入托管服务或定制 Provider 前，请通过 [光禾AI官方渠道](https://www.guangheai.com/contact.html) 确认接口与服务范围。

### 4. 启动前端

在仓库根目录进入 Web：

```powershell
Set-Location guanghe-web
npm ci
Copy-Item .env.example .env.local
```

编辑 `.env.local`：

```dotenv
VITE_API_BASE_URL=http://127.0.0.1:8080
```

然后执行：

```powershell
npm run dev -- --host 127.0.0.1
```

前端变量会进入浏览器代码，不能放入服务器 Secret、JWT 签名密钥或供应商密钥。Web 只能访问 Java `/api/v1/**`。若浏览器提示跨域，核对 Java CORS 策略和实际 origin；Vite 配置当前没有替你建立业务 API 代理。

## 契约生成与跨端一致性

从 Web 模块执行：

```powershell
npm run generate:api
npm run check:api
```

默认读取同级 `guanghe-bankend/src/main/resources/contracts/openapi/browser-openapi.yaml`。也可用 `JAVA_BROWSER_OPENAPI` 显式覆盖路径。

**当前快照的已知问题**：Java Browser OpenAPI 缺少 Web 已有的 Canvas 类型，因此生成后会影响画布编译。当前请保留随源码提供的生成文件；这两个命令用于后续修复契约时核对，不能把 `STALE` 当作已通过。详见 [公开版本说明](PUBLIC-RELEASE.md)。

从 Agent 模块核对运行清单：

```powershell
uv run python scripts/verify_runtime_catalog.py --java-repository ../guanghe-bankend --web-repository ../guanghe-web
```

原项目以三个独立仓库发布，部分历史发布脚本仍依赖三仓 commit 和部署记录。当前合并仓库适合阅读、开发和重新建立自己的发布流程，不应直接把原部署脚本当作针对本仓库的一键生产发布。

## 遇到问题

先提供模块、命令、工具版本和脱敏错误码，再到 [GitHub Issues](https://github.com/1056210674-sketch/guanghe-project-example/issues) 提交复现信息。不要上传 `.env`、真实账号、访问令牌、完整客户资料或原始供应商响应。
