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
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.text.Text;
import javafx.stage.Window;

/** Flashcard review of the learned sentences (Polish front, English back); one pane inside the English window. */
final class ReviewPane extends VBox {

    private static final Color TEXT = Color.web("#d4d4d4");

    private final Window owner;
    private final Runnable home;
    private final int size;
    private final Label counter = new Label();
    private final Text front = bigText(26, null);
    private final Label hint = new Label("R = record, Space = show answer");
    private final VBox frontBox = new VBox(16);
    private final Text english = bigText(26, null);
    private final Text say = bigText(15, "-fx-font-family: '" + SentencesPane.fontFamily() + "';");
    private final Text watch = bigText(15, null);
    private final Button next = new Button("Next (Space)");
    private final Button listen = new Button("Listen (L)");
    private final Button remove = new Button("Remove (Del)");
    private final Button removeFront = new Button("Remove (Del)");
    private final Label notice = new Label();
    private final Label endNotice = new Label();
    private final VBox backBox = new VBox(14);
    private final Text endText = bigText(20, null);
    private final Button more;
    private final Button homeButton = new Button("Home (Esc)");
    private final Button homeFront = new Button("Home (Esc)");
    private final Button homeBack = new Button("Home (Esc)");
    private final Button backFront = new Button("Back (←)");
    private final Button backBack = new Button("Back (←)");
    private final Button endBack = new Button("Back (←)");
    private final AttemptsPanel attempts;
    private final VBox content = new VBox();
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
        this.home = home;
        this.size = size;
        this.onRemove = onRemove;
        this.all = new ArrayList<>(sentences);
        this.more = new Button(size + " more (Space)");
        attempts = new AttemptsPanel(owner);
        counter.getStyleClass().add("history-meta");
        counter.setPadding(new Insets(8));
        hint.getStyleClass().add("history-meta");
        var frontRow = new javafx.scene.layout.HBox(8, removeFront, backFront, homeFront);
        frontBox.getChildren().addAll(front, attempts.recordButton(), frontRow, hint, notice);
        frontBox.setAlignment(Pos.TOP_LEFT);

        next.setOnAction(e -> answered());
        next.setFocusTraversable(false);
        more.setOnAction(e -> extend());
        homeButton.setOnAction(e -> home.run());
        for (var b : List.of(homeFront, homeBack)) {
            b.setFocusTraversable(false);
            b.setOnAction(e -> {
                attempts.stopAll();
                home.run();
            });
        }
        for (var b : List.of(backFront, backBack, endBack)) {
            b.setFocusTraversable(false);
            b.setOnAction(e -> back());
        }
        attempts.addToButtonRow(next);
        listen.setFocusTraversable(false);
        listen.setOnAction(e -> attempts.listen(current.english()));
        attempts.addToButtonRow(listen);
        remove.setFocusTraversable(false);
        removeFront.setFocusTraversable(false);
        remove.setOnAction(e -> remove());
        removeFront.setOnAction(e -> remove());
        attempts.addToButtonRow(remove);
        attempts.addToButtonRow(backBack);
        attempts.addToButtonRow(homeBack);
        for (var n : List.of(notice, endNotice)) {
            n.getStyleClass().add("history-meta");
            n.setManaged(false);
            n.setVisible(false);
        }
        attempts.setOnStopped(saved -> {
            if (!revealed) {
                reveal();
            } else if (saved) {
                attempts.playMine();
            }
        });
        backBox.getChildren().addAll(english, say, watch, attempts);

        content.setPadding(new Insets(24));
        content.setSpacing(16);
        VBox.setVgrow(content, Priority.ALWAYS);
        for (var t : List.of(front, english, say, watch, endText)) {
            t.wrappingWidthProperty().bind(content.widthProperty().subtract(52));
        }
        getChildren().addAll(counter, content);
        start();
    }

    private static Text bigText(int size, String style) {
        var t = new Text();
        t.setFill(TEXT);
        t.setStyle("-fx-font-size: " + size + "px;" + (style == null ? "" : " " + style));
        return t;
    }

    /** Handles R, Space, P, F, L, Delete and Left; true when the key was used. Esc belongs to the window. */
    boolean onKey(KeyEvent e) {
        var code = e.getCode();
        if (code == KeyCode.LEFT && deck != null && deck.canBack()) {
            back();
            return true;
        }
        if (current == null && code == KeyCode.SPACE && deck != null) {
            extend();
            return true;
        }
        if (current == null) {
            return false;
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
            if (code == KeyCode.SPACE) {
                reveal();
                return true;
            }
            return false;
        }
        switch (code) {
            case L -> attempts.listen(current.english());
            case P -> attempts.playMine();
            case F -> attempts.playFirst();
            case SPACE -> answered();
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
        if (current == null) {
            attempts.setSentence(null);
            showEnd();
            return;
        }
        attempts.setSentence(current);
        counter.setText("card " + (deck.done() + 1) + " of " + deck.sessionSize() + " · "
                + deck.labelOf(current.english()));
        front.setText(current.pl());
        backFront.setDisable(!deck.canBack());
        backBack.setDisable(!deck.canBack());
        content.getChildren().setAll(frontBox);
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
        content.getChildren().setAll(backBox);
        attempts.playMine();
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

    /** Shows the previous graded card again on its front side and undoes its grade. */
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
        }
        var nextReview = deck.nextReviewText();
        if (nextReview != null) {
            sb.append(' ').append(nextReview).append('.');
        }
        counter.setText("");
        endText.setText(sb.toString());
        var end = new VBox(16, endText);
        if (!endNotice.getText().isEmpty()) {
            end.getChildren().add(endNotice);
        }
        if (deck.sessionSize() > 0) {
            end.getChildren().add(more);
        }
        if (deck.canBack()) {
            end.getChildren().add(endBack);
        }
        end.getChildren().add(homeButton);
        content.getChildren().setAll(end, new Region());
    }

    private void extend() {
        if (deck.extend(size) > 0) {
            showNext();
        } else {
            endText.setText("Nothing more due today.");
            content.getChildren().setAll(new VBox(16, endText, homeButton), new Region());
        }
    }
}
