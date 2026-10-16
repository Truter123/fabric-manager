package dev.kamil.fabric.search;

import dev.kamil.fabric.Dialogs;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.function.Consumer;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.stage.Window;
import javafx.util.Duration;

/** Popup that searches all transcripts and hands the chosen hit to a consumer (resume the session). */
public final class TranscriptSearchWindow {

    private static final int LIMIT = 50;
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final Stage stage = new Stage(StageStyle.UNDECORATED);
    private final List<Path> claudeHomes;
    private final Consumer<TranscriptSearch.Hit> onChoose;
    private final TextField query = new TextField();
    private final ListView<TranscriptSearch.Hit> list = new ListView<>();
    private final PauseTransition debounce = new PauseTransition(Duration.millis(300));
    private long generation;

    public TranscriptSearchWindow(Window owner, List<Path> claudeHomes, Consumer<TranscriptSearch.Hit> onChoose) {
        this.claudeHomes = claudeHomes;
        this.onChoose = onChoose;
        stage.initOwner(owner);

        query.setPromptText("Search transcripts");
        query.textProperty().addListener((o, a, b) -> debounce.playFromStart());
        debounce.setOnFinished(e -> search());
        list.setCellFactory(v -> new HitCell());
        list.setPlaceholder(new Label("Type to search all sessions."));
        list.setPrefHeight(360);
        list.setOnMouseClicked(e -> {
            if (e.getButton() == MouseButton.PRIMARY && e.getClickCount() == 2) {
                choose();
            }
        });
        VBox.setVgrow(list, Priority.ALWAYS);
        var hint = new Label("Enter search / open   Esc close");
        hint.getStyleClass().add("snippet-hint");

        var root = new VBox(8, query, list, hint);
        root.getStyleClass().add("snippet-picker");
        root.setPadding(new Insets(10));
        root.setPrefSize(720, 460);
        var scene = new Scene(root);
        scene.getStylesheets().add(Dialogs.class.getResource("/app.css").toExternalForm());
        scene.getStylesheets().add(TranscriptSearchWindow.class.getResource("/snippets.css").toExternalForm());
        scene.addEventFilter(KeyEvent.KEY_PRESSED, this::onKey);
        stage.setScene(scene);
        stage.focusedProperty().addListener((o, was, now) -> {
            if (!now) {
                stage.hide();
            }
        });
    }

    /** Shows the popup centred on the owner window. */
    public void show() {
        var owner = stage.getOwner();
        stage.setOnShown(e -> {
            stage.setX(owner.getX() + (owner.getWidth() - stage.getWidth()) / 2);
            stage.setY(owner.getY() + (owner.getHeight() - stage.getHeight()) / 3);
            query.requestFocus();
            query.selectAll();
        });
        stage.show();
    }

    private void onKey(KeyEvent event) {
        switch (event.getCode()) {
            case ESCAPE -> {
                Dialogs.closeLater(stage);
                event.consume();
            }
            case ENTER -> {
                if (list.getSelectionModel().getSelectedItem() != null && !debounce.getStatus().equals(
                        javafx.animation.Animation.Status.RUNNING)) {
                    choose();
                } else {
                    debounce.stop();
                    search();
                }
                event.consume();
            }
            case DOWN -> {
                move(1);
                event.consume();
            }
            case UP -> {
                move(-1);
                event.consume();
            }
            default -> {
            }
        }
    }

    private void move(int delta) {
        int next = Math.max(0, Math.min(list.getItems().size() - 1, list.getSelectionModel().getSelectedIndex() + delta));
        list.getSelectionModel().select(next);
        list.scrollTo(next);
    }

    private void choose() {
        var hit = list.getSelectionModel().getSelectedItem();
        if (hit != null) {
            Platform.runLater(() -> {
                stage.hide();
                onChoose.accept(hit);
            });
        }
    }

    private void search() {
        var text = query.getText() == null ? "" : query.getText();
        long mine = ++generation;
        Thread.ofVirtual().start(() -> {
            var hits = TranscriptSearch.search(claudeHomes, text, LIMIT);
            Platform.runLater(() -> {
                if (mine == generation) {
                    list.getItems().setAll(hits);
                    if (!hits.isEmpty()) {
                        list.getSelectionModel().select(0);
                    }
                }
            });
        });
    }

    private static final class HitCell extends ListCell<TranscriptSearch.Hit> {
        @Override
        protected void updateItem(TranscriptSearch.Hit hit, boolean empty) {
            super.updateItem(hit, empty);
            if (empty || hit == null) {
                setGraphic(null);
                return;
            }
            var project = hit.project() == null || hit.project().getFileName() == null
                    ? hit.sessionId() : hit.project().getFileName().toString();
            var date = hit.time() == null ? "" : DATE.format(hit.time().atZone(ZoneId.systemDefault()));
            var head = new Label(project + "   " + date + "   " + hit.role());
            head.getStyleClass().add("snippet-name");
            var snippet = new Label(hit.snippet());
            snippet.getStyleClass().add("snippet-preview");
            setGraphic(new VBox(2, head, snippet));
        }
    }
}
