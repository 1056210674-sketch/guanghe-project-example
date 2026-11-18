package com.guanghe.backend.infrastructure;

import java.io.File;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

final class TestTlsSupport {

    private static final String CA_RESOURCE = "certs/zhixiaohe-test-ca.crt";

    private TestTlsSupport() {
    }

    static File caCertificateFile() {
        try {
            var resource = requiredResource();
            if (!"file".equalsIgnoreCase(resource.getProtocol())) {
                throw new IllegalStateException("TLS Gate CA must resolve to a local classpath file");
            }
            return new File(resource.toURI());
        } catch (URISyntaxException exception) {
            throw new IllegalStateException("Invalid TLS Gate CA resource path", exception);
        }
    }

    static SSLContext sslContext() throws Exception {
        var certificateFactory = CertificateFactory.getInstance("X.509");
        var keyStore = KeyStore.getInstance(KeyStore.getDefaultType());
        keyStore.load(null);
        try (InputStream input = requiredResource().openStream()) {
            keyStore.setCertificateEntry("zhixiaohe-test-ca", certificateFactory.generateCertificate(input));
        }

        var trustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trustManagerFactory.init(keyStore);
        var sslContext = SSLContext.getInstance("TLS");
        sslContext.init(null, trustManagerFactory.getTrustManagers(), null);
        return sslContext;
    }

    private static java.net.URL requiredResource() {
        var resource = TestTlsSupport.class.getClassLoader().getResource(CA_RESOURCE);
        if (resource == null) {
            throw new IllegalStateException("Missing TLS Gate CA resource: " + CA_RESOURCE);
        }
        return resource;
    }
}
