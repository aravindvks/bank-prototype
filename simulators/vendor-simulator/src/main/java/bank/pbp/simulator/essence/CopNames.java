package bank.pbp.simulator.essence;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Simplified Confirmation of Payee name matching, for the simulator only. The real matching is
 * done by the responding bank under Pay.UK rules; this is enough to demonstrate each outcome.
 */
final class CopNames {

    private static final Set<String> NOISE = Set.of("mr", "mrs", "ms", "miss", "dr", "ltd", "limited", "plc", "the");

    private CopNames() {
    }

    /** MATCH, CLOSE_MATCH or NO_MATCH. */
    static String compare(String given, String holder) {
        List<String> g = words(given);
        List<String> h = words(holder);
        if (g.isEmpty()) {
            return "NO_MATCH";
        }
        if (g.equals(h)) {
            return "MATCH";
        }
        String gs = String.join(" ", g);
        String hs = String.join(" ", h);
        if (distance(gs, hs) <= 2) {
            return "CLOSE_MATCH";
        }
        // Same surname and the same first initial: "R Kumar" for "Ravi Kumar".
        if (g.size() >= 2 && h.size() >= 2 && g.get(g.size() - 1).equals(h.get(h.size() - 1))
                && g.get(0).charAt(0) == h.get(0).charAt(0)) {
            return "CLOSE_MATCH";
        }
        return "NO_MATCH";
    }

    private static List<String> words(String name) {
        String clean = name == null ? "" : name.toLowerCase(Locale.UK).replaceAll("[^a-z0-9 ]", " ");
        return java.util.Arrays.stream(clean.trim().split("\\s+"))
                .filter(w -> !w.isEmpty() && !NOISE.contains(w))
                .toList();
    }

    private static int distance(String a, String b) {
        int[] prev = new int[b.length() + 1];
        int[] cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            prev[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] t = prev;
            prev = cur;
            cur = t;
        }
        return prev[b.length()];
    }
}
