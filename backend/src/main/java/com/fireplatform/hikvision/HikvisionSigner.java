package com.fireplatform.hikvision;

import java.net.URI;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.Locale;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Artemis JSON POST signing. The exact signed UTF-8 body bytes are also sent. */
public final class HikvisionSigner {
    private final String origin;
    private static final DateTimeFormatter DATE = DateTimeFormatter
        .ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US).withZone(ZoneOffset.UTC);
    private final String appKey;
    private final SecretKeySpec signingKey;

    public HikvisionSigner(String origin, String appKey, String appSecret) {
        this.origin = origin;
        this.appKey = appKey;
        this.signingKey = new SecretKeySpec(appSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }

    public HttpRequest post(String apiPath, byte[] body, Instant now, String nonce) {
        if (!apiPath.matches("/api/[A-Za-z0-9/]+")) throw new IllegalArgumentException("Invalid API path");
        String path = "/artemis" + apiPath;
        String date = DATE.format(now);
        String timestamp = Long.toString(now.toEpochMilli());
        try {
            String md5 = Base64.getEncoder().encodeToString(MessageDigest.getInstance("MD5").digest(body));
            String canonical = new StringBuilder(256).append("POST\n*/*\n").append(md5)
                .append("\napplication/json\n").append(date).append('\n')
                .append("x-ca-key:").append(appKey).append('\n')
                .append("x-ca-nonce:").append(nonce).append('\n')
                .append("x-ca-timestamp:").append(timestamp).append('\n').append(path).toString();
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(signingKey);
            String signature = Base64.getEncoder().encodeToString(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
            return HttpRequest.newBuilder(URI.create(origin + path)).timeout(Duration.ofSeconds(25))
                .header("Accept", "*/*").header("Content-MD5", md5).header("Content-Type", "application/json")
                .header("Date", date).header("X-Ca-Key", appKey).header("X-Ca-Nonce", nonce)
                .header("X-Ca-Timestamp", timestamp)
                .header("X-Ca-Signature-Headers", "x-ca-key,x-ca-nonce,x-ca-timestamp")
                .header("X-Ca-Signature", signature).POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Artemis signing algorithms unavailable");
        }
    }
}
