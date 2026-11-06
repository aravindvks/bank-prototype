package bank.pbp.orchestrator.payments;

/**
 * Workflow input built from a payment.create command. Plain strings keep it safe for Temporal's
 * JSON data converter; amounts stay decimal strings.
 *
 * @param paymentType INTERNAL when the creditor sort code is this bank's, otherwise FPS
 *                    (Finastra picks the actual UK rail in Increment 1)
 * @param initiatedAt ISO 8601 instant; also the payment row's partition key
 */
public record PaymentCommand(
        String paymentId,
        String customerId,
        String correlationId,
        String debtorAccount,
        String creditorName,
        String creditorSortCode,
        String creditorAccountNumber,
        String amount,
        String currency,
        String remittanceInfo,
        String paymentType,
        String idempotencyKey,
        String initiatedAt) {
}
