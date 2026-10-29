# 快速上手

本目录的脚本负责把"启动 pi-java"这件事变得可重复，避免每次都要回忆
JDK 路径、环境变量名和模型档案参数。

## 前置条件

| 项目 | 要求 | 检查方式 |
|---|---|---|
| JDK | 21 或更高 | 脚本会自动探测，无需手动配置 |
| 构建产物 | `pi-java-app/target/pi-java-app-0.2.0.jar` | `mvn -q -DskipTests package` |
| Git（可选） | 工作区是本机 Git 仓库时才有“本轮改动”与撤销 | `git --version` |
| API Key | 可选；不设置时只能用 Fake 离线模型 | 见下文 |

> **为什么要脚本**：本机 PATH 上的 `java` 可能是 JDK 17，直接 `java -jar` 会报
> `UnsupportedClassVersionError`。脚本按 `JAVA_HOME` → `~/.jdks` → PATH 的顺序
> 找到真正的 JDK 21+，并用它的绝对路径启动。

> **重新构建前先停掉服务**：`mvn verify` / `mvn package` 会重写上面的 jar，
> 运行中的实例随后会失效（端口可连但页面一直转圈）。改完代码后按
> “停服 → 打包 → 启动”的顺序操作；只想跑测试时用 `mvn -q test`，不触碰 jar。

## 一、离线试用（不需要 Key）

```powershell
.\scripts\start.ps1
```

> 脚本可在 **Windows PowerShell 5.1** 与 **PowerShell 7** 下运行。
> Windows 若提示"禁止运行脚本"，用以下任一方式：
> - 当前会话临时放行：`Set-ExecutionPolicy -Scope Process Bypass`
> - 或直接调用：`powershell -ExecutionPolicy Bypass -File .\scripts\start.ps1`

打开 <http://127.0.0.1:8080>：首次启动会显示**创建管理员**卡片，填写登录名、
显示名与至少 8 位密码即可进入主界面；登录状态保存在 HttpOnly Cookie 里，
关闭浏览器再打开仍然有效。之后每次打开页面会先检查登录状态，未登录或登录过期
会回到登录卡，用同样的账号密码登录即可；设置中心“账号与空间”里可以查看当前
租户与账号并登出。需要多个人分开使用时，管理员可以在 `POST /api/v1/tenants`
创建新租户，或通过 API 建普通成员账号（见
[05 API 与 CLI](05-api-and-cli.md#12-鉴权账号与租户m19)）。

新建会话时选择 **Fake Demo（离线）**，然后输入：

| 输入 | 说明 |
|---|---|
| `/read README.md` | 读取文件 |
| `/ls .` | 列目录 |
| `/write notes.txt 你好` | 写文件，会弹出审批框 |
| `/tool` | 列出全部工具（含插件），选中后填表运行 |
| `/tool repo_map {"query":"附件 配额"}` | 按关键词定位相关文件；省略参数则看仓库概览 |
| `/skill:deploy 任务` | 使用技能（需先创建技能，见下） |

`/tool` 后面不需要记工具名，也不需要手写 JSON：菜单会显示每个工具的中文名称与说明，
选中后按表单填写参数即可。工具卡片、审批弹窗与历史消息同样使用这些中文名称。

新建会话时「会话名称」可以留空：首条消息发出后，侧栏会自动用它的一句话摘要命名，
之后随时可以在会话菜单里改名。升级前遗留的“新会话”也会在启动时按首条消息回填。

Fake 模型只会执行这些固定指令，不会真正思考；它用来验证链路是否通畅。

写工具成功后，输入框上方会出现“本轮改动”面板；点击可查看逐轮 diff 与提交建议，
“撤销本轮”会把本轮改动的文件还原到运行前，并回退对话。该能力要求工作区是
Git 仓库（安装 git 即可，不需要提交），且不会修改你的分支、HEAD 或提交历史。

## 二、接入 DeepSeek（真正开始用）

### 1. 设置 Key（仅当前终端有效，不落盘）

```powershell
$env:PI_DEEPSEEK_KEY = "sk-你的key"
```

关闭终端后失效，需要重新设置。这是刻意设计：Key 不写入任何文件。

### 2. 启动

```powershell
.\scripts\start.ps1
```

脚本会自动完成：

1. 检测 JDK 21+；
2. 启动服务（demo profile，H2 文件库落在 `.\data\`）；
3. 等待 `/api/v1/system/info` 就绪；
4. 若 `deepseek` 模型档案不存在则创建（**只存环境变量名**，不存 Key）；
5. 打印访问地址。

### 3. 使用

打开 <http://127.0.0.1:8080> → 新建会话 → 模型选择 **DeepSeek Chat** → 用自然语言提问。

模型会自主调用工具：`read` / `ls` / `grep` / `glob` 读代码，`repo_map` 先看仓库
结构与按任务定位文件；`write` / `edit` 改文件（需审批）；`bash` 执行命令
（默认拒绝，需在策略里放行）。

## 二点五、命令行（CLI）

同一个 jar 不带参数时启动 Web 服务；带子命令时进入 CLI，退出后进程结束。
CLI 与 Web 共用同一个 Spring 容器、数据库、工作目录校验与审批管线。

```powershell
$Jar = "pi-java-app\target\pi-java-app-0.2.0.jar"

# 单次执行：默认流式输出
& $Java $Jar run "读取 pom.xml 并总结依赖" --cwd D:\code\demo

# 脚本友好的 JSON 输出（stdout 只有一个 JSON，提示走 stderr）
& $Java $Jar run "跑一遍测试并总结失败" --cwd D:\code\demo --json --timeout 900

# 交互式对话：终端里直接进入对话框界面
& $Java $Jar chat --cwd D:\code\demo
& $Java $Jar chat --session 01J...
# 管道或脚本里退回逐行模式；需要固定逐行输出时加 --plain
"你好`n/exit`n" | & $Java $Jar chat --plain

# 只读查询
& $Java $Jar sessions list --cwd D:\code\demo --limit 20
& $Java $Jar sessions show 01J...
& $Java $Jar models list
& $Java $Jar tools list --cwd D:\code\demo

# 显式启动 Web
& $Java $Jar serve --port 8081
```

执行 `run` 时：

- 写工具（`write`/`edit`/`bash` 等按策略需要审批的工具）会在终端询问审批，
  输入 `y` 批准、其它输入或直接回车拒绝；
- stdin 已结束（EOF）时一律按拒绝处理，适合无人值守脚本；
- `--approve` 自动批准本次 run 的全部审批，仅建议在可信工作区使用；
- 退出码：`0` 成功、`1` 运行失败或超时、`2` 参数错误、`3` 审批被拒绝、`4` 取消。

`chat` 在真实终端里默认进入交互式界面：方框输入区、彩色流式输出、
输入 `/` 即淡色列出可用命令、`Tab` 补全斜杠命令、`↑/↓` 翻历史。`Ctrl+C`
运行中取消当前任务，空闲时连按两次退出；`Ctrl+D` 随时退出。`--plain`
强制使用逐行模式。

两种模式都支持：

| 命令 | 说明 |
|---|---|
| `/help` | 显示帮助 |
| `/exit`、`/quit` | 退出 |
| `/abort` | 取消运行中的任务（逐行模式可用；TUI 运行中直接 Ctrl+C） |
| `/compact` | 手动压缩当前上下文 |
| `/model [ID]` | 查看或切换模型 |
| `/tree` | 查看当前分支 entry |
| `/session` | 查看会话信息 |
| `/skill:名称 任务` | 以技能方式发送消息 |

> 本机 PATH 上的 `java` 可能不是 JDK 21，请先确认 `java -version`；
> 或把 `$Java` 换成 JDK 21 的绝对路径（`scripts\start.ps1` 里的探测逻辑可参考）。

## 三、脚本参数

```powershell
.\scripts\start.ps1 [-Port 8081] [-SkipModel] [-Profile deepseek]
```

| 参数 | 默认值 | 说明 |
|---|---|---|
| `-Port` | `8080` | 监听端口；被占用时会提示 |
| `-SkipModel` | 关闭 | 跳过模型档案创建/检查 |
| `-Profile` | `deepseek` | 模型档案 ID |
| `$env:PI_AUTH_TOKEN` | 无 | 可选；脚本检查/创建模型档案所需的登录令牌 |

> M19 起所有接口都需要登录，脚本自身没有账号密码：只有设置了 `PI_AUTH_TOKEN`
> 才会检查/创建模型档案，否则只提示、不影响启动。最省事的方式是登录后在
> “设置 → 模型与密钥”里创建一次，档案保存在数据库里，之后每次启动都有效。

## 四、换成其它模型服务

脚本头部集中了四个参数，改完即可：

```powershell
$ApiKeyEnv     = "PI_DEEPSEEK_KEY"
$ModelBaseUrl  = "https://api.deepseek.com/v1"
$ModelName     = "deepseek-chat"
$ContextWindow = 64000
```

改完后在浏览器登录，到“设置 → 模型与密钥”创建同名档案即可（脚本只在设置了
`$env:PI_AUTH_TOKEN` 时才代劳）。

常见的 OpenAI-compatible 服务：

| 服务 | baseUrl | modelName | 上下文窗口 |
|---|---|---|---|
| 通义千问 | `https://dashscope.aliyuncs.com/compatible-mode/v1` | `qwen-max` | 32000 |
| Moonshot | `https://api.moonshot.cn/v1` | `moonshot-v1-32k` | 32000 |
| 本地 Ollama | `http://127.0.0.1:11434/v1` | `qwen2.5-coder:14b` | 32000 |

> `contextWindow` 填太小会导致压缩频繁触发，填太大会让上下文溢出后才报错。
> 建议按服务商文档里的真实值填写。

## 五、技能（可选）

技能把团队规范沉淀成可复用指令，按需加载，不占用每次请求的上下文。

```powershell
$dir = Join-Path $env:USERPROFILE ".pi-java\skills\deploy"
New-Item -ItemType Directory -Force -Path $dir | Out-Null
```

在 `$dir\SKILL.md` 中写入：

```markdown
---
name: deploy
description: 打包与发布流程
---
第一步：执行 mvn -q package
第二步：运行 mvn verify
```

之后：

```powershell
curl http://127.0.0.1:8080/api/v1/skills          # 查看目录
```

对话中输入 `/skill:deploy 发布 1.2.0`，技能正文会被展开后发送。

## 六、项目约定（可选）

在项目根目录放 `AGENTS.md`，内容会自动进入 system prompt，用来声明
代码风格、测试要求等团队约定。

## 六点五、子代理（M8）

模型可以在对话中自行调用 `subagent` 工具，把一段自包含的任务交给子代理执行，
只拿回结论。默认策略是 `ASK`（需要审批），放行后模型才会使用。

参数示例（模型自行填写，也可手工通过 `/tool` 触发）：

```json
{
  "mode": "SPAWN",
  "task": "阅读 pom.xml，总结项目用到的所有第三方依赖及其用途",
  "persona": "严谨的构建工程师",
  "tools": ["read", "grep"],
  "timeoutSeconds": 120
}
```

- `mode=SPAWN`：全新子会话，不继承当前对话；
- `mode=FORK`：继承当前上下文继续工作；
- `tools`：白名单，只能是你已经拥有的工具的子集；
- 子代理是**新会话**，会出现在会话树里，可单独查看其完整过程。

相关配置：

```yaml
pi:
  agent:
    max-subagent-depth: 2              # 用户 → 子 → 孙；到上限后不再提供 subagent 工具
    subagent-timeout-seconds: 300      # 默认超时
    subagent-max-timeout-seconds: 600  # 上限，不得超过 600（受工具调度约束）
```

放行方式（默认 ASK）：

```powershell
curl -X PUT http://127.0.0.1:8080/api/v1/tool-policies `
  -H "Content-Type: application/json" `
  -d '{"scope":"USER","toolName":"subagent","effect":"ALLOW"}'
```

`scope` 可取 `USER`（所有工作区）或 `WORKSPACE`（需要同时给 `cwd`）。

> 注意：使用 Fake Demo 模型时子代理无法真正执行（Fake 按最后一条用户消息匹配指令），
> 需要用真实模型验证效果。

## 六点七、计划与自动验证（M14）

给会话配置一次验证命令，代理改过文件并准备收尾时会自动执行：

```powershell
# 启动前设置；留空表示关闭
$env:PI_VERIFY_COMMAND = "mvn -q test"
```

也可以在 `application.yml` 中固定：

```yaml
pi:
  verify:
    command: "mvn -q test"   # Windows 也支持 cmd 命令
    timeout-seconds: 120
    max-output-chars: 6000
    retry-on-failure: true   # 失败后把输出回写一次，让模型修复
```

体验待办面板（Fake demo 可直接使用）：

```text
/tool todo {"todos":[{"content":"读代码","status":"done"},{"content":"改实现","status":"in_progress"},{"content":"跑测试","status":"pending"}]}
```

- 待办最多 20 条、最多一条 `in_progress`；每次调用整表替换，传空数组清空；
- 计划写入会话日志，刷新、切换会话或重启后仍在；
- 验证只在本次 run 执行过 `write` / `edit` / `bash` 时运行；
- 失败输出会作为用户消息交回模型，同一 run 最多重试一次；
- CLI 用 `/plan` 查看当前计划与最近验证结果。

详见 [13 计划、待办与自动验证](13-plans-and-verification.md)。

## 六点八、Docker 与接口文档（M15）

没有 JDK 21 时可以直接用容器；数据库落在命名卷，仓库目录挂到 `/workspace`：

```bash
docker compose up --build
```

MySQL 变体与手动 `docker build` / `docker run` 命令见
[14 部署、容器与 CI](14-deployment.md)。

服务启动后：

- <http://127.0.0.1:8080/swagger-ui.html>：Swagger UI；
- <http://127.0.0.1:8080/v3/api-docs>：OpenAPI 3 JSON。

容器内固定使用 `/app/data` 存 H2；本机运行可用 `PI_DATA_DIR` 改数据目录，
例如 `$env:PI_DATA_DIR = "D:\pi-java-data"`。

## 六点九、图片输入与附件管理（M16/M17）

Web UI 输入框左侧的图片按钮、剪贴板粘贴或直接拖入，都可以添加图片；
发送前会显示缩略图，点 × 可移除。demo profile 自动创建的 `fake-demo`
档案支持视觉能力，可以直接体验完整链路；自己创建模型档案时，只有
`endpoint.capabilities.vision=true` 的模型才允许发送图片。

```text
支持格式：PNG / JPEG / WebP / GIF
单张上限：5 MB（压缩后）    单条上限：4 张    整个请求：6 MB
```

超过 1.5 MB 的非 GIF 图片会先在浏览器里缩放到最长边 1568 px、转成 WebP，
再上传；卡片显示压缩后大小，悬停可看原图大小。压缩失败或浏览器不支持时
回退原图，服务端仍会按 5 MB 复核。

服务端会：

- 按文件头识别格式，剥离 EXIF/XMP/注释等元数据后再落盘；
- 对清洗后的内容做 SHA-256 去重，相同图片只存一份；
- 按 `pi.attachments.max-total-bytes`（默认 512 MB）限制总量，
  超限返回 413 `ATTACHMENT_QUOTA_EXCEEDED`；
- 每天 03:40 回收“没有会话引用、且超过 `gc-min-age`”的附件；
  也可以在“设置 → 系统信息 → 附件存储”里查看用量并手动清理。

图片先上传再随消息发送，只保存附件 id 引用，不把 base64 写进会话日志；
存储位置为 `$PI_DATA_DIR/attachments/`，备份或迁移数据目录时要连同此目录一起复制。
接口细节见 [05 API 与 CLI](05-api-and-cli.md)，实现记录见
[M16](milestones/M16.md) 与 [M17](milestones/M17.md)。

## 七、常见问题

**Q：脚本报"表达式或语句中包含意外的标记"，满屏解析错误**
用的是 Windows PowerShell 5.1，且脚本文件被保存成了**无 BOM 的 UTF-8**，中文被按 GBK
解析导致字符串断裂。本仓库的 `start.ps1` 已带 UTF-8 BOM；如果你自己复制或改过文件，
请用"UTF-8 with BOM"重新保存。

**Q：提示"未找到 JDK 21"**
安装 JDK 21，或把 `JAVA_HOME` 指向它。脚本只能在你已安装的 JDK 中选择。
（PowerShell 5.1 会把 `java -version` 的 stderr 当作终止性错误，脚本已做了兼容处理，
不会再误报版本为 0。）

**Q：端口被占用**
换端口：`.\scripts\start.ps1 -Port 8081`。或先确认占用者：
`Get-NetTCPConnection -LocalPort 8080 -State Listen`

**Q：选了 DeepSeek 但报"缺少 API Key 环境变量"**
Key 是在**启动服务的那个终端**里设置的。如果换了终端，需要重新
`$env:PI_DEEPSEEK_KEY = "..."` 并重启服务。

**Q：会话数据在哪**
`.\data\pi-java.mv.db`（H2 文件库；可用 `PI_DATA_DIR` 改目录），图片附件在
`.\data\attachments\`。删除该目录即清空全部会话、账号与令牌，下次启动会重新建表
并显示管理员初始化页面。

**Q：忘记密码了怎么办**
M19 只提供本地账号，没有邮件重置。可在关闭服务后删除 `.\data` 重新初始化，
或用 `POST /api/v1/users` 在管理员账号下新建一个账号。

**Q：想换成 MySQL**
启动时加 `--spring.profiles.active=mysql`，并通过 `PI_MYSQL_URL`、
`PI_MYSQL_USER`、`PI_MYSQL_PASSWORD` 环境变量注入连接信息。

**Q：`bash` 工具调不动**
默认策略是 `DENY`。通过 `PUT /api/v1/tool-policies` 放行，或在 Web UI 中调整策略。

## 八、安全提示

- 服务默认只监听 `127.0.0.1`；M19 起 Web 与 HTTP API 需要登录，但仍建议只在本机
  或可信内网使用，不要把默认端口直接暴露到公网；
- 账号密码只保存在本地数据库（PBKDF2 哈希），登录令牌只保存 SHA-256 摘要；
- Key 只从环境变量读取，数据库里只保存变量名；
- `write` / `edit` 默认需要人工审批；`bash` 默认拒绝；
- 插件运行在同进程内，类加载隔离不是安全沙箱，只安装可信来源的插件。
