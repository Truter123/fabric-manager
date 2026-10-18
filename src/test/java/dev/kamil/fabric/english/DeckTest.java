package dev.kamil.fabric.english;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DeckTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 16);

    private static Sentence s(String en) {
        return new Sentence("2026-10-01", en, "pl " + en, "say", "watch");
    }

    private static Map<String, CardProgress> map(CardProgress... cards) {
        var m = new java.util.HashMap<String, CardProgress>();
        for (var c : cards) {
            m.put(c.english(), c);
        }
        return m;
    }

    private static final Map<String, CardProgress> NONE = Map.of();

    @Test
    void sessionSizeLimitsTheCardsAndLabelsTellTheBox() {
        var deck = new Deck(List.of(s("A"), s("B"), s("C")),
                map(new CardProgress("A", 1, TODAY), new CardProgress("B", 3, TODAY),
                        new CardProgress("C", 1, TODAY.plusDays(4))),
                TODAY, null, 1);
        assertEquals(1, deck.sessionSize());
        assertEquals("due today", deck.labelOf("A"));
        assertEquals("box 3", deck.labelOf("B"));
        assertEquals(1, deck.extend(1));
        assertEquals(2, deck.sessionSize());
        assertEquals("Next review: in 4 days (1)", deck.nextReviewText());
    }

    @Test
    void unlearnedSentencesAreNotCards() {
        var deck = new Deck(List.of(s("A"), s("B")), map(new CardProgress("B", 2, TODAY)), TODAY);
        assertEquals(List.of("B"), deck.progress().stream().map(CardProgress::english).toList());
        assertEquals("B", deck.next().english());
        assertEquals(1, deck.sessionSize());
        assertNull(new Deck(List.of(s("A")), NONE, TODAY).next());
    }

    @Test
    void blankCardsAreIgnored() {
        var deck = new Deck(List.of(new Sentence("d", "", "pl", "", ""), new Sentence("d", "en", " ", "", "")),
                Map.of(), TODAY);
        assertNull(deck.next());
        assertEquals(0, deck.progress().size());
    }

    @Test
    void goodMovesBoxOneToTwoDueTomorrow() {
        var deck = new Deck(List.of(s("A")), map(new CardProgress("A", 1, TODAY)), TODAY);
        deck.grade(Deck.Grade.GOOD);
        assertEquals(new CardProgress("A", 2, TODAY.plusDays(1)), deck.progress().get(0));
        assertNull(deck.next());
        assertEquals(1, deck.done());
        assertEquals(1, deck.good());
        assertEquals(TODAY.plusDays(1), deck.nextDueDate());
        assertEquals(1, deck.nextDueCount());
    }

    @Test
    void easyCapsAtBoxFive() {
        var deck = new Deck(List.of(s("A")), map(new CardProgress("A", 4, TODAY)), TODAY);
        deck.grade(Deck.Grade.EASY);
        assertEquals(new CardProgress("A", 5, TODAY.plusDays(16)), deck.progress().get(0));
    }

    @Test
    void againRequeuesAtEndOfSession() {
        var deck = new Deck(List.of(s("A"), s("B")), map(new CardProgress("A", 3, TODAY), new CardProgress("B", 3, TODAY)), TODAY);
        var first = deck.next().english();
        deck.grade(Deck.Grade.AGAIN);
        assertEquals(1, deck.progress().stream().filter(p -> p.english().equals(first)).findFirst().get().box());
        var second = deck.next().english();
        deck.grade(Deck.Grade.GOOD);
        assertEquals(first, deck.next().english());
        assertEquals(false, first.equals(second));
        deck.grade(Deck.Grade.GOOD);
        assertNull(deck.next());
        assertEquals(2, deck.done());
        assertEquals(1, deck.good());
    }

    @Test
    void sessionIsLimitedToTen() {
        var list = new ArrayList<Sentence>();
        var saved = new ArrayList<CardProgress>();
        for (int i = 0; i < 25; i++) {
            list.add(s("S" + i));
            saved.add(new CardProgress("S" + i, 2, TODAY));
        }
        var deck = new Deck(list, map(saved.toArray(new CardProgress[0])), TODAY);
        assertEquals(10, deck.sessionSize());
        assertEquals(25, deck.dueCount());
    }

    @Test
    void shuffledSessionHasSameCards() {
        var list = new ArrayList<Sentence>();
        var saved = new ArrayList<CardProgress>();
        for (int i = 0; i < 15; i++) {
            list.add(s("S" + i));
            saved.add(new CardProgress("S" + i, 2, TODAY.minusDays(i)));
        }
        var plain = new Deck(list, map(saved.toArray(new CardProgress[0])), TODAY);
        var shuffled = new Deck(list, map(saved.toArray(new CardProgress[0])), TODAY, new java.util.Random(42));
        assertEquals(drain(plain), drain(shuffled));
    }

    private static java.util.Set<String> drain(Deck deck) {
        var set = new java.util.HashSet<String>();
        while (deck.next() != null) {
            set.add(deck.next().english());
            deck.grade(Deck.Grade.GOOD);
        }
        return set;
    }

    @Test
    void extendAddsCardsNotInSession() {
        var list = new ArrayList<Sentence>();
        var saved = new ArrayList<CardProgress>();
        for (int i = 0; i < 13; i++) {
            list.add(s("S" + i));
            saved.add(new CardProgress("S" + i, 2, TODAY));
        }
        var deck = new Deck(list, map(saved.toArray(new CardProgress[0])), TODAY);
        assertEquals(10, deck.sessionSize());
        assertEquals(3, deck.extend(5));
        assertEquals(13, deck.sessionSize());
        var seen = new java.util.HashSet<String>();
        while (deck.next() != null) {
            assertEquals(true, seen.add(deck.next().english()));
            deck.grade(Deck.Grade.GOOD);
        }
        assertEquals(13, seen.size());
        assertEquals(0, deck.extend(5));
    }

    @Test
    void notDueCardsAreSkipped() {
        var deck = new Deck(List.of(s("A")), map(new CardProgress("A", 2, TODAY.plusDays(1))), TODAY);
        assertNull(deck.next());
        assertEquals(0, deck.dueCount());
        assertEquals(TODAY.plusDays(1), deck.nextDueDate());
    }

    @Test
    void deletedSentencesAreDroppedFromProgress() {
        var deck = new Deck(List.of(s("A")), map(new CardProgress("A", 2, TODAY),
                new CardProgress("Gone", 3, TODAY)), TODAY);
        assertEquals(List.of("A"), deck.progress().stream().map(CardProgress::english).toList());
    }

    @Test
    void oldestDueFirst() {
        var deck = new Deck(List.of(s("A"), s("B"), s("C")),
                map(new CardProgress("A", 2, TODAY.minusDays(1)), new CardProgress("B", 2, TODAY.minusDays(5)),
                        new CardProgress("C", 2, TODAY.minusDays(3))), TODAY);
        assertEquals("B", deck.next().english());
        deck.grade(Deck.Grade.GOOD);
        assertEquals("C", deck.next().english());
        deck.grade(Deck.Grade.GOOD);
        assertEquals("A", deck.next().english());
    }

    @Test
    void backRestoresTheCardItsProgressAndTheCounts() {
        var deck = new Deck(List.of(s("A"), s("B")),
                map(new CardProgress("A", 3, TODAY), new CardProgress("B", 3, TODAY)), TODAY);
        var first = deck.next();
        deck.grade(Deck.Grade.GOOD);
        var second = deck.next();
        assertEquals(1, deck.done());
        assertEquals(true, deck.canBack());
        assertEquals(true, deck.back());
        assertEquals(first, deck.next());
        assertEquals(new CardProgress(first.english(), 3, TODAY), deck.progressOf(first.english()));
        assertEquals(0, deck.done());
        assertEquals(0, deck.good());
        deck.grade(Deck.Grade.GOOD);
        assertEquals(second, deck.next());
    }

    @Test
    void backUndoesAnAgainAndKeepsQueueOrder() {
        var deck = new Deck(List.of(s("A"), s("B")),
                map(new CardProgress("A", 3, TODAY), new CardProgress("B", 3, TODAY)), TODAY);
        var first = deck.next();
        deck.grade(Deck.Grade.AGAIN);
        var second = deck.next();
        assertEquals(true, deck.back());
        assertEquals(first, deck.next());
        assertEquals(3, deck.progressOf(first.english()).box());
        deck.grade(Deck.Grade.GOOD);
        assertEquals(second, deck.next());
        deck.grade(Deck.Grade.GOOD);
        assertNull(deck.next());
        assertEquals(2, deck.done());
        assertEquals(2, deck.good());
    }

    @Test
    void backGoesSeveralStepsAndIsANoOpAtTheStart() {
        var deck = new Deck(List.of(s("A"), s("B")),
                map(new CardProgress("A", 1, TODAY), new CardProgress("B", 1, TODAY)), TODAY);
        assertEquals(false, deck.canBack());
        assertEquals(false, deck.back());
        var first = deck.next();
        deck.grade(Deck.Grade.GOOD);
        deck.grade(Deck.Grade.EASY);
        assertNull(deck.next());
        assertEquals(true, deck.back());
        assertEquals(true, deck.back());
        assertEquals(false, deck.back());
        assertEquals(first, deck.next());
        assertEquals(0, deck.done());
        assertEquals(1, deck.progressOf("A").box());
        assertEquals(1, deck.progressOf("B").box());
    }

    @Test
    void skipLeavesTheProgressUnchangedAndMovesToTheNextCard() {
        var deck = new Deck(List.of(s("A"), s("B")),
                map(new CardProgress("A", 3, TODAY), new CardProgress("B", 3, TODAY)), TODAY);
        var first = deck.next();
        deck.skip();
        assertEquals(new CardProgress(first.english(), 3, TODAY), deck.progressOf(first.english()));
        assertEquals(false, first.equals(deck.next()));
        assertEquals(0, deck.done());
        assertEquals(0, deck.good());
        assertEquals(1, deck.position());
        assertEquals(true, deck.canBack());
        deck.skip();
        assertNull(deck.next());
        assertEquals(2, deck.position());
        deck.skip();
        assertEquals(2, deck.position());
    }

    @Test
    void backAfterASkipReturnsTheCardToTheFrontAndRestoresThePosition() {
        var deck = new Deck(List.of(s("A"), s("B")),
                map(new CardProgress("A", 3, TODAY), new CardProgress("B", 3, TODAY)), TODAY);
        var first = deck.next();
        deck.skip();
        var second = deck.next();
        assertEquals(true, deck.back());
        assertEquals(first, deck.next());
        assertEquals(0, deck.position());
        assertEquals(new CardProgress(first.english(), 3, TODAY), deck.progressOf(first.english()));
        assertEquals(false, deck.canBack());
        deck.grade(Deck.Grade.GOOD);
        assertEquals(second, deck.next());
    }

    @Test
    void positionCountsGradedAndSkippedCards() {
        var deck = new Deck(List.of(s("A"), s("B"), s("C")),
                map(new CardProgress("A", 1, TODAY.minusDays(3)), new CardProgress("B", 1, TODAY.minusDays(2)),
                        new CardProgress("C", 1, TODAY.minusDays(1))), TODAY);
        assertEquals(0, deck.position());
        deck.grade(Deck.Grade.GOOD);
        assertEquals(1, deck.position());
        deck.skip();
        assertEquals(2, deck.position());
        assertEquals(1, deck.done());
        assertEquals(1, deck.skipped());
        deck.back();
        assertEquals(1, deck.position());
        assertEquals(0, deck.skipped());
        deck.back();
        assertEquals(0, deck.position());
    }

    @Test
    void aSkippedCardIsNotOfferedAgainByExtend() {
        var deck = new Deck(List.of(s("A")), map(new CardProgress("A", 1, TODAY)), TODAY);
        deck.skip();
        assertEquals(0, deck.extend(5));
        assertEquals(1, deck.dueCount());
    }

    @Test
    void discardedCardsAreNotInTheHistory() {
        var deck = new Deck(List.of(s("A"), s("B")),
                map(new CardProgress("A", 1, TODAY), new CardProgress("B", 1, TODAY)), TODAY);
        deck.discard();
        assertEquals(false, deck.canBack());
        deck.grade(Deck.Grade.GOOD);
        assertEquals(true, deck.back());
        assertEquals(false, deck.back());
    }
}
