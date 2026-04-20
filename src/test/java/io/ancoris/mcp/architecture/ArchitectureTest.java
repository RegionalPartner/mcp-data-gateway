package io.ancoris.mcp.architecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noFields;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

@AnalyzeClasses(
        packages = "io.ancoris.mcp",
        importOptions = ImportOption.DoNotIncludeTests.class
)
public class ArchitectureTest {

    private static final String ROOT = "io.ancoris.mcp";

    // model is the base layer — no outbound deps on other project packages
    @ArchTest
    static final ArchRule MODEL_IS_SELF_CONTAINED =
            noClasses().that().resideInAPackage(ROOT + ".model..")
                    .should().dependOnClassesThat()
                    .resideInAnyPackage(
                            ROOT + ".tools..", ROOT + ".connector..", ROOT + ".security..",
                            ROOT + ".audit..", ROOT + ".config..", ROOT + ".oauth.."
                    )
                    .because("model must have no upward dependencies");

    // connector reads data — must not call tool or security logic
    @ArchTest
    static final ArchRule CONNECTOR_ISOLATION =
            noClasses().that().resideInAPackage(ROOT + ".connector..")
                    .should().dependOnClassesThat()
                    .resideInAnyPackage(
                            ROOT + ".tools..", ROOT + ".security..", ROOT + ".audit..", ROOT + ".config.."
                    )
                    .because("connector is a data-access layer; it must not depend on tools, security, or config");

    // tools are the MCP-facing layer — must not reach into internal security classes
    @ArchTest
    static final ArchRule TOOLS_DO_NOT_DEPEND_ON_INTERNAL_SECURITY =
            noClasses().that().resideInAPackage(ROOT + ".tools..")
                    .should().dependOnClassesThat()
                    .resideInAPackage(ROOT + ".security..")
                    .because("tools must use Spring Security APIs directly, not internal security classes");

    // Repositories belong only in audit and security packages
    @ArchTest
    static final ArchRule REPOSITORIES_IN_PERSISTENCE_PACKAGES =
            classes().that().areAnnotatedWith("org.springframework.stereotype.Repository")
                    .should().resideInAnyPackage(ROOT + ".security..", ROOT + ".audit..")
                    .because("@Repository interfaces belong only in security and audit packages");

    // No field injection — constructor injection only
    @ArchTest
    static final ArchRule NO_FIELD_INJECTION =
            noFields().should().beAnnotatedWith("org.springframework.beans.factory.annotation.Autowired")
                    .because("constructor injection is mandatory; field injection hides dependencies");

    // No console output in production code
    @ArchTest
    static final ArchRule NO_SYSTEM_OUT =
            noClasses().should().accessField(System.class, "out")
                    .orShould().accessField(System.class, "err")
                    .because("use SLF4J, not System.out/err");

    // oauth <-> security cycle is pre-existing: OAuthController depends on ApiKeyService
    // and ApiKeyFilter depends on JwtTokenService. Ignored here to prevent new cycles elsewhere.
    @ArchTest
    static final ArchRule NO_NEW_PACKAGE_CYCLES =
            slices().matching(ROOT + ".(*)..")
                    .should().beFreeOfCycles()
                    .ignoreDependency(
                            resideInAPackage(ROOT + ".oauth.."),
                            resideInAPackage(ROOT + ".security..")
                    )
                    .ignoreDependency(
                            resideInAPackage(ROOT + ".security.."),
                            resideInAPackage(ROOT + ".oauth..")
                    );
}
