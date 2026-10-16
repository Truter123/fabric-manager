package dev.kamil.fabric.ssh;

import dev.kamil.fabric.Dialogs;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.stage.Window;
import dev.kamil.fabric.snippets.Fuzzy;

/** Command-palette popup that picks a host from the ssh config. */
public final class SshPicker {

    private final Stage stage = new Stage(StageStyle.UNDECORATED);
    private final List<SshHost> hosts;
    private final Consumer<SshHost> onPick;
    private final TextField filter = new TextField();
    private final ListView<SshHost> list = new ListView<>();

    public SshPicker(Window owner, List<SshHost> hosts, Consumer<SshHost> onPick) {
        this.hosts = hosts;
        this.onPick = onPick;
        stage.initOwner(owner);

        filter.setPromptText("Filter hosts");
        filter.textProperty().addListener((o, a, b) -> refresh());
        list.setCellFactory(v -> new HostCell());
        list.setPlaceholder(new Label("No hosts in ~/.ssh/config"));
        list.setPrefHeight(280);
        VBox.setVgrow(list, Priority.ALWAYS);
        var hint = new Label("Enter connect   Esc close");
        hint.getStyleClass().add("snippet-hint");
        var root = new VBox(8, filter, list, hint);
        root.getStyleClass().add("snippet-picker");
        root.setPadding(new Insets(10));
        root.setPrefSize(560, 380);

        var scene = new Scene(root);
        scene.getStylesheets().add(Dialogs.class.getResource("/app.css").toExternalForm());
        scene.getStylesheets().add(SshPicker.class.getResource("/snippets.css").toExternalForm());
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
        filter.clear();
        refresh();
        var owner = stage.getOwner();
        stage.setOnShown(e -> {
            stage.setX(owner.getX() + (owner.getWidth() - stage.getWidth()) / 2);
            stage.setY(owner.getY() + (owner.getHeight() - stage.getHeight()) / 3);
            filter.requestFocus();
        });
        stage.show();
    }

    private static int score(String query, SshHost host) {
        return Fuzzy.score(query, host.alias(), host.detail());
    }

    private void refresh() {
        var query = filter.getText() == null ? "" : filter.getText();
        List<SshHost> shown;
        if (query.isBlank()) {
            shown = hosts;
        } else {
            shown = hosts.stream()
                    .filter(h -> score(query, h) >= 0)
                    .sorted(Comparator.comparingInt((SshHost h) -> score(query, h)).reversed())
                    .toList();
        }
        list.getItems().setAll(shown);
        if (!shown.isEmpty()) {
            list.getSelectionModel().select(0);
        }
    }

    private void onKey(KeyEvent event) {
        switch (event.getCode()) {
            case ESCAPE -> {
                stage.hide();
                event.consume();
            }
            case ENTER -> {
                var selected = list.getSelectionModel().getSelectedItem();
                if (selected != null) {
                    stage.hide();
                    onPick.accept(selected);
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

    private static final class HostCell extends ListCell<SshHost> {
        @Override
        protected void updateItem(SshHost host, boolean empty) {
            super.updateItem(host, empty);
            if (empty || host == null) {
                setGraphic(null);
                return;
            }
            var name = new Label(host.alias());
            name.getStyleClass().add("snippet-name");
            var detail = new Label(host.detail());
            detail.getStyleClass().add("snippet-preview");
            var row = new HBox(10, name, detail);
            row.setAlignment(Pos.CENTER_LEFT);
            setGraphic(row);
        }
    }
}
