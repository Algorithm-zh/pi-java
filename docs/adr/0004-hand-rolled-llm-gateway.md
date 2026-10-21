# ADR 0004：手写 LLM Gateway，不引入 Spring AI

- 状态：Accepted
- 日期：2026-10-21
- 决策者：pi-java 架构

## 背景

v0.1 只需要两类模型接入：

1. `FakeLlmProvider`：确定性脚本，用于离线测试与 demo；
2. `OpenAI-compatible`：DeepSeek、OpenAI、Qwen、Ollama 等兼容 `/chat/completions` 的服务。

候选方案：

- Spring AI：生态完整，但抽象层厚、版本耦合强，SSE/工具调用细节不易完全掌控；
- LangChain4j：功能多，但同样引入较大依赖与自己的消息模型；
- 官方 SDK：各厂商接口不一致，多 provider 需要多套依赖；
- 手写 JDK `HttpClient` + Jackson SSE 解析：依赖最少，控制力最强。

pi-java 的核心竞争力不在“支持最多 provider”，而在 agent loop、会话日志与治理。
模型接入应当是可替换的适配器，而不是框架核心。

## 决策

定义领域端口 `LlmGateway`，v0.1 在 infrastructure 手写两个实现：

```java
interface LlmGateway {
    LlmStream stream(LlmRequest request, LlmCallContext context);
}
```

- HTTP：JDK 21 `HttpClient`，支持流式响应体；
- SSE：手写解析 `data:` 行、`[DONE]`、事件拼接与增量 toolCall 合并；
- JSON：Jackson 仅存在于 infrastructure，不进入 domain；
- 重试：只在 adapter 内对 429/5xx/网络错误重试最多 2 次；
  **首个 delta 已输出后不重试**，避免重复内容；
- 取消：关闭响应体，向上抛 `ABORTED`；
- 密钥：模型档案只存环境变量名，运行时读取，不落库、不进日志；
- `context_length_exceeded`：由应用层压缩一次后重试，adapter 不自行压缩；
- Anthropic 原生、OAuth 登录、图片输入留到 v0.2。

## 结果

正面：

- 依赖少，构建快，排障路径清晰；
- 完全掌控流式增量、toolCall 拼接、usage 解析、取消与重试语义；
- 领域层只认 `LlmGateway`，新增 provider 不改用例与领域模型；
- WireMock 可精确模拟各类流式故障。

负面：

- 需要自己维护 SSE 解析与 provider 差异（错误码、usage 字段、toolCall 增量格式）；
- 没有 Spring AI 的自动配置、可观测性与生态集成；
- 新 provider 的适配成本高于“换一个 starter”；
- 协议变更需要自己跟进。

## 边界

- 不使用 Spring AI、LangChain4j、官方 SDK；
- 不在 domain/application 出现 HTTP、JSON、SSE 类型；
- adapter 不实现“对话记忆”“RAG”“工具编排”，这些由 agent loop 与工具管线负责；
- 不做双层重试：application 不重试 LLM 调用；
- 不在日志中打印请求体全文与 Authorization。

## 替代方案

### Spring AI

抽象完整，但流式 toolCall 与取消语义受框架约束，且引入较大依赖；
与“小内核、可控适配器”的目标不符，v0.1 不采用。

### 每个 provider 一个官方 SDK

DeepSeek/OpenAI 兼容接口本身简单，官方 SDK 反而增加版本与传递依赖；
v0.2 若接入 Anthropic 原生，可在 adapter 内单独引入其 SDK，不污染其他实现。

### 只支持 Fake

无法完成真实编码任务验收；Fake 只用于测试与 demo，不是产品能力。
