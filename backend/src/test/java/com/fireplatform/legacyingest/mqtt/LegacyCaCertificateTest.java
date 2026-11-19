package com.fireplatform.legacyingest.mqtt;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/** round3 只读导出里的 EMQX 公开证书：根 CA 可解析、服务器证书链可验证、指纹可固定。 */
class LegacyCaCertificateTest {
    private static final Path CA_DIR = caDir();

    @Test void rootCaSignsServerCertificate() throws Exception {
        Assumptions.assumeTrue(Files.isRegularFile(CA_DIR.resolve("EMQ-RootCA-cacert.pem")), "round3 CA 证书不存在，跳过");
        CertificateFactory factory = CertificateFactory.getInstance("X.509");
        X509Certificate root;
        X509Certificate server;
        try (InputStream in = Files.newInputStream(CA_DIR.resolve("EMQ-RootCA-cacert.pem"))) {
            root = (X509Certificate) factory.generateCertificate(in);
        }
        try (InputStream in = Files.newInputStream(CA_DIR.resolve("EMQ-Server-cert.pem"))) {
            server = (X509Certificate) factory.generateCertificate(in);
        }
        assertThat(root.getSubjectX500Principal().getName()).contains("CN=RootCA");
        assertThat(root.getIssuerX500Principal()).isEqualTo(root.getSubjectX500Principal());
        assertThat(server.getIssuerX500Principal()).isEqualTo(root.getSubjectX500Principal());
        assertThat(root.getNotAfter().toInstant().toString()).startsWith("2030-05-06");
        assertThat(server.getNotAfter().toInstant().toString()).startsWith("2030-05-06");
        server.verify(root.getPublicKey());
        assertThat(fileSha256(CA_DIR.resolve("EMQ-RootCA-cacert.pem"))).isEqualTo("5E6E554C4B99C47BE177DDAB6F062C0023FEC6282B5EA4C19328A1651D811E82");
        assertThat(fileSha256(CA_DIR.resolve("EMQ-Server-cert.pem"))).isEqualTo("C4FA36D9AA46BF0E219BA3B81A11DC38D1C4751E4D193889DCB84EE19ED2E503");
        assertThat(fingerprint(root)).hasSize(64);
        // 旧平台不下发 CA 链，客户端必须显式带 cafile；MqttSubscriber 从 ca-file 构建 trust store。
        assertThat(root.getBasicConstraints()).isGreaterThanOrEqualTo(0);
    }

    private static String fileSha256(Path path) throws Exception {
        return HexFormat.of().withUpperCase().formatHex(
                MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
    }

    private static String fingerprint(X509Certificate certificate) throws Exception {
        return HexFormat.of().withUpperCase().formatHex(
                MessageDigest.getInstance("SHA-256").digest(certificate.getEncoded()));
    }

    private static Path caDir() {
        for (String candidate : List.of("../artifacts/legacy-round3/20261102/round3/ca", "artifacts/legacy-round3/20261102/round3/ca",
                "/mnt/d/烟感/fire-platform/artifacts/legacy-round3/20261102/round3/ca")) {
            Path path = Path.of(candidate);
            if (Files.isDirectory(path)) return path;
        }
        return Path.of("../artifacts/legacy-round3/20261102/round3/ca");
    }
}
