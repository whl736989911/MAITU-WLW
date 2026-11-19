package com.fireplatform.identity;

import jakarta.validation.constraints.*;
import java.time.LocalDate;
import java.util.List;
import com.fireplatform.shared.Limits;

public final class UnitDirectoryModels {
    private UnitDirectoryModels() { }
    public record UnitDetails(String id,String name,boolean enabled,long revision,String creditCode,String regionId,String unitType,String industry,String mapAddress,String detailAddress,String contactName,String contactPhone,String remark,Double latitude,Double longitude,LocalDate serviceStart,LocalDate serviceEnd,LocalDate engineeringServiceStart,LocalDate engineeringServiceEnd,java.time.OffsetDateTime archivedAt) { }
    public record UnitCreate(@NotBlank @Size(max=32) String name,@Size(min=9,max=18) String creditCode,@Size(max=64) String regionId,@Size(max=64) String unitType,@Size(max=128) String industry,@Size(max=256) String mapAddress,@Size(max=40) String detailAddress,@Size(max=30) String contactName,@Size(max=18) String contactPhone,@Size(max=70) String remark,@DecimalMin("-90.0") @DecimalMax("90.0") Double latitude,@DecimalMin("-180.0") @DecimalMax("180.0") Double longitude,LocalDate serviceStart,LocalDate serviceEnd,LocalDate engineeringServiceStart,LocalDate engineeringServiceEnd,@Size(max=30) String adminUsername,@Size(max=200) String adminName,@Size(max=128) String adminInitialPassword,@Size(max=Limits.MAX_PERMISSION_ENTRIES) List<@NotBlank String> adminPermissions) { }
    public record UnitWrite(@NotBlank @Size(max=32) String name,@Size(min=9,max=18) String creditCode,@Size(max=64) String regionId,@Size(max=64) String unitType,@Size(max=128) String industry,@Size(max=256) String mapAddress,@Size(max=40) String detailAddress,@Size(max=30) String contactName,@Size(max=18) String contactPhone,@Size(max=70) String remark,@DecimalMin("-90.0") @DecimalMax("90.0") Double latitude,@DecimalMin("-180.0") @DecimalMax("180.0") Double longitude,LocalDate serviceStart,LocalDate serviceEnd,LocalDate engineeringServiceStart,LocalDate engineeringServiceEnd,@NotNull @Min(0) Long expectedRevision) { }
}
