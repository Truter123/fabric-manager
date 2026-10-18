package dev.kamil.fabric.english;

import static dev.kamil.fabric.english.FxSupport.await;
import static dev.kamil.fabric.english.FxSupport.button;
import static dev.kamil.fabric.english.FxSupport.fx;
import static dev.kamil.fabric.english.FxSupport.pulse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import javafx.event.Event;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.text.Text;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Drives the Review mode of the English window with a fake recorder and temporary files; skipped without a display. */
class ReviewPaneE2ETest {

    private static final Sentence A = new Sentence("2026-10-01", "I am late", "Spozniam sie", "ai am leit", "**late** show");
    private static final Sentence B = new Sentence("2026-10-02", "Good morning", "Dzien dobry", "gud morning", "");
    private static final Sentence C = new Sentence("2026-10-03", "See you soon", "Do zobaczenia", "si ju sun", "Friends ep 1");
    private static final List<Sentence> ALL = List.of(A, B, C);

    @TempDir
    Path dir;

    private Path sentencesFile;
    private Path progress;
    private RecordingHistory history;

    @BeforeAll
    static void startFx() {
        FxSupport.startFx();
    }

    @BeforeEach
    void prepare() throws Exception {
        progress = dir.resolve("my-list.progress.csv");
        sentencesFile = dir.resolve("my-list.md");
        EnglishWindow.dir = dir;
        SentenceFile.save(sentencesFile, ALL);
        var today = java.time.LocalDate.now();
        CardProgress.save(progress, List.of(new CardProgress(A.english(), 1, today),
                new CardProgress(B.english(), 1, today), new CardProgress(C.english(), 1, today)));
        RecordingHistory.standardRoot = dir.resolve("recordings");
        history = new RecordingHistory(dir.resolve("recordings"));
        Recorder.fake = true;
        Recorder.LOG.clear();
        FxSupport.takeErr();
    }

    @AfterEach
    void close() throws Exception {
        fx(() -> {
            var w = EnglishWindow.openStage();
            if (w != null) {
                w.hide();
            }
        });
        var err = FxSupport.takeErr();
        assertFalse(err.contains("Exception"), "exception on stderr: " + err);
    }

    /** Opens the English window on {@code sentences} and presses 2 for Review. */
    private void open(List<Sentence> sentences) throws Exception {
        SentenceFile.save(sentencesFile, sentences);
        fx(() -> EnglishWindow.open(null));
        pulse();
        key(KeyCode.DIGIT2);
    }

    private static Stage stage() {
        return EnglishWindow.openStage();
    }

    private void key(KeyCode code) throws Exception {
        fx(() -> Event.fireEvent(stage().getScene().getRoot(),
                new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, false, false, false, false)));
        pulse();
    }

    private void click(String text) throws Exception {
        fx(() -> button(stage().getScene().getRoot(), text).fire());
        pulse();
    }

    /** Non-blank texts of the card area, in scene order. */
    private List<String> texts() throws Exception {
        return fx(() -> {
            var out = new ArrayList<String>();
            collect(stage().getScene().getRoot(), out);
            return out;
        });
    }

    private static void collect(Node node, List<String> out) {
        if (node.getClass() == Text.class) {
            if (!((Text) node).getText().isBlank()) {
                out.add(((Text) node).getText());
            }
        } else if (node instanceof javafx.scene.Parent p) {
            for (Node child : p.getChildrenUnmodifiable()) {
                collect(child, out);
            }
        }
    }

    /** The header title without its "Review · " prefix: {@code card 1 of 3 · due today}, or "" on an end screen. */
    private String counter() throws Exception {
        return fx(() -> {
            for (Node n : stage().getScene().getRoot().lookupAll(".history-meta")) {
                if (n instanceof Label l && l.getText().startsWith("Review")) {
                    return l.getText().replaceFirst("^Review( · )?", "");
                }
            }
            return null;
        });
    }

    private boolean primary(String button) throws Exception {
        return fx(() -> button(stage().getScene().getRoot(), button).getStyleClass().contains("primary"));
    }

    private CardProgress row(Sentence card) throws Exception {
        return CardProgress.load(progress).stream().filter(p -> p.english().equals(card.english())).findFirst().get();
    }

    private Sentence current() throws Exception {
        var front = texts().get(0);
        return ALL.stream().filter(s -> s.pl().equals(front)).findFirst().orElseThrow();
    }

    private boolean disabled(String button) throws Exception {
        return fx(() -> button(stage().getScene().getRoot(), button).isDisabled());
    }

    private List<String> buttons() throws Exception {
        return fx(() -> FxSupport.buttonTexts(stage().getScene().getRoot()));
    }

    // ---- tests

    @Test
    void opensOnTheFrontOfTheFirstCard() throws Exception {
        open(ALL);
        assertEquals("English", fx(() -> stage().getTitle()));
        assertEquals("card 1 of 3 · due today", counter());
        var card = current();
        assertEquals(List.of(card.pl()), texts().subList(0, 1));
        assertTrue(buttons().contains("Record (R)"));
        assertTrue(buttons().contains("Show answer (Space)"));
        assertFalse(texts().contains(card.english()));
    }

    @Test
    void spaceRevealsTheBackWithoutSpeaking() throws Exception {
        open(ALL);
        var card = current();
        key(KeyCode.SPACE);
        var shown = texts();
        assertEquals(card.english(), shown.get(0));
        assertTrue(shown.contains(card.say()));
        if (!card.watchPlain().isBlank()) {
            assertTrue(shown.contains(card.watchPlain()));
        }
        assertTrue(buttons().containsAll(List.of("Me (P)", "Try again (R)", "First (F)", "Next (→)")));
        assertTrue(disabled("Me (P)"));
        assertTrue(disabled("First (F)"));
        assertFalse(Recorder.LOG.contains("speak:" + card.english()), Recorder.LOG.toString());
        // Space on the back answers: the next card shows
        key(KeyCode.SPACE);
        assertEquals("card 2 of 3 · due today", counter());
    }

    @Test
    void lPlaysTheVoiceOnTheBackOnly() throws Exception {
        var savedCache = Voice.cacheDir;
        Voice.cacheDir = dir.resolve("tts");
        Voice.fake = true;
        Voice.LOG.clear();
        try {
            open(ALL);
            var card = current();
            key(KeyCode.L);
            assertEquals(List.of(), List.copyOf(Voice.LOG), "no voice on the front");
            key(KeyCode.SPACE);
            assertTrue(buttons().contains("Listen (L)"));
            Recorder.LOG.clear();
            key(KeyCode.L);
            var played = "play:" + Voice.key(card.english(), false) + ".wav";
            FxSupport.await(() -> Recorder.LOG.contains(played));
            assertEquals(List.of("voice:normal:" + card.english()), List.copyOf(Voice.LOG));
        } finally {
            Voice.fake = false;
            Voice.cacheDir = savedCache;
        }
    }

    @Test
    void thereIsNoModelVoice() throws Exception {
        open(ALL);
        key(KeyCode.SPACE);
        Recorder.LOG.clear();
        key(KeyCode.M);
        assertFalse(buttons().contains("Model (M)"));
        assertEquals(List.of(), List.copyOf(Recorder.LOG));
    }

    @Test
    void gradingAllCardsSavesProgressAndShowsTheEnd() throws Exception {
        open(ALL);
        for (int i = 1; i <= 3; i++) {
            assertEquals("card " + i + " of 3 · due today", counter());
            key(KeyCode.SPACE);
            click("Next (→)");
        }
        assertEquals("", counter());
        assertEquals(List.of("Done: 3 cards. Next review: tomorrow (3)."), texts());
        assertTrue(buttons().contains("10 more (Space)"));
        assertTrue(buttons().contains("Home (Esc)"));
        var saved = CardProgress.load(progress);
        assertEquals(3, saved.size());
        for (var p : saved) {
            assertEquals(2, p.box());
            assertEquals(LocalDate.now().plusDays(1), p.due());
        }
        // nothing else is due: the extra cards request says so
        click("10 more (Space)");
        assertEquals(List.of("Nothing more due today."), texts());
    }

    @Test
    void savedProgressKeepsFutureCardsOutOfTheSession() throws Exception {
        CardProgress.save(progress, List.of(new CardProgress("I am late", 1, LocalDate.now()),
                new CardProgress("Good morning", 3, LocalDate.now().plusDays(3)),
                new CardProgress("See you soon", 1, LocalDate.now())));
        open(ALL);
        assertEquals("card 1 of 2 · due today", counter());
        key(KeyCode.SPACE);
        key(KeyCode.SPACE);
        key(KeyCode.SPACE);
        key(KeyCode.SPACE);
        assertEquals(List.of("Done: 2 cards. Next review: tomorrow (2)."), texts());
    }

    @Test
    void emptyDeckSaysNothingDueAndSpaceTriesMore() throws Exception {
        // a list without a card (no Polish front): Review is enabled, its deck is empty
        open(List.of(new Sentence("2026-10-01", "No front", "", "", "")));
        assertEquals(List.of("Nothing due today."), texts());
        assertFalse(buttons().contains("10 more (Space)"));
        key(KeyCode.SPACE);
        assertEquals(List.of("Nothing more due today."), texts());
    }

    @Test
    void recordingOnTheFrontRevealsAndKeepsTheAttempt() throws Exception {
        open(ALL);
        var card = current();
        key(KeyCode.R);
        assertTrue(buttons().contains("● Recording… (R to stop)"));
        Recorder.LOG.clear();
        key(KeyCode.R);
        assertEquals(card.english(), texts().get(0));
        assertEquals(1, history.list(card.english()).size());
        // the recording just made plays back once, to compare with the answer
        var made = history.list(card.english()).get(0).file().getFileName().toString();
        assertEquals(List.of("play:" + made), List.copyOf(Recorder.LOG));
        assertFalse(disabled("Me (P)"));
        assertTrue(disabled("First (F)"));
        // one attempt row with its own play button
        assertTrue(buttons().contains("▶"));
        Recorder.LOG.clear();
        click("Me (P)");
        click("▶");
        var wav = history.list(card.english()).get(0).file().getFileName().toString();
        assertEquals(List.of("play:" + wav, "play:" + wav), List.copyOf(Recorder.LOG));
    }

    @Test
    void tryAgainOnTheBackAddsAnAttemptAndPlaysIt() throws Exception {
        open(ALL);
        var card = current();
        key(KeyCode.SPACE);
        click("Try again (R)");
        assertTrue(buttons().contains("● Recording… (R to stop)"));
        Recorder.LOG.clear();
        key(KeyCode.R);
        assertTrue(buttons().contains("Try again (R)"));
        var first = history.list(card.english());
        assertEquals(1, first.size());
        assertEquals(List.of("play:" + first.get(0).file().getFileName()), List.copyOf(Recorder.LOG));

        Thread.sleep(1100); // attempt files are named to the second
        key(KeyCode.R);
        key(KeyCode.R);
        var all = history.list(card.english());
        assertEquals(2, all.size());
        assertFalse(disabled("First (F)"));
        assertEquals(2, fx(() -> stage().getScene().getRoot().lookupAll(".button").stream()
                .filter(n -> n instanceof Button b && "▶".equals(b.getText())).count()).intValue());
        Recorder.LOG.clear();
        key(KeyCode.F);
        key(KeyCode.P);
        assertEquals(List.of("play:" + all.get(1).file().getFileName(), "play:" + all.get(0).file().getFileName()),
                List.copyOf(Recorder.LOG));
    }

    @Test
    void answeringWhileRecordingStopsAndSavesTheAttempt() throws Exception {
        open(ALL);
        var card = current();
        key(KeyCode.SPACE);
        click("Try again (R)");
        click("Next (→)");
        assertEquals("card 2 of 3 · due today", counter());
        assertEquals(1, history.list(card.english()).size());
        assertTrue(buttons().contains("Record (R)"));
    }

    @Test
    void escapeSavesARunningRecordingAndGoesHome() throws Exception {
        open(ALL);
        var card = current();
        key(KeyCode.R);
        key(KeyCode.ESCAPE);
        assertEquals(1, history.list(card.english()).size());
        assertTrue(buttons().contains("Review (3 due)"));
        assertTrue(fx(() -> stage().isShowing()));
    }

    @Test
    void homeButtonOnTheEndScreenGoesHome() throws Exception {
        open(List.of(B));
        key(KeyCode.SPACE);
        key(KeyCode.SPACE);
        click("Home (Esc)");
        assertTrue(buttons().contains("Review (0 due)"));
    }

    @Test
    void reviewStartsFreshEachTimeFromHome() throws Exception {
        open(ALL);
        key(KeyCode.SPACE);
        key(KeyCode.ESCAPE);
        key(KeyCode.DIGIT2);
        assertEquals("card 1 of 3 · due today", counter());
    }

    @Test
    void dailySizeLimitsTheSession() throws Exception {
        dev.kamil.fabric.settings.AppSettings.shared().setInt(EnglishWindow.DAILY_SIZE_KEY, 5);
        try {
            var many = new ArrayList<Sentence>();
            var rows = new ArrayList<CardProgress>();
            for (int i = 0; i < 7; i++) {
                many.add(new Sentence("2026-10-01", "En" + i, "Pl" + i, "", ""));
                rows.add(new CardProgress("En" + i, 1, LocalDate.now()));
            }
            CardProgress.save(progress, rows);
            open(many);
            assertEquals("card 1 of 5 · due today", counter());
        } finally {
            dev.kamil.fabric.settings.AppSettings.shared().setInt(EnglishWindow.DAILY_SIZE_KEY, 10);
        }
    }

    @Test
    void sentencesWithoutPolishAreSkipped() throws Exception {
        open(List.of(new Sentence("2026-10-01", "No front", "", "", ""), B));
        assertEquals("card 1 of 1 · due today", counter());
        assertEquals(List.of(B.pl()), texts().subList(0, 1));
    }

    private void assertRemovedCard(Sentence gone) throws Exception {
        assertEquals("card 1 of 2 · due today", counter());
        assertFalse(texts().get(0).equals(gone.pl()));
        var kept = new ArrayList<>(ALL);
        kept.remove(gone);
        var saved = SentenceFile.load(sentencesFile);
        assertEquals(2, saved.size());
        assertTrue(saved.containsAll(kept));
        assertEquals(List.of(gone), SentenceFile.load(sentencesFile.resolveSibling("my-list.removed.md")));
        key(KeyCode.ESCAPE);
        assertTrue(buttons().contains("Sentences (2)"), buttons().toString());
        assertTrue(buttons().contains("Review (2 due)"), buttons().toString());
    }

    @Test
    void deleteOnTheFrontRemovesTheCard() throws Exception {
        open(ALL);
        var gone = current();
        key(KeyCode.DELETE);
        assertRemovedCard(gone);
    }

    @Test
    void deleteOnTheBackRemovesTheCardWithoutGrading() throws Exception {
        open(ALL);
        var gone = current();
        key(KeyCode.SPACE);
        key(KeyCode.DELETE);
        assertRemovedCard(gone);
        assertEquals(3, CardProgress.load(progress).size());
    }

    @Test
    void removeButtonWorksOnBothSides() throws Exception {
        open(ALL);
        var first = current();
        click("Remove (Del)");
        assertRemovedCardNoLeave(first);
        var second = current();
        key(KeyCode.SPACE);
        click("Remove (Del)");
        assertEquals("card 1 of 1 · due today", counter());
        assertEquals(1, SentenceFile.load(sentencesFile).size());
        assertEquals(2, SentenceFile.load(sentencesFile.resolveSibling("my-list.removed.md")).size());
        assertTrue(SentenceFile.load(sentencesFile.resolveSibling("my-list.removed.md")).containsAll(List.of(first, second)));
    }

    private void assertRemovedCardNoLeave(Sentence gone) throws Exception {
        assertEquals("card 1 of 2 · due today", counter());
        assertFalse(texts().get(0).equals(gone.pl()));
    }

    @Test
    void backRestoresTheGradedCardOnItsFrontAndItsCsvRow() throws Exception {
        CardProgress.save(progress, List.of(new CardProgress(A.english(), 3, LocalDate.now()),
                new CardProgress(B.english(), 3, LocalDate.now()), new CardProgress(C.english(), 3, LocalDate.now())));
        open(ALL);
        assertTrue(disabled("Back (←)"));
        var card = current();
        key(KeyCode.SPACE);
        key(KeyCode.SPACE);
        assertEquals("card 2 of 3 · box 3", counter());
        var graded = CardProgress.load(progress).stream().filter(p -> p.english().equals(card.english())).findFirst().get();
        assertEquals(4, graded.box());
        assertFalse(disabled("Back (←)"));
        key(KeyCode.LEFT);
        assertEquals("card 1 of 3 · box 3", counter());
        assertEquals(card.pl(), texts().get(0));
        assertTrue(buttons().contains("Record (R)"));
        var restored = CardProgress.load(progress).stream().filter(p -> p.english().equals(card.english())).findFirst().get();
        assertEquals(new CardProgress(card.english(), 3, LocalDate.now()), restored);
        assertTrue(disabled("Back (←)"));
    }

    @Test
    void backFromTheEndScreenShowsTheLastGradedCard() throws Exception {
        open(ALL);
        for (int i = 0; i < 3; i++) {
            key(KeyCode.SPACE);
            key(KeyCode.SPACE);
        }
        assertEquals("", counter());
        click("Back (←)");
        assertEquals("card 3 of 3 · due today", counter());
        assertEquals(1, CardProgress.load(progress).stream().filter(p -> p.box() == 1).count());
    }

    @Test
    void homeButtonOnTheFrontAndTheBackGoesHome() throws Exception {
        open(ALL);
        click("Home (Esc)");
        assertFalse(buttons().contains("Record (R)"));
        key(KeyCode.DIGIT2);
        key(KeyCode.SPACE);
        click("Home (Esc)");
        assertFalse(buttons().contains("Next (→)"));
    }

    @Test
    void theFrameShowsEachButtonOnceOnTheFront() throws Exception {
        open(ALL);
        for (var text : List.of("Show answer (Space)", "Record (R)", "Back (←)", "Next (→)", "Remove (Del)",
                "Home (Esc)")) {
            assertEquals(1, buttons().stream().filter(text::equals).count(), text + " in " + buttons());
        }
        assertTrue(primary("Show answer (Space)"));
        assertFalse(primary("Next (→)"));
    }

    @Test
    void theFrameShowsEachButtonOnceOnTheBackAndNextIsPrimary() throws Exception {
        open(ALL);
        key(KeyCode.SPACE);
        for (var text : List.of("Listen (L)", "Back (←)", "Next (→)", "Remove (Del)", "Home (Esc)")) {
            assertEquals(1, buttons().stream().filter(text::equals).count(), text + " in " + buttons());
        }
        assertFalse(buttons().contains("Show answer (Space)"));
        assertTrue(primary("Next (→)"));
    }

    @Test
    void showAnswerButtonRevealsTheBack() throws Exception {
        open(ALL);
        var card = current();
        click("Show answer (Space)");
        assertEquals(card.english(), texts().get(0));
        assertFalse(buttons().contains("Show answer (Space)"));
        assertTrue(buttons().contains("Me (P)"));
    }

    /** An older recording of the card stays silent on Show answer; Me (P) plays it on request. */
    @Test
    void showAnswerDoesNotPlayAnOlderRecording() throws Exception {
        open(ALL);
        var card = current();
        Files.write(history.newFile(card.english(), java.time.LocalDateTime.now().minusDays(1)), new byte[] {0});
        Recorder.LOG.clear();
        click("Show answer (Space)");
        assertEquals(card.english(), texts().get(0));
        assertFalse(disabled("Me (P)"));
        assertTrue(Recorder.LOG.stream().noneMatch(s -> s.startsWith("play:")), Recorder.LOG.toString());
        click("Me (P)");
        assertTrue(Recorder.LOG.stream().anyMatch(s -> s.startsWith("play:")), Recorder.LOG.toString());
    }

    @Test
    void nextOnTheFrontSkipsTheCardAndLeavesItsCsvRow() throws Exception {
        open(ALL);
        var skipped = current();
        var before = Files.readString(progress);
        click("Next (→)");
        assertEquals("card 2 of 3 · due today", counter());
        assertFalse(current().equals(skipped));
        assertEquals(before, Files.readString(progress));
        assertEquals(new CardProgress(skipped.english(), 1, LocalDate.now()), row(skipped));
    }

    @Test
    void backAfterASkipShowsTheSkippedCardOnItsFront() throws Exception {
        open(ALL);
        var skipped = current();
        click("Next (→)");
        assertFalse(disabled("Back (←)"));
        click("Back (←)");
        assertEquals("card 1 of 3 · due today", counter());
        assertEquals(skipped.pl(), texts().get(0));
        assertTrue(buttons().contains("Record (R)"));
        assertTrue(disabled("Back (←)"));
        assertEquals(new CardProgress(skipped.english(), 1, LocalDate.now()), row(skipped));
    }

    @Test
    void rightArrowSkipsOnTheFrontAndGradesGoodOnTheBack() throws Exception {
        open(ALL);
        var first = current();
        key(KeyCode.RIGHT);
        assertEquals("card 2 of 3 · due today", counter());
        assertEquals(new CardProgress(first.english(), 1, LocalDate.now()), row(first));
        var second = current();
        key(KeyCode.SPACE);
        key(KeyCode.RIGHT);
        assertEquals("card 3 of 3 · due today", counter());
        assertEquals(new CardProgress(second.english(), 2, LocalDate.now().plusDays(1)), row(second));
        assertEquals(new CardProgress(first.english(), 1, LocalDate.now()), row(first));
    }

    @Test
    void enterActsLikeSpace() throws Exception {
        open(ALL);
        var card = current();
        key(KeyCode.ENTER);
        assertEquals(card.english(), texts().get(0));
        key(KeyCode.ENTER);
        assertEquals("card 2 of 3 · due today", counter());
        assertEquals(2, row(card).box());
        for (int i = 0; i < 4; i++) {
            key(KeyCode.ENTER);
        }
        assertEquals(List.of("Done: 3 cards. Next review: tomorrow (3)."), texts());
        // on the end screen Enter asks for more cards, like Space
        key(KeyCode.ENTER);
        assertEquals(List.of("Nothing more due today."), texts());
    }

    @Test
    void theEndScreenCountsSkippedCardsAndMakesMorePrimary() throws Exception {
        open(ALL);
        click("Next (→)");
        for (int i = 0; i < 2; i++) {
            key(KeyCode.SPACE);
            key(KeyCode.SPACE);
        }
        assertEquals(List.of("Done: 2 cards. Skipped 1, still due. Next review: tomorrow (2)."), texts());
        assertTrue(primary("10 more (Space)"));
        assertFalse(primary("Next (→)"));
        assertTrue(disabled("Next (→)"));
        assertTrue(disabled("Remove (Del)"));
        assertFalse(disabled("Back (←)"));
    }

    @Test
    void backStillWorksOnTheNothingMoreDueTodayScreen() throws Exception {
        open(ALL);
        for (int i = 0; i < 3; i++) {
            key(KeyCode.SPACE);
            key(KeyCode.SPACE);
        }
        click("10 more (Space)");
        assertEquals(List.of("Nothing more due today."), texts());
        assertFalse(disabled("Back (←)"));
        assertTrue(disabled("Next (→)"));
        click("Back (←)");
        assertEquals("card 3 of 3 · due today", counter());
        assertEquals(1, CardProgress.load(progress).stream().filter(p -> p.box() == 1).count());
    }
}
