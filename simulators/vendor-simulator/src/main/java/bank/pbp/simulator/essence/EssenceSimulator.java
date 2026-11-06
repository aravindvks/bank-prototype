package bank.pbp.simulator.essence;

import bank.pbp.simulator.Scenario;
import bank.pbp.simulator.WebhookSender;
import java.math.BigDecimal;
import java.security.SecureRandom;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Finastra Fusion Essence stand-in: parties, accounts, balances, holds, same-bank transfers,
 * outbound UK payments with asynchronous scheme status, and inbound credits.
 * Field names are placeholders until the Essence API specification is received.
 *
 * <p>Available balance = ledger balance − open holds.
 */
@RestController
@RequestMapping("/essence/v1")
public class EssenceSimulator {

    public static final String BANK_SORT_CODE = "200001";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final Map<String, Account> accounts = new ConcurrentHashMap<>();
    private final Map<String, Hold> holds = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Object>> byRequestId = new ConcurrentHashMap<>();
    /** Every posting, as Essence would report them for reconciliation (GET /postings?date=). */
    private final List<Map<String, Object>> postings = new java.util.concurrent.CopyOnWriteArrayList<>();
    private final WebhookSender webhooks;
    private final String webhookUrl;

    public EssenceSimulator(WebhookSender webhooks, @Value("${sim.essence.webhook-url}") String webhookUrl) {
        this.webhooks = webhooks;
        this.webhookUrl = webhookUrl;
        reset();
    }

    public final synchronized void reset() {
        accounts.clear();
        holds.clear();
        byRequestId.clear();
        postings.clear();
        for (Account a : List.of(
                new Account("ACC-5501", "41550123", "Priya Shah", "1000.00"),
                new Account("ACC-5510", "41550198", "Ravi Kumar", "800.00"),
                new Account("ACC-5530", "41550301", "Meera Nair", "300.00"),
                new Account("ACC-7001", "71700011", "Sharma Traders Ltd", "25000.00"))) {
            accounts.put(a.id, a);
        }
    }

    // ---------- parties and accounts ----------

    @PostMapping("/parties")
    public ResponseEntity<?> createParty(@RequestBody Map<String, Object> req,
                                         @RequestHeader(value = Scenario.HEADER, required = false) String scenario) {
        Scenario.apply(scenario);
        Map<String, Object> cached = byRequestId.get("party:" + req.get("clientRequestId"));
        if (cached != null) {
            return ResponseEntity.ok(cached);
        }
        Map<String, Object> body = Map.of("partyId", "ESS-PTY-" + (10000 + RANDOM.nextInt(90000)),
                "name", String.valueOf(req.get("name")), "status", "ACTIVE");
        byRequestId.put("party:" + req.get("clientRequestId"), body);
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }

    @PostMapping("/accounts")
    public synchronized ResponseEntity<?> openAccount(@RequestBody Map<String, Object> req,
                                                      @RequestHeader(value = Scenario.HEADER, required = false) String scenario) {
        Scenario.apply(scenario);
        Map<String, Object> cached = byRequestId.get("account:" + req.get("clientRequestId"));
        if (cached != null) {
            return ResponseEntity.ok(cached);
        }
        String id;
        do {
            id = "ACC-" + (6000 + RANDOM.nextInt(3999));
        } while (accounts.containsKey(id));
        String number;
        do {
            number = String.valueOf(40_000_000 + RANDOM.nextInt(9_999_999));
        } while (findByNumber(BANK_SORT_CODE, number).isPresent());
        Account a = new Account(id, number, String.valueOf(req.getOrDefault("name", "")), "0.00");
        accounts.put(id, a);
        Map<String, Object> body = Map.of("accountId", id, "sortCode", BANK_SORT_CODE, "accountNumber", number,
                "partyId", String.valueOf(req.get("partyId")), "status", "ACTIVE");
        byRequestId.put("account:" + req.get("clientRequestId"), body);
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }

    @GetMapping("/accounts/{accountId}/balance")
    public ResponseEntity<?> balance(@PathVariable String accountId,
                                     @RequestHeader(value = Scenario.HEADER, required = false) String scenario) {
        Scenario.apply(scenario);
        Account a = accounts.get(accountId);
        if (a == null) {
            return error(HttpStatus.NOT_FOUND, "ACCOUNT_NOT_FOUND", "No account " + accountId);
        }
        return ResponseEntity.ok(Map.of("accountId", a.id, "available", available(a).toPlainString(),
                "ledger", a.ledger.toPlainString(), "currency", "GBP"));
    }

    // ---------- holds (card authorisations) ----------

    @PostMapping("/accounts/{accountId}/holds")
    public synchronized ResponseEntity<?> placeHold(@PathVariable String accountId, @RequestBody Map<String, Object> req,
                                                    @RequestHeader(value = Scenario.HEADER, required = false) String scenario) {
        Scenario.apply(scenario);
        Map<String, Object> cached = byRequestId.get("hold:" + req.get("clientRequestId"));
        if (cached != null) {
            return ResponseEntity.ok(cached);
        }
        Account a = accounts.get(accountId);
        if (a == null) {
            return error(HttpStatus.NOT_FOUND, "ACCOUNT_NOT_FOUND", "No account " + accountId);
        }
        BigDecimal amount = new BigDecimal(String.valueOf(req.get("amount")));
        if (available(a).compareTo(amount) < 0) {
            return error(HttpStatus.UNPROCESSABLE_ENTITY, "INSUFFICIENT_FUNDS", "Available balance is below the amount");
        }
        Hold h = new Hold("HLD-" + shortId(), accountId, amount);
        holds.put(h.id, h);
        Map<String, Object> body = Map.of("holdId", h.id, "accountId", accountId, "amount", amount.toPlainString(),
                "available", available(a).toPlainString());
        byRequestId.put("hold:" + req.get("clientRequestId"), body);
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }

    /** Presentment: turns the hold into a posted debit for the final amount. */
    @PostMapping("/holds/{holdId}/capture")
    public synchronized ResponseEntity<?> capture(@PathVariable String holdId, @RequestBody Map<String, Object> req) {
        Hold h = holds.remove(holdId);
        if (h == null) {
            return error(HttpStatus.NOT_FOUND, "HOLD_NOT_FOUND", "No open hold " + holdId);
        }
        Account a = accounts.get(h.accountId);
        BigDecimal amount = req.get("amount") == null ? h.amount : new BigDecimal(String.valueOf(req.get("amount")));
        a.ledger = a.ledger.subtract(amount);
        String postingRef = "PST-" + shortId();
        post(a.id, amount.negate(), postingRef, "CARD_PRESENTMENT");
        return ResponseEntity.ok(Map.of("postingRef", postingRef, "amount", amount.toPlainString()));
    }

    @DeleteMapping("/holds/{holdId}")
    public synchronized ResponseEntity<?> release(@PathVariable String holdId) {
        Hold h = holds.remove(holdId);
        return h == null
                ? error(HttpStatus.NOT_FOUND, "HOLD_NOT_FOUND", "No open hold " + holdId)
                : ResponseEntity.ok(Map.of("holdId", holdId, "status", "RELEASED"));
    }

    // ---------- same-bank transfer ----------

    @PostMapping("/transfers")
    public synchronized ResponseEntity<?> transfer(@RequestBody TransferRequest req,
                                                   @RequestHeader(value = Scenario.HEADER, required = false) String scenario) {
        Scenario.apply(scenario);
        Map<String, Object> previous = byRequestId.get("transfer:" + req.clientRequestId());
        if (previous != null) {
            return ResponseEntity.ok(previous);
        }
        Account debit = accounts.get(req.debitAccountId());
        if (debit == null) {
            return error(HttpStatus.NOT_FOUND, "ACCOUNT_NOT_FOUND", "No debit account " + req.debitAccountId());
        }
        Optional<Account> credit = findByNumber(req.creditSortCode(), req.creditAccountNumber());
        if (credit.isEmpty()) {
            return error(HttpStatus.NOT_FOUND, "ACCOUNT_NOT_FOUND", "No credit account for the sort code and account number");
        }
        BigDecimal amount = new BigDecimal(req.amount());
        if (available(debit).compareTo(amount) < 0) {
            return error(HttpStatus.UNPROCESSABLE_ENTITY, "INSUFFICIENT_FUNDS", "Available balance is below the amount");
        }
        debit.ledger = debit.ledger.subtract(amount);
        credit.get().ledger = credit.get().ledger.add(amount);
        post(debit.id, amount.negate(), req.clientRequestId(), "TRANSFER_OUT");
        post(credit.get().id, amount, req.clientRequestId(), "TRANSFER_IN");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("postingRef", "PST-" + shortId());
        body.put("status", "POSTED");
        body.put("debitAccountId", debit.id);
        body.put("creditAccountId", credit.get().id);
        byRequestId.put("transfer:" + req.clientRequestId(), body);
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }

    // ---------- outbound UK payments (Finastra routes to FPS, CHAPS or Bacs) ----------

    /**
     * Accepts the payment, debits the account, and reports scheme status by webhook:
     * ACCEPTED after 1 s, then COMPLETED after 2 s. A creditor sort code starting with 99, or the
     * scenario {@code scheme-reject}, is rejected by the scheme and the debit is reversed.
     */
    @PostMapping("/payments")
    public synchronized ResponseEntity<?> submitPayment(@RequestBody Map<String, Object> req,
                                                        @RequestHeader(value = Scenario.HEADER, required = false) String scenario) {
        if (!"scheme-reject".equals(scenario)) {
            Scenario.apply(scenario);
        }
        Map<String, Object> previous = byRequestId.get("payment:" + req.get("clientRequestId"));
        if (previous != null) {
            return ResponseEntity.ok(previous);
        }
        Account debit = accounts.get(String.valueOf(req.get("debitAccountId")));
        if (debit == null) {
            return error(HttpStatus.NOT_FOUND, "ACCOUNT_NOT_FOUND", "No debit account " + req.get("debitAccountId"));
        }
        BigDecimal amount = new BigDecimal(String.valueOf(req.get("amount")));
        if (available(debit).compareTo(amount) < 0) {
            return error(HttpStatus.UNPROCESSABLE_ENTITY, "INSUFFICIENT_FUNDS", "Available balance is below the amount");
        }
        debit.ledger = debit.ledger.subtract(amount);
        post(debit.id, amount.negate(), String.valueOf(req.get("endToEndId")), "PAYMENT_OUT");
        String finastraRef = "FIN-" + shortId();
        String creditorSortCode = String.valueOf(req.get("creditorSortCode"));
        boolean reject = "scheme-reject".equals(scenario) || creditorSortCode.startsWith("99");
        String scheme = amount.compareTo(new BigDecimal("1000000.00")) > 0 ? "CHAPS" : "FPS";

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("finastraRef", finastraRef);
        body.put("status", "SUBMITTED");
        body.put("scheme", scheme);
        byRequestId.put("payment:" + req.get("clientRequestId"), body);

        String endToEndId = String.valueOf(req.get("endToEndId"));
        webhooks.send(webhookUrl, statusEvent(finastraRef, endToEndId, scheme, "ACCEPTED", null), 1000);
        if (reject) {
            webhooks.send(webhookUrl, statusEvent(finastraRef, endToEndId, scheme, "REJECTED",
                    "AC01 Incorrect account number"), 2000);
            synchronized (this) {
                debit.ledger = debit.ledger.add(amount);
                post(debit.id, amount, endToEndId, "PAYMENT_RETURN");
            }
        } else {
            webhooks.send(webhookUrl, statusEvent(finastraRef, endToEndId, scheme, "COMPLETED", null), 3000);
        }
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(body);
    }

    private static Map<String, Object> statusEvent(String ref, String endToEndId, String scheme, String status,
                                                   String reason) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("eventId", "EVT-" + shortId());
        e.put("type", "payment.status");
        e.put("finastraRef", ref);
        e.put("endToEndId", endToEndId);
        e.put("scheme", scheme);
        e.put("status", status);
        e.put("reason", reason);
        return e;
    }

    // ---------- inbound credits (demo toolbar "Receive £20") ----------

    /** Simulates a Faster Payment arriving from another bank: credits the account and notifies the bank. */
    @PostMapping("/sim/inbound")
    public synchronized ResponseEntity<?> inbound(@RequestBody Map<String, Object> req) {
        Account a = accounts.get(String.valueOf(req.get("creditAccountId")));
        if (a == null) {
            return error(HttpStatus.NOT_FOUND, "ACCOUNT_NOT_FOUND", "No account " + req.get("creditAccountId"));
        }
        BigDecimal amount = new BigDecimal(String.valueOf(req.getOrDefault("amount", "20.00")));
        a.ledger = a.ledger.add(amount);
        String inRef = "FIN-IN-" + shortId();
        post(a.id, amount, inRef, "PAYMENT_IN");
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("eventId", "EVT-" + shortId());
        e.put("type", "credit.received");
        e.put("finastraRef", inRef);
        e.put("scheme", "FPS");
        e.put("creditAccountId", a.id);
        e.put("amount", amount.toPlainString());
        e.put("currency", "GBP");
        e.put("debtorName", String.valueOf(req.getOrDefault("debtorName", "J Smith")));
        e.put("debtorSortCode", String.valueOf(req.getOrDefault("debtorSortCode", "040004")));
        e.put("reference", String.valueOf(req.getOrDefault("reference", "Payment")));
        webhooks.send(webhookUrl, e, 200);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(e);
    }

    // ---------- postings (reconciliation source) ----------

    @GetMapping("/postings")
    public List<Map<String, Object>> postings(@org.springframework.web.bind.annotation.RequestParam String date) {
        return postings.stream().filter(p -> date.equals(p.get("date"))).toList();
    }

    /** Demo control: a posting nobody at the bank asked for, so reconciliation has something to find. */
    @PostMapping("/sim/rogue-posting")
    public synchronized Map<String, Object> rogue(@RequestBody Map<String, Object> req) {
        Account a = accounts.get(String.valueOf(req.getOrDefault("accountId", "ACC-5501")));
        BigDecimal amount = new BigDecimal(String.valueOf(req.getOrDefault("amount", "7.77")));
        a.ledger = a.ledger.subtract(amount);
        return post(a.id, amount.negate(), "ROGUE-" + shortId(), "MANUAL_ADJUSTMENT");
    }

    private Map<String, Object> post(String accountId, BigDecimal signedAmount, String ref, String type) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("postingId", "PST-" + shortId());
        p.put("date", java.time.LocalDate.now(java.time.ZoneOffset.UTC).toString());
        p.put("accountId", accountId);
        p.put("amount", signedAmount.toPlainString());
        p.put("ref", ref);
        p.put("type", type);
        postings.add(p);
        return p;
    }

    // ---------- helpers ----------

    private Optional<Account> findByNumber(String sortCode, String number) {
        return accounts.values().stream()
                .filter(a -> BANK_SORT_CODE.equals(sortCode) && a.accountNumber.equals(number))
                .findFirst();
    }

    private BigDecimal available(Account a) {
        BigDecimal held = holds.values().stream().filter(h -> h.accountId.equals(a.id))
                .map(h -> h.amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        return a.ledger.subtract(held);
    }

    private static String shortId() {
        return UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }

    private static ResponseEntity<Map<String, String>> error(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).body(Map.of("errorCode", code, "message", message));
    }

    public record TransferRequest(String debitAccountId, String creditSortCode, String creditAccountNumber,
                                  String amount, String currency, String reference, String clientRequestId) {
    }

    static final class Account {
        final String id;
        final String accountNumber;
        final String name;
        BigDecimal ledger;

        Account(String id, String accountNumber, String name, String ledger) {
            this.id = id;
            this.accountNumber = accountNumber;
            this.name = name;
            this.ledger = new BigDecimal(ledger);
        }
    }

    record Hold(String id, String accountId, BigDecimal amount) {
    }
}
