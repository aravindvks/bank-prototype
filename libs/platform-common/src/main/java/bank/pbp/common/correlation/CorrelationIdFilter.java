package bank.pbp.common.correlation;

import bank.pbp.common.ids.Ids;
import bank.pbp.common.ids.JourneyType;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Reads X-Correlation-ID from the request, or starts a new journey ID when it is missing or
 * malformed, and echoes it on the response.
 */
public class CorrelationIdFilter extends OncePerRequestFilter {

    private final JourneyType defaultType;

    public CorrelationIdFilter(JourneyType defaultType) {
        this.defaultType = defaultType;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String incoming = request.getHeader(Correlation.HEADER);
        String id = Ids.isCorrelationId(incoming) ? incoming : Ids.newCorrelationId(defaultType);
        Correlation.set(id);
        response.setHeader(Correlation.HEADER, id);
        try {
            chain.doFilter(request, response);
        } finally {
            Correlation.clear();
        }
    }
}
