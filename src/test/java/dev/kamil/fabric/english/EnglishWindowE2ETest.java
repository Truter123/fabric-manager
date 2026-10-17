package dev.kamil.fabric.english;

import static dev.kamil.fabric.english.FxSupport.await;
import static dev.kamil.fabric.english.FxSupport.awaitClosed;
import static dev.kamil.fabric.english.FxSupport.awaitDialog;
import static dev.kamil.fabric.english.FxSupport.button;
import static dev.kamil.fabric.english.FxSupport.dialogWith;
import static dev.kamil.fabric.english.FxSupport.fx;
import static dev.kamil.fabric.english.FxSupport.later;
import static dev.kamil.fabric.english.FxSupport.press;
import static dev.kamil.fabric.english.FxSupport.pulse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import javafx.event.Event;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.TitledPane;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.stage.WindowEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Drives the real English sentences window against temporary files; skipped without a display. */
class EnglishWindowE2ETest {

    private static final Sentence A = new Sentence("2026-10-01", "I am late", "Spozniam sie", "ai am leit", "**late** show");
    private static final Sentence B = new Sentence("2026-10-02", "Good morning", "Dzien dobry", "gud morning", "");
    private static final Sentence C = new Sentence("2026-10-03", "See you soon", "Do zobaczenia", "si ju sun", "Friends ep 1");

    @TempDir
    Path dir;

    private Path sentences;
    private Path removedFile;
    private Path savedCache;

    @BeforeAll
    static void startFx() {
        FxSupport.startFx();
    }

    @BeforeEach
    void prepare() throws Exception {
        sentences = dir.resolve("english/everyday.md");
        removedFile = dir.resolve("english/removed.md");
        EnglishWindow.file = sentences;
        ProgressStore.file = dir.resolve("english/flashcards.csv");
        RecordingHistory.standardRoot = dir.resolve("english/recordings");
        savedCache = Voice.cacheDir;
        Voice.cacheDir = dir.resolve("english/tts");
        Voice.fake = true;
        Voice.LOG.clear();
        Recorder.fake = true;
        Recorder.LOG.clear();
        SentenceFile.save(sentences, List.of(A, B, C));
        var today = java.time.LocalDate.now();
        CardProgress.save(ProgressStore.file, List.of(new CardProgress(A.english(), 1, today),
                new CardProgress(B.english(), 1, today), new CardProgress(C.english(), 1, today)));
        FxSupport.takeErr();
        openWindow();
    }

    @AfterEach
    void close() throws Exception {
        fx(() -> {
            var w = EnglishWindow.openStage();
            if (w != null) {
                w.hide();
            }
        });
        Voice.fake = false;
        Voice.cacheDir = savedCache;
        var err = FxSupport.takeErr();
        assertFalse(err.contains("Exception"), "exception on stderr: " + err);
    }

    /** Opens the window on Home and presses 3 for the Sentences cards. */
    private void openWindow() throws Exception {
        fx(() -> EnglishWindow.open(null));
        pulse();
        homeKey(KeyCode.DIGIT3);
    }

    private void homeKey(KeyCode code) throws Exception {
        fx(() -> key(root(), code, false));
        pulse();
        pulse();
    }

    private List<String> buttons() throws Exception {
        return fx(() -> FxSupport.buttonTexts(root()));
    }

    // ---- helpers (call fx-thread ones inside fx)

    private static Stage stage() {
        return EnglishWindow.openStage();
    }

    /** The scene root after a CSS and layout pass, so the ScrollPane skin holds the cards. */
    private static javafx.scene.Parent root() {
        var root = stage().getScene().getRoot();
        root.applyCss();
        root.layout();
        return root;
    }

    private static TextField search() {
        return (TextField) root().lookup(".text-field");
    }

    private static String statusText() {
        var labels = new ArrayList<Label>();
        for (Node n : root().lookupAll(".history-meta")) {
            if (n instanceof Label l) {
                labels.add(l);
            }
        }
        return labels.get(labels.size() - 1).getText();
    }

    private static String countText() {
        for (Node n : root().lookupAll(".history-meta")) {
            if (n instanceof Label l && l.getText().matches("\\d+ of \\d+")) {
                return l.getText();
            }
        }
        return null;
    }

    private String status() throws Exception {
        return fx(EnglishWindowE2ETest::statusText);
    }

    private String count() throws Exception {
        return fx(EnglishWindowE2ETest::countText);
    }

    private String title() throws Exception {
        return fx(() -> stage().getTitle());
    }

    private static List<Node> cardNodes() {
        return new ArrayList<>(root().lookupAll(".sentence-card"));
    }

    private static String englishOf(Node card) {
        return ((Label) card.lookup(".sentence-english")).getText();
    }

    /** The English line of every sentence card, top to bottom. */
    private List<String> english() throws Exception {
        return fx(() -> cardNodes().stream().map(EnglishWindowE2ETest::englishOf).toList());
    }

    private static Node card(String english) {
        return cardNodes().stream().filter(c -> englishOf(c).equals(english)).findFirst().orElseThrow();
    }

    private static List<String> labelsOf(Node parent) {
        return parent.lookupAll(".label").stream().filter(n -> n instanceof Label).map(n -> ((Label) n).getText())
                .toList();
    }

    private static Button cardButton(String english, String text) {
        return card(english).lookupAll(".button").stream().map(n -> (Button) n).filter(b -> text.equals(b.getText()))
                .findFirst().orElseThrow();
    }

    /** Clicks a card button that opens no modal dialog. */
    private void clickCard(String english, String text) throws Exception {
        fx(() -> cardButton(english, text).fire());
        pulse();
    }

    private void clickCardOpening(String english, String text, String dialog) throws Exception {
        later(() -> cardButton(english, text).fire());
        awaitDialog(dialog);
    }

    /** Clicks a toolbar button that opens no modal dialog. */
    private void click(String text) throws Exception {
        fx(() -> button(root(), text).fire());
        pulse();
    }

    /** Clicks a toolbar button that opens a modal dialog, and returns once it is up. */
    private void clickOpening(String text, String dialog) throws Exception {
        later(() -> button(root(), text).fire());
        awaitDialog(dialog);
    }

    private List<String> removedRows() throws Exception {
        return fx(() -> root().lookupAll(".sentence-removed").stream()
                .map(n -> labelsOf(n).get(0)).toList());
    }

    private void key(Node target, KeyCode code, boolean ctrl) {
        Event.fireEvent(target, new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, false, ctrl, false, false));
    }

    private void fillDialog(String english, String pl, String say, String watch) throws Exception {
        fx(() -> {
            var pane = dialogWith("Add sentence") != null ? dialogWith("Add sentence") : dialogWith("Edit sentence");
            var grid = (GridPane) ((javafx.scene.layout.VBox) pane.getContent()).getChildren().get(0);
            var fields = grid.getChildren().stream().filter(n -> n instanceof TextField).map(n -> (TextField) n).toList();
            fields.get(0).setText(english);
            fields.get(1).setText(pl);
            fields.get(2).setText(say);
            grid.getChildren().stream().filter(n -> n instanceof TextArea).map(n -> (TextArea) n).findFirst()
                    .get().setText(watch);
        });
    }

    private DatePicker dialogDate() throws Exception {
        return fx(() -> {
            var pane = dialogWith("Add sentence") != null ? dialogWith("Add sentence") : dialogWith("Edit sentence");
            var grid = (GridPane) ((javafx.scene.layout.VBox) pane.getContent()).getChildren().get(0);
            return (DatePicker) grid.getChildren().stream().filter(n -> n instanceof DatePicker).findFirst().get();
        });
    }

    /** Removes a sentence by its card button. */
    private void remove(String english) throws Exception {
        clickCard(english, "Remove");
    }

    private TitledPane removedPane() {
        return (TitledPane) root().lookup(".sentence-removed-pane");
    }

    // ---- tests

    @Test
    void loadsSentencesAsCardsNewestFirst() throws Exception {
        assertEquals(List.of("See you soon", "Good morning", "I am late"), english());
        assertEquals("3 of 3", count());
        assertEquals("Loaded 3 sentences", status());
        assertEquals("English", title());
        var first = fx(() -> labelsOf(card("See you soon")));
        assertTrue(first.containsAll(List.of("See you soon", "si ju sun", "Do zobaczenia", "Added 3 Oct 2026",
                "No recordings yet.")), first.toString());
        assertEquals(List.of("Listen", "Slow", "Record", "Compare", "Edit", "Remove"),
                fx(() -> card("See you soon").lookupAll(".button").stream().map(n -> ((Button) n).getText()).toList()));
    }

    @Test
    void sameDateKeepsTheLaterFileLinesFirst() throws Exception {
        var x = new Sentence("2026-10-03", "First line", "p", "s", "");
        var y = new Sentence("2026-10-03", "Second line", "p", "s", "");
        SentenceFile.save(sentences, List.of(x, y, A));
        fx(() -> key(root(), KeyCode.F5, false));
        pulse();
        assertEquals(List.of("Second line", "First line", "I am late"), english());
    }

    @Test
    void unparseableDateHidesTheAddedLine() throws Exception {
        SentenceFile.save(sentences, List.of(new Sentence("someday", "No date", "p", "s", "")));
        fx(() -> key(root(), KeyCode.F5, false));
        pulse();
        assertTrue(fx(() -> labelsOf(card("No date")).stream().noneMatch(t -> t.startsWith("Added"))));
    }

    @Test
    void searchFiltersCardsAndEscapeClears() throws Exception {
        fx(() -> search().setText("zobacz"));
        assertEquals(List.of("See you soon"), english());
        assertEquals("1 of 3", count());
        fx(() -> search().setText("friends"));
        assertEquals(List.of("See you soon"), english());
        fx(() -> search().setText("morning"));
        assertEquals(List.of("Good morning"), english());
        fx(() -> search().setText("nothing matches"));
        assertEquals(List.of(), english());
        assertEquals("0 of 3", count());
        fx(() -> key(search(), KeyCode.ESCAPE, false));
        assertEquals("", fx(() -> search().getText()));
        assertEquals(3, english().size());
        assertEquals("3 of 3", count());
    }

    @Test
    void addSentenceThenSave() throws Exception {
        clickOpening("Add", "Add sentence");
        assertEquals(LocalDate.now(), dialogDate().getValue());
        fillDialog("  New one ", "Nowy", "`niu wan`", "line one\nline two");
        press("Add sentence", "Save");
        awaitClosed("Add sentence");
        pulse();
        assertEquals(List.of("New one", "See you soon", "Good morning", "I am late"), english());
        assertEquals("Added — 1 unsaved change", status());
        assertEquals("English *", title());
        assertEquals("4 of 4", count());

        click("Save");
        assertEquals("Saved 4 sentences", status());
        assertEquals("English", title());
        var onDisk = SentenceFile.load(sentences);
        assertEquals(4, onDisk.size());
        assertEquals("New one", onDisk.get(3).english());
        assertEquals("niu wan", onDisk.get(3).say());
        assertEquals("line one line two", onDisk.get(3).watch());
        assertTrue(Files.exists(sentences.resolveSibling("everyday.md.bak")));
    }

    @Test
    void addCancelledChangesNothing() throws Exception {
        clickOpening("Add", "Add sentence");
        fillDialog("Should not appear", "x", "", "");
        press("Add sentence", "Cancel");
        awaitClosed("Add sentence");
        pulse();
        assertEquals(3, english().size());
        assertEquals("English", title());
    }

    @Test
    void editViaButtonUpdatesTheCardAndMovesItByDate() throws Exception {
        clickCardOpening("Good morning", "Edit", "Edit sentence");
        assertEquals(LocalDate.parse("2026-10-02"), dialogDate().getValue());
        fx(() -> {
            var pane = dialogWith("Edit sentence");
            var grid = (GridPane) ((javafx.scene.layout.VBox) pane.getContent()).getChildren().get(0);
            var fields = grid.getChildren().stream().filter(n -> n instanceof TextField).map(n -> (TextField) n).toList();
            assertEquals("Good morning", fields.get(0).getText());
            assertEquals("Dzien dobry", fields.get(1).getText());
        });
        fillDialog("Good evening", "Dobry wieczor", "gud ivning", "");
        var picker = dialogDate();
        fx(() -> picker.setValue(LocalDate.parse("2026-11-05")));
        press("Edit sentence", "Save");
        awaitClosed("Edit sentence");
        pulse();
        assertEquals(List.of("Good evening", "See you soon", "I am late"), english());
        assertEquals("Edited — 1 unsaved change", status());
        var labels = fx(() -> labelsOf(card("Good evening")));
        assertTrue(labels.containsAll(List.of("gud ivning", "Dobry wieczor", "Added 5 Nov 2026")), labels.toString());
    }

    @Test
    void doubleClickOnTheCardTextOpensTheEditor() throws Exception {
        later(() -> card("I am late").lookup(".sentence-text").fireEvent(new MouseEvent(MouseEvent.MOUSE_CLICKED, 5, 5,
                5, 5, MouseButton.PRIMARY, 2, false, false, false, false, true, false, false, true, false, false,
                null)));
        awaitDialog("Edit sentence");
        press("Edit sentence", "Cancel");
        awaitClosed("Edit sentence");
        assertEquals(3, english().size());
    }

    @Test
    void removeShowsAUndoRowAndCountsAsAChange() throws Exception {
        remove("Good morning");
        assertEquals(List.of("See you soon", "I am late"), english());
        assertEquals(List.of("Good morning — Removed"), removedRows());
        assertEquals("Removed — 1 unsaved change", status());
        assertEquals("English *", title());
        assertEquals("2 of 2", count());

        fx(() -> button(root(), "Undo").fire());
        pulse();
        assertEquals(List.of("See you soon", "Good morning", "I am late"), english());
        assertEquals(List.of(), removedRows());
        assertEquals("Restored — 2 unsaved changes", status());
    }

    @Test
    void removeThenSaveMovesTheSentenceToRemovedFile() throws Exception {
        remove("Good morning");
        click("Save");
        assertEquals("Saved 2 sentences", status());
        assertEquals(List.of(A, C), SentenceFile.load(sentences));
        assertEquals(List.of(B), SentenceFile.load(removedFile));
        assertEquals(List.of(), removedRows());
        assertEquals("Removed (1)", fx(() -> removedPane().getText()));
        assertTrue(fx(() -> removedPane().isManaged()));
        // removed sentences do not count on Home
        homeKey(KeyCode.ESCAPE);
        assertTrue(buttons().contains("Sentences (2)"));
        homeKey(KeyCode.DIGIT3);

        // Restore brings it back, Save writes it to everyday.md again
        fx(() -> {
            removedPane().setExpanded(true);
            ((Parent) removedPane().getContent()).lookupAll(".button").stream().map(n -> (Button) n)
                    .filter(b -> "Restore".equals(b.getText())).findFirst().orElseThrow().fire();
        });
        pulse();
        assertEquals(List.of("See you soon", "Good morning", "I am late"), english());
        assertEquals("Restored — 1 unsaved change", status());
        assertFalse(fx(() -> removedPane().isManaged()));
        click("Save");
        assertEquals(3, SentenceFile.load(sentences).size());
        assertEquals(List.of(), SentenceFile.load(removedFile));
    }

    @Test
    void loadReadsRemovedFile() throws Exception {
        assertNull(fx(() -> removedPane().isManaged() ? "shown" : null));
        SentenceFile.save(sentences, List.of(A, C));
        SentenceFile.save(removedFile, List.of(B));
        fx(() -> key(root(), KeyCode.F5, false));
        pulse();
        assertEquals(List.of("See you soon", "I am late"), english());
        assertEquals("Removed (1)", fx(() -> removedPane().getText()));
        assertEquals("2 of 2", count());
    }

    @Test
    void compareWithoutATakeAsksToRecordOne() throws Exception {
        clickCard("I am late", "Compare");
        assertTrue(fx(() -> labelsOf(card("I am late")).contains("Record a take first.")));
        assertEquals(List.of(), List.copyOf(Recorder.LOG));
    }

    @Test
    void listenAndSlowMakeTheVoiceAndPlayIt() throws Exception {
        clickCard("See you soon", "Listen");
        var normal = "play:" + Voice.key("See you soon", false) + ".wav";
        await(() -> Recorder.LOG.contains(normal));
        clickCard("See you soon", "Slow");
        var slow = "play:" + Voice.key("See you soon", true) + ".wav";
        await(() -> Recorder.LOG.contains(slow));
        assertEquals(List.of("voice:normal:See you soon", "voice:slow:See you soon"), List.copyOf(Voice.LOG));
        // the second Listen reuses the cached WAV
        clickCard("See you soon", "Listen");
        await(() -> Recorder.LOG.size() == 3);
        assertEquals(2, Voice.LOG.size());
    }

    @Test
    void recordingAddsATakeAndCompareStopsAtTheNewestTake() throws Exception {
        clickCard("Good morning", "Record");
        assertTrue(fx(() -> cardButton("Good morning", "Stop") != null));
        clickCard("Good morning", "Stop");
        var history = new RecordingHistory(dir.resolve("english/recordings"));
        var takes = history.list("Good morning");
        assertEquals(1, takes.size());
        assertTrue(fx(() -> cardButton("Good morning", "Record") != null));
        var labels = fx(() -> labelsOf(card("Good morning")));
        assertTrue(labels.contains(RecordingHistory.label(takes.get(0).at())), labels.toString());
        assertFalse(labels.contains("No recordings yet."));

        Recorder.LOG.clear();
        clickCard("Good morning", "Compare");
        var voice = "play:" + Voice.key("Good morning", false) + ".wav";
        var take = "play:" + takes.get(0).file().getFileName();
        await(() -> Recorder.LOG.size() == 2);
        assertEquals(List.of(voice, take), List.copyOf(Recorder.LOG));
    }

    @Test
    void deletingATakeAsksForConfirmation() throws Exception {
        var history = new RecordingHistory(dir.resolve("english/recordings"));
        var wav = history.newFile("Good morning", java.time.LocalDateTime.now());
        Files.write(wav, new byte[] {1});
        fx(() -> key(root(), KeyCode.F5, false));
        pulse();
        clickCardOpening("Good morning", "Delete", "Delete this recording?");
        press("Delete this recording?", "Cancel");
        awaitClosed("Delete this recording?");
        pulse();
        assertEquals(1, history.count("Good morning"));
        clickCardOpening("Good morning", "Delete", "Delete this recording?");
        press("Delete this recording?", "Delete");
        awaitClosed("Delete this recording?");
        pulse();
        assertEquals(0, history.count("Good morning"));
        assertTrue(fx(() -> labelsOf(card("Good morning")).contains("No recordings yet.")));
    }

    @Test
    void thereIsNoRecordingsButtonAndNoDeleteKey() throws Exception {
        assertFalse(buttons().contains("Recordings"));
        later(() -> key(root(), KeyCode.R, true));
        pulse();
        assertNull(FxSupport.stageTitled("Recordings"));
        fx(() -> key(root(), KeyCode.DELETE, false));
        pulse();
        assertEquals(3, english().size());
    }

    @Test
    void reloadWithUnsavedChangesCanCancelOrDiscard() throws Exception {
        remove("I am late");
        assertEquals(2, english().size());

        clickOpening("⟳", "Unsaved changes");
        assertEquals("1 unsaved change.", fx(() -> dialogWith("Unsaved changes").getContentText()));
        press("Unsaved changes", "Cancel");
        awaitClosed("Unsaved changes");
        pulse();
        assertEquals(2, english().size());

        clickOpening("⟳", "Unsaved changes");
        press("Unsaved changes", "Discard");
        awaitClosed("Unsaved changes");
        pulse();
        assertEquals(3, english().size());
        assertEquals("Loaded 3 sentences", status());
        assertEquals("English", title());
    }

    @Test
    void unsavedPromptCanSave() throws Exception {
        remove("I am late");
        clickOpening("⟳", "Unsaved changes");
        press("Unsaved changes", "Save");
        awaitClosed("Unsaved changes");
        pulse();
        assertEquals(2, SentenceFile.load(sentences).size());
        assertEquals(List.of(A), SentenceFile.load(removedFile));
        assertEquals(2, english().size());
    }

    @Test
    void closingWithUnsavedChangesIsGuarded() throws Exception {
        remove("I am late");
        later(() -> stage().fireEvent(new WindowEvent(stage(), WindowEvent.WINDOW_CLOSE_REQUEST)));
        awaitDialog("Unsaved changes");
        press("Unsaved changes", "Cancel");
        awaitClosed("Unsaved changes");
        pulse();
        assertTrue(fx(() -> stage().isShowing()));

        later(() -> stage().fireEvent(new WindowEvent(stage(), WindowEvent.WINDOW_CLOSE_REQUEST)));
        awaitDialog("Unsaved changes");
        press("Unsaved changes", "Discard");
        awaitClosed("Unsaved changes");
        await(() -> stage() == null || !stage().isShowing());
        assertEquals(3, SentenceFile.load(sentences).size());
        assertFalse(Files.exists(removedFile));
    }

    @Test
    void saveDetectsFileChangedOnDisk() throws Exception {
        remove("I am late");
        Files.setLastModifiedTime(sentences, FileTime.fromMillis(System.currentTimeMillis() + 60_000));

        clickOpening("Save", "File changed on disk");
        press("File changed on disk", "Cancel");
        awaitClosed("File changed on disk");
        pulse();
        assertEquals(3, SentenceFile.load(sentences).size());

        clickOpening("Save", "File changed on disk");
        press("File changed on disk", "Overwrite");
        awaitClosed("File changed on disk");
        pulse();
        assertEquals(2, SentenceFile.load(sentences).size());
        assertEquals("Saved 2 sentences", status());

        // reload choice discards the edits and reloads from disk
        remove("See you soon");
        Files.setLastModifiedTime(sentences, FileTime.fromMillis(System.currentTimeMillis() + 120_000));
        clickOpening("Save", "File changed on disk");
        press("File changed on disk", "Reload (lose edits)");
        awaitClosed("File changed on disk");
        pulse();
        assertEquals(2, english().size());
        assertEquals("Loaded 2 sentences", status());
    }

    @Test
    void keyboardShortcutsRunTheToolbarActions() throws Exception {
        later(() -> key(root(), KeyCode.N, true));
        awaitDialog("Add sentence");
        press("Add sentence", "Cancel");
        awaitClosed("Add sentence");

        // Ctrl+S saves; F5 reloads
        Files.writeString(sentences, SentenceFile.format(List.of(A)));
        fx(() -> key(root(), KeyCode.F5, false));
        pulse();
        assertEquals(List.of("I am late"), english());
        fx(() -> key(root(), KeyCode.S, true));
        pulse();
        assertEquals("Saved 1 sentences", status());
    }

    private void pick(String size) throws Exception {
        fx(() -> root().lookupAll(".toggle-button").stream()
                .map(n -> (javafx.scene.control.ToggleButton) n).filter(b -> size.equals(b.getText()))
                .findFirst().get().fire());
        pulse();
    }

    @Test
    void thereIsNoPracticeButton() throws Exception {
        assertFalse(fx(() -> FxSupport.buttonTexts(root())).contains("Practice"));
    }

    @Test
    void escapeGoesHomeAndHomeShowsTheCounts() throws Exception {
        homeKey(KeyCode.ESCAPE);
        assertEquals(List.of("Learn (0 new)", "Review (3 due)", "Sentences (3)"),
                buttons().stream().filter(t -> t.contains("(")).toList());
        assertFalse(fx(() -> button(root(), "Learn (0 new)").isDisabled()));
        assertNull(fx(() -> root().lookup(".sentence-card")));
        homeKey(KeyCode.DIGIT3);
        assertEquals(3, english().size());
    }

    @Test
    void escapeInTheSearchFieldClearsBeforeGoingHome() throws Exception {
        fx(() -> search().setText("late"));
        fx(() -> key(search(), KeyCode.ESCAPE, false));
        pulse();
        assertEquals(3, english().size());
        homeKey(KeyCode.ESCAPE);
        assertTrue(buttons().contains("Sentences (3)"));
    }

    @Test
    void twoOpensReviewAndEscapeReturns() throws Exception {
        homeKey(KeyCode.ESCAPE);
        homeKey(KeyCode.DIGIT2);
        assertEquals("English", title());
        assertTrue(buttons().contains("Record (R)"));
        homeKey(KeyCode.ESCAPE);
        assertTrue(buttons().contains("Review (3 due)"));
    }

    @Test
    void homeShowsNextReviewWhenNothingIsDueAndTheSizePicker() throws Exception {
        CardProgress.save(ProgressStore.file, List.of(new CardProgress(A.english(), 2, LocalDate.now().plusDays(1)),
                new CardProgress(B.english(), 2, LocalDate.now().plusDays(1)),
                new CardProgress(C.english(), 2, LocalDate.now().plusDays(3))));
        homeKey(KeyCode.ESCAPE);
        assertTrue(buttons().contains("Review (0 due)"));
        assertTrue(fx(() -> root().lookupAll(".label").stream()
                .anyMatch(n -> n instanceof Label l && "Next review: tomorrow (2)".equals(l.getText()))));
        assertEquals(List.of("5", "10", "15", "20"), fx(() -> root().lookupAll(".toggle-button")
                .stream().map(n -> ((javafx.scene.control.ToggleButton) n).getText()).toList()));
        pick("15");
        assertEquals(15, EnglishWindow.dailySize());
        pick("10");
        pulse();
        assertEquals(10, EnglishWindow.dailySize());
    }

    @Test
    void missingFileShowsErrorAndSaveCreatesIt() throws Exception {
        fx(() -> stage().hide());
        Files.delete(sentences);
        Files.deleteIfExists(sentences.resolveSibling("everyday.md.bak"));
        later(() -> EnglishWindow.open(null));
        awaitDialog("Cannot read sentences");
        press("Cannot read sentences", "OK");
        awaitClosed("Cannot read sentences");
        pulse();
        homeKey(KeyCode.DIGIT3);
        assertEquals("File missing — Save creates it", status());
        assertEquals("0 of 0", count());
        click("Save");
        assertEquals("Saved 0 sentences", status());
        assertTrue(Files.exists(sentences));
    }

    @Test
    void emptyListShowsPlaceholder() throws Exception {
        SentenceFile.save(sentences, List.of());
        fx(() -> key(root(), KeyCode.F5, false));
        pulse();
        assertEquals(0, english().size());
        assertTrue(fx(() -> labelsOf(root()).contains("No sentences")));
        assertNull(FxSupport.stageTitled("Recordings"));
    }
}
