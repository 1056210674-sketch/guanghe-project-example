package com.guanghe.backend.integration.internal.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.guanghe.backend.integration.internal.config.InternalCommunicationProperties;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

class PythonDiagnosticAnalysisClientTests {
    @Test void onlyDiagnosticAnalysisUsesLongerTransportAndExistingSynthesisFamilyKeepsSeventySeconds() {
        assertThat(PythonInternalClient.DIAGNOSTIC_ANALYSIS_READ_TIMEOUT).isEqualTo(Duration.ofSeconds(130));
        assertThat(PythonInternalClient.SYNTHESIS_READ_TIMEOUT).isEqualTo(Duration.ofSeconds(70));
        var ordinaryBuilder=RestClient.builder().baseUrl("http://ordinary.invalid");
        var analysisBuilder=RestClient.builder().baseUrl("http://analysis.invalid");
        var ordinary=MockRestServiceServer.bindTo(ordinaryBuilder).build();
        var analysis=MockRestServiceServer.bindTo(analysisBuilder).build();
        analysis.expect(requestTo("http://analysis.invalid/internal/v1/geo/diagnostic-analyses"))
            .andExpect(method(HttpMethod.POST)).andRespond(withStatus(HttpStatus.OK).contentType(MediaType.APPLICATION_JSON).body("{}"));
        for(String path:new String[]{"diagnostic-preparations","period-report-summaries","syntheses"})
            ordinary.expect(requestTo("http://ordinary.invalid/internal/v1/geo/"+path))
                .andExpect(method(HttpMethod.POST)).andRespond(withStatus(HttpStatus.OK).contentType(MediaType.APPLICATION_JSON).body("{}"));
        var client=client(ordinaryBuilder.build(),analysisBuilder.build());
        var request=new ObjectMapper().createObjectNode().put("jobId","test");
        assertThat(client.analyzeGeoDiagnostic(request).available()).isTrue();
        assertThat(client.prepareGeoDiagnostic(request).available()).isTrue();
        assertThat(client.summarizeGeoPeriodReport(request).available()).isTrue();
        assertThat(client.synthesizeGeo(request).available()).isTrue();
        analysis.verify();ordinary.verify();
    }

    @Test void analysisFailureDoesNotReplayOrUseTheOrdinaryClient() {
        var unused=RestClient.builder().baseUrl("http://ordinary.invalid");
        var ordinary=MockRestServiceServer.bindTo(unused).build();
        var builder=RestClient.builder().baseUrl("http://analysis.invalid");
        var analysis=MockRestServiceServer.bindTo(builder).build();
        var calls=new AtomicInteger();
        analysis.expect(requestTo("http://analysis.invalid/internal/v1/geo/diagnostic-analyses"))
            .andExpect(request->calls.incrementAndGet())
            .andRespond(withStatus(HttpStatus.GATEWAY_TIMEOUT).contentType(MediaType.APPLICATION_JSON)
                .body("{\"detail\":{\"code\":\"GEO_DIAGNOSTIC_ANALYSIS_DEADLINE_EXCEEDED\"}}"));
        var result=client(unused.build(),builder.build()).analyzeGeoDiagnostic(new ObjectMapper().createObjectNode());
        assertThat(result.available()).isFalse();assertThat(result.httpStatus()).isEqualTo(504);
        assertThat(calls.get()).isEqualTo(1);analysis.verify();ordinary.verify();
    }

    private PythonInternalClient client(RestClient ordinary,RestClient analysis) {
        var properties=new InternalCommunicationProperties();properties.setKeyId("test-key");
        properties.setSecret("0123456789abcdef0123456789abcdef");
        return new PythonInternalClient(ordinary,ordinary,ordinary,ordinary,analysis,new ObjectMapper(),
            new InternalHmacRequestSigner(properties,Clock.systemUTC()),1_048_576,1_048_576);
    }
}
