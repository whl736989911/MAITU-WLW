package com.fireplatform.notification.officialaccount;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Official Account callback cryptography and hardened XML parsing.
 *
 * <p>Implements the published signature and AES-CBC/PKCS#7 message envelope. XML parsing refuses
 * DOCTYPE, external entities, XInclude and entity expansion so an untrusted callback cannot read
 * local files or expand a billion-laughs body.</p>
 */
public final class WechatOfficialAccountCrypto {
    private WechatOfficialAccountCrypto() { }

    public static final int MAX_XML_BYTES = 64 * 1024;
    private static final int MAX_FIELDS = 64;
    private static final int MAX_FIELD_CHARS = 32 * 1024;

    /** Plain signature: SHA1 of sorted token/timestamp/nonce. */
    public static String signature(String token, String timestamp, String nonce) {
        return sha1(sortedConcat(token, timestamp, nonce));
    }

    /** Encrypted-mode signature: SHA1 of sorted token/timestamp/nonce/Encrypt. */
    public static String messageSignature(String token, String timestamp, String nonce, String encrypt) {
        if (encrypt == null || encrypt.isBlank()) throw new IllegalArgumentException("encrypt");
        return sha1(sortedConcat(token, timestamp, nonce, encrypt));
    }

    public static boolean valid(String expected, String supplied) {
        if (expected == null || supplied == null) return false;
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII), supplied.getBytes(StandardCharsets.US_ASCII));
    }

    /**
     * Decrypts the official message envelope and verifies the embedded AppID.
     *
     * @return the plaintext XML/JSON payload exactly as decrypted
     */
    public static String decrypt(String encodingAesKey, String encrypt, String expectedAppId) {
        if (encodingAesKey == null || encodingAesKey.length() != 43) throw new IllegalArgumentException("encoding key");
        if (encrypt == null || encrypt.isBlank()) throw new IllegalArgumentException("encrypt");
        try {
            byte[] key = Base64.getDecoder().decode(encodingAesKey + "=");
            if (key.length != 32) throw new IllegalArgumentException("encoding key size");
            byte[] encrypted = Base64.getDecoder().decode(encrypt);
            if (encrypted.length == 0 || encrypted.length > MAX_XML_BYTES) throw new IllegalArgumentException("cipher size");
            Cipher cipher = Cipher.getInstance("AES/CBC/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(java.util.Arrays.copyOfRange(key, 0, 16)));
            byte[] padded = cipher.doFinal(encrypted);
            int pad = padded[padded.length - 1] & 0xff;
            if (pad < 1 || pad > 32 || pad > padded.length) throw new IllegalArgumentException("padding");
            for (int index = padded.length - pad; index < padded.length; index++) {
                if ((padded[index] & 0xff) != pad) throw new IllegalArgumentException("padding");
            }
            int length = padded.length - pad;
            if (length < 20) throw new IllegalArgumentException("payload");
            int messageLength = ((padded[16] & 0xff) << 24) | ((padded[17] & 0xff) << 16) | ((padded[18] & 0xff) << 8) | (padded[19] & 0xff);
            if (messageLength < 0 || messageLength > length - 20) throw new IllegalArgumentException("message length");
            String message = new String(padded, 20, messageLength, StandardCharsets.UTF_8);
            String appId = new String(padded, 20 + messageLength, length - 20 - messageLength, StandardCharsets.UTF_8);
            if (expectedAppId != null && !expectedAppId.isBlank() && !expectedAppId.equals(appId)) {
                throw new IllegalArgumentException("appid mismatch");
            }
            return message;
        } catch (IllegalArgumentException invalid) {
            throw invalid;
        } catch (Exception invalid) {
            throw new IllegalArgumentException("decrypt failed", invalid);
        }
    }

    /** XXE-blocked flat {@code <xml>} parser with bounded size, field count and field length. */
    public static Map<String, String> parseXml(byte[] raw) {
        if (raw == null || raw.length == 0 || raw.length > MAX_XML_BYTES) throw new IllegalArgumentException("xml size");
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setFeature("http://javax.xml.XMLConstants/feature/secure-processing", true);
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            factory.setNamespaceAware(false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            Document document = factory.newDocumentBuilder().parse(new ByteArrayInputStream(raw));
            Element root = document.getDocumentElement();
            if (root == null || !"xml".equals(root.getNodeName())) throw new IllegalArgumentException("root");
            NodeList nodes = root.getChildNodes();
            Map<String, String> values = new LinkedHashMap<>();
            for (int index = 0; index < nodes.getLength(); index++) {
                Node node = nodes.item(index);
                if (node.getNodeType() != Node.ELEMENT_NODE) continue;
                if (values.size() >= MAX_FIELDS) throw new IllegalArgumentException("field count");
                String name = node.getNodeName();
                String text = node.getTextContent();
                if (text != null && text.length() > MAX_FIELD_CHARS) throw new IllegalArgumentException("field size");
                values.put(name, text);
            }
            if (values.isEmpty()) throw new IllegalArgumentException("empty xml");
            return values;
        } catch (IllegalArgumentException invalid) {
            throw invalid;
        } catch (Exception invalid) {
            throw new IllegalArgumentException("invalid xml", invalid);
        }
    }

    /** Stable dedup identity for one provider event; no raw body is stored. */
    public static String eventKey(Map<String, String> fields) {
        StringBuilder canonical = new StringBuilder();
        for (String key : java.util.List.of("ToUserName", "FromUserName", "CreateTime", "MsgType", "Event", "MsgID", "Status", "EventKey")) {
            canonical.append(fields.getOrDefault(key, "")).append('\u0000');
        }
        return WechatOfficialAccountSecretStorage.sha256(canonical.toString());
    }

    private static String sortedConcat(String... values) {
        String[] sorted = values.clone();
        java.util.Arrays.sort(sorted);
        return String.join("", sorted);
    }

    private static String sha1(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-1").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte item : digest) hex.append(String.format("%02x", item));
            return hex.toString();
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
