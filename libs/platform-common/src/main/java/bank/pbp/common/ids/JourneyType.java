package bank.pbp.common.ids;

/** Journey prefixes used in correlation IDs (LLD section 1). */
public enum JourneyType {
    /** Onboarding and KYC/KYB. */
    ONB,
    /** Outbound and inbound payments. */
    PAY,
    /** Card lifecycle and card controls. */
    CRD,
    /** Card authorisations and other single transactions. */
    TXN,
    /** Customer profile changes. */
    CUS,
    /** Reconciliation runs. */
    REC
}
