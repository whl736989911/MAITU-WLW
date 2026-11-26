package com.fireplatform.legacyingest.importer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LegacyDictionaryImporterTest {
    private static final Path PROCESS_ENV_ARCHIVE = Path.of("target", "legacy-dictionary-importer-process-env-fixture");

    @TempDir Path temporary;

    @BeforeAll
    static void prepareProcessEnvironmentFixture() throws IOException {
        writeArchive(PROCESS_ENV_ARCHIVE);
    }

    @Test
    void previewFailsClosedWhenSpringAppendixPropertyIsUnset() {
        var importer = new LegacyDictionaryImporter(null, "", "");

        assertThatThrownBy(importer::previewAllDevices)
            .hasMessageContaining("LEGACY_APPENDIX");
    }

    @Test
    void previewFailsClosedWhenConfiguredAppendixIsInvalid() {
        var importer = new LegacyDictionaryImporter(null, temporary.resolve("missing-archive").toString(), "");

        assertThatThrownBy(importer::previewAllDevices)
            .hasMessageContaining("LEGACY_APPENDIX");
    }

    @Test
    void previewReadsOnlyAnExplicitSyntheticArchive() throws IOException {
        Path archive = temporary.resolve("explicit-archive");
        writeArchive(archive);
        var importer = new LegacyDictionaryImporter(null, archive.toString(), "");

        var preview = importer.previewAllDevices();

        assertThat(preview.totalRows()).isEqualTo(1);
        assertThat(preview.importable().count()).isEqualTo(1);
    }

    @Test
    void explicitHierarchyExportRemainsPartOfTheArchiveDigest() throws IOException {
        Path archive = temporary.resolve("hierarchy-archive");
        writeArchive(archive);
        Path hierarchy = temporary.resolve("org_all.csv");
        Files.writeString(hierarchy, "type,id,name,parent_id,company_id,address\nplace,place-1,First name,,company-1,First address\n");
        var importer = new LegacyDictionaryImporter(null, archive.toString(), hierarchy.toString());
        String firstDigest = importer.sourceDigest();

        Files.writeString(hierarchy, "type,id,name,parent_id,company_id,address\nplace,place-1,Second name,,company-1,Second address\n");

        assertThat(importer.sourceDigest()).isNotEqualTo(firstDigest);
    }

    private static void writeArchive(Path root) throws IOException {
        Path legacy = root.resolve("legacy");
        Files.createDirectories(legacy);
        Files.createDirectories(root.resolve("codecs"));
        Files.writeString(legacy.resolve("companies.tsv"), "c_id\tc_name\ncompany-1\tSynthetic company\n");
        Files.writeString(legacy.resolve("places.tsv"), "p_id\tp_cid\tp_name\nplace-1\tcompany-1\tSynthetic place\n");
        Files.writeString(legacy.resolve("products.tsv"), "p_id\tp_model\tp_manufacturer\nproduct-1\tSynthetic model\tMaker\n");
        Files.writeString(legacy.resolve("product_attrs.tsv"), "pa_pid\tpa_key\tpa_value\nproduct-1\tkey\tvalue\n");
        Files.writeString(legacy.resolve("devices_tsf.tsv"),
            "d_device_id\td_eui\td_id\td_model\td_pid\td_company_id\nlegacy-device\tsynthetic-eui\tdevice-1\tSynthetic model\tplace-1\tcompany-1\n");
    }
}
