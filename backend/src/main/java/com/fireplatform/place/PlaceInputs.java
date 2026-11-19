package com.fireplatform.place;

import jakarta.validation.constraints.*;

public final class PlaceInputs {
    private PlaceInputs() { }
    public record Create(@NotBlank @Size(max=200) String name, @Size(max=64) String parentPlaceId) { }
    public record Update(@NotBlank @Size(max=200) String name, @Size(max=64) String parentPlaceId, @NotNull @Min(0) Long expectedRevision) { }
    public record Delete(@NotNull @Min(0) Long expectedRevision) { }
    public record Move(@Size(max=64) String targetParentId, @NotNull @Min(0) Long expectedRevision, @NotBlank @Size(max=128) String snapshot, @NotBlank @Size(max=1000) String reason) { }
}
