package dev.kamil.fabric.session;

import dev.kamil.fabric.git.CloneDialog;
import dev.kamil.fabric.Dialogs;
import dev.kamil.fabric.UndoBar;
import java.nio.file.Path;
import java.util.List;
import javafx.beans.binding.Bindings;
import javafx.beans.binding.BooleanBinding;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.Window;

/** Dialog that asks for a project folder and the tool to run in it. */
public final class ProjectPicker extends Dialog<ProjectPicker.Choice> {

    /** What the user picked. */
    public record Choice(Path directory, Tool tool, Account account, SavedSession resume) {
        Choice(Path directory, Tool tool, Account account) {
            this(directory, tool, account, null);
        }
    }

    private static final int SESSION_LIMIT = 20;

    private static final Tool CUSTOM_ENTRY = new Tool("custom…", null);

    private final ListView<Path> list = new ListView<>();
    private final ComboBox<Tool> toolBox = new ComboBox<>();
    private final ComboBox<Account> accountBox = new ComboBox<>();
    private final TextField customField = new TextField();
    private final ListView<SavedSession> sessions = new ListView<>();

    public ProjectPicker(Window owner, RecentProjects recent, SessionStore store, java.util.Set<String> openIds) {
        initOwner(owner);
        setTitle("Open project");
        var pane = getDialogPane();
        Dialogs.style(pane);
        var open = new ButtonType("Open", ButtonBar.ButtonData.OK_DONE);
        pane.getButtonTypes().addAll(open, ButtonType.CANCEL);

        list.getItems().setAll(recent.projects());
        list.setCellFactory(v -> new ProjectCell());
        list.setPrefHeight(220);
        list.setMinHeight(160);
        var empty = new Label("No recent projects yet.\nClick Browse… to pick a folder.");
        empty.getStyleClass().add("project-path");
        list.setPlaceholder(empty);
        if (!list.getItems().isEmpty()) {
            list.getSelectionModel().select(0);
        }
        list.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2 && list.getSelectionModel().getSelectedItem() != null) {
                ((Button) pane.lookupButton(open)).fire();
            }
        });

        var browse = new Button("Browse…");
        browse.setOnAction(e -> browse(owner));
        var cloneButton = new Button("Clone…");
        cloneButton.setOnAction(e -> clone(owner));
        var folderButtons = new HBox(8, browse, cloneButton);

        var tools = new java.util.ArrayList<>(Tool.available());
        tools.add(CUSTOM_ENTRY);
        toolBox.getItems().setAll(tools);
        toolBox.setCellFactory(v -> toolCell());
        toolBox.setButtonCell(toolCell());
        toolBox.getSelectionModel().select(initialTool(recent, tools));
        customField.setPromptText("command, e.g. claude --resume");
        recent.lastTool().filter(t -> "custom".equals(t.name())).ifPresent(t -> customField.setText(t.command()));
        var customSelected = Bindings.createBooleanBinding(
                () -> toolBox.getValue() == CUSTOM_ENTRY, toolBox.valueProperty());
        customField.visibleProperty().bind(customSelected);
        customField.managedProperty().bind(customSelected);

        var home = Path.of(System.getProperty("user.home"));
        var accounts = Account.available(home);
        accountBox.getItems().setAll(accounts);
        accountBox.setCellFactory(v -> accountCell());
        accountBox.setButtonCell(accountCell());
        accountBox.setValue(Account.personal(home));
        var accountLabel = new Label("Account");
        var manyAccounts = accounts.size() > 1;
        accountLabel.setVisible(manyAccounts);
        accountLabel.setManaged(manyAccounts);
        accountBox.setVisible(manyAccounts);
        accountBox.setManaged(manyAccounts);
        list.getSelectionModel().selectedItemProperty().addListener((o, was, now) -> {
            if (now != null) {
                accountBox.setValue(recent.accountFor(now));
            }
        });
        if (list.getSelectionModel().getSelectedItem() != null) {
            accountBox.setValue(recent.accountFor(list.getSelectionModel().getSelectedItem()));
        }

        sessions.setCellFactory(v -> new SessionCell());
        sessions.setPrefHeight(150);
        sessions.setMinHeight(90);
        var noSessions = new Label("No earlier sessions.");
        noSessions.getStyleClass().add("project-path");
        sessions.setPlaceholder(noSessions);
        var resume = new Button("Resume");
        resume.disableProperty().bind(sessions.getSelectionModel().selectedItemProperty().isNull());
        resume.setOnAction(e -> resumeSelected(open));
        sessions.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2 && sessions.getSelectionModel().getSelectedItem() != null) {
                resumeSelected(open);
            }
        });
        Runnable reload = () -> {
            var project = list.getSelectionModel().getSelectedItem();
            var found = project == null ? store.recent(SESSION_LIMIT + openIds.size())
                    : store.recent(project, SESSION_LIMIT + openIds.size());
            sessions.getItems().setAll(found.stream().filter(s -> !openIds.contains(s.id()))
                    .limit(SESSION_LIMIT).toList());
        };
        list.getSelectionModel().selectedItemProperty().addListener((o, was, now) -> reload.run());
        reload.run();
        var clear = new Button("Clear");
        clear.disableProperty().bind(Bindings.isEmpty(sessions.getItems()));
        clear.setOnAction(e -> {
            var ids = sessions.getItems().stream().map(SavedSession::id).toList();
            if (Dialogs.confirm(pane.getScene().getWindow(), "Clear " + ids.size() + " recent sessions?",
                    "They are removed from this list. Pinned sessions stay.", "Clear")) {
                Dialogs.attempt(pane.getScene().getWindow(), "Cannot clear sessions", () -> store.delete(ids));
                reload.run();
            }
        });
        var undoBar = new UndoBar();
        sessions.setOnKeyPressed(e -> {
            var selected = sessions.getSelectionModel().getSelectedItem();
            if (e.getCode() == javafx.scene.input.KeyCode.DELETE && selected != null) {
                var window = pane.getScene().getWindow();
                boolean pinned = store.isPinned(selected.id());
                if (Dialogs.attempt(window, "Cannot remove the session", () -> store.delete(List.of(selected.id())))
                        && !pinned) {
                    undoBar.offer(UndoBar.deleted(selected.title()), () -> {
                        Dialogs.attempt(window, "Cannot restore the session", () -> store.restore(selected));
                        reload.run();
                    });
                }
                reload.run();
                e.consume();
            }
        });
        resume.setTooltip(new javafx.scene.control.Tooltip("Resume the selected session (double-click)"));
        clear.setTooltip(new javafx.scene.control.Tooltip("Remove the listed sessions; Delete removes the selected one"));

        var content = new VBox(8, new Label("Project folder"), list, folderButtons, new Label("Tool"), toolBox, customField,
                accountLabel, accountBox,
                new Label("Recent sessions"), sessions, new HBox(8, resume, clear, undoBar),
                Dialogs.keyHint("Enter open   Delete remove session (in the list)   Esc cancel"));
        content.setPadding(new Insets(12));
        content.setPrefWidth(480);
        pane.setContent(content);

        BooleanBinding invalid = list.getSelectionModel().selectedItemProperty().isNull()
                .or(customSelected.and(Bindings.createBooleanBinding(
                        () -> customField.getText().isBlank(), customField.textProperty())));
        pane.lookupButton(open).disableProperty().bind(invalid);

        // Keyboard starts on the list (arrows + Enter), or on Browse… when there is nothing to pick yet.
        setOnShown(e -> (list.getItems().isEmpty() ? browse : list).requestFocus());

        setResultConverter(button -> {
            if (button != open) {
                return null;
            }
            var tool = toolBox.getValue() == CUSTOM_ENTRY
                    ? Tool.custom(customField.getText().trim()) : toolBox.getValue();
            return new Choice(list.getSelectionModel().getSelectedItem(), tool, accountBox.getValue());
        });
    }

    private void resumeSelected(ButtonType open) {
        var resumed = sessions.getSelectionModel().getSelectedItem();
        setResult(new Choice(resumed.project(), resumed.tool(), resumed.account(), resumed));
        close();
    }

    private static Tool initialTool(RecentProjects recent, List<Tool> tools) {
        var last = recent.lastTool();
        if (last.isPresent()) {
            if ("custom".equals(last.get().name())) {
                return CUSTOM_ENTRY;
            }
            if (tools.contains(last.get())) {
                return last.get();
            }
        }
        return tools.get(0);
    }

    private static ListCell<Tool> toolCell() {
        return new ListCell<>() {
            @Override
            protected void updateItem(Tool item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item.name());
            }
        };
    }

    private static ListCell<Account> accountCell() {
        return new ListCell<>() {
            @Override
            protected void updateItem(Account item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item.name());
            }
        };
    }

    private void clone(Window owner) {
        var selected = list.getSelectionModel().getSelectedItem();
        var first = selected != null ? selected : list.getItems().isEmpty() ? null : list.getItems().get(0);
        var parent = first == null ? null : first.getParent();
        new CloneDialog(owner, parent).showAndWait().ifPresent(path -> {
            list.getItems().remove(path);
            list.getItems().add(0, path);
            list.getSelectionModel().select(0);
        });
    }

    private void browse(Window owner) {
        var chooser = new DirectoryChooser();
        chooser.setTitle("Project folder");
        var selected = list.getSelectionModel().getSelectedItem();
        var initial = selected != null ? selected
                : !list.getItems().isEmpty() ? list.getItems().get(0) : Path.of(System.getProperty("user.home"));
        if (java.nio.file.Files.isDirectory(initial)) {
            chooser.setInitialDirectory(initial.toFile());
        }
        var dir = chooser.showDialog(owner);
        if (dir != null) {
            var path = dir.toPath();
            list.getItems().remove(path);
            list.getItems().add(0, path);
            list.getSelectionModel().select(0);
        }
    }

    /** "5 min ago", "yesterday"; coarse on purpose. */
    static String relativeTime(java.time.Instant then, java.time.Instant now) {
        var minutes = java.time.Duration.between(then, now).toMinutes();
        if (minutes < 1) {
            return "just now";
        }
        if (minutes < 60) {
            return minutes + " min ago";
        }
        var hours = minutes / 60;
        if (hours < 24) {
            return hours + (hours == 1 ? " hour ago" : " hours ago");
        }
        var days = hours / 24;
        return days == 1 ? "yesterday" : days + " days ago";
    }

    /** Title in bold; tool, relative time and last status dim underneath. */
    private static final class SessionCell extends ListCell<SavedSession> {
        @Override
        protected void updateItem(SavedSession item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null) {
                setGraphic(null);
                return;
            }
            var title = new Label(item.title());
            title.getStyleClass().add("project-name");
            var detail = item.tool().name() + "  \u00b7  " + relativeTime(item.startedAt(), java.time.Instant.now())
                    + (item.lastStatus() == null ? "" : "  \u00b7  " + item.lastStatus().toLowerCase());
            var info = new Label(detail);
            info.getStyleClass().add("project-path");
            setGraphic(new VBox(1, title, info));
        }
    }

    /** Folder name in bold, full path dim underneath. */
    private static final class ProjectCell extends ListCell<Path> {
        @Override
        protected void updateItem(Path item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null) {
                setGraphic(null);
                return;
            }
            var name = new Label(item.getFileName() == null ? "/" : item.getFileName().toString());
            name.getStyleClass().add("project-name");
            var full = new Label(item.toString());
            full.getStyleClass().add("project-path");
            setGraphic(new VBox(1, name, full));
        }
    }
}
