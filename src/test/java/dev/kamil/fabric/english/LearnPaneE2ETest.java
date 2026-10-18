package dev.kamil.fabric.english;

import static dev.kamil.fabric.english.FxSupport.button;
import static dev.kamil.fabric.english.FxSupport.fx;
import static dev.kamil.fabric.english.FxSupport.pulse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import javafx.event.Event;
import javafx.scene.Node;
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

/** Drives the Learn mode of the English window with a fake recorder and temporary files; skipped without a display. */
class LearnPaneE2ETest {

    private static final Sentence A = new Sentence("2026-10-01", "I am late", "Spozniam sie", "ai am leit", "**late** show");
    private static final Sentence B = new Sentence("2026-10-02", "Good morning", "Dzien dobry", "gud morning", "");
    private static final Sentence C = new Sentence("2026-10-03", "See you soon", "Do zobaczenia", "si ju sun", "Friends ep 1");

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

    /** Opens the English window on {@code sentences} and presses 1 for Learn. */
    private void open(Sentence... sentences) throws Exception {
        SentenceFile.save(sentencesFile, List.of(sentences));
        fx(() -> EnglishWindow.open(null));
        pulse();
        key(KeyCode.DIGIT1);
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

    private String counter() throws Exception {
        return fx(() -> {
            for (Node n : stage().getScene().getRoot().lookupAll(".history-meta")) {
                if (n instanceof Label l && l.getText().startsWith("Learn")) {
                    return l.getText().replaceFirst("^Learn( · )?", "");
                }
            }
            return null;
        });
    }

    /** The whole header title, e.g. {@code Learn · card 1 of 2}. */
    private String title() throws Exception {
        return fx(() -> {
            for (Node n : stage().getScene().getRoot().lookupAll(".history-meta")) {
                if (n instanceof Label l && l.getText().startsWith("Learn")) {
                    return l.getText();
                }
            }
            return null;
        });
    }

    private boolean primary(String button) throws Exception {
        return fx(() -> button(stage().getScene().getRoot(), button).getStyleClass().contains("primary"));
    }

    private boolean disabled(String button) throws Exception {
        return fx(() -> button(stage().getScene().getRoot(), button).isDisabled());
    }

    /** How many buttons under the root carry exactly this text. */
    private long countOf(String button) throws Exception {
        return buttons().stream().filter(button::equals).count();
    }

    private List<String> buttons() throws Exception {
        return fx(() -> FxSupport.buttonTexts(stage().getScene().getRoot()));
    }

    // ---- tests

    @Test
    void cardShowsEverythingAtOnce() throws Exception {
        open(A, B);
        assertEquals("card 1 of 2", counter());
        assertEquals(List.of("I am late", "Spozniam sie", "ai am leit", "late show"), texts());
        assertTrue(buttons().containsAll(List.of("Me (P)", "First (F)", "I know it (Space)", "Next (→)")));
        assertTrue(disabled("I know it (Space)"));
    }

    @Test
    void cardHasEachControlOnceAndKnowItIsThePrimaryAction() throws Exception {
        open(A, B);
        for (var text : List.of("I know it (Space)", "Listen (L)", "Back (←)", "Next (→)", "Remove (Del)", "Home (Esc)")) {
            assertEquals(1, countOf(text), text + " in " + buttons());
        }
        assertTrue(primary("I know it (Space)"));
        assertFalse(primary("Next (→)"));
    }

    @Test
    void titleReadsLearnCardNOfN() throws Exception {
        open(A, B);
        assertEquals("Learn · card 1 of 2", title());
        key(KeyCode.RIGHT);
        assertEquals("Learn · card 2 of 2", title());
    }

    @Test
    void rightArrowGoesOnWithoutWriting() throws Exception {
        open(A, B);
        key(KeyCode.RIGHT);
        assertEquals("card 2 of 2", counter());
        assertEquals("Good morning", texts().get(0));
        assertFalse(Files.exists(progress));
    }

    @Test
    void nextButtonGoesOnWithoutWriting() throws Exception {
        open(A, B);
        click("Next (→)");
        assertEquals("card 2 of 2", counter());
        assertEquals("Good morning", texts().get(0));
        assertFalse(Files.exists(progress));
    }

    @Test
    void enterActsLikeSpace() throws Exception {
        open(A, B);
        key(KeyCode.ENTER);
        assertEquals("card 1 of 2", counter());
        assertFalse(Files.exists(progress));
        key(KeyCode.R);
        key(KeyCode.R);
        key(KeyCode.ENTER);
        assertEquals(List.of(new CardProgress(A.english(), 1, LocalDate.now().plusDays(1))), CardProgress.load(progress));
        assertEquals("card 2 of 2", counter());
        assertEquals("Good morning", texts().get(0));
    }

    @Test
    void endScreenDisablesNextAndRemoveAndKeepsBack() throws Exception {
        open(A);
        learnFirst();
        assertEquals("Learn", title());
        assertTrue(disabled("Next (→)"));
        assertTrue(disabled("Remove (Del)"));
        assertFalse(disabled("Back (←)"));
        assertEquals(1, countOf("Home (Esc)"));
    }

    /** Wrapped text must not hold the pane at the window width, or a narrower window clips its left edge. */
    @Test
    void paneCanShrinkBelowTheWidthItWasLaidOutAt() throws Exception {
        var longWatch = "verb first, then the object, and plural articles ".repeat(12);
        open(new Sentence("2026-10-01", "I am late", "Spozniam sie", "ai am leit", longWatch));
        var minWidth = fx(() -> {
            var root = stage().getScene().getRoot();
            root.applyCss();
            root.layout();
            return ((javafx.scene.layout.Region) root.getChildrenUnmodifiable().get(0)).minWidth(-1);
        });
        assertTrue(minWidth < 400, "pane min width " + minWidth);
    }

    @Test
    void spaceIsIgnoredBeforeARecording() throws Exception {
        open(A, B);
        key(KeyCode.SPACE);
        assertEquals("card 1 of 2", counter());
        assertFalse(Files.exists(progress));
    }

    @Test
    void knowItAfterARecordingSavesTomorrowBox1AndShowsTheNextCard() throws Exception {
        open(A, B);
        key(KeyCode.R);
        assertTrue(buttons().contains("● Recording… (R to stop)"));
        Recorder.LOG.clear();
        key(KeyCode.R);
        assertEquals(1, history.list(A.english()).size());
        assertEquals(1, Recorder.LOG.stream().filter(l -> l.startsWith("play:")).count(), Recorder.LOG.toString());
        assertFalse(disabled("I know it (Space)"));
        key(KeyCode.SPACE);
        assertEquals(List.of(new CardProgress(A.english(), 1, LocalDate.now().plusDays(1))), CardProgress.load(progress));
        assertEquals("card 2 of 2", counter());
        assertEquals("Good morning", texts().get(0));
        assertTrue(disabled("I know it (Space)"));
    }

    @Test
    void lPlaysTheVoiceOfTheCurrentSentence() throws Exception {
        var savedCache = Voice.cacheDir;
        Voice.cacheDir = dir.resolve("tts");
        Voice.fake = true;
        Voice.LOG.clear();
        try {
            open(A, B);
            assertTrue(buttons().contains("Listen (L)"));
            Recorder.LOG.clear();
            key(KeyCode.L);
            var played = "play:" + Voice.key(A.english(), false) + ".wav";
            FxSupport.await(() -> Recorder.LOG.contains(played));
            assertEquals(List.of("voice:normal:" + A.english()), List.copyOf(Voice.LOG));
        } finally {
            Voice.fake = false;
            Voice.cacheDir = savedCache;
        }
    }

    @Test
    void skipGoesOnWithoutWriting() throws Exception {
        open(A, B);
        key(KeyCode.N);
        assertEquals("card 2 of 2", counter());
        assertFalse(Files.exists(progress));
        click("Next (→)");
        assertEquals("", counter());
        assertEquals(List.of("Learned 0. Review starts tomorrow."), texts());
        assertFalse(Files.exists(progress));
    }

    @Test
    void endScreenOffersMoreAndHome() throws Exception {
        dev.kamil.fabric.settings.AppSettings.shared().setInt(EnglishWindow.DAILY_SIZE_KEY, 5);
        try {
            var many = new Sentence[6];
            for (int i = 0; i < 6; i++) {
                many[i] = new Sentence("2026-10-01", "En" + i, "Pl" + i, "", "");
            }
            open(many);
            for (int i = 0; i < 5; i++) {
                key(KeyCode.R);
                key(KeyCode.R);
                key(KeyCode.SPACE);
            }
            assertEquals(List.of("Learned 5. Review starts tomorrow."), texts());
            assertTrue(buttons().contains("5 more (Space)"));
            assertTrue(buttons().contains("Home (Esc)"));
            key(KeyCode.SPACE);
            assertEquals("card 1 of 1", counter());
            assertEquals("En5", texts().get(0));
        } finally {
            dev.kamil.fabric.settings.AppSettings.shared().setInt(EnglishWindow.DAILY_SIZE_KEY, 10);
        }
    }

    @Test
    void nothingNewShowsItsOwnMessage() throws Exception {
        CardProgress.save(progress, List.of(new CardProgress(A.english(), 1, LocalDate.now())));
        open(A);
        assertEquals(List.of("Nothing new to learn."), texts());
        assertFalse(buttons().stream().anyMatch(t -> t.endsWith("more (Space)")));
    }

    @Test
    void learnedSentenceLeavesTheHomeCount() throws Exception {
        open(A, B, C);
        key(KeyCode.ESCAPE);
        assertTrue(buttons().contains("Learn (3 new)"));
        key(KeyCode.DIGIT1);
        key(KeyCode.R);
        key(KeyCode.R);
        key(KeyCode.SPACE);
        key(KeyCode.ESCAPE);
        assertTrue(buttons().contains("Learn (2 new)"), buttons().toString());
    }

    @Test
    void escapeSavesARunningRecordingAndGoesHome() throws Exception {
        open(A, B);
        key(KeyCode.R);
        key(KeyCode.ESCAPE);
        assertEquals(1, history.list(A.english()).size());
        assertTrue(buttons().contains("Learn (2 new)"));
    }

    private void assertRemovedFirstCard() throws Exception {
        assertEquals("card 1 of 2", counter());
        assertEquals("Good morning", texts().get(0));
        assertEquals(List.of(B, C), SentenceFile.load(sentencesFile));
        assertEquals(List.of(A), SentenceFile.load(sentencesFile.resolveSibling("my-list.removed.md")));
        assertEquals("English", fx(() -> stage().getTitle()));
        assertTrue(fx(() -> stage().getScene().getRoot().lookupAll(".history-meta").stream()
                .anyMatch(n -> n instanceof Label l && l.isVisible()
                        && l.getText().equals("Removed. Restore it in Sentences → Removed."))));
        key(KeyCode.ESCAPE);
        assertTrue(buttons().contains("Learn (2 new)"), buttons().toString());
        assertTrue(buttons().contains("Sentences (2)"), buttons().toString());
    }

    @Test
    void deleteKeyRemovesTheCurrentCardAndSavesBothFiles() throws Exception {
        open(A, B, C);
        key(KeyCode.DELETE);
        assertRemovedFirstCard();
    }

    @Test
    void removeButtonDoesTheSame() throws Exception {
        open(A, B, C);
        assertTrue(buttons().contains("Remove (Del)"));
        click("Remove (Del)");
        assertRemovedFirstCard();
    }

    @Test
    void removingTheLastCardShowsTheEndScreenWithTheHint() throws Exception {
        open(A);
        key(KeyCode.DELETE);
        assertEquals(List.of("Nothing new to learn."), texts());
        assertEquals(List.of(), SentenceFile.load(sentencesFile));
        assertEquals(List.of(A), SentenceFile.load(sentencesFile.resolveSibling("my-list.removed.md")));
    }

    private void learnFirst() throws Exception {
        key(KeyCode.R);
        key(KeyCode.R);
        key(KeyCode.SPACE);
    }

    @Test
    void backAfterKnowItShowsThePreviousCardAndForgetsItsRow() throws Exception {
        open(A, B);
        assertTrue(disabled("Back (←)"));
        learnFirst();
        assertEquals(List.of(new CardProgress(A.english(), 1, LocalDate.now().plusDays(1))), CardProgress.load(progress));
        assertFalse(disabled("Back (←)"));
        key(KeyCode.LEFT);
        assertEquals("card 1 of 2", counter());
        assertEquals("I am late", texts().get(0));
        assertEquals(List.of(), CardProgress.load(progress));
        assertTrue(disabled("Back (←)"));
        key(KeyCode.LEFT);
        assertEquals("card 1 of 2", counter());
    }

    @Test
    void backAfterSkipShowsTheSkippedCardAgain() throws Exception {
        open(A, B);
        key(KeyCode.N);
        click("Back (←)");
        assertEquals("I am late", texts().get(0));
        assertFalse(Files.exists(progress));
    }

    @Test
    void backFromTheEndScreenShowsTheLastCard() throws Exception {
        open(A);
        learnFirst();
        assertEquals(List.of("Learned 1. Review starts tomorrow."), texts());
        click("Back (←)");
        assertEquals("card 1 of 1", counter());
        assertEquals("I am late", texts().get(0));
        assertEquals(List.of(), CardProgress.load(progress));
        key(KeyCode.R);
        key(KeyCode.R);
        key(KeyCode.SPACE);
        key(KeyCode.LEFT);
        assertEquals("I am late", texts().get(0));
    }

    @Test
    void backSkipsOverARemovedCard() throws Exception {
        open(A, B, C);
        key(KeyCode.N);
        key(KeyCode.DELETE);
        assertEquals("See you soon", texts().get(0));
        key(KeyCode.LEFT);
        assertEquals("I am late", texts().get(0));
    }

    @Test
    void homeButtonOnTheCardGoesHome() throws Exception {
        open(A, B);
        click("Home (Esc)");
        assertTrue(buttons().stream().anyMatch(b -> b.contains("Learn")) || !buttons().contains("Next (→)"));
        assertFalse(buttons().contains("Next (→)"));
    }
}
