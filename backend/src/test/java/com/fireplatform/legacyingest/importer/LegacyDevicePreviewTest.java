package com.fireplatform.legacyingest.importer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties={"app.roles=all","app.simulation-enabled=true","app.storage=h2-dev","spring.datasource.url=jdbc:h2:mem:legacy_device_preview;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"})
class LegacyDevicePreviewTest {
    private static final Path APPENDIX=appendixPath();
    private static final String INITIAL_PASSWORD="Legacy-preview-"+UUID.randomUUID();
    @Autowired LegacyDictionaryImporter importer;

    @DynamicPropertySource static void appendix(DynamicPropertyRegistry registry) {
        registry.add("LEGACY_APPENDIX",()->APPENDIX.toString());
        registry.add("APP_BOOTSTRAP_USERNAME",()->"admin");
        registry.add("APP_BOOTSTRAP_PASSWORD",()->INITIAL_PASSWORD);
        registry.add("APP_DEMO_CUSTOMER_PASSWORD",()->INITIAL_PASSWORD);
        registry.add("APP_DEMO_CUSTOMER_B_PASSWORD",()->INITIAL_PASSWORD);
    }

    @BeforeAll static void requireArchive() {
        Assumptions.assumeTrue(Files.isDirectory(APPENDIX.resolve("legacy")),"真实归档目录不存在，跳过设备预览验收");
    }

    @Test void previewsEveryArchivedDeviceWithoutDatabaseWrites() {
        var stats=importer.previewAllDevices();
        assertThat(stats.totalRows()).isEqualTo(14_886);
        assertThat(stats.importable().count()+stats.placeMissing().count()+stats.modelMultiple().count()
                +stats.modelUnmatched().count()+stats.deviceIdOrEuiInvalid().count()).isEqualTo(stats.totalRows());
        printCategory("可导入",stats.importable());
        printCategory("场所缺失",stats.placeMissing());
        printCategory("型号多候选（不自动绑定）",stats.modelMultiple());
        printCategory("型号无匹配产品",stats.modelUnmatched());
        printCategory("d_device_id/d_eui 冲突或为空",stats.deviceIdOrEuiInvalid());
    }

    private static void printCategory(String label,LegacyDictionaryImporter.PreviewCategory category) {
        var example=category.example();
        System.out.printf("设备导入 dry-run %-28s count=%d example=%s%n",label,category.count(),
                example==null?"none":"p_id="+example.pId()+" model="+example.model()
                        +(example.selectedLegacyProductId()==null?"":" selected_product_p_id="+example.selectedLegacyProductId()));
    }

    private static Path appendixPath() {
        String environment=System.getenv("LEGACY_APPENDIX");
        if(environment!=null&&!environment.isBlank()) return Path.of(environment);
        for(String candidate:List.of("/mnt/d/烟感/_incoming/omp-docs/appendix","D:/烟感/_incoming/omp-docs/appendix")) {
            Path path=Path.of(candidate); if(Files.isDirectory(path)) return path;
        }
        return Path.of("D:/烟感/_incoming/omp-docs/appendix");
    }
}
