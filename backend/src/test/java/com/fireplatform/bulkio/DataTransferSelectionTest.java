package com.fireplatform.bulkio;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DataTransferSelectionTest {
    @Test
    void omittedRangeMeansAuthorizedFullScope() {
        var selection = DataTransferFilters.selection(null, java.util.Set.of());
        assertFalse(selection.empty());
    }

    @Test
    void explicitEmptyUnitAndPlaceRangesMeanNoRows() {
        var selection = DataTransferFilters.selection(Map.of("unit_ids", "", "place_ids", ""), java.util.Set.of());
        assertTrue(selection.empty());
    }

    @Test
    void explicitEmptySelectedIdsNeverPromoteToFullScope() {
        
        var data = new DataTransferDomain.ExportData(List.of("id", "name"), List.of(List.of("a", "A")));
        var selected = DataTransferFilters.selected(data, List.of());
        assertTrue(selected.rows().isEmpty());
    }
}
