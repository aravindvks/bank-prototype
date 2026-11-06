package bank.pbp.simulator.essence;

import bank.pbp.simulator.Scenario;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Finastra Fusion Essence stand-in: accounts, balances and same-bank transfers.
 * Seeded with the four demo personas used across the HLD flows.
 */
@RestController
@RequestMapping("/essence/v1")
public class EssenceSimulator {

    public static final String BANK_SORT_CODE = "200001";

    private final Map<String, Account> accounts = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Object>> transfersByRequestId = new ConcurrentHashMap<>();

    public EssenceSimulator() {
        reset();
    }

    public final synchronized void reset() {
        accounts.clear();
        transfersByRequestId.clear();
        for (Account a : List.of(
                new Account("ACC-5501", BANK_SORT_CODE, "41550123", "Priya Shah", new BigDecimal("1000.00")),
                new Account("ACC-5510", BANK_SORT_CODE, "41550198", "Ravi Kumar", new BigDecimal("800.00")),
                new Account("ACC-5530", BANK_SORT_CODE, "41550301", "Meera Nair", new BigDecimal("300.00")),
                new Account("ACC-7001", BANK_SORT_CODE, "71700011", "Sharma Traders Ltd", new BigDecimal("25000.00")))) {
            accounts.put(a.id, a);
        }
    }

    @GetMapping("/accounts/{accountId}/balance")
    public ResponseEntity<?> balance(@PathVariable String accountId,
                                     @RequestHeader(value = Scenario.HEADER, required = false) String scenario) {
        Scenario.apply(scenario);
        Account a = accounts.get(accountId);
        if (a == null) {
            return error(HttpStatus.NOT_FOUND, "ACCOUNT_NOT_FOUND", "No account " + accountId);
        }
        return ResponseEntity.ok(Map.of("accountId", a.id, "available", a.balance.toPlainString(), "currency", "GBP"));
    }

    @PostMapping("/transfers")
    public synchronized ResponseEntity<?> transfer(@RequestBody TransferRequest req,
                                                   @RequestHeader(value = Scenario.HEADER, required = false) String scenario) {
        Scenario.apply(scenario);
        Map<String, Object> previous = transfersByRequestId.get(req.clientRequestId());
        if (previous != null) {
            return ResponseEntity.ok(previous);
        }
        Account debit = accounts.get(req.debitAccountId());
        if (debit == null) {
            return error(HttpStatus.NOT_FOUND, "ACCOUNT_NOT_FOUND", "No debit account " + req.debitAccountId());
        }
        Optional<Account> credit = accounts.values().stream()
                .filter(a -> a.sortCode.equals(req.creditSortCode()) && a.accountNumber.equals(req.creditAccountNumber()))
                .findFirst();
        if (credit.isEmpty()) {
            return error(HttpStatus.NOT_FOUND, "ACCOUNT_NOT_FOUND", "No credit account for the sort code and account number");
        }
        BigDecimal amount = new BigDecimal(req.amount());
        if (debit.balance.compareTo(amount) < 0) {
            return error(HttpStatus.UNPROCESSABLE_ENTITY, "INSUFFICIENT_FUNDS", "Available balance is below the amount");
        }
        debit.balance = debit.balance.subtract(amount);
        credit.get().balance = credit.get().balance.add(amount);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("postingRef", "PST-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase());
        body.put("status", "POSTED");
        body.put("debitAccountId", debit.id);
        body.put("creditAccountId", credit.get().id);
        transfersByRequestId.put(req.clientRequestId(), body);
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }

    private static ResponseEntity<Map<String, String>> error(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).body(Map.of("errorCode", code, "message", message));
    }

    public record TransferRequest(String debitAccountId, String creditSortCode, String creditAccountNumber,
                                  String amount, String currency, String reference, String clientRequestId) {
    }

    static final class Account {
        final String id;
        final String sortCode;
        final String accountNumber;
        final String name;
        BigDecimal balance;

        Account(String id, String sortCode, String accountNumber, String name, BigDecimal balance) {
            this.id = id;
            this.sortCode = sortCode;
            this.accountNumber = accountNumber;
            this.name = name;
            this.balance = balance;
        }
    }
}
