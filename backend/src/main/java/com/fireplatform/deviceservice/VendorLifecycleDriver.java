package com.fireplatform.deviceservice;

import com.fasterxml.jackson.databind.JsonNode;

/** Vendor-cloud lifecycle calls used by preregistration/commissioning. Never used for control. */
public interface VendorLifecycleDriver {
    CloudResult register(CloudRegistration registration, String verificationCode);

    LookupResult lookup(String serial, String externalDeviceId);

    CloudResult delete(String externalDeviceId, String serial);

    /**
     * True only for adapters that can associate an already-imported legacy ledger asset from the
     * local mirror without inventing a remote lookup contract. The registration flow still records
     * the operator-supplied ledger id and never claims NS provisioning or unregistration.
     */
    default boolean supportsImportedLedgerAssociation() { return false; }

    /** Driver-specific validation of the exact ledger add body before a draft is persisted. */
    default void validateRegistration(CloudRegistration registration, JsonNode vendorPayload) { }

    record CloudRegistration(String deviceName, String deviceSerial, String deviceModel, Integer deviceType,
                             Integer communicationType, String verificationCode, Integer producterId,
                             Integer productModelId, String location, Boolean autoAddChannel, String vendorUnitId,
                             java.math.BigDecimal latitude, java.math.BigDecimal longitude,
                             JsonNode vendorPayload) { }

    /** SUCCEEDED means vendor accepted and returned a device identity; it is not physical activation. */
    record CloudResult(String outcome, String vendorCode, String externalDeviceId, JsonNode result, String note) { }

    /** state: FOUND, NOT_FOUND, AMBIGUOUS, REJECTED, UNKNOWN. */
    record LookupResult(String state, String externalDeviceId, JsonNode result, String note) { }
}
