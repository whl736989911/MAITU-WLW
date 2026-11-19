package com.fireplatform.shared;

/** Read-only health port. Configuration does not depend on notification implementation. */
public interface NotificationStorageReadiness { boolean contactStorageReady(); }
