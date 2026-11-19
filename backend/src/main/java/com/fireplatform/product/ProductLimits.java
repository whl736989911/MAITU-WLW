package com.fireplatform.product;

import java.math.BigDecimal;

/** Security/resource policy, not a user-editable business setting. */
public final class ProductLimits {
    private ProductLimits() { }
    public static final int MAX_POINTER_LENGTH=256, MAX_POINTER_DEPTH=16, MAX_MAPPINGS=64, MAX_RAW_STRING=256;
    public static final int MAX_SCHEMA_BYTES=32768, MAX_SAMPLE_BYTES=65536, MAX_SAMPLES=16;
    public static final int MAX_MEASUREMENTS=64, MAX_MEASUREMENT_LABEL=128, MAX_MEASUREMENT_UNIT=32;
    public static final int MAX_SAMPLE_READINGS=64;
    public static final int MAX_DECIMAL_PRECISION=18, MAX_DECIMAL_SCALE=18;
    public static final int MAX_RESULT_PRECISION=64, MAX_RESULT_SCALE=36;
    public static final BigDecimal MAX_ABSOLUTE=new BigDecimal("1000000000000");
}
