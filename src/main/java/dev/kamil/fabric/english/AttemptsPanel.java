package dev.kamil.fabric.english;

import dev.kamil.fabric.Dialogs;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.function.Consumer;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

/**
 * Recording UI for one sentence: Me (P), Try again (R), First (F) and the newest five attempts with play and
 * delete. {@link #recordButton()} is a separate Record (R) button for a screen that has not shown the answer yet.
 */
final class AttemptsPanel extends VBox {

    private final Window owner;
    private final Recorder recorder = new Recorder();
    private final RecordingHistory history = RecordingHistory.standard();
    private final Button record = new Button("Record (R)");
    private final Button mine = new Button("Me (P)");
    private final Button retry = new Button("Try again (R)");
    private final Button first = new Button("First (F)");
    private final HBox buttons = new HBox(8, mine, retry, first);
    private final VBox list = new VBox(4);
    private Sentence current;
    private Consumer<Boolean> onStopped = saved -> { };

    AttemptsPanel(Window owner) {
        super(14);
        this.owner = owner;
        for (var b : List.of(record, mine, retry, first)) {
            b.setFocusTraversable(false);
        }
        record.setOnAction(e -> toggleRecording());
        retry.setOnAction(e -> toggleRecording());
        mine.setOnAction(e -> playMine());
        first.setOnAction(e -> playFirst());
        getChildren().addAll(buttons, list);
    }

    /** The Record (R) button for a screen without the panel; it shares the recording state. */
    Button recordButton() {
        return record;
    }

    /** Called after a recording was stopped by {@link #toggleRecording()}; the argument says if an attempt was saved. */
    void setOnStopped(Consumer<Boolean> onStopped) {
        this.onStopped = onStopped;
    }

    /** Shows the attempts of {@code sentence}; a running recording is stopped first. */
    void setSentence(Sentence sentence) {
        stopAll();
        current = sentence;
        refresh();
    }

    boolean recording() {
        return recorder.recording();
    }

    boolean hasAttempts() {
        return current != null && !attempts().isEmpty();
    }

    /** Starts a recording, or stops the running one, saves it and tells the {@link #setOnStopped} callback. */
    void toggleRecording() {
        if (current == null) {
            return;
        }
        if (recorder.recording()) {
            boolean saved = stopRecording();
            refresh();
            onStopped.accept(saved);
            return;
        }
        try {
            recorder.start();
            record.setText("● Recording… (R to stop)");
            retry.setText("● Recording… (R to stop)");
        } catch (IOException e) {
            Dialogs.error(owner, "Cannot record", e.getMessage());
        }
    }

    /** Stops the capture and saves it as a new attempt; true when an attempt was saved. */
    boolean stopRecording() {
        record.setText("Record (R)");
        retry.setText("Try again (R)");
        if (!recorder.recording() || current == null) {
            return false;
        }
        try {
            var saved = recorder.stop(history.newFile(current.english(), LocalDateTime.now()));
            if (saved == null) {
                return false;
            }
            history.prune(current.english(), RecordingHistory.KEEP);
            return true;
        } catch (IOException e) {
            Dialogs.error(owner, "Cannot save the recording", e.getMessage());
            return false;
        }
    }

    /** Stops playback and saves a running recording. */
    void stopAll() {
        recorder.stopPlayback();
        if (recorder.recording() && current != null) {
            stopRecording();
        }
    }

    void playMine() {
        var all = attempts();
        if (!all.isEmpty()) {
            playFile(all.get(0).file());
        }
    }

    void playFirst() {
        var all = attempts();
        if (all.size() > 1) {
            playFile(all.get(all.size() - 1).file());
        }
    }

    /** Plays the voice WAV of {@code english} (made on first use on a background thread). */
    void listen(String english) {
        Voice.async(english, false, wav -> playFile(wav),
                message -> Dialogs.error(owner, "Cannot play the voice", message));
    }

    private void playFile(Path file) {
        recorder.stopPlayback();
        recorder.play(file, null);
    }

    private List<RecordingHistory.Attempt> attempts() {
        if (current == null) {
            return List.of();
        }
        try {
            return history.list(current.english());
        } catch (UncheckedIOException e) {
            return List.of();
        }
    }

    /** Re-reads the attempts: enables Me and First and rebuilds the newest-five list. */
    void refresh() {
        var all = attempts();
        mine.setDisable(all.isEmpty());
        first.setDisable(all.size() < 2);
        list.getChildren().clear();
        for (var a : all.subList(0, Math.min(5, all.size()))) {
            var play = new Button("▶");
            play.setFocusTraversable(false);
            play.setOnAction(e -> playFile(a.file()));
            var delete = new Button("Delete");
            delete.setFocusTraversable(false);
            delete.setOnAction(e -> deleteAttempt(a));
            var row = new HBox(8, play, new Label(RecordingHistory.label(a.at())), delete);
            row.setAlignment(Pos.CENTER_LEFT);
            list.getChildren().add(row);
        }
    }

    private void deleteAttempt(RecordingHistory.Attempt a) {
        if (!Dialogs.confirm(owner, "Delete this recording?", RecordingHistory.label(a.at()), "Delete")) {
            return;
        }
        try {
            recorder.stopPlayback();
            history.delete(a.file());
        } catch (IOException e) {
            Dialogs.error(owner, "Cannot delete the recording", e.getMessage());
        }
        refresh();
    }
}
