package dev.configflow.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Enforces the Clean Architecture dependency rules of docs/03 and docs/04 at the
 * class level (module boundaries already enforce them at compile time; this catches
 * accidental loosening of module dependencies too).
 */
class LayeringRulesTest {

    private static JavaClasses allClasses;

    @BeforeAll
    static void importClasses() {
        allClasses = new ClassFileImporter()
                .withImportOption(new ImportOption.DoNotIncludeTests())
                .importPackages("dev.configflow");
    }

    @Test
    void domainDependsOnNothingButItselfAndJdk() {
        classes()
                .that().resideInAPackage("dev.configflow.domain..")
                .should().onlyDependOnClassesThat()
                .resideInAnyPackage("java..", "dev.configflow.domain..")
                .check(allClasses);
    }

    @Test
    void domainHasNoFrameworkOrVcsLibraryDependencies() {
        noClasses()
                .that().resideInAPackage("dev.configflow.domain..")
                .should().dependOnClassesThat()
                .resideInAnyPackage(
                        "org.springframework..",
                        "org.eclipse.jgit..",
                        "org.tmatesoft..",
                        "org.flywaydb..",
                        "org.sqlite..",
                        "dev.configflow.application..",
                        "dev.configflow.infrastructure..",
                        "dev.configflow.api..",
                        "dev.configflow.config..")
                .check(allClasses);
    }

    @Test
    void applicationDependsOnlyOnDomainAndJdk() {
        classes()
                .that().resideInAPackage("dev.configflow.application..")
                .should().onlyDependOnClassesThat()
                .resideInAnyPackage("java..", "dev.configflow.domain..", "dev.configflow.application..")
                .check(allClasses);
    }

    @Test
    void infrastructureModulesDoNotDependOnEachOtherOrOuterLayers() {
        List<String> infraPackages = List.of(
                "dev.configflow.infrastructure.git..",
                "dev.configflow.infrastructure.svn..",
                "dev.configflow.infrastructure.persistence..",
                "dev.configflow.infrastructure.credential..",
                "dev.configflow.infrastructure.ai..");
        for (String pkg : infraPackages) {
            List<String> forbidden = new ArrayList<>(List.of(
                    "dev.configflow.application..",
                    "dev.configflow.api..",
                    "dev.configflow.config.."));
            infraPackages.stream().filter(other -> !other.equals(pkg)).forEach(forbidden::add);

            noClasses()
                    .that().resideInAPackage(pkg)
                    .should().dependOnClassesThat()
                    .resideInAnyPackage(forbidden.toArray(String[]::new))
                    .check(allClasses);
        }
    }
}
