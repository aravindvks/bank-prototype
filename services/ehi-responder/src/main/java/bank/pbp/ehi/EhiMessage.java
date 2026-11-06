package bank.pbp.ehi;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/**
 * Our model of an EHI message. Field names are placeholders: the translation module maps
 * Thredd's real format to this once the sandbox EHI specification is received (LLD section 5).
 */
public record EhiMessage(
        @NotBlank String messageId,
        @NotBlank @Pattern(regexp = "AUTHORISATION") String messageType,
        @NotBlank String cardToken,
        @NotBlank @Pattern(regexp = "\\d{1,15}\\.\\d{2}") String amount,
        @NotBlank String currency,
        @NotNull @Valid Merchant merchant) {

    public record Merchant(@NotBlank String name, String mcc, String channel) {
    }
}
