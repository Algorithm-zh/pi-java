# 示例插件

验证 `pi-java-plugin-api` 的零依赖契约与插件生命周期。

## 计划内容

- `hello` 工具：输入 `name`，返回问候；
- `SessionStart` hook：记录会话开始；
- `hello` 命令：向当前会话发送一条插件消息；
- `META-INF/pi-plugin.json`：manifest；
- 独立编译：只依赖 plugin-api（provided），不依赖宿主其他模块。

## 构建

```bash
mvn -q -pl examples/pi-plugin-example -am package
```

产物是普通 jar，可在插件管理页或 CLI 中安装。

## 约束

- 插件工具完整名带 `com.example.hello.` 前缀；
- 不打包 `plugin-api`；
- 不访问 Spring/MyBatis/domain 类型；
- 插件拥有 JVM 权限，只安装可信来源。

## 当前状态

M0：目录与 pom 已建立；实现从 M5 开始。
