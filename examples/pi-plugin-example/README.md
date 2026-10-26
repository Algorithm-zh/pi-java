# 示例插件

验证 `pi-java-plugin-api` 的零依赖契约、工具注册与插件生命周期。

## 内容

- `HelloPlugin`：入口类，启动时注册 `greet` 工具；
- `HelloTool`：输入 `name`，返回中文问候；
- `META-INF/pi-plugin.json`：插件清单；
- 独立编译：只依赖 plugin-api（provided），不依赖宿主其他模块。

## 构建

```bash
mvn -q -pl examples/pi-plugin-example -am package
```

产物是普通 jar：`target/pi-plugin-example-0.2.0.jar`。

## 安装与启用

```bash
curl -X POST http://127.0.0.1:8080/api/v1/plugins \
  -H 'Content-Type: application/json' \
  -d '{"jarPath":"D:/code/pi-java/examples/pi-plugin-example/target/pi-plugin-example-0.2.0.jar"}'

curl -X POST http://127.0.0.1:8080/api/v1/plugins/com.piagent.example.hello/enable
curl http://127.0.0.1:8080/api/v1/tools
```

启用后工具完整名为 `com.piagent.example.hello.greet`。

## 在 demo 中调用

Fake 模型支持 `/tool <工具名> <JSON参数>`：

1. 在 Web UI 新建会话；
2. 输入 `/tool com.piagent.example.hello.greet {"name":"pi"}`；
3. 应看到插件工具卡片与结果 `你好，pi！`。

停用插件：

```bash
curl -X POST http://127.0.0.1:8080/api/v1/plugins/com.piagent.example.hello/disable
```

停用后再次调用会得到 `工具未注册`；已经开始的 run 仍可使用旧工具快照完成。

## 约束

- 不打包 `plugin-api`，由宿主统一提供；
- 不访问 Spring/MyBatis/domain 类型；
- 插件拥有 JVM 权限，只安装可信来源；
- 命名空间 `com.piagent.example.hello` 与工具前缀一致。

## 当前状态

M5：实现问候工具与完整安装/启用/调用/停用链路；hook 与命令注册推迟到后续里程碑。
