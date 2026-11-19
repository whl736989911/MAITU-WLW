package com.fireplatform;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.*;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.*;

class SimCardArchitectureTest {
    final JavaClasses classes=new ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS).importPackages("com.fireplatform");
    @Test void manualSimDomainHasNoDeviceProviderNotificationNetworkOrSchedulerDependencies(){noClasses().that().resideInAPackage("..simcard..").should().dependOnClassesThat().resideInAnyPackage("..device..","..devicegroup..","..alarm..","..product..","..ingestion..","..processing..","..notification..","..bootstrap..","java.net..","java.util.concurrent..","java.nio.file..","javax.script..","org.springframework.scheduling..").check(classes);}
    @Test void simDependencyIsRestrictedToTheMasterDataTransferFacade(){
        Set<String> facade=Set.of("SimCardCommands","SimCardInputs","SimCardInputs$Metadata","SimCardModels","SimCardModels$Card");
        for(var origin:classes) if(!origin.getPackageName().equals("com.fireplatform.simcard"))
            for(var dependency:origin.getDirectDependenciesFromSelf()) {
                var target=dependency.getTargetClass();
                if(!target.getPackageName().equals("com.fireplatform.simcard"))continue;
                assertThat(origin.getName()).as(dependency.getDescription()).isEqualTo("com.fireplatform.bulkio.MasterDataTransferDomain");
                assertThat(facade).as(dependency.getDescription()).contains(target.getName().substring("com.fireplatform.simcard.".length()));
            }
    }
    @Test void onlyPublicDatabaseTimeIsUsedFromConfiguration(){for(var type:classes)if(type.getPackageName().equals("com.fireplatform.simcard"))for(var dependency:type.getDirectDependenciesFromSelf())if(dependency.getTargetClass().getPackageName().equals("com.fireplatform.configuration"))assertThat(dependency.getTargetClass().getName()).isEqualTo("com.fireplatform.configuration.DatabaseTime");}
    @Test void allSqlWritersRespectTheTwoNewTableOwnersAndHistoryIsAppendOnly()throws Exception{
        Set<String> owned=Set.of("sim_cards","sim_card_changes");var pattern=Pattern.compile("(?i)\\b(INSERT\\s+INTO|UPDATE|DELETE\\s+FROM)\\s+([a-z_]+)");Path root=Path.of("src/main/java/com/fireplatform");
        try(var files=Files.walk(root)){for(Path file:files.filter(p->p.toString().endsWith(".java")).toList()){
            String owner=root.relativize(file).getName(0).toString();var matcher=pattern.matcher(Files.readString(file));while(matcher.find()){
                String table=matcher.group(2).toLowerCase(Locale.ROOT);if(Set.of("of","with","the","where","set").contains(table))continue;
                if(owned.contains(table))assertThat(owner).as("table owner %s at %s",table,file).isEqualTo("simcard");
                if(owner.equals("simcard")){assertThat(owned).as("no foreign-domain direct write at %s",file).contains(table);if(table.equals("sim_card_changes"))assertThat(matcher.group(1).replaceAll("\\s+"," ").toUpperCase(Locale.ROOT)).isEqualTo("INSERT INTO");}
            }
        }}
    }
}
