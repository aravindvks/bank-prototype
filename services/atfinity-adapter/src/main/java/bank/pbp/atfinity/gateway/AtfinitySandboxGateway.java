package bank.pbp.atfinity.gateway;

import bank.pbp.common.error.PbpException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Connector for the Atfinity sandbox, to be written against the Atfinity API (authentication,
 * client and case creation, document upload, workflow codes). Fails clearly until then.
 */
@Component
@ConditionalOnProperty(name = "vendor.atfinity.mode", havingValue = "sandbox")
public class AtfinitySandboxGateway implements AtfinityGateway {

    @Override
    public KycCase openCase(String customerId, String subjectName, String workflowCode) {
        throw new PbpException("VND-UNAVAIL-001", 503, "Vendor unavailable",
                "Atfinity sandbox connector is not built yet (cases.create): waiting for the Atfinity API "
                        + "specification. Set vendor.atfinity.mode=simulator.");
    }
}
