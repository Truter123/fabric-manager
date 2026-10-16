package dev.kamil.fabric.ssh;

import dev.kamil.fabric.Dialogs;
import java.io.File;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.stage.Window;
import dev.kamil.fabric.snippets.Fuzzy;

/** Command-palette popup that picks a host from the ssh config and the hosts added in the app. */
public final class SshPicker {

    private final Stage stage = new Stage(StageStyle.UNDECORATED);
    private final List<SshHost> configHosts;
    private final SshHostStore store;
    private final Consumer<SshEntry> onPick;
    private final TextField filter = new TextField();
    private final ListView<SshEntry> list = new ListView<>();
    private final CheckBox showHidden = new CheckBox("Show hidden");
    private final Button add = new Button("Add");
    private final Button edit = new Button("Edit");
    private final Button remove = new Button("Delete");
    private List<SshEntry> hosts = List.of();
    /** True while a form or dialog has the focus, so losing focus does not close the popup. */
    private boolean dialogOpen;

    public SshPicker(Window owner, List<SshHost> configHosts, SshHostStore store, Consumer<SshEntry> onPick) {
        this.configHosts = configHosts;
        this.store = store;
        this.onPick = onPick;
        stage.initOwner(owner);

        filter.setPromptText("Filter hosts");
        filter.textProperty().addListener((o, a, b) -> refresh());
        list.setCellFactory(v -> new HostCell());
        list.setPlaceholder(new Label("No hosts - press Add"));
        list.setPrefHeight(280);
        VBox.setVgrow(list, Priority.ALWAYS);
        var hint = new Label("Enter connect   Esc close");
        hint.getStyleClass().add("snippet-hint");
        var spacer = new javafx.scene.layout.Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        for (var b : List.of(add, edit, remove)) {
            b.setFocusTraversable(false);
        }
        showHidden.setFocusTraversable(false);
        showHidden.selectedProperty().addListener((o, a, b) -> refresh());
        add.setOnAction(e -> addHost());
        edit.setOnAction(e -> editHost());
        remove.setOnAction(e -> removeHost());
        list.getSelectionModel().selectedItemProperty().addListener((o, a, b) -> updateButtons());
        var actions = new HBox(8, add, edit, remove, spacer, showHidden);
        actions.setAlignment(Pos.CENTER_LEFT);
        var root = new VBox(8, filter, list, actions, hint);
        root.getStyleClass().add("snippet-picker");
        root.setPadding(new Insets(10));
        root.setPrefSize(560, 380);

        var scene = new Scene(root);
        scene.getStylesheets().add(Dialogs.class.getResource("/app.css").toExternalForm());
        scene.getStylesheets().add(SshPicker.class.getResource("/snippets.css").toExternalForm());
        scene.addEventFilter(KeyEvent.KEY_PRESSED, this::onKey);
        stage.setScene(scene);
        stage.focusedProperty().addListener((o, was, now) -> {
            if (!now && !dialogOpen) {
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

    private static int score(String query, SshEntry host) {
        return Fuzzy.score(query, host.alias(), host.detail());
    }

    private void refresh() {
        var query = filter.getText() == null ? "" : filter.getText();
        hosts = SshEntry.merge(configHosts, store.list(), store.hidden(), showHidden.isSelected());
        List<SshEntry> shown;
        if (query.isBlank()) {
            shown = hosts;
        } else {
            shown = hosts.stream()
                    .filter(h -> score(query, h) >= 0)
                    .sorted(Comparator.comparingInt((SshEntry h) -> score(query, h)).reversed())
                    .toList();
        }
        var keep = list.getSelectionModel().getSelectedIndex();
        list.getItems().setAll(shown);
        if (!shown.isEmpty()) {
            list.getSelectionModel().select(Math.max(0, Math.min(keep, shown.size() - 1)));
        }
        updateButtons();
    }

    private void updateButtons() {
        var selected = list.getSelectionModel().getSelectedItem();
        edit.setDisable(selected == null || !selected.fromApp());
        remove.setDisable(selected == null);
        remove.setText(selected == null || selected.fromApp() ? "Delete" : selected.hidden() ? "Unhide" : "Hide");
    }

    private void addHost() {
        form(null);
    }

    private void editHost() {
        var selected = list.getSelectionModel().getSelectedItem();
        if (selected != null && selected.fromApp()) {
            form(selected.app());
        }
    }

    private void removeHost() {
        var selected = list.getSelectionModel().getSelectedItem();
        if (selected == null) {
            return;
        }
        if (selected.fromApp()) {
            dialogOpen = true;
            boolean yes;
            try {
                yes = Dialogs.confirm(stage, "Delete host " + selected.alias() + "?",
                        "It is removed from FabricManager only.", "Delete");
            } finally {
                dialogOpen = false;
            }
            if (yes && Dialogs.attempt(stage, "Cannot delete the host", () -> store.delete(selected.app().id()))) {
                refresh();
            }
        } else if (Dialogs.attempt(stage, "Cannot change the host", () -> {
            if (selected.hidden()) {
                store.unhide(selected.alias());
            } else {
                store.hide(selected.alias());
            }
        })) {
            refresh();
        }
        filter.requestFocus();
    }

    /** Add (existing == null) or edit form; saves into the store. */
    private void form(AppHost existing) {
        var name = new TextField(existing == null ? "" : existing.name());
        var hostName = new TextField(existing == null ? "" : existing.hostName());
        var user = new TextField(existing == null || existing.user() == null ? "" : existing.user());
        var port = new TextField(existing == null ? "22" : String.valueOf(existing.port()));
        var identity = new TextField(existing == null || existing.identity() == null ? "" : existing.identity());
        var browse = new Button("Browse...");
        browse.setOnAction(e -> {
            var chooser = new FileChooser();
            chooser.setTitle("Identity file");
            var ssh = new File(System.getProperty("user.home"), ".ssh");
            if (ssh.isDirectory()) {
                chooser.setInitialDirectory(ssh);
            }
            var file = chooser.showOpenDialog(stage);
            if (file != null) {
                identity.setText(file.getAbsolutePath());
            }
        });
        var grid = new GridPane();
        grid.setHgap(8);
        grid.setVgap(8);
        grid.addRow(0, new Label("Name"), name);
        grid.addRow(1, new Label("HostName"), hostName);
        grid.addRow(2, new Label("User"), user);
        grid.addRow(3, new Label("Port"), port);
        grid.addRow(4, new Label("IdentityFile"), identity, browse);
        GridPane.setHgrow(name, Priority.ALWAYS);
        name.setPrefColumnCount(24);

        var dialog = new Dialog<ButtonType>();
        dialog.setTitle("FabricManager");
        dialog.setHeaderText(existing == null ? "Add SSH host" : "Edit SSH host");
        dialog.getDialogPane().setContent(grid);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        Dialogs.style(dialog.getDialogPane());
        dialog.initOwner(stage);
        dialog.getDialogPane().lookupButton(ButtonType.OK).addEventFilter(javafx.event.ActionEvent.ACTION, ev -> {
            var error = save(existing, name.getText(), hostName.getText(), user.getText(), port.getText(),
                    identity.getText());
            if (error != null) {
                Dialogs.error(dialog.getOwner(), "Cannot save the host", error);
                ev.consume();
            }
        });
        dialogOpen = true;
        try {
            dialog.showAndWait();
        } finally {
            dialogOpen = false;
        }
        refresh();
        stage.requestFocus();
        filter.requestFocus();
    }

    /** Saves the form; returns the problem to show, or null when saved. */
    private String save(AppHost existing, String name, String hostName, String user, String port, String identity) {
        int portNumber;
        try {
            portNumber = port.isBlank() ? 22 : Integer.parseInt(port.strip());
        } catch (NumberFormatException e) {
            return "The port must be a number";
        }
        var clash = configHosts.stream().anyMatch(h -> h.alias().equals(name.strip()));
        if (clash) {
            return "'" + name.strip() + "' is already an alias in ~/.ssh/config";
        }
        try {
            if (existing == null) {
                store.add(name, hostName, user, portNumber, identity);
            } else {
                store.update(existing.id(), name, hostName, user, portNumber, identity);
            }
            return null;
        } catch (IllegalStateException e) {
            return e.getMessage();
        }
    }

    private void onKey(KeyEvent event) {
        switch (event.getCode()) {
            case ESCAPE -> {
                Dialogs.closeLater(stage);
                event.consume();
            }
            case ENTER -> {
                var selected = list.getSelectionModel().getSelectedItem();
                if (selected != null) {
                    Platform.runLater(() -> {
                        stage.hide();
                        onPick.accept(selected);
                    });
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

    private static final class HostCell extends ListCell<SshEntry> {
        @Override
        protected void updateItem(SshEntry host, boolean empty) {
            super.updateItem(host, empty);
            if (empty || host == null) {
                setGraphic(null);
                return;
            }
            var name = new Label(host.alias());
            name.getStyleClass().add("snippet-name");
            var detail = new Label(host.detail());
            detail.getStyleClass().add("snippet-preview");
            var tag = new Label(host.fromApp() ? "app" : host.hidden() ? "config, hidden" : "config");
            tag.getStyleClass().add("snippet-badge");
            var row = new HBox(10, name, detail, tag);
            row.setAlignment(Pos.CENTER_LEFT);
            setGraphic(row);
        }
    }
}
