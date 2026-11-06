package bank.pbp.essence.gateway;

/**
 * Everything the bank needs from Finastra Fusion Essence, in our own terms. Two connectors
 * implement it, chosen by {@code vendor.essence.mode}: {@code simulator} (default) and
 * {@code sandbox}. Nothing outside this adapter knows which one is running.
 */
public interface EssenceGateway {

    enum Lane {
        /** Card authorisations: short timeout inside Thredd's limit. */
        EHI,
        /** Everything else: 10 s timeout. */
        STANDARD
    }

    Party createParty(String name, String partyType, String idempotencyKey);

    OpenedAccount openAccount(String partyId, String name, String productCode, String idempotencyKey);

    Balance balance(String accountId, Lane lane, String scenario);

    Hold placeHold(String accountId, String amount, String currency, String reference, String idempotencyKey,
                   String scenario);

    /** Presentment: converts the hold into a posted debit. Returns the posting reference. */
    String captureHold(String holdId, String amount);

    void releaseHold(String holdId);

    /** Same-bank transfer. Returns the posting reference. */
    String transfer(TransferInstruction instruction, String scenario);

    /** Outbound UK payment; Finastra chooses FPS, CHAPS or Bacs and reports status by webhook. */
    PaymentSubmission submitPayment(PaymentInstruction instruction, String scenario);

    /** All postings for a business date (reconciliation, 30 s timeout per LLD). */
    java.util.List<Posting> postings(String date);

    record Posting(String postingId, String date, String accountId, String amount, String ref, String type) {
    }

    record Party(String partyId) {
    }

    record OpenedAccount(String accountId, String sortCode, String accountNumber) {
    }

    record Balance(String accountId, String available, String ledger, String currency) {
    }

    record Hold(String holdId, String available) {
    }

    record PaymentSubmission(String finastraRef, String status, String scheme) {
    }

    record TransferInstruction(String debitAccount, String creditorSortCode, String creditorAccountNumber,
                               String amount, String currency, String reference, String idempotencyKey) {
    }

    record PaymentInstruction(String debitAccount, String creditorName, String creditorSortCode,
                              String creditorAccountNumber, String amount, String currency, String reference,
                              String endToEndId, String idempotencyKey) {
    }
}
