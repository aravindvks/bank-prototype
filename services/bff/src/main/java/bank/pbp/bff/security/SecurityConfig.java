package bank.pbp.bff.security;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Login (LLD section 12). With {@code pbp.auth.enabled=true} every API call needs a bearer token
 * from Identity Domains (Keycloak stands in locally). Realm roles become authorities:
 * {@code staff} may view the staff portal; {@code compliance} may decide reviews and run
 * reconciliation. Customers are any signed-in user linked to a bank customer.
 */
@Configuration
public class SecurityConfig {

    @Bean
    @ConditionalOnProperty(name = "pbp.auth.enabled", havingValue = "true")
    SecurityFilterChain secured(HttpSecurity http) throws Exception {
        DefaultBearerTokenResolver tokens = new DefaultBearerTokenResolver();
        // Browsers' EventSource cannot send headers, so the live-push stream may carry the token in the URL.
        tokens.setAllowUriQueryParameter(true);
        http.csrf(c -> c.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(a -> a
                        .dispatcherTypeMatchers(jakarta.servlet.DispatcherType.ASYNC, jakarta.servlet.DispatcherType.ERROR)
                        .permitAll()
                        .requestMatchers("/actuator/**").permitAll()
                        .requestMatchers(HttpMethod.POST, "/v1/staff/reviews/**", "/v1/staff/recon/runs")
                        .hasRole("compliance")
                        .requestMatchers("/v1/staff/**").hasAnyRole("staff", "compliance")
                        .anyRequest().authenticated())
                .oauth2ResourceServer(o -> o
                        .bearerTokenResolver(tokens)
                        .jwt(j -> j.jwtAuthenticationConverter(new RealmRoles())));
        return http.build();
    }

    @Bean
    @ConditionalOnProperty(name = "pbp.auth.enabled", havingValue = "false", matchIfMissing = true)
    SecurityFilterChain open(HttpSecurity http) throws Exception {
        http.csrf(c -> c.disable()).authorizeHttpRequests(a -> a.anyRequest().permitAll());
        return http.build();
    }

    /** Keycloak / Identity Domains realm roles (realm_access.roles) → ROLE_ authorities. */
    static final class RealmRoles implements Converter<Jwt, AbstractAuthenticationToken> {
        @Override
        public AbstractAuthenticationToken convert(Jwt jwt) {
            Object realm = jwt.getClaims().get("realm_access");
            Collection<?> roles = realm instanceof Map<?, ?> m && m.get("roles") instanceof Collection<?> c ? c : List.of();
            List<GrantedAuthority> authorities = roles.stream()
                    .map(r -> (GrantedAuthority) new SimpleGrantedAuthority("ROLE_" + r))
                    .toList();
            return new JwtAuthenticationToken(jwt, authorities, jwt.getClaimAsString("preferred_username"));
        }
    }
}
