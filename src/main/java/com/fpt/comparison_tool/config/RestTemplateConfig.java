package com.fpt.comparison_tool.config;

import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.socket.ConnectionSocketFactory;
import org.apache.hc.client5.http.socket.PlainConnectionSocketFactory;
import org.apache.hc.client5.http.ssl.SSLConnectionSocketFactory;
import org.apache.hc.core5.http.config.Registry;
import org.apache.hc.core5.http.config.RegistryBuilder;
import org.apache.hc.core5.ssl.SSLContextBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.web.client.DefaultResponseErrorHandler;
import org.springframework.web.client.RestTemplate;

import javax.net.ssl.SSLContext;
import java.security.KeyManagementException;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;

/**
 * HTTP client used for all source/target calls.
 *
 * Trust-all SSL + connection pooling so the tool works on local machines
 * behind corporate proxies / self-signed certificates.
 *
 * Two settings are required by the execution engine and must not be removed:
 *   - read timeout: without it a dead endpoint hangs a worker thread forever
 *   - no-throw error handler: 4xx/5xx are VALID responses that
 *     ExecutionService evaluates via the compareErrorResponses config
 */
@Configuration
public class RestTemplateConfig {

    @Bean
    public RestTemplate restTemplate() throws KeyStoreException, NoSuchAlgorithmException, KeyManagementException {
        SSLContext sslContext = SSLContextBuilder
                .create()
                .loadTrustMaterial((chain, authType) -> true)
                .build();

        Registry<ConnectionSocketFactory> registry = RegistryBuilder.<ConnectionSocketFactory>create()
                .register("http", PlainConnectionSocketFactory.getSocketFactory())
                .register("https", new SSLConnectionSocketFactory(sslContext))
                .build();

        PoolingHttpClientConnectionManager connectionManager =
                new PoolingHttpClientConnectionManager(registry);
        connectionManager.setMaxTotal(100);
        connectionManager.setDefaultMaxPerRoute(20);

        CloseableHttpClient httpClient = HttpClients.custom()
                .setConnectionManager(connectionManager)
                .build();

        HttpComponentsClientHttpRequestFactory requestFactory =
                new HttpComponentsClientHttpRequestFactory(httpClient);

        requestFactory.setConnectTimeout(Duration.ofMillis(5000));
        requestFactory.setConnectionRequestTimeout(Duration.ofMillis(30000));
        requestFactory.setReadTimeout(Duration.ofSeconds(20));   // required — see class javadoc

        RestTemplate restTemplate = new RestTemplate(requestFactory);

        // Never throw for any HTTP status — required by ExecutionService
        restTemplate.setErrorHandler(new DefaultResponseErrorHandler() {
            @Override
            public boolean hasError(HttpStatusCode statusCode) {
                return false;
            }
        });

        return restTemplate;
    }
}
