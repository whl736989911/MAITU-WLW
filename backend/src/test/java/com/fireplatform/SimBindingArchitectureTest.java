package com.fireplatform;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.*;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.*;

class SimBindingArchitectureTest {
    final JavaClasses classes=new ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS).importPackages("com.fireplatform");
    @Test void bindingDomainNeverImportsCardDeviceBusinessOrProviderSchedulerNetworkClasses(){noClasses().that().resideInAPackage("..simbinding..").should().dependOnClassesThat().resideInAnyPackage("..simcard..","..device..","..devicegroup..","..alarm..","..product..","..ingestion..","..processing..","..notification..","..bootstrap..","java.net..","java.util.concurrent..","java.nio.file..","javax.script..","org.springframework.scheduling..").check(classes);}
    @Test void existingDomainsDoNotDependOnTheNewBindingDomain(){noClasses().that().resideOutsideOfPackage("..simbinding..").should().dependOnClassesThat().resideInAPackage("..simbinding..").check(classes);}
    @Test void theOnlyConfigurationDependencyIsThePublicDatabaseStatementClock(){for(var type:classes)if(type.getPackageName().equals("com.fireplatform.simbinding"))for(var dependency:type.getDirectDependenciesFromSelf())if(dependency.getTargetClass().getPackageName().equals("com.fireplatform.configuration"))assertThat(dependency.getTargetClass().getName()).isEqualTo("com.fireplatform.configuration.DatabaseTime");}
    @Test void everyBindingSqlWriteIsOwnedAndHistoryIsInsertOnly()throws Exception{Set<String> tables=Set.of("sim_card_associations","sim_card_association_changes");var pattern=Pattern.compile("(?i)\\b(INSERT\\s+INTO|UPDATE|DELETE\\s+FROM)\\s+([a-z_]+)");Path root=Path.of("src/main/java/com/fireplatform");try(var files=Files.walk(root)){for(Path file:files.filter(p->p.toString().endsWith(".java")).toList()){String owner=root.relativize(file).getName(0).toString();var matcher=pattern.matcher(Files.readString(file));while(matcher.find()){String table=matcher.group(2).toLowerCase(Locale.ROOT);if(Set.of("of","with","the","where","set").contains(table))continue;if(tables.contains(table))assertThat(owner).as("owner for %s",table).isEqualTo("simbinding");if(owner.equals("simbinding")){assertThat(tables).contains(table);if(table.equals("sim_card_association_changes"))assertThat(matcher.group(1).replaceAll("\\s+"," ").toUpperCase(Locale.ROOT)).isEqualTo("INSERT INTO");}}}}}
}
