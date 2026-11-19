package com.fireplatform.shared;

/** Inbound settings port: identity must not depend on the configuration implementation. */
public interface PageSizePolicy { long defaultPageSize(); }
