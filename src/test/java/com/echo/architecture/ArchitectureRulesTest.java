package com.echo.architecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * 模組邊界規則，詳見 docs/design/engine-extensibility.md §3。
 * <p>
 * 只放目前已經成立的規則；新增規則前先確認現有程式碼不違反，避免用例外清單掩蓋問題。
 */
@AnalyzeClasses(packages = "com.echo", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureRulesTest {

    /** 核心不依賴 Spring、JPA、Servlet 或應用層，之後才能抽成獨立模組供嵌入式使用。 */
    @ArchTest
    static final ArchRule coreDependsOnlyOnPlainLibraries = classes()
            .that().resideInAPackage("com.echo.core..")
            .should().onlyDependOnClassesThat().resideInAnyPackage(
                    "com.echo.core..",
                    "java..",
                    "javax.xml..",
                    "org.w3c.dom..",
                    "org.xml.sax..",
                    "com.fasterxml.jackson..",
                    "com.jayway.jsonpath..")
            .because("the engine core must stay embeddable without Spring, JPA or a servlet container");

    /** 應用層可以使用核心，核心不能反過來依賴應用層。 */
    @ArchTest
    static final ArchRule coreDoesNotDependOnApplicationPackages = noClasses()
            .that().resideInAPackage("com.echo.core..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "com.echo.agent..", "com.echo.client..", "com.echo.config..",
                    "com.echo.controller..", "com.echo.dto..", "com.echo.entity..",
                    "com.echo.jms..", "com.echo.migration..", "com.echo.pipeline..",
                    "com.echo.protocol..", "com.echo.repository..", "com.echo.service..",
                    "com.echo.util..");

    @ArchTest
    static final ArchRule httpProtocolDoesNotDependOnJms = noClasses()
            .that().resideInAPackage("com.echo.protocol.http..")
            .should().dependOnClassesThat().resideInAnyPackage("com.echo.protocol.jms..", "com.echo.jms..");

    @ArchTest
    static final ArchRule jmsProtocolDoesNotDependOnHttp = noClasses()
            .that().resideInAnyPackage("com.echo.protocol.jms..", "com.echo.jms..")
            .should().dependOnClassesThat().resideInAPackage("com.echo.protocol.http..");
}
