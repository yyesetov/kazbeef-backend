package kz.kazbeef.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.function.Function;

/**
 * HTTP-клиент для одного источника: таймауты, User-Agent с контактом,
 * минимальная пауза между запросами и повтор с нарастающей паузой при 5xx/429/сетевых ошибках (ФТ-7).
 */
public class SourceHttpClient {

    private static final Logger log = LoggerFactory.getLogger(SourceHttpClient.class);

    private final RestClient restClient;
    private final Duration minDelay;
    private final int maxAttempts;
    private final Duration initialBackoff;
    private long lastRequestNanos;

    public SourceHttpClient(String baseUrl, KazbeefProperties.Http http, Duration minDelay) {
        HttpClient jdk = HttpClient.newBuilder()
                .connectTimeout(http.connectTimeout())
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(jdk);
        factory.setReadTimeout(http.readTimeout());
        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(factory)
                .defaultHeader(HttpHeaders.USER_AGENT, http.userAgent())
                .build();
        this.minDelay = minDelay;
        this.maxAttempts = Math.max(1, http.maxAttempts());
        this.initialBackoff = http.initialBackoff();
    }

    /** Выполняет запрос с ограничением частоты и повторами. */
    public <T> T execute(Function<RestClient, T> call) {
        Duration backoff = initialBackoff;
        for (int attempt = 1; ; attempt++) {
            throttle();
            try {
                return call.apply(restClient);
            } catch (RestClientResponseException e) {
                int status = e.getStatusCode().value();
                boolean retryable = status == 429 || status >= 500;
                if (!retryable || attempt >= maxAttempts) {
                    throw e;
                }
                log.warn("HTTP {} от источника, попытка {}/{}, пауза {}", status, attempt, maxAttempts, backoff);
            } catch (ResourceAccessException e) {
                if (attempt >= maxAttempts) {
                    throw e;
                }
                log.warn("Сетевая ошибка: {}, попытка {}/{}, пауза {}", e.getMessage(), attempt, maxAttempts, backoff);
            }
            sleep(backoff);
            backoff = backoff.multipliedBy(2);
        }
    }

    private synchronized void throttle() {
        long now = System.nanoTime();
        long waitNanos = lastRequestNanos + minDelay.toNanos() - now;
        if (lastRequestNanos != 0 && waitNanos > 0) {
            sleep(Duration.ofNanos(waitNanos));
        }
        lastRequestNanos = System.nanoTime();
    }

    private static void sleep(Duration d) {
        try {
            Thread.sleep(d);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Прервано ожидание", ie);
        }
    }
}
