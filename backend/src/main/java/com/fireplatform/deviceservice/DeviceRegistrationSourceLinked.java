package com.fireplatform.deviceservice;

import java.time.OffsetDateTime;

/** In-transaction source link; adapters may reconsider retained records under existing ownership rules. */
public record DeviceRegistrationSourceLinked(String driverKey, String externalDeviceId, OffsetDateTime linkedAt) { }
