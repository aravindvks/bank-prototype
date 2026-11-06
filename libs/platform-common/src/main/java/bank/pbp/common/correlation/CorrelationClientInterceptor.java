package bank.pbp.common.correlation;

import java.io.IOException;
import java.time.Instant;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

/** Adds the internal headers from LLD section 1 to every outbound RestClient call. */
public class CorrelationClientInterceptor implements ClientHttpRequestInterceptor {

    private final String actor;

    public CorrelationClientInterceptor(String serviceName) {
        this.actor = "svc:" + serviceName;
    }

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
            throws IOException {
        Correlation.current().ifPresent(id -> request.getHeaders().set(Correlation.HEADER, id));
        if (!request.getHeaders().containsKey("X-Actor")) {
            request.getHeaders().set("X-Actor", actor);
        }
        request.getHeaders().set("X-Request-Time", Instant.now().toString());
        return execution.execute(request, body);
    }
}
