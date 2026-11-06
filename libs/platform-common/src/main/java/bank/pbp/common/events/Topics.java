package bank.pbp.common.events;

/** Kafka topic names from LLD section 9. Each topic also has a {@code <topic>.DLT}. */
public final class Topics {

    public static final String PAYMENT_COMMANDS = "payment-commands";
    public static final String ONBOARDING_COMMANDS = "onboarding-commands";
    public static final String CARD_COMMANDS = "card-commands";
    public static final String CUSTOMER_COMMANDS = "customer-commands";

    public static final String KYC_EVENTS = "kyc-events";
    public static final String CARD_EVENTS = "card-events";
    public static final String PAYMENT_EVENTS = "payment-events";
    public static final String ONBOARDING_EVENTS = "onboarding-events";
    public static final String MAPPING_EVENTS = "mapping-events";

    private Topics() {
    }

    public static String dlt(String topic) {
        return topic + ".DLT";
    }
}
