package com.fireplatform.notification;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Pure provider-receipt mapping and correlation rules shared by manual and scheduled reconciliation. */
public final class NotificationDeliveryReceiptEvidence {
    private NotificationDeliveryReceiptEvidence() { }

    public static final int SMS_LOOKBACK_DAYS = 30;
    public static final int VOICE_LOOKBACK_MONTHS = 6;
    public static final String UNCONFIRMED = "UNCONFIRMED", WAITING = "WAITING", DELIVERED = "DELIVERED", FAILED = "FAILED",
            HEARD_FULL = "HEARD_FULL", CONNECTED_PARTIAL = "CONNECTED_PARTIAL", NOT_CONNECTED = "NOT_CONNECTED",
            ACCEPTED = "ACCEPTED";
    /** Only transient reasons are retried by the optional scheduler; account/snapshot/window mismatches never are. */
    public static final java.util.Set<String> RETRYABLE_REASONS = java.util.Set.of(
            "SEND_WAITING","NOT_SYNCHRONIZED","RECEIPT_NOT_FOUND","PROVIDER_UNAVAILABLE","QUERY_REJECTED",
            "UNKNOWN_SEND_STATUS","UNKNOWN_PROVIDER_STATUS","PROVIDER_NOT_CONFIGURED");
    /** Official Account completion is callback-driven; no polling query exists in the vendor contract. */
    public static final String WECHAT_CALLBACK_ONLY = "WECHAT_CALLBACK_ONLY";

    /** A mapped state plus the machine reason that must be shown when the state is not a positive proof. */
    public record Mapped(String state, String reason) { }

    /** SendStatus=3 alone is not delivery: the carrier ErrCode=DELIVERED is required too. */
    public static Mapped sms(int sendStatus, String errCode) {
        String code = trim(errCode);
        if (sendStatus == 1) return new Mapped(WAITING, "SEND_WAITING");
        if (sendStatus == 2) return new Mapped(FAILED, "SEND_FAILED");
        if (sendStatus == 3) {
            if ("DELIVERED".equalsIgnoreCase(code)) return new Mapped(DELIVERED, null);
            if (code == null) return new Mapped(UNCONFIRMED, "MISSING_ERR_CODE");
            return new Mapped(FAILED, "CARRIER_REJECTED");
        }
        return new Mapped(UNCONFIRMED, "UNKNOWN_SEND_STATUS");
    }

    /**
     * Only documented terminal operator statuses are accepted. 200000/200001 additionally require a connected start
     * time and positive duration; duration 0 means the callee did not connect.
     */
    public static Mapped voice(String providerStatus, Integer duration, String startAt) {
        String code = trim(providerStatus);
        if (code == null) return new Mapped(UNCONFIRMED, "MISSING_PROVIDER_STATUS");
        boolean connected = trim(startAt) != null && duration != null && duration > 0;
        return switch (code) {
            case "200000" -> connected ? new Mapped(HEARD_FULL, null) : new Mapped(UNCONFIRMED, "INCONSISTENT_EVIDENCE");
            case "200001" -> connected ? new Mapped(CONNECTED_PARTIAL, null) : new Mapped(UNCONFIRMED, "INCONSISTENT_EVIDENCE");
            case "200002", "200003", "200004", "200005", "200007", "200010", "200011" ->
                    duration != null && duration > 0 ? new Mapped(UNCONFIRMED, "INCONSISTENT_EVIDENCE")
                            : new Mapped(NOT_CONNECTED, null);
            default -> new Mapped(UNCONFIRMED, "UNKNOWN_PROVIDER_STATUS");
        };
    }

    /**
     * Official Account template completion event. Only the three published statuses are mapped;
     * anything else stays unconfirmed rather than being presented as delivered or failed.
     */
    public static Mapped wechat(String providerStatus) {
        String code = trim(providerStatus);
        if (code == null) return new Mapped(UNCONFIRMED, "MISSING_PROVIDER_STATUS");
        String normalized = code.toLowerCase(java.util.Locale.ROOT).replace(": ", ":");
        return switch (normalized) {
            case "success" -> new Mapped(DELIVERED, null);
            case "failed:user block" -> new Mapped(FAILED, "USER_BLOCKED");
            case "failed:system failed" -> new Mapped(FAILED, "SYSTEM_FAILED");
            default -> new Mapped(UNCONFIRMED, "UNKNOWN_PROVIDER_STATUS");
        };
    }

    /**
     * Compares the provider's masked destination with the snapshotted full number. A blank mask is accepted because the
     * query itself was scoped by the stored full phone and BizId; a present mask must not contradict it.
     */
    public static boolean phoneCompatible(String normalized, String masked) {
        String digits = digits(normalized);
        if (digits == null) return false;
        String mask = masked == null ? "" : masked.replaceAll("[^0-9*]", "");
        if (mask.isEmpty()) return true;
        int firstStar = mask.indexOf('*');
        if (firstStar < 0) return mask.equals(digits);
        String prefix = mask.substring(0, firstStar);
        String suffix = mask.substring(mask.lastIndexOf('*') + 1);
        return digits.startsWith(prefix) && (suffix.isEmpty() || digits.endsWith(suffix));
    }

    /** The provider may return a masked or an unexpected full number; only a mask is ever persisted or returned. */
    public static String maskProviderPhone(String value) {
        String text = trim(value);
        if (text == null) return null;
        if (text.indexOf('*') >= 0) return text;
        String digits = text.replaceAll("\\D", "");
        if (digits.length() < 7) return "****";
        return digits.substring(0, 3) + "****" + digits.substring(digits.length() - 4);
    }

    private static String digits(String phone) {
        if (phone == null) return null;
        String digits = phone.replaceAll("\\D", "");
        if (digits.length() == 13 && digits.startsWith("86")) digits = digits.substring(2);
        return digits.isEmpty() ? null : digits;
    }

    /** Monotonic strength; terminal states never regress and equal-strength terminal states never replace each other. */
    public static int rank(String channel, String state) {
        if ("SMS".equals(channel)) return switch (String.valueOf(state)) {
            case UNCONFIRMED -> 0;
            case WAITING -> 1;
            case DELIVERED, FAILED -> 2;
            default -> -1;
        };
        if ("VOICE".equals(channel)) return switch (String.valueOf(state)) {
            case UNCONFIRMED -> 0;
            case WAITING -> 1;
            case CONNECTED_PARTIAL, NOT_CONNECTED -> 2;
            case HEARD_FULL -> 3;
            default -> -1;
        };
        if ("WECHAT".equals(channel)) return switch (String.valueOf(state)) {
            case UNCONFIRMED -> 0;
            case ACCEPTED -> 1;
            case DELIVERED, FAILED -> 2;
            default -> -1;
        };
        return -1;
    }

    public static String stronger(String channel, String current, String candidate) {
        if (current == null) return candidate;
        if (rank(channel, candidate) > rank(channel, current)) return candidate;
        return current;
    }

    public static String digest(String... parts) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            StringBuilder canonical = new StringBuilder();
            for (String part : parts) canonical.append(part == null ? "" : part).append('\u0000');
            return java.util.HexFormat.of().formatHex(sha.digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (Exception error) { throw new IllegalStateException("Digest unavailable"); }
    }

    public static String trim(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
