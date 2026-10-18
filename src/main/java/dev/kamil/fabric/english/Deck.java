package dev.kamil.fabric.english;

import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Leitner-box session over the sentences; pure logic, no UI. */
public final class Deck {

    /** How the user rated a card. */
    public enum Grade { AGAIN, GOOD, EASY }

    static final int SESSION_LIMIT = 10;
    static final int MAX_BOX = 5;
    private static final int[] INTERVAL_DAYS = {0, 0, 1, 3, 7, 16};

    private final LocalDate today;
    private final Map<String, Sentence> cards = new LinkedHashMap<>();
    private final Map<String, CardProgress> progress = new LinkedHashMap<>();
    private final Deque<Sentence> queue = new ArrayDeque<>();
    private final java.util.Set<String> inSession = new java.util.HashSet<>();
    private final java.util.Random random;
    private final int size;
    private int total;
    private int done;
    private int good;
    private int skipped;
    private final java.util.Set<String> missed = new java.util.HashSet<>();
    private final Deque<Step> history = new ArrayDeque<>();

    /** State before one grade or skip, so {@link #back()} can restore it. */
    private record Step(Sentence card, CardProgress old, int done, int good, int skipped, boolean wasMissed) {
    }

    public Deck(List<Sentence> sentences, Map<String, CardProgress> saved, LocalDate today) {
        this(sentences, saved, today, null);
    }

    public Deck(List<Sentence> sentences, Map<String, CardProgress> saved, LocalDate today,
                java.util.Random random) {
        this(sentences, saved, today, random, SESSION_LIMIT);
    }

    /** A session of up to {@code size} due cards; {@link #extend(int)} callers pass the same size. */
    public Deck(List<Sentence> sentences, Map<String, CardProgress> saved, LocalDate today,
                java.util.Random random, int size) {
        this.today = today;
        this.random = random;
        this.size = size;
        for (var s : sentences) {
            if (s.english() == null || s.english().isBlank() || s.pl() == null || s.pl().isBlank()) {
                continue;
            }
            var p = saved.get(s.english());
            if (p != null && cards.putIfAbsent(s.english(), s) == null) {
                progress.put(s.english(), p);
            }
        }
        total = 0;
        add(size);
    }

    /** Adds up to {@code n} more cards not yet in this session (due reviews); returns how many. */
    public int extend(int n) {
        return add(n);
    }

    private int add(int limit) {
        var picked = new ArrayList<Sentence>();
        cards.values().stream()
                .filter(s -> !inSession.contains(s.english()) && !progress.get(s.english()).due().isAfter(today))
                .sorted(Comparator.comparing((Sentence s) -> progress.get(s.english()).due()))
                .limit(limit)
                .forEach(picked::add);
        if (random != null) {
            java.util.Collections.shuffle(picked, random);
        }
        for (var s : picked) {
            inSession.add(s.english());
            queue.addLast(s);
        }
        total += picked.size();
        return picked.size();
    }

    /** The current card, or null when the session is over. */
    public Sentence next() {
        return queue.peekFirst();
    }

    /** Drops the current card from this session without grading it; the card is not scheduled again. */
    public void discard() {
        var card = queue.pollFirst();
        if (card == null) {
            return;
        }
        cards.remove(card.english());
        progress.remove(card.english());
        history.removeIf(step -> step.card().english().equals(card.english()));
        total--;
    }

    /**
     * Leaves the current card for this session without a grade: it keeps its progress, stays due and is not
     * offered again until a new session. {@link #back()} returns it to the front unchanged.
     */
    public void skip() {
        var card = queue.pollFirst();
        if (card == null) {
            return;
        }
        history.addFirst(new Step(card, progress.get(card.english()), done, good, skipped,
                missed.contains(card.english())));
        skipped++;
    }

    /** True when a grade or skip of this session can be undone. */
    public boolean canBack() {
        return !history.isEmpty();
    }

    /**
     * Undoes the last grade or skip: the card returns to the front of the queue with its old progress and counts.
     * Returns false when nothing was graded or skipped yet.
     */
    public boolean back() {
        var step = history.pollFirst();
        if (step == null) {
            return false;
        }
        var english = step.card().english();
        queue.removeLastOccurrence(step.card());
        queue.addFirst(step.card());
        progress.put(english, step.old());
        done = step.done();
        good = step.good();
        skipped = step.skipped();
        if (step.wasMissed()) {
            missed.add(english);
        } else {
            missed.remove(english);
        }
        return true;
    }

    public void grade(Grade grade) {
        var card = queue.pollFirst();
        if (card == null) {
            return;
        }
        var old = progress.get(card.english());
        history.addFirst(new Step(card, old, done, good, skipped, missed.contains(card.english())));
        if (grade == Grade.AGAIN) {
            progress.put(card.english(), new CardProgress(card.english(), 1, today));
            missed.add(card.english());
            queue.addLast(card);
            return;
        }
        int box = Math.min(MAX_BOX, old.box() + (grade == Grade.EASY ? 2 : 1));
        progress.put(card.english(), new CardProgress(card.english(), box, today.plusDays(INTERVAL_DAYS[box])));
        done++;
        if (!missed.contains(card.english())) {
            good++;
        }
    }

    /** Progress of the sentences that still exist, in sentence order. */
    public List<CardProgress> progress() {
        return new ArrayList<>(progress.values());
    }

    /** Cards finished in this session (graded Good or Easy). */
    public int done() {
        return done;
    }

    /** Cards left behind in this session: graded Good or Easy, or skipped; the number before the current card. */
    public int position() {
        return done + skipped;
    }

    /** Cards skipped in this session; they keep their progress and stay due. */
    public int skipped() {
        return skipped;
    }

    /** Cards finished in this session without an Again. */
    public int good() {
        return good;
    }

    /** Cards in this session's queue at the start. */
    public int sessionSize() {
        return total;
    }

    /** Saved progress of a card of this deck, or null. */
    public CardProgress progressOf(String english) {
        return progress.get(english);
    }

    /** {@code due today} for a first-box card, otherwise {@code box N}. */
    public String labelOf(String english) {
        var p = progress.get(english);
        if (p == null) {
            return "";
        }
        return p.box() <= 1 && !p.due().isAfter(today) ? "due today" : "box " + p.box();
    }

    /** {@code Next review: tomorrow (4)} / {@code Next review: in 3 days (2)}, or null when nothing is scheduled. */
    public String nextReviewText() {
        var date = nextDueDate();
        if (date == null) {
            return null;
        }
        long days = java.time.temporal.ChronoUnit.DAYS.between(today, date);
        return "Next review: " + (days == 1 ? "tomorrow" : "in " + days + " days") + " (" + nextDueCount() + ")";
    }

    /** Cards currently due (due today or earlier). */
    public int dueCount() {
        return (int) progress.values().stream().filter(p -> !p.due().isAfter(today)).count();
    }

    /** Earliest due date after today, or null. */
    public LocalDate nextDueDate() {
        return progress.values().stream().map(CardProgress::due).filter(d -> d.isAfter(today))
                .min(Comparator.naturalOrder()).orElse(null);
    }

    /** How many cards are due on {@link #nextDueDate()}. */
    public int nextDueCount() {
        var date = nextDueDate();
        return date == null ? 0 : (int) progress.values().stream().filter(p -> p.due().equals(date)).count();
    }
}
