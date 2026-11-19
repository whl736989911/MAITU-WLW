package com.fireplatform.product;

/** Read-only cross-domain port. A published snapshot can never be edited or silently substituted. */
public interface PublishedVersionCatalog {
    record PublishedVersion(String id,String productId,String productName,String model,long versionNumber,long revision,String batteryComparator,java.math.BigDecimal batteryThreshold,String batteryCapability) { }
    PublishedVersion requirePublished(String versionId);
    /**
     * Same immutable published snapshot, additionally requiring the owning product to be active. Used only at
     * new-use doors (new device registration, new source binding); parsing/capture/history keep using
     * {@link #requirePublished(String)} so archiving never stops deployed assets.
     */
    PublishedVersion requireAvailableForNewUse(String versionId);
}
