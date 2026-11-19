package com.fireplatform.place;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSetter;
import jakarta.validation.constraints.*;

public final class PlaceInputs {
    private PlaceInputs() { }
    public record Create(@NotBlank @Size(max=200) String name, @Size(max=64) String parentPlaceId) { }
    /** Metadata only: omission keeps the stored parent; an explicit null asserts a root place. */
    public static final class Update {
        @NotBlank @Size(max=200) private final String name;
        @NotNull @Min(0) private final Long expectedRevision;
        @Size(max=64) private String parentPlaceId;
        private boolean parentSpecified;

        @JsonCreator
        public Update(@JsonProperty("name") String name, @JsonProperty("expected_revision") Long expectedRevision) {
            this.name = name;
            this.expectedRevision = expectedRevision;
        }

        @JsonSetter("parent_place_id")
        private void acceptParent(String parentPlaceId) {
            this.parentPlaceId = parentPlaceId;
            this.parentSpecified = true;
        }

        String name() { return name; }
        Long expectedRevision() { return expectedRevision; }
        String parentPlaceId() { return parentPlaceId; }
        boolean parentSpecified() { return parentSpecified; }
    }
    public record Delete(@NotNull @Min(0) Long expectedRevision) { }
    public record Move(@Size(max=64) String targetParentId, @NotNull @Min(0) Long expectedRevision, @NotBlank @Size(max=128) String snapshot, @NotBlank @Size(max=1000) String reason) { }
}
