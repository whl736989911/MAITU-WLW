package com.fireplatform;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;
import static org.assertj.core.api.Assertions.*;

class ArchitectureTest {
    private final JavaClasses classes=new ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS).importPackages("com.fireplatform");
    @Test void domainPackagesHaveNoCycles() { slices().matching("com.fireplatform.(*)..").should().beFreeOfCycles().check(classes); }
    @Test void moduleDependenciesPointTowardPublicLowerLevelBoundaries() {
        noClasses().that().resideInAPackage("..shared..").should().dependOnClassesThat().resideInAnyPackage("..identity..","..configuration..","..device..","..alarm..","..product..","..ingestion..","..processing..","..bootstrap..").check(classes);
        noClasses().that().resideInAPackage("..identity..").should().dependOnClassesThat().resideInAnyPackage("..configuration..","..device..","..alarm..","..product..","..ingestion..","..processing..","..bootstrap..").check(classes);
        noClasses().that().resideInAPackage("..configuration..").should().dependOnClassesThat().resideInAnyPackage("..device..","..alarm..","..product..","..ingestion..","..processing..","..bootstrap..").check(classes);
        noClasses().that().resideInAPackage("..alarm..").should().dependOnClassesThat().resideInAnyPackage("..device..","..product..","..ingestion..","..processing..","..bootstrap..").check(classes);
        noClasses().that().resideInAPackage("..device..").should().dependOnClassesThat().resideInAnyPackage("..ingestion..","..processing..","..bootstrap..").check(classes);
        noClasses().that().resideInAPackage("..ingestion..").should().dependOnClassesThat().resideInAnyPackage("..processing..","..bootstrap..","javax.script..","java.net..").check(classes);
        noClasses().that().resideInAPackage("..product..").should().dependOnClassesThat().resideInAnyPackage("..device..","..alarm..","..ingestion..","..processing..","..bootstrap..").check(classes);
        noClasses().that().resideInAPackage("..processing..").should().dependOnClassesThat().resideInAnyPackage("..alarm..","..bootstrap..","javax.script..","java.net..","java.lang.reflect..").check(classes);
        noClasses().that().resideInAPackage("..product..").should().dependOnClassesThat().resideInAnyPackage("javax.script..","java.net..","java.net.http..","java.lang.reflect..","java.nio.file..").check(classes);
        noClasses().that().resideInAnyPackage("..shared..","..identity..","..configuration..","..device..","..alarm..","..processing..","..ingestion..","..product..").should().dependOnClassesThat().resideInAPackage("..notification..").check(classes);
        noClasses().that().resideInAPackage("..notification..").should().dependOnClassesThat().resideInAnyPackage("..processing..","..ingestion..","..product..","..bootstrap..","java.net..","java.nio.file..","javax.script..").check(classes);
    }
}
