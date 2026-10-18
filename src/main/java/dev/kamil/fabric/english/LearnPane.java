package dev.kamil.fabric.english;

import dev.kamil.fabric.Dialogs;
import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import javafx.geometry.Insets;
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

/** Study of the new sentences: see everything, record yourself, then "I know it"; one pane inside the English window. */
final class LearnPane extends VBox {

    private static final Color TEXT = Color.web("#d4d4d4");

    private final Window owner;
    private final int size;
    private final List<Sentence> all;
    private final Consumer<Sentence> onRemove;
    private final Label counter = new Label();
    private final Text english = bigText(26, null);
    private final Text pl = bigText(20, null);
    private final Text say = bigText(15, "-fx-font-family: '" + SentencesPane.fontFamily() + "';");
    private final Text watch = bigText(15, null);
    private final Label hint = new Label("Record yourself at least once (R)");
    private final Button know = new Button("I know it (Space)");
    private final Button skip = new Button("Skip (N)");
    private final Button listen = new Button("Listen (L)");
    private final Button remove = new Button("Remove (Del)");
    private final Label notice = new Label();
    private final Label endNotice = new Label();
    private final Text endText = bigText(20, null);
    private final Button more;
    private final Button homeButton = new Button("Home (Esc)");
    private final Button cardHome = new Button("Home (Esc)");
    private final Button back = new Button("Back (←)");
    private final Button endBack = new Button("Back (←)");
    private final Set<String> known = new HashSet<>();
    private final AttemptsPanel attempts;
    private final VBox card = new VBox(14);
    private final VBox content = new VBox();
    private final Set<String> skipped = new HashSet<>();
    private List<Sentence> batch = new ArrayList<>();
    private int index;
    private int learnedCount;
    private int remaining;
    private Sentence current;

    /**
     * Starts learning the new sentences among {@code sentences}, {@code size} at a time;
     * {@code onRemove} soft-removes a sentence in the shell (Remove button, Delete key).
     */
    LearnPane(Window owner, List<Sentence> sentences, int size, Runnable home, Consumer<Sentence> onRemove) {
        this.owner = owner;
        this.size = size;
        this.onRemove = onRemove;
        this.all = new ArrayList<>(sentences);
        this.more = new Button(size + " more (Space)");
        attempts = new AttemptsPanel(owner);
        counter.getStyleClass().add("history-meta");
        counter.setPadding(new Insets(8));
        hint.getStyleClass().add("history-meta");

        know.setFocusTraversable(false);
        skip.setFocusTraversable(false);
        know.setOnAction(e -> know());
        skip.setOnAction(e -> skip());
        more.setOnAction(e -> startBatch());
        homeButton.setOnAction(e -> home.run());
        cardHome.setFocusTraversable(false);
        cardHome.setOnAction(e -> {
            attempts.stopAll();
            home.run();
        });
        back.setFocusTraversable(false);
        back.setOnAction(e -> back());
        endBack.setFocusTraversable(false);
        endBack.setOnAction(e -> back());
        attempts.addToButtonRow(know);
        attempts.addToButtonRow(skip);
        listen.setFocusTraversable(false);
        listen.setOnAction(e -> listen());
        attempts.addToButtonRow(listen);
        remove.setFocusTraversable(false);
        remove.setOnAction(e -> remove());
        attempts.addToButtonRow(remove);
        attempts.addToButtonRow(back);
        attempts.addToButtonRow(cardHome);
        for (var n : List.of(notice, endNotice)) {
            n.getStyleClass().add("history-meta");
            n.setManaged(false);
            n.setVisible(false);
        }
        attempts.setOnStopped(saved -> {
            if (saved) {
                attempts.playMine();
            }
            updateKnow();
        });
        card.getChildren().addAll(english, pl, say, watch, attempts, hint, notice);

        content.setPadding(new Insets(24));
        content.setSpacing(16);
        VBox.setVgrow(content, Priority.ALWAYS);
        for (var t : List.of(english, pl, say, watch, endText)) {
            t.wrappingWidthProperty().bind(content.widthProperty().subtract(52));
        }
        getChildren().addAll(counter, content);
        startBatch();
    }

    private static Text bigText(int size, String style) {
        var t = new Text();
        t.setFill(TEXT);
        t.setStyle("-fx-font-size: " + size + "px;" + (style == null ? "" : " " + style));
        return t;
    }

    /** Handles R, P, F, L, Delete, Space, N and Left; true when the key was used. Esc belongs to the window. */
    boolean onKey(KeyEvent e) {
        var code = e.getCode();
        if (current == null) {
            if (code == KeyCode.SPACE && remaining > 0) {
                startBatch();
                return true;
            }
            if (code == KeyCode.LEFT && !batch.isEmpty()) {
                back();
                return true;
            }
            return false;
        }
        switch (code) {
            case R -> attempts.toggleRecording();
            case P -> attempts.playMine();
            case F -> attempts.playFirst();
            case L -> listen();
            case DELETE -> remove();
            case N -> skip();
            case LEFT -> back();
            case SPACE -> know();
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

    private List<Sentence> pending() {
        Map<String, CardProgress> saved = new LinkedHashMap<>();
        try {
            saved = ProgressStore.load();
        } catch (IOException e) {
            Dialogs.error(owner, "Cannot read flashcard progress", e.getMessage());
        }
        var result = new ArrayList<>(Learning.unlearned(all, saved));
        result.removeIf(s -> skipped.contains(s.english()));
        return result;
    }

    private void startBatch() {
        var pending = pending();
        batch = new ArrayList<>(pending.subList(0, Math.min(size, pending.size())));
        remaining = pending.size() - batch.size();
        index = 0;
        learnedCount = 0;
        known.clear();
        showCard();
    }

    private void showCard() {
        if (index >= batch.size()) {
            current = null;
            attempts.setSentence(null);
            showEnd();
            return;
        }
        current = batch.get(index);
        attempts.setSentence(current);
        counter.setText("new " + (index + 1) + " of " + batch.size() + " · new");
        english.setText(current.english());
        pl.setText(current.pl());
        say.setText(current.say());
        say.setManaged(!current.say().isBlank());
        watch.setText(current.watchPlain());
        watch.setManaged(!current.watchPlain().isBlank());
        content.getChildren().setAll(card);
        updateKnow();
    }

    private void updateKnow() {
        boolean ready = current != null && attempts.hasAttempts();
        know.setDisable(!ready);
        back.setDisable(index == 0);
        hint.setVisible(!ready);
        hint.setManaged(!ready);
    }

    private void know() {
        if (current == null || !attempts.hasAttempts()) {
            return;
        }
        attempts.stopAll();
        setNotice(false);
        try {
            ProgressStore.save(List.of(Learning.learned(current.english(), LocalDate.now())), all);
            learnedCount++;
            known.add(current.english());
        } catch (IOException e) {
            Dialogs.error(owner, "Cannot save flashcard progress", e.getMessage());
        }
        index++;
        showCard();
    }

    /** Shows the previous card of this batch; a card marked "I know it" becomes new again. */
    private void back() {
        if (index == 0 || batch.isEmpty()) {
            return;
        }
        attempts.stopAll();
        setNotice(false);
        var previous = batch.get(index - 1);
        if (known.remove(previous.english())) {
            try {
                ProgressStore.forget(previous.english());
                learnedCount--;
            } catch (IOException e) {
                Dialogs.error(owner, "Cannot save flashcard progress", e.getMessage());
                known.add(previous.english());
                return;
            }
        }
        skipped.remove(previous.english());
        index--;
        showCard();
    }

    private void listen() {
        if (current != null) {
            attempts.listen(current.english());
        }
    }

    private void remove() {
        if (current == null) {
            return;
        }
        attempts.stopAll();
        var removed = current;
        all.remove(removed);
        batch.remove(index);
        onRemove.accept(removed);
        setNotice(true);
        showCard();
    }

    private void setNotice(boolean shown) {
        var text = shown ? "Removed. Restore it in Sentences → Removed." : "";
        for (var n : List.of(notice, endNotice)) {
            n.setText(text);
            n.setManaged(shown);
            n.setVisible(shown);
        }
    }

    private void skip() {
        if (current == null) {
            return;
        }
        attempts.stopAll();
        setNotice(false);
        skipped.add(current.english());
        index++;
        showCard();
    }

    private void showEnd() {
        counter.setText("");
        endText.setText(batch.isEmpty()
                ? "Nothing new to learn."
                : "Learned " + learnedCount + ". Review starts tomorrow.");
        var end = new VBox(16, endText);
        if (!endNotice.getText().isEmpty()) {
            end.getChildren().add(endNotice);
        }
        if (remaining > 0) {
            end.getChildren().add(more);
        }
        if (!batch.isEmpty()) {
            end.getChildren().add(endBack);
        }
        end.getChildren().add(homeButton);
        content.getChildren().setAll(end, new Region());
    }
}
