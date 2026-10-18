package dev.kamil.fabric.english;

import dev.kamil.fabric.settings.AppSettings;
import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Map;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.stage.Window;

/** The English window: a Home screen with Learn, Review and Sentences modes. */
public final class EnglishWindow {

    /** The sentences file; tests point it at a temporary file. */
    static Path file = Path.of("/home/kamil/Documents/Tools/english/everyday.md");
    /** Where removed sentences are kept: removed.md next to {@link #file}. */
    static Path removedFile() {
        return file.resolveSibling("removed.md");
    }

    static final String DAILY_SIZE_KEY = "english.daily_size";
    static final int[] DAILY_SIZES = {5, 10, 15, 20};
    private static EnglishWindow instance;

    private enum Mode { HOME, LEARN, REVIEW, SENTENCES }

    private final Stage window = new Stage();
    private final StackPane root = new StackPane();
    private final SentencesPane sentences;
    private LearnPane learn;
    private ReviewPane review;
    private Mode mode = Mode.HOME;

    /** The open window, or null; for tests. */
    static Stage openStage() {
        return instance == null ? null : instance.window;
    }

    /** Opens the window, or brings the existing one to front. */
    public static void open(Window owner) {
        if (instance != null) {
            instance.window.show();
            instance.window.setIconified(false);
            instance.window.toFront();
            instance.window.requestFocus();
            return;
        }
        var created = new EnglishWindow(owner);
        instance = created;
        created.window.setOnHidden(e -> {
            created.leaveReview();
            instance = null;
        });
        created.window.show();
        created.sentences.load();
        created.showHome();
    }

    /** Cards shown per session: 5, 10, 15 or 20 (default 10). */
    static int dailySize() {
        int n = AppSettings.shared().getInt(DAILY_SIZE_KEY, 10);
        for (int allowed : DAILY_SIZES) {
            if (allowed == n) {
                return n;
            }
        }
        return 10;
    }

    static String fontFamily() {
        return SentencesPane.fontFamily();
    }

    private EnglishWindow(Window owner) {
        window.initOwner(owner);
        sentences = new SentencesPane(window, this::updateTitle);
        updateTitle();
        var scene = new Scene(root, 1400, 800);
        scene.getStylesheets().add(EnglishWindow.class.getResource("/app.css").toExternalForm());
        var acc = scene.getAccelerators();
        acc.put(new KeyCodeCombination(KeyCode.N, KeyCombination.CONTROL_DOWN), () -> inSentences(sentences::add));
        acc.put(new KeyCodeCombination(KeyCode.S, KeyCombination.CONTROL_DOWN), () -> inSentences(sentences::save));
        acc.put(new KeyCodeCombination(KeyCode.T, KeyCombination.CONTROL_DOWN), () -> inSentences(sentences::toggleView));
        acc.put(new KeyCodeCombination(KeyCode.E, KeyCombination.CONTROL_DOWN), () -> inSentences(sentences::editSelected));
        acc.put(new KeyCodeCombination(KeyCode.F5), () -> inSentences(sentences::reload));
        scene.addEventFilter(KeyEvent.KEY_PRESSED, this::onKey);
        window.setOnCloseRequest(e -> {
            leaveReview();
            if (!sentences.discardOk()) {
                e.consume();
            }
        });
        window.setScene(scene);
    }

    private void inSentences(Runnable action) {
        if (mode == Mode.SENTENCES) {
            action.run();
        }
    }

    private void updateTitle() {
        window.setTitle("English" + (sentences.changes() > 0 ? " *" : ""));
    }

    // ---- keys ----

    private void onKey(KeyEvent e) {
        var code = e.getCode();
        switch (mode) {
            case HOME -> {
                if (code == KeyCode.DIGIT1 || code == KeyCode.NUMPAD1) {
                    showLearn();
                    e.consume();
                } else if (code == KeyCode.DIGIT2 || code == KeyCode.NUMPAD2) {
                    showReview();
                    e.consume();
                } else if (code == KeyCode.DIGIT3 || code == KeyCode.NUMPAD3) {
                    showSentences();
                    e.consume();
                }
            }
            case LEARN -> {
                if (code == KeyCode.ESCAPE) {
                    showHome();
                    e.consume();
                } else if (learn != null && learn.onKey(e)) {
                    e.consume();
                }
            }
            case REVIEW -> {
                if (code == KeyCode.ESCAPE) {
                    showHome();
                    e.consume();
                } else if (review != null && review.onKey(e)) {
                    e.consume();
                }
            }
            case SENTENCES -> {
                if (code == KeyCode.ESCAPE && !sentences.escapeClearsSearch(e) && !sentences.isEditing()) {
                    showHome();
                    e.consume();
                }
            }
        }
    }

    // ---- modes ----

    private void leaveReview() {
        sentences.stopAll();
        if (learn != null) {
            learn.stopAll();
            learn = null;
        }
        if (review != null) {
            review.stopAll();
            review = null;
        }
    }

    private void show(Node node) {
        root.getChildren().setAll(node);
    }

    private void showHome() {
        leaveReview();
        mode = Mode.HOME;
        show(buildHome());
    }

    private void showLearn() {
        mode = Mode.LEARN;
        learn = new LearnPane(window, sentences.sentences(), dailySize(), this::showHome, sentences::removeAndSave);
        show(learn);
    }

    private void showReview() {
        mode = Mode.REVIEW;
        review = new ReviewPane(window, sentences.sentences(), dailySize(), this::showHome, sentences::removeAndSave);
        show(review);
    }

    private void showSentences() {
        mode = Mode.SENTENCES;
        show(sentences);
        sentences.focusSearch();
    }

    private Node buildHome() {
        var all = sentences.sentences();
        Map<String, CardProgress> saved = Map.of();
        try {
            saved = ProgressStore.load();
        } catch (IOException e) {
            // Home still shows; Review reports the error when it opens
        }
        var deck = new Deck(all, saved, LocalDate.now(), null, dailySize());
        int due = deck.dueCount();
        int fresh = Learning.unlearned(all, saved).size();

        var title = new Label("English");
        title.getStyleClass().add("home-title");
        var learnButton = homeButton("Learn (" + fresh + " new)", "Learn new sentences (1)", this::showLearn);
        var reviewButton = homeButton("Review (" + due + " due)", "Review learned sentences (2)", this::showReview);
        var sents = homeButton("Sentences (" + all.size() + ")", "Edit the sentences (3)", this::showSentences);

        var sizeLabel = new Label("Cards per session");
        sizeLabel.getStyleClass().add("history-meta");
        var group = new ToggleGroup();
        var picker = new HBox(6, sizeLabel);
        picker.setAlignment(Pos.CENTER);
        for (int n : DAILY_SIZES) {
            var b = new ToggleButton(Integer.toString(n));
            b.setToggleGroup(group);
            b.setFocusTraversable(false);
            b.setSelected(n == dailySize());
            b.setOnAction(e -> {
                if (b.isSelected()) {
                    AppSettings.shared().setInt(DAILY_SIZE_KEY, n);
                    showHome();
                } else {
                    b.setSelected(true);
                }
            });
            picker.getChildren().add(b);
        }

        var box = new VBox(14, title, learnButton, reviewButton, sents, picker);
        box.setAlignment(Pos.CENTER);
        box.setPadding(new Insets(24));
        var next = due == 0 ? deck.nextReviewText() : null;
        if (next != null) {
            var label = new Label(next);
            label.getStyleClass().add("history-meta");
            box.getChildren().add(label);
        }
        return box;
    }

    private static Button homeButton(String text, String tip, Runnable action) {
        var b = new Button(text);
        b.getStyleClass().add("home-button");
        b.setTooltip(new javafx.scene.control.Tooltip(tip));
        b.setFocusTraversable(false);
        b.setOnAction(e -> action.run());
        return b;
    }
}
