package com.huawei.it.ex.one.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Dependency guard for the bounded-context architecture.
 *
 * <p>The legacy layer-first root packages are no longer supported. Cross-context collaboration must use application
 * service interfaces and application models without reaching another context's domain or adapters.</p>
 */
class DddDependencyArchitectureTest {
    private static JavaClasses productionClasses;

    static final ArchRule domainMustRemainFrameworkIndependent = noClasses()
            .that().resideInAPackage("..domain..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "org.springframework..",
                    "reactor..",
                    "org.apache.ibatis..",
                    "com.huawei.it.ex.one.application..",
                    "com.huawei.it.ex.one.infrastructure..",
                    "com.huawei.it.ex.one.interfaces.."
            );

    static final ArchRule interfacesMustNotReachInfrastructure = noClasses()
            .that().resideInAPackage("..interfaces..")
            .should().dependOnClassesThat().resideInAPackage("..infrastructure..");

    static final ArchRule boundedContextApplicationsMustNotReachTechnicalAdapters = noClasses()
            .that().resideInAnyPackage(
                    "com.huawei.it.ex.one.chat.application..",
                    "com.huawei.it.ex.one.intent.application..",
                    "com.huawei.it.ex.one.runtime.application..",
                    "com.huawei.it.ex.one.document.application..",
                    "com.huawei.it.ex.one.share.application.."
            )
            .should().dependOnClassesThat().resideInAnyPackage(
                    "..infrastructure..",
                    "..interfaces.."
            )
            .allowEmptyShould(true);

    static final ArchRule boundedContextServicesMustNotDependOnForeignDomains = noClasses()
            .that().resideInAPackage("com.huawei.it.ex.one.chat.application..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "com.huawei.it.ex.one.intent.domain..",
                    "com.huawei.it.ex.one.runtime.domain..",
                    "com.huawei.it.ex.one.document.domain..",
                    "com.huawei.it.ex.one.share.domain.."
            )
            .allowEmptyShould(true);

    static final ArchRule boundedContextInterfacesMustEndInService = classes()
            .that().resideInAnyPackage(
                    "com.huawei.it.ex.one.chat.application.service..",
                    "com.huawei.it.ex.one.intent.application.service..",
                    "com.huawei.it.ex.one.runtime.application.service..",
                    "com.huawei.it.ex.one.document.application.service..",
                    "com.huawei.it.ex.one.share.application.service.."
            )
            .and().areTopLevelClasses()
            .and().areInterfaces()
            .should().haveSimpleNameEndingWith("Service")
            .allowEmptyShould(true);

    static final ArchRule chatMustUseForeignApplicationBoundaries = noClasses()
            .that().resideInAPackage("com.huawei.it.ex.one.chat..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "com.huawei.it.ex.one.intent.domain..",
                    "com.huawei.it.ex.one.intent.infrastructure..",
                    "com.huawei.it.ex.one.intent.interfaces..",
                    "com.huawei.it.ex.one.intent.compat..",
                    "com.huawei.it.ex.one.intent.application.repository..",
                    "com.huawei.it.ex.one.intent.application.client..",
                    "com.huawei.it.ex.one.runtime.domain..",
                    "com.huawei.it.ex.one.runtime.infrastructure..",
                    "com.huawei.it.ex.one.runtime.interfaces..",
                    "com.huawei.it.ex.one.runtime.application.repository..",
                    "com.huawei.it.ex.one.runtime.application.client..",
                    "com.huawei.it.ex.one.document.domain..",
                    "com.huawei.it.ex.one.document.infrastructure..",
                    "com.huawei.it.ex.one.document.interfaces..",
                    "com.huawei.it.ex.one.document.application.repository..",
                    "com.huawei.it.ex.one.document.application.client..",
                    "com.huawei.it.ex.one.share.domain..",
                    "com.huawei.it.ex.one.share.infrastructure..",
                    "com.huawei.it.ex.one.share.interfaces..",
                    "com.huawei.it.ex.one.share.application.repository..",
                    "com.huawei.it.ex.one.share.application.client.."
            )
            .allowEmptyShould(true);

    static final ArchRule chatMustUseForeignServiceInterfaces = classes()
            .that().resideInAPackage("com.huawei.it.ex.one.chat..")
            .should(new ArchCondition<>("depend only on foreign application service interfaces") {
                @Override
                public void check(JavaClass source, ConditionEvents events) {
                    source.getDirectDependenciesFromSelf().stream()
                            .filter(dependency -> foreignServicePackage(
                                    dependency.getTargetClass().getPackageName()))
                            .filter(dependency -> !dependency.getTargetClass().isInterface())
                            .forEach(dependency -> events.add(SimpleConditionEvent.violated(
                                    source, dependency.getDescription())));
                }
            })
            .allowEmptyShould(true);

    static final ArchRule intentMustNotReachOtherContexts = noClasses()
            .that().resideInAPackage("com.huawei.it.ex.one.intent..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "com.huawei.it.ex.one.chat..",
                    "com.huawei.it.ex.one.runtime..",
                    "com.huawei.it.ex.one.document..",
                    "com.huawei.it.ex.one.share.."
            )
            .allowEmptyShould(true);

    static final ArchRule runtimeMustNotReachOtherContexts = noClasses()
            .that().resideInAPackage("com.huawei.it.ex.one.runtime..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "com.huawei.it.ex.one.chat..",
                    "com.huawei.it.ex.one.intent..",
                    "com.huawei.it.ex.one.document..",
                    "com.huawei.it.ex.one.share.."
            )
            .allowEmptyShould(true);

    static final ArchRule documentMustNotReachOtherContexts = noClasses()
            .that().resideInAPackage("com.huawei.it.ex.one.document..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "com.huawei.it.ex.one.chat..",
                    "com.huawei.it.ex.one.intent..",
                    "com.huawei.it.ex.one.runtime..",
                    "com.huawei.it.ex.one.share.."
            )
            .allowEmptyShould(true);

    static final ArchRule shareMustUseChatApplicationBoundary = noClasses()
            .that().resideInAPackage("com.huawei.it.ex.one.share..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "com.huawei.it.ex.one.chat.domain..",
                    "com.huawei.it.ex.one.chat.infrastructure..",
                    "com.huawei.it.ex.one.chat.interfaces..",
                    "com.huawei.it.ex.one.chat.application.repository..",
                    "com.huawei.it.ex.one.chat.application.coordinator.."
            )
            .allowEmptyShould(true);

    static final ArchRule boundedContextsMustBeFreeOfCycles = slices()
            .matching("com.huawei.it.ex.one.(*)..")
            .should().beFreeOfCycles();

    static final ArchRule legacyRootPackagesMustRemainEmpty = noClasses()
            .should().resideInAnyPackage(
                    "com.huawei.it.ex.one.application..",
                    "com.huawei.it.ex.one.domain..",
                    "com.huawei.it.ex.one.infrastructure..",
                    "com.huawei.it.ex.one.interfaces.."
            )
            .allowEmptyShould(true);

    @BeforeAll
    static void importProductionClasses() {
        productionClasses = new ClassFileImporter()
                .withImportOption(new ImportOption.DoNotIncludeTests())
                .importPackages("com.huawei.it.ex.one");
    }

    @Test
    void domainRemainsFrameworkIndependent() {
        domainMustRemainFrameworkIndependent.check(productionClasses);
    }

    @Test
    void interfacesDoNotReachInfrastructure() {
        interfacesMustNotReachInfrastructure.check(productionClasses);
    }

    @Test
    void boundedContextApplicationsDoNotReachTechnicalAdapters() {
        boundedContextApplicationsMustNotReachTechnicalAdapters.check(productionClasses);
    }

    @Test
    void chatApplicationDoesNotReachForeignDomains() {
        boundedContextServicesMustNotDependOnForeignDomains.check(productionClasses);
    }

    @Test
    void boundedContextInterfacesUseServiceNames() {
        boundedContextInterfacesMustEndInService.check(productionClasses);
    }

    @Test
    void chatUsesOnlyForeignApplicationBoundaries() {
        chatMustUseForeignApplicationBoundaries.check(productionClasses);
        chatMustUseForeignServiceInterfaces.check(productionClasses);
    }

    @Test
    void intentRuntimeAndDocumentRemainIndependent() {
        intentMustNotReachOtherContexts.check(productionClasses);
        runtimeMustNotReachOtherContexts.check(productionClasses);
        documentMustNotReachOtherContexts.check(productionClasses);
    }

    @Test
    void shareUsesOnlyTheChatApplicationBoundary() {
        shareMustUseChatApplicationBoundary.check(productionClasses);
    }

    @Test
    void boundedContextsAreFreeOfCycles() {
        boundedContextsMustBeFreeOfCycles.check(productionClasses);
    }

    @Test
    void legacyRootPackagesRemainEmpty() {
        legacyRootPackagesMustRemainEmpty.check(productionClasses);
    }

    private static boolean foreignServicePackage(String packageName) {
        return packageName.startsWith("com.huawei.it.ex.one.intent.application.service")
                || packageName.startsWith("com.huawei.it.ex.one.runtime.application.service")
                || packageName.startsWith("com.huawei.it.ex.one.document.application.service")
                || packageName.startsWith("com.huawei.it.ex.one.share.application.service");
    }
}
