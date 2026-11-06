package bank.pbp.atfinity.gateway;

/**
 * What the bank needs from Atfinity, in our own terms. Connectors chosen by
 * {@code vendor.atfinity.mode}: {@code simulator} (default) or {@code sandbox}.
 */
public interface AtfinityGateway {

    /** Opens a KYC or KYB case. {@code customerId} goes to Atfinity as the external reference. */
    KycCase openCase(String customerId, String subjectName, String workflowCode);

    record KycCase(String caseId, String status) {
    }
}
