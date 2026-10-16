package dev.kamil.fabric.snippets;

/** Subsequence matching with a score; no JavaFX. */
public final class Fuzzy {

    private Fuzzy() {
    }

    /** Best of the name (weighted double) and the text, or -1 when the query is not a subsequence of either. */
    public static int score(String query, String name, String text) {
        if (query.isBlank()) {
            return 0;
        }
        int inName = score(query, name);
        int inText = score(query, text);
        if (inName < 0 && inText < 0) {
            return -1;
        }
        return Math.max(inName < 0 ? -1 : inName * 2, inText);
    }

    /** Greedy subsequence score, case-insensitive; -1 when no match. Consecutive and word-start hits score more. */
    public static int score(String query, String target) {
        var q = query.strip().toLowerCase();
        var t = target.toLowerCase();
        int score = 0;
        int ti = 0;
        int run = 0;
        for (int qi = 0; qi < q.length(); qi++) {
            char c = q.charAt(qi);
            int found = t.indexOf(c, ti);
            if (found < 0) {
                return -1;
            }
            boolean consecutive = found == ti && qi > 0;
            boolean wordStart = found == 0 || !Character.isLetterOrDigit(t.charAt(found - 1));
            run = consecutive ? run + 1 : 0;
            score += 1 + run * 3 + (wordStart ? 5 : 0);
            ti = found + 1;
        }
        return score - Math.min(t.length() / 20, 3);
    }
}
