package dev.kamil.fabric.tasks;

import dev.kamil.fabric.Dialogs;
import dev.kamil.fabric.session.Database;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.MenuItem;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.stage.Stage;
import javafx.stage.Window;

/** Non-modal window with tasks that have an optional due date, grouped by urgency. */
public final class TasksWindow {

    private static final int DONE_LIMIT = 50;
    private static final DateTimeFormatter CHIP = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH);

    private sealed interface Row permits Header, Item {
    }

    private record Header(String title, boolean overdue, boolean doneToggle) implements Row {
    }

    private record Item(Task task) implements Row {
    }

    private static TasksWindow instance;

    private final Stage window = new Stage();
    private final TaskStore store;
    private final javafx.collections.ObservableList<Row> rows = FXCollections.observableArrayList();
    private final ListView<Row> list = new ListView<>(rows);
    private final TextField input = new TextField();
    private final DatePicker date = new DatePicker();
    private boolean showDone;

    /** Opens the window, or brings the existing one to front. */
    public static void open(Window owner, Database db) {
        if (instance != null) {
            instance.window.show();
            instance.window.setIconified(false);
            instance.window.toFront();
            instance.window.requestFocus();
            return;
        }
        var created = new TasksWindow(owner, db);
        instance = created;
        created.window.setOnHidden(e -> instance = null);
        created.window.show();
        created.input.requestFocus();
    }

    private TasksWindow(Window owner, Database db) {
        store = new TaskStore(db);
        window.initOwner(owner);

        input.setPromptText("New task, press Enter");
        input.setOnAction(e -> add());
        HBox.setHgrow(input, Priority.ALWAYS);
        date.setPromptText("No date");
        date.setPrefWidth(130);
        var today = new Button("Today");
        today.setOnAction(e -> date.setValue(LocalDate.now()));
        var tomorrow = new Button("Tomorrow");
        tomorrow.setOnAction(e -> date.setValue(LocalDate.now().plusDays(1)));
        var noDate = new Button("No date");
        noDate.setOnAction(e -> date.setValue(null));
        var addButton = new Button("Add");
        addButton.setOnAction(e -> add());
        var bar = new HBox(8, input, date, today, tomorrow, noDate, addButton);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setPadding(new Insets(8));

        list.setCellFactory(v -> new TaskCell());
        list.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.DELETE && list.getSelectionModel().getSelectedItem() instanceof Item item) {
                Dialogs.attempt(window, "Cannot delete the task", () -> store.delete(item.task().id()));
                refresh();
            }
        });

        var root = new BorderPane(list);
        root.setTop(bar);
        var scene = new Scene(root, 700, 600);
        scene.getStylesheets().add(TasksWindow.class.getResource("/app.css").toExternalForm());
        window.setScene(scene);
        refresh();
    }

    private void add() {
        var text = input.getText().strip();
        if (text.isEmpty()) {
            return;
        }
        if (!Dialogs.attempt(window, "Cannot save the task", () -> store.add(text, date.getValue()))) {
            return;
        }
        input.clear();
        input.requestFocus();
        refresh();
    }

    private void refresh() {
        var today = LocalDate.now();
        var overdue = new ArrayList<Row>();
        var dueToday = new ArrayList<Row>();
        var later = new ArrayList<Row>();
        var undated = new ArrayList<Row>();
        for (var task : store.open()) {
            var item = new Item(task);
            if (task.due() == null) {
                undated.add(item);
            } else if (task.due().isBefore(today)) {
                overdue.add(item);
            } else if (task.due().isEqual(today)) {
                dueToday.add(item);
            } else {
                later.add(item);
            }
        }
        var all = new ArrayList<Row>();
        section(all, new Header("Overdue", true, false), overdue);
        section(all, new Header("Today", false, false), dueToday);
        section(all, new Header("Later", false, false), later);
        section(all, new Header("No date", false, false), undated);
        var done = showDone ? store.doneRecent(DONE_LIMIT) : List.<Task>of();
        var doneCount = showDone ? done.size() : store.doneRecent(DONE_LIMIT).size();
        all.add(new Header((showDone ? "▾ " : "▸ ") + "Done (" + doneCount + ")", false, true));
        done.forEach(t -> all.add(new Item(t)));
        rows.setAll(all);

        var count = store.dueCount(today);
        var title = new StringBuilder("Tasks");
        if (count.overdue() > 0 || count.today() > 0) {
            title.append(" — ").append(count.overdue()).append(" overdue, ").append(count.today()).append(" today");
        }
        window.setTitle(title.toString());
    }

    private static void section(List<Row> all, Header header, List<Row> items) {
        if (!items.isEmpty()) {
            all.add(header);
            all.addAll(items);
        }
    }

    private void changeDate(Task task) {
        var picker = new DatePicker(task.due());
        picker.setPromptText("No date");
        var apply = new ButtonType("Change", ButtonBar.ButtonData.OK_DONE);
        var dialog = new Dialog<Boolean>();
        dialog.setTitle("FabricManager");
        dialog.setHeaderText("Due date");
        dialog.getDialogPane().setContent(picker);
        dialog.getDialogPane().getButtonTypes().addAll(apply, ButtonType.CANCEL);
        Dialogs.style(dialog.getDialogPane());
        dialog.initOwner(window);
        dialog.setResultConverter(b -> b == apply);
        if (dialog.showAndWait().orElse(false)) {
            Dialogs.attempt(window, "Cannot change the date", () -> store.setDue(task.id(), picker.getValue()));
            refresh();
        }
    }

    /** Cell for either a section header or a task. */
    private final class TaskCell extends ListCell<Row> {
        private final CheckBox check = new CheckBox();
        private final Label text = new Label();
        private final Label chip = new Label();
        private final Region spacer = new Region();
        private final HBox box = new HBox(8, check, text, spacer, chip);
        private final Label header = new Label();
        private Task task;

        TaskCell() {
            text.setWrapText(true);
            text.setMaxWidth(Double.MAX_VALUE);
            HBox.setHgrow(spacer, Priority.ALWAYS);
            box.setAlignment(Pos.CENTER_LEFT);
            check.setOnAction(e -> {
                if (task == null) {
                    return;
                }
                Dialogs.attempt(window, "Cannot update the task", () -> {
                    if (check.isSelected()) {
                        store.done(task.id());
                    } else {
                        store.undo(task.id());
                    }
                });
                refresh();
            });
            var change = new MenuItem("Change date…");
            change.setOnAction(e -> {
                if (task != null) {
                    changeDate(task);
                }
            });
            var delete = new MenuItem("Delete");
            delete.setOnAction(e -> {
                if (task != null) {
                    Dialogs.attempt(window, "Cannot delete the task", () -> store.delete(task.id()));
                    refresh();
                }
            });
            var menu = new ContextMenu(change, delete);
            setOnContextMenuRequested(e -> {
                if (task == null) {
                    e.consume();
                }
            });
            setContextMenu(menu);
            setOnMouseClicked(e -> {
                if (getItem() instanceof Header h && h.doneToggle()) {
                    showDone = !showDone;
                    refresh();
                }
            });
        }

        @Override
        protected void updateItem(Row row, boolean empty) {
            super.updateItem(row, empty);
            task = null;
            header.getStyleClass().removeAll("task-header", "task-header-overdue");
            chip.getStyleClass().removeAll("task-date", "task-date-overdue");
            box.getStyleClass().remove("task-done");
            if (empty || row == null) {
                setGraphic(null);
                return;
            }
            if (row instanceof Header h) {
                header.setText(h.title());
                header.getStyleClass().add("task-header");
                if (h.overdue()) {
                    header.getStyleClass().add("task-header-overdue");
                }
                setGraphic(header);
                return;
            }
            task = ((Item) row).task();
            text.setText(task.text());
            check.setSelected(task.done());
            chip.setText(task.due() == null ? "" : CHIP.format(task.due()));
            chip.setVisible(task.due() != null);
            chip.getStyleClass().add("task-date");
            if (!task.done() && task.due() != null && task.due().isBefore(LocalDate.now())) {
                chip.getStyleClass().add("task-date-overdue");
            }
            if (task.done()) {
                box.getStyleClass().add("task-done");
            }
            box.setPrefWidth(getListView().getWidth() - 24);
            box.prefWidthProperty().bind(getListView().widthProperty().subtract(24));
            setGraphic(box);
        }
    }
}
