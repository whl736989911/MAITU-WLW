package com.fireplatform;

import com.tngtech.archunit.core.importer.*;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

class DeviceGroupOwnershipTest {
    @Test void newDomainWritesOnlyTwoOwnedTablesAndUsesPublicAuditIdempotencyPorts()throws Exception{
        var writes=Pattern.compile("(?i)\\b(?:INSERT\\s+INTO|UPDATE|DELETE\\s+FROM)\\s+([a-z_]+)");var found=new HashSet<String>();
        try(var files=Files.walk(Path.of("src/main/java/com/fireplatform/devicegroup"))){for(Path file:files.filter(p->p.toString().endsWith(".java")).toList()){String source=Files.readString(file);var matcher=writes.matcher(source);while(matcher.find()){String table=matcher.group(1).toLowerCase(Locale.ROOT);if(Set.of("of","with","the","where","set").contains(table))continue;assertThat(Set.of("device_groups","device_group_members")).as("owned write in %s",file).contains(table);found.add(table);}assertThat(source).doesNotContain("DeviceQueryService","notification_records","SET physical_state","SET last_message_at");}}
        assertThat(found).containsExactlyInAnyOrder("device_groups","device_group_members");
    }
    @Test void newDomainIsAcyclicAndCannotCallOtherMutableDomainImplementations(){var classes=new ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS).importPackages("com.fireplatform");slices().matching("com.fireplatform.(*)..").should().beFreeOfCycles().check(classes);noClasses().that().resideInAPackage("..devicegroup..").should().dependOnClassesThat().resideInAnyPackage("..device..","..alarm..","..notification..","..processing..","..ingestion..","..product..","..bootstrap..").check(classes);}
}
