package com.guanghe.backend.integration.contract;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class NginxConfigurationContractTests {

    @Test
    void publicTemplateOnlyProxiesApiAndReplacesUntrustedForwardingHeaders() throws Exception {
        String configuration = Files.readString(Path.of(
                "deploy", "nginx", "zhixiaohe-java-test.conf.template"));

        assertThat(configuration)
                .contains("return 308 https://${SERVER_NAME}$request_uri")
                .doesNotContain("https://$host$request_uri")
                .contains("listen 443 ssl http2")
                // A ^~ prefix would prevent the event-stream regex from disabling buffering.
                .contains("location /api/ {")
                .doesNotContain("location ^~ /api/")
                .contains("location ~ ^/api/v1/conversations/[^/]+/events$ {")
                .contains("proxy_buffering off;")
                .contains("add_header X-Accel-Buffering \"no\" always;")
                .contains("location = /api/v1/assets/images")
                .contains("proxy_pass http://127.0.0.1:8080")
                .contains("location ^~ /internal/v1/")
                .contains("location ^~ /actuator/")
                .contains("client_max_body_size 1m")
                .contains("client_max_body_size 22m")
                .contains("Strict-Transport-Security \"max-age=31536000\" always")
                .contains("proxy_set_header X-Forwarded-For $remote_addr")
                .contains("proxy_set_header Forwarded \"for=$remote_addr;proto=$scheme\"")
                .doesNotContain("$proxy_add_x_forwarded_for")
                .doesNotContain("127.0.0.1:8000");
    }
}
