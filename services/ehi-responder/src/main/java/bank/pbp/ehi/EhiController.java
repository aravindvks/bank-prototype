package bank.pbp.ehi;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Endpoint Thredd calls through the dedicated EHI load-balancer listener (not the API gateway).
 * Signature or mutual-TLS checks are added once Thredd confirms its method (LLD section 12).
 */
@RestController
public class EhiController {

    private final AuthorisationService authorisations;

    public EhiController(AuthorisationService authorisations) {
        this.authorisations = authorisations;
    }

    @PostMapping("/ehi/v1/messages")
    public AuthorisationService.Decision handle(@Valid @RequestBody EhiMessage message) {
        return authorisations.authorise(message);
    }
}
