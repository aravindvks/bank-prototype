package bank.pbp.simulator.credit;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Stand-in for the credit bureau and decision engine (vendor not chosen yet). The lending policy
 * lives here, as it would in the vendor's engine, never in our services. A soft search, then:
 * <ul>
 *   <li>DECLINE: employment UNEMPLOYED with income under £12,000, or the affordable limit is under £250
 *       (reason AFFORDABILITY); a name starting "Decline" (reason BUREAU_ADVERSE, for demos)</li>
 *   <li>REFER (manual underwriting): requested limit over £5,000, or income over £150,000</li>
 *   <li>APPROVE: limit = the requested limit, capped at the affordable limit (3 × monthly disposable
 *       income, rounded down to £100) and £15,000. Purchase APR 24.9%, or 22.9% for limits of £5,000+.</li>
 * </ul>
 * Monthly disposable income = annual income × 0.75 / 12 − housing − other commitments.
 */
@RestController
@RequestMapping("/credit/v1")
public class CreditDecisionSimulator {

    private final Map<String, Map<String, Object>> byKey = new ConcurrentHashMap<>();

    @PostMapping("/decisions")
    public ResponseEntity<Map<String, Object>> decide(@RequestBody Map<String, Object> req,
                                                      @RequestHeader(value = "IdempotencyKey", required = false) String key) {
        if (key != null && byKey.containsKey(key)) {
            return ResponseEntity.ok(byKey.get(key));
        }
        BigDecimal income = money(req.get("annualIncome"));
        BigDecimal housing = money(req.getOrDefault("monthlyHousing", "0"));
        BigDecimal commitments = money(req.getOrDefault("monthlyCommitments", "0"));
        BigDecimal requested = money(req.getOrDefault("requestedLimit", "1000"));
        String employment = String.valueOf(req.getOrDefault("employmentStatus", "EMPLOYED"));
        String name = String.valueOf(req.getOrDefault("name", ""));

        BigDecimal disposable = income.multiply(new BigDecimal("0.75")).divide(new BigDecimal("12"), 2, RoundingMode.DOWN)
                .subtract(housing).subtract(commitments);
        BigDecimal affordable = disposable.multiply(new BigDecimal("3"))
                .divide(new BigDecimal("100"), 0, RoundingMode.DOWN).multiply(new BigDecimal("100"))
                .min(new BigDecimal("15000")).max(BigDecimal.ZERO);

        String outcome;
        List<String> reasons = new ArrayList<>();
        BigDecimal limit = null;
        if (name.startsWith("Decline")) {
            outcome = "DECLINE";
            reasons.add("BUREAU_ADVERSE");
        } else if ("UNEMPLOYED".equals(employment) && income.compareTo(new BigDecimal("12000")) < 0) {
            outcome = "DECLINE";
            reasons.add("AFFORDABILITY");
        } else if (affordable.compareTo(new BigDecimal("250")) < 0) {
            outcome = "DECLINE";
            reasons.add("AFFORDABILITY");
        } else if (requested.compareTo(new BigDecimal("5000")) > 0 || income.compareTo(new BigDecimal("150000")) > 0) {
            outcome = "REFER";
            reasons.add(requested.compareTo(new BigDecimal("5000")) > 0 ? "LIMIT_ABOVE_AUTO_APPROVAL" : "HIGH_INCOME_VERIFY");
            limit = requested.min(affordable);
        } else {
            outcome = "APPROVE";
            limit = requested.min(affordable);
            if (limit.compareTo(requested) < 0) {
                reasons.add("LIMIT_REDUCED_AFFORDABILITY");
            }
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("decisionId", "dec_" + UUID.randomUUID().toString().substring(0, 12));
        body.put("bureauRef", "bur_" + UUID.randomUUID().toString().substring(0, 12));
        body.put("searchType", "SOFT");
        body.put("outcome", outcome);
        body.put("creditLimit", limit == null ? null : limit.setScale(2).toPlainString());
        body.put("purchaseApr", limit != null && limit.compareTo(new BigDecimal("5000")) >= 0 ? "22.9" : "24.9");
        body.put("monthlyDisposable", disposable.toPlainString());
        body.put("reasons", reasons);
        if (key != null) {
            byKey.put(key, body);
        }
        return ResponseEntity.ok(body);
    }

    private static BigDecimal money(Object o) {
        return new BigDecimal(String.valueOf(o)).setScale(2, RoundingMode.HALF_UP);
    }
}
