package dev.kamil.fabric.english;

import dev.kamil.fabric.Dialogs;
import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.Consumer;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.text.Text;
import javafx.stage.Window;

/** Flashcard review of the learned sentences (Polish front, English back); one pane inside the English window. */
final class ReviewPane extends VBox {

    private static final Color TEXT = Color.web("#d4d4d4");

    private final Window owner;
    private final int size;
    private final CardFrame frame;
    private final Text front = bigText(26, null);
    private final VBox frontBox = new VBox(16);
    private final Text english = bigText(26, null);
    private final Text say = bigText(15, "-fx-font-family: '" + SentencesPane.fontFamily() + "';");
    private final Text watch = bigText(15, null);
    private final Button showAnswer = new Button("Show answer (Space)");
    private final Button listen = new Button("Listen (L)");
    private final Label notice = new Label();
    private final Label endNotice = new Label();
    private final VBox backBox = new VBox(14);
    private final VBox endBox = new VBox(16);
    private final Text endText = bigText(20, null);
    private final Button more;
    private final AttemptsPanel attempts;
    private final List<Sentence> all;
    private final Consumer<Sentence> onRemove;
    private Deck deck;
    private Sentence current;
    private boolean revealed;

    /**
     * Starts a review of the learned cards among {@code sentences}, {@code size} due cards at a time;
     * {@code onRemove} soft-removes a sentence in the shell (Remove button, Delete key).
     */
    ReviewPane(Window owner, List<Sentence> sentences, int size, Runnable home, Consumer<Sentence> onRemove) {
        this.owner = owner;
        this.size = size;
        this.onRemove = onRemove;
        this.all = new ArrayList<>(sentences);
        this.more = new Button(size + " more (Space)");
        attempts = new AttemptsPanel(owner);
        frame = new CardFrame(() -> {
            attempts.stopAll();
            home.run();
        }, this::back, this::next, this::remove);

        showAnswer.setOnAction(e -> reveal());
        more.setOnAction(e -> extend());
        listen.setOnAction(e -> attempts.listen(current.english()));
        for (var b : List.of(showAnswer, more, listen)) {
            b.setFocusTraversable(false);
        }
        for (var n : List.of(notice, endNotice)) {
            n.getStyleClass().add("history-meta");
            n.setManaged(false);
            n.setVisible(false);
        }
        // A recording just made plays back (to compare with the answer); Show answer alone plays nothing.
        attempts.setOnStopped(saved -> {
            if (!revealed) {
                reveal();
            }
            if (saved) {
                attempts.playMine();
            }
        });
        frontBox.getChildren().addAll(front, new HBox(8, showAnswer, attempts.recordButton()), notice);
        backBox.getChildren().addAll(english, say, watch, new HBox(8, listen), attempts);

        var column = frame.content();
        for (var t : List.of(front, english, say, watch, endText)) {
            t.wrappingWidthProperty().bind(column.widthProperty().subtract(4));
        }
        for (var box : List.of(frontBox, backBox, endBox)) {
            box.setMinWidth(0);
        }
        setMinWidth(0);
        VBox.setVgrow(frame, Priority.ALWAYS);
        getChildren().add(frame);
        start();
    }

    private static Text bigText(int size, String style) {
        var t = new Text();
        t.setFill(TEXT);
        t.setStyle("-fx-font-size: " + size + "px;" + (style == null ? "" : " " + style));
        return t;
    }

    /** Handles R, Space, Enter, P, F, L, Delete, Left and Right; true when the key was used. Esc belongs to the window. */
    boolean onKey(KeyEvent e) {
        var code = e.getCode();
        boolean primary = code == KeyCode.SPACE || code == KeyCode.ENTER;
        if (code == KeyCode.LEFT && deck != null && deck.canBack()) {
            back();
            return true;
        }
        if (current == null && primary && deck != null) {
            extend();
            return true;
        }
        if (current == null) {
            return false;
        }
        if (code == KeyCode.RIGHT) {
            next();
            return true;
        }
        if (code == KeyCode.R) {
            attempts.toggleRecording();
            return true;
        }
        if (code == KeyCode.DELETE) {
            remove();
            return true;
        }
        if (!revealed) {
            if (primary) {
                reveal();
                return true;
            }
            return false;
        }
        switch (code) {
            case L -> attempts.listen(current.english());
            case P -> attempts.playMine();
            case F -> attempts.playFirst();
            case SPACE, ENTER -> answered();
            default -> {
                return false;
            }
        }
        return true;
    }

    /** Stops playback and saves a running recording; called before leaving the pane. */
    void stopAll() {
        attempts.stopAll();
    }

    // ---- flow ----

    private void start() {
        Map<String, CardProgress> saved = new LinkedHashMap<>();
        try {
            saved = ProgressStore.load();
        } catch (IOException e) {
            Dialogs.error(owner, "Cannot read flashcard progress", e.getMessage());
        }
        deck = new Deck(all, saved, LocalDate.now(), new Random(), size);
        showNext();
    }

    private void showNext() {
        current = deck.next();
        revealed = false;
        frame.backButton().setDisable(!deck.canBack());
        if (current == null) {
            attempts.setSentence(null);
            showEnd();
            return;
        }
        attempts.setSentence(current);
        frame.setTitle("Review · card " + (deck.position() + 1) + " of " + deck.sessionSize() + " · "
                + deck.labelOf(current.english()));
        front.setText(current.pl());
        frame.nextButton().setDisable(false);
        frame.removeButton().setDisable(false);
        frame.setPrimary(showAnswer);
        frame.setContent(frontBox);
    }

    private void reveal() {
        if (current == null || revealed) {
            return;
        }
        attempts.stopRecording();
        setNotice(false);
        revealed = true;
        english.setText(current.english());
        attempts.refresh();
        say.setText(current.say());
        say.setManaged(!current.say().isBlank());
        watch.setText(current.watchPlain());
        watch.setManaged(!current.watchPlain().isBlank());
        frame.setPrimary(frame.nextButton());
        frame.setContent(backBox);
    }

    /** Next (→): skips the card on the front, finishes it on the back; nothing on an end screen. */
    private void next() {
        if (current == null) {
            return;
        }
        if (revealed) {
            answered();
        } else {
            skip();
        }
    }

    /** Leaves the card for this session without a grade; it keeps its progress, so nothing is saved. */
    private void skip() {
        attempts.stopAll();
        setNotice(false);
        deck.skip();
        showNext();
    }

    private void answered() {
        if (!revealed || current == null) {
            return;
        }
        attempts.stopAll();
        setNotice(false);
        deck.grade(Deck.Grade.GOOD);
        try {
            ProgressStore.save(deck.progress(), all);
        } catch (IOException e) {
            Dialogs.error(owner, "Cannot save flashcard progress", e.getMessage());
        }
        showNext();
    }

    /** Shows the previous graded or skipped card again on its front side and undoes its grade. */
    private void back() {
        if (deck == null || !deck.canBack()) {
            return;
        }
        attempts.stopAll();
        setNotice(false);
        deck.back();
        try {
            ProgressStore.save(deck.progress(), all);
        } catch (IOException e) {
            Dialogs.error(owner, "Cannot save flashcard progress", e.getMessage());
        }
        showNext();
    }

    /** Takes the current card out of this session without grading it. */
    private void remove() {
        if (current == null) {
            return;
        }
        attempts.stopAll();
        var removed = current;
        deck.discard();
        all.remove(removed);
        onRemove.accept(removed);
        setNotice(true);
        showNext();
    }

    private void setNotice(boolean shown) {
        var text = shown ? "Removed. Restore it in Sentences → Removed." : "";
        for (var n : List.of(notice, endNotice)) {
            n.setText(text);
            n.setManaged(shown);
            n.setVisible(shown);
        }
    }

    private void showEnd() {
        var sb = new StringBuilder();
        if (deck.sessionSize() == 0) {
            sb.append("Nothing due today.");
        } else {
            sb.append("Done: ").append(deck.done()).append(" cards.");
            if (deck.skipped() > 0) {
                sb.append(" Skipped ").append(deck.skipped()).append(", still due.");
            }
        }
        var nextReview = deck.nextReviewText();
        if (nextReview != null) {
            sb.append(' ').append(nextReview).append('.');
        }
        frame.setTitle("Review");
        endText.setText(sb.toString());
        endBox.getChildren().setAll(endText);
        if (!endNotice.getText().isEmpty()) {
            endBox.getChildren().add(endNotice);
        }
        if (deck.sessionSize() > 0) {
            endBox.getChildren().add(more);
        }
        frame.nextButton().setDisable(true);
        frame.removeButton().setDisable(true);
        frame.setPrimary(deck.sessionSize() > 0 ? more : null);
        frame.setContent(endBox);
    }

    private void extend() {
        if (deck.extend(size) > 0) {
            showNext();
        } else {
            endText.setText("Nothing more due today.");
            endBox.getChildren().setAll(endText);
            frame.setPrimary(null);
            frame.setContent(endBox);
        }
    }
}
