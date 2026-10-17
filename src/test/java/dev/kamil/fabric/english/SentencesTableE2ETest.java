package dev.kamil.fabric.english;

import static dev.kamil.fabric.english.FxSupport.fx;
import static dev.kamil.fabric.english.FxSupport.pulse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.kamil.fabric.settings.AppSettings;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javafx.event.Event;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Drives the Table view of the English Sentences mode; skipped without a display. */
class SentencesTableE2ETest {

    private static final Sentence A = new Sentence("2026-10-01", "I am late", "Spozniam sie", "ai am leit", "**late** show");
    private static final Sentence B = new Sentence("2026-10-02", "Good morning", "Dzien dobry", "gud morning", "");
    private static final Sentence C = new Sentence("2026-10-03", "See you soon", "Do zobaczenia", "si ju sun", "Friends ep 1");

    @TempDir
    Path dir;

    private Path sentences;
    private Path removedFile;
    private AppSettings previousSettings;
    private Path savedCache;

    @BeforeAll
    static void startFx() {
        FxSupport.startFx();
    }

    @BeforeEach
    void prepare() throws Exception {
        previousSettings = AppSettings.use(AppSettings.inMemory());
        sentences = dir.resolve("english/everyday.md");
        removedFile = dir.resolve("english/removed.md");
        EnglishWindow.file = sentences;
        ProgressStore.file = dir.resolve("english/flashcards.csv");
        RecordingHistory.standardRoot = dir.resolve("english/recordings");
        savedCache = Voice.cacheDir;
        Voice.cacheDir = dir.resolve("english/tts");
        Voice.fake = true;
        Recorder.fake = true;
        SentenceFile.save(sentences, List.of(A, B, C));
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
        AppSettings.use(previousSettings);
        var err = FxSupport.takeErr();
        assertFalse(err.contains("Exception"), "exception on stderr: " + err);
    }

    private void openWindow() throws Exception {
        fx(() -> EnglishWindow.open(null));
        pulse();
        fx(() -> key(root(), KeyCode.DIGIT3, false));
        pulse();
        pulse();
    }

    private void reopen() throws Exception {
        fx(() -> EnglishWindow.openStage().hide());
        pulse();
        openWindow();
    }

    private static Stage stage() {
        return EnglishWindow.openStage();
    }

    private static javafx.scene.Parent root() {
        var root = stage().getScene().getRoot();
        root.applyCss();
        root.layout();
        return root;
    }

    private static void key(Node target, KeyCode code, boolean ctrl) {
        Event.fireEvent(target, new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, false, ctrl, false, false));
    }

    @SuppressWarnings("unchecked")
    private static TableView<Sentence> table() {
        return (TableView<Sentence>) root().lookup(".table-view");
    }

    private static ToggleButton toggle(String text) {
        for (Node n : root().lookupAll(".toggle-button")) {
            if (n instanceof ToggleButton t && text.equals(t.getText())) {
                return t;
            }
        }
        throw new AssertionError("no toggle " + text);
    }

    private void showTable() throws Exception {
        fx(() -> toggle("Table").fire());
        pulse();
    }

    private List<String> rows() throws Exception {
        return fx(() -> table().getItems().stream().map(Sentence::english).toList());
    }

    private String title() throws Exception {
        return fx(() -> stage().getTitle());
    }

    private String status() throws Exception {
        return fx(() -> {
            var labels = new ArrayList<Label>();
            for (Node n : root().lookupAll(".history-meta")) {
                if (n instanceof Label l) {
                    labels.add(l);
                }
            }
            return labels.get(labels.size() - 1).getText();
        });
    }

    private void select(int row, String column) throws Exception {
        fx(() -> {
            var t = table();
            TableColumn<Sentence, ?> col = t.getColumns().stream().filter(c -> c.getText().equals(column))
                    .findFirst().orElseThrow();
            t.getSelectionModel().select(row, col);
            t.requestFocus();
        });
        pulse();
    }

    /** Starts editing the cell with F2, types the text and presses Enter. */
    private void editCell(int row, String column, String text) throws Exception {
        select(row, column);
        fx(() -> key(table(), KeyCode.F2, false));
        pulse();
        fx(() -> {
            var field = (TextField) table().lookupAll(".text-field").stream()
                    .filter(n -> n instanceof TextField).findFirst().orElseThrow();
            field.setText(text);
            key(field, KeyCode.ENTER, false);
        });
        pulse();
    }

    @Test
    void tableShowsAllSentencesNewestFirst() throws Exception {
        showTable();
        assertEquals(List.of("See you soon", "Good morning", "I am late"), rows());
    }

    @Test
    void searchFiltersRows() throws Exception {
        showTable();
        fx(() -> ((TextField) root().lookup(".text-field")).setText("morning"));
        pulse();
        assertEquals(List.of("Good morning"), rows());
    }

    @Test
    void inlineEditCommitsAndSaves() throws Exception {
        showTable();
        editCell(2, "English", "I am very late");
        assertEquals(List.of("See you soon", "Good morning", "I am very late"), rows());
        assertEquals("English *", title());
        assertEquals("Edited — 1 unsaved change", status());
    }

    @Test
    void saveWritesEdit() throws Exception {
        showTable();
        editCell(2, "English", "I am very late");
        fx(() -> stage().getScene().getAccelerators().entrySet().stream()
                .filter(e -> e.getKey().toString().contains("Ctrl+S")).findFirst().orElseThrow().getValue().run());
        pulse();
        assertTrue(Files.readString(sentences).contains("I am very late"));
        assertEquals("English", title());
    }

    @Test
    void escapeCancelsEditAndStaysInSentences() throws Exception {
        showTable();
        select(1, "English");
        fx(() -> key(table(), KeyCode.ENTER, false));
        pulse();
        fx(() -> {
            var field = (TextField) table().lookupAll(".text-field").stream().findFirst().orElseThrow();
            field.setText("changed");
            key(field, KeyCode.ESCAPE, false);
        });
        pulse();
        assertEquals("English", title());
        assertEquals(List.of("See you soon", "Good morning", "I am late"), rows());
        assertTrue(fx(() -> table().isVisible() && table().getScene() != null));
        assertTrue(fx(() -> root().lookup(".table-view") != null));
    }

    @Test
    void badDateIsRejected() throws Exception {
        showTable();
        editCell(0, "Date", "tomorrow");
        assertEquals("Date must be yyyy-MM-dd", status());
        assertEquals("2026-10-03", fx(() -> table().getItems().get(0).date()));
        assertEquals("English", title());
    }

    @Test
    void goodDateIsAccepted() throws Exception {
        showTable();
        editCell(0, "Date", "2026-09-01");
        assertEquals("2026-09-01", fx(() -> table().getItems().stream()
                .filter(s -> s.english().equals("See you soon")).findFirst().orElseThrow().date()));
    }

    @Test
    void watchEditKeepsBold() throws Exception {
        showTable();
        select(2, "Watch");
        fx(() -> key(table(), KeyCode.F2, false));
        pulse();
        var initial = fx(() -> ((TextField) table().lookupAll(".text-field").stream().findFirst().orElseThrow()).getText());
        assertEquals("**late** show", initial);
        editCell(2, "Watch", "**very** late");
        assertEquals("**very** late", fx(() -> table().getItems().get(2).watch()));
    }

    @Test
    void sayStripsBackticks() throws Exception {
        showTable();
        editCell(1, "Say", "`gud` morning");
        assertEquals("gud morning", fx(() -> table().getItems().get(1).say()));
    }

    @Test
    void ctrlTTogglesAndChoiceSurvivesReopen() throws Exception {
        var toggleKey = new javafx.scene.input.KeyCodeCombination(KeyCode.T,
                javafx.scene.input.KeyCombination.CONTROL_DOWN);
        fx(() -> stage().getScene().getAccelerators().get(toggleKey).run());
        pulse();
        assertTrue(fx(() -> toggle("Table").isSelected()));
        assertEquals("table", AppSettings.shared().getString(SentencesPane.VIEW_KEY, "cards"));
        reopen();
        assertTrue(fx(() -> toggle("Table").isSelected()));
        assertEquals(3, rows().size());
        fx(() -> stage().getScene().getAccelerators().get(toggleKey).run());
        pulse();
        assertTrue(fx(() -> toggle("Cards").isSelected()));
        assertEquals("cards", AppSettings.shared().getString(SentencesPane.VIEW_KEY, "cards"));
    }

    @Test
    void deleteRemovesAndSaveWritesRemoved() throws Exception {
        showTable();
        select(1, "English");
        fx(() -> key(table(), KeyCode.DELETE, false));
        pulse();
        assertEquals(List.of("See you soon", "I am late"), rows());
        assertEquals("English *", title());
        fx(() -> stage().getScene().getAccelerators().entrySet().stream()
                .filter(e -> e.getKey().toString().contains("Ctrl+S")).findFirst().orElseThrow().getValue().run());
        pulse();
        assertTrue(Files.readString(removedFile).contains("Good morning"));
        assertFalse(Files.readString(sentences).contains("Good morning"));
    }

    @Test
    void switchingKeepsSearchAndChanges() throws Exception {
        showTable();
        editCell(1, "English", "Good evening");
        fx(() -> ((TextField) root().lookup(".text-field")).setText("evening"));
        pulse();
        fx(() -> toggle("Cards").fire());
        pulse();
        assertEquals("English *", title());
        assertEquals(1, fx(() -> root().lookupAll(".sentence-card").size()));
        fx(() -> toggle("Table").fire());
        pulse();
        assertEquals(List.of("Good evening"), rows());
    }
}
