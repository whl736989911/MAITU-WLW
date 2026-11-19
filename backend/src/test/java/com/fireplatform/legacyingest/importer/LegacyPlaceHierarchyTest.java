package com.fireplatform.legacyingest.importer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class LegacyPlaceHierarchyTest {
    @Test void mapsExactPlaceAndParentIdsFromOrgExport() {
        var source=LegacyPlaceHierarchy.parse("type,id,name,parent_id,company_id,address\r\n"
                +"company,294,唯亭街道,0,,江苏省苏州市\r\n"
                +"place,323,新镇社区,0,294,新镇社区\r\n"
                +"place,369,青灯新村东区,323,294,\"江苏省苏州市,园区\"\r\n"
                +"place,370,楼栋,369,294,楼栋\r\n");

        assertThat(source.places()).containsEntry("323",new LegacyPlaceHierarchy.Place("323","新镇社区",null,"294"));
        assertThat(source.places()).containsEntry("369",new LegacyPlaceHierarchy.Place("369","青灯新村东区","323","294"));
        assertThat(source.matchesPlace("369","青灯新村东区","294")).isTrue();
        assertThat(source.matchesPlace("369","青灯新村西区","294")).isFalse();
        assertThat(source.matchesPlace("369","青灯新村东区","327")).isFalse();
        assertThat(source.places()).containsEntry("370",new LegacyPlaceHierarchy.Place("370","楼栋","369","294"));
        assertThat(source.sha256()).hasSize(64);
    }

    @Test void rejectsMissingParentsAndCyclesInsteadOfFlatteningThem() {
        assertThatThrownBy(() -> LegacyPlaceHierarchy.parse("type,id,name,parent_id,company_id,address\nplace,2,child,1,294,\n"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("absent parent");
        assertThatThrownBy(() -> LegacyPlaceHierarchy.parse("type,id,name,parent_id,company_id,address\nplace,1,a,2,294,\nplace,2,b,1,294,\n"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Cycle");
    }
}
