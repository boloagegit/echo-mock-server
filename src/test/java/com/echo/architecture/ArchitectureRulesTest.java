package com.echo.architecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Module boundary rules, see docs/design/engine-extensibility.md §3 for details.
 * <p>
 * Only put the rules that are currently established; before adding new rules, confirm that the existing code does not violate it to avoid covering up the problem with the exception list.
 */
@AnalyzeClasses(packages = "com.echo", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureRulesTest {

    /** The core does not depend on Spring, JPA, Servlet or the application layer before it can be extracted into independent modules for embedded use. */
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

    /** The application layer can use the core, but the core cannot in turn depend on the application layer. */
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

    /**
     * Cross-protocol rule queries and cache synchronization do not reference protocol-specific rule classes directly;
     * {@code ProtocolDescriptor} supplies protocol differences, so adding a protocol requires no changes to these classes.
     */
    @ArchTest
    static final ArchRule protocolAgnosticServicesUseDescriptors = noClasses()
            .that().haveFullyQualifiedName("com.echo.service.RuleQueryService")
            .or().haveFullyQualifiedName("com.echo.service.CacheInvalidationService")
            .should().dependOnClassesThat().haveFullyQualifiedName("com.echo.entity.HttpRule")
            .orShould().dependOnClassesThat().haveFullyQualifiedName("com.echo.entity.JmsRule")
            .orShould().dependOnClassesThat().haveFullyQualifiedName("com.echo.entity.HttpRuleAction")
            .orShould().dependOnClassesThat().haveFullyQualifiedName("com.echo.entity.JmsRuleAction");
}
