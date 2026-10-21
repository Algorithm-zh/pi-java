package com.piagent.app.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * 模块依赖方向的架构守护测试。
 *
 * <p>这些规则是编译期依赖声明的第二道保险：即使有人误加 Maven 依赖，
 * 只要出现了越界引用，{@code mvn verify} 就会失败。规则按“谁能看见谁”
 * 描述，而不是按文件名，因此新增类不会被遗漏。
 */
@AnalyzeClasses(packages = "com.piagent", importOptions = ImportOption.DoNotIncludeTests.class)
class ModuleBoundaryTest {

    /** 领域层必须只依赖 JDK，保证领域模型可脱离框架单测。 */
    @ArchTest
    static final ArchRule DOMAIN_IS_PURE_JDK = noClasses()
            .that().resideInAPackage("com.piagent.domain..")
            .should().dependOnClassesThat()
            .resideOutsideOfPackages("java..", "com.piagent.domain..");

    /** 应用层只编排领域用例，不直接触碰 Web、ORM、JSON 与插件适配器。 */
    @ArchTest
    static final ArchRule APPLICATION_AVOIDS_ADAPTERS = noClasses()
            .that().resideInAPackage("com.piagent.application..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "com.piagent.infrastructure..",
                    "com.piagent.trigger..",
                    "com.piagent.plugin.api..",
                    "org.apache.ibatis..",
                    "com.fasterxml.jackson..",
                    "org.springframework.web..",
                    "org.springframework.http..",
                    "jakarta..");

    /** 插件契约是第三方可见的稳定 API，只能出现 JDK 类型。 */
    @ArchTest
    static final ArchRule PLUGIN_API_IS_JDK_ONLY = noClasses()
            .that().resideInAPackage("com.piagent.plugin.api..")
            .should().dependOnClassesThat()
            .resideOutsideOfPackages("java..", "com.piagent.plugin.api..");

    /** 触发层只调用应用层，不绕过用例直接使用仓储或 Mapper。 */
    @ArchTest
    static final ArchRule TRIGGER_AVOIDS_INFRASTRUCTURE = noClasses()
            .that().resideInAPackage("com.piagent.trigger..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "com.piagent.infrastructure..",
                    "org.apache.ibatis..");

    /** 基础设施是适配器，不得反向依赖 HTTP/CLI 触发层。 */
    @ArchTest
    static final ArchRule INFRASTRUCTURE_AVOIDS_TRIGGER = noClasses()
            .that().resideInAPackage("com.piagent.infrastructure..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "com.piagent.trigger..");
}
