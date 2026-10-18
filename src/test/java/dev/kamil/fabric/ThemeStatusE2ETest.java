package dev.kamil.fabric;

import static dev.kamil.fabric.FxHarness.fx;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import javafx.css.PseudoClass;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.IndexedCell;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Guard for the status colours of app.css: the muted grey passes WCAG AA on the hover background, history-meta is
 * muted everywhere, a selected cell carries an accent marker without moving the row, a focused list or tree or table
 * shows an accent ring without resizing, and a focused default or primary button gets a visible ring.
 */
class ThemeStatusE2ETest {

    private static final Color MUTED = Color.web("#9a9390");
    private static final Color ACCENT = Color.web("#ff7a3d");
    private static final Color TEXT = Color.web("#e8e4e1");
    private static final PseudoClass FOCUSED = PseudoClass.getPseudoClass("focused");

    @BeforeAll
    static void startFx() {
        FxHarness.start();
    }

    @AfterEach
    void closeAll() throws Exception {
        fx(() -> {
            for (var w : new ArrayList<>(Window.getWindows())) {
                w.hide();
            }
            return null;
        });
    }

    @Test
    void mutedTextPassesAaOnTheHoverBackground() {
        assertTrue(contrast(MUTED, Color.web("#262220")) >= 4.5);
        assertTrue(contrast(MUTED, Color.web("#141211")) >= 4.5);
    }

    @Test
    void historyMetaIsMutedInsideAndOutsideTheHistoryWindow() throws Exception {
        for (var scope : new String[] {null, "history"}) {
            var color = fx(() -> {
                var label = new Label("2 hidden");
                label.getStyleClass().add("history-meta");
                show(scope, label);
                return label.getTextFill();
            });
            assertEquals(MUTED, color, "history-meta in scope " + scope);
        }
    }

    @Test
    void selectedListAndTreeCellsGetAnAccentMarkerAndKeepTheirWidth() throws Exception {
        for (var scope : new String[] {null, "history", "picker", "branch-popup"}) {
            fx(() -> {
                var list = new ListView<String>();
                list.getItems().addAll("one", "two", "three");
                show(scope, list);
                list.getSelectionModel().clearAndSelect(0);
                settle(list);
                assertMarker("list " + scope, cell(list, 0), cell(list, 1));

                var root = new TreeItem<>("root");
                root.getChildren().addAll(new TreeItem<>("a"), new TreeItem<>("b"));
                root.setExpanded(true);
                var tree = new TreeView<>(root);
                show(scope, tree);
                tree.getSelectionModel().clearAndSelect(1);
                settle(tree);
                assertMarker("tree " + scope, cell(tree, 1), cell(tree, 2));
                return null;
            });
        }
    }

    @Test
    void selectedTableRowGetsAnAccentMarkerWithoutMovingTheColumns() throws Exception {
        for (var scope : new String[] {null, "history"}) {
            fx(() -> {
                var table = new TableView<String>();
                var column = new TableColumn<String, String>("Name");
                column.setCellValueFactory(c -> new javafx.beans.property.ReadOnlyStringWrapper(c.getValue()));
                table.getColumns().add(column);
                table.getItems().addAll("a", "b", "c");
                show(scope, table);
                table.getSelectionModel().clearAndSelect(0);
                settle(table);
                var selected = row(table, 0);
                var normal = row(table, 1);
                var fills = selected.getBackground().getFills();
                var marker = fills.stream().filter(f -> ACCENT.equals(f.getFill())).findFirst().orElse(null);
                assertNotNull(marker, "no accent layer on the selected row, scope " + scope);
                assertEquals(0, marker.getInsets().getLeft(), 0.01);
                // the topmost layer is the row's own background, 3 px in from the left, so the accent shows as a bar
                var top = fills.get(fills.size() - 1);
                assertNotEquals(ACCENT, top.getFill());
                assertEquals(3, top.getInsets().getLeft(), 0.01, "scope " + scope);
                assertTrue(normal.getBackground() == null || normal.getBackground().getFills().stream()
                        .noneMatch(f -> ACCENT.equals(f.getFill())));
                assertEquals(normal.getInsets().getLeft(), selected.getInsets().getLeft(), 0.01);
                return null;
            });
        }
    }

    @Test
    void focusedListTreeAndTableShowAnAccentRingWithoutResizing() throws Exception {
        fx(() -> {
            var list = new ListView<String>();
            list.getItems().add("one");
            var tree = new TreeView<>(new TreeItem<>("root"));
            var table = new TableView<String>();
            var box = new VBox(list, tree, table);
            show("history", box);
            for (Region control : new Region[] {list, tree, table}) {
                var before = control.getInsets().getLeft();
                control.pseudoClassStateChanged(FOCUSED, true);
                control.applyCss();
                var ring = control.getBorder();
                assertNotNull(ring, control.getClass().getSimpleName());
                assertEquals(ACCENT, ring.getStrokes().get(0).getTopStroke(), control.getClass().getSimpleName());
                assertEquals(before, control.getInsets().getLeft(), 0.01, control.getClass().getSimpleName());
                control.pseudoClassStateChanged(FOCUSED, false);
                control.applyCss();
                assertEquals(before, control.getInsets().getLeft(), 0.01, control.getClass().getSimpleName());
            }
            return null;
        });
    }

    @Test
    void focusedDefaultAndPrimaryButtonsShowARing() throws Exception {
        fx(() -> {
            var def = new Button("Save");
            def.setDefaultButton(true);
            var primary = new Button("Add");
            primary.getStyleClass().add("primary");
            var plain = new Button("Cancel");
            show(null, new VBox(def, primary, plain));
            for (var b : new Button[] {def, primary}) {
                b.pseudoClassStateChanged(FOCUSED, false);
                b.applyCss();
                assertEquals(ACCENT, b.getBorder().getStrokes().get(0).getTopStroke(), b.getText() + " idle");
                b.pseudoClassStateChanged(FOCUSED, true);
                b.applyCss();
                assertEquals(TEXT, b.getBorder().getStrokes().get(0).getTopStroke(), b.getText() + " focused");
                assertNotEquals(ACCENT, b.getBorder().getStrokes().get(0).getTopStroke());
            }
            return null;
        });
    }

    // ---- helpers

    private static void assertMarker(String name, IndexedCell<?> selected, IndexedCell<?> normal) {
        assertNotNull(selected, name + ": no selected cell");
        assertNotNull(normal, name + ": no normal cell");
        var marker = selected.getBorder().getStrokes().get(0);
        assertEquals(ACCENT, marker.getLeftStroke(), name);
        assertEquals(3, marker.getWidths().getLeft(), 0.01, name);
        var idle = normal.getBorder().getStrokes().get(0);
        assertNotEquals(ACCENT, idle.getLeftStroke(), name + " idle");
        assertEquals(3, idle.getWidths().getLeft(), 0.01, name + " idle width");
        assertEquals(normal.getInsets().getLeft(), selected.getInsets().getLeft(), 0.01, name + " insets");
    }

    private static void settle(Parent control) {
        control.applyCss();
        control.layout();
    }

    private static IndexedCell<?> cell(Node control, int index) {
        for (var n : control.lookupAll(".indexed-cell")) {
            if (n instanceof IndexedCell<?> c && c.getIndex() == index && !c.isEmpty()) {
                return c;
            }
        }
        return null;
    }

    private static IndexedCell<?> row(TableView<?> table, int index) {
        for (var n : table.lookupAll(".table-row-cell")) {
            if (n instanceof IndexedCell<?> c && c.getIndex() == index) {
                return c;
            }
        }
        return null;
    }

    /** Puts the node in a scene of app.css, inside the scope's style class when given, and lays it out. */
    private static Stage show(String scope, Node content) {
        var holder = new BorderPane(content);
        if (scope != null) {
            holder.getStyleClass().add(scope);
        }
        var scene = new Scene(holder, 420, 480);
        scene.getStylesheets().add(ThemeStatusE2ETest.class.getResource("/app.css").toExternalForm());
        var stage = new Stage();
        stage.setScene(scene);
        stage.show();
        ((Parent) scene.getRoot()).applyCss();
        scene.getRoot().layout();
        return stage;
    }

    private static double contrast(Color a, Color b) {
        double l1 = lum(a);
        double l2 = lum(b);
        return (Math.max(l1, l2) + 0.05) / (Math.min(l1, l2) + 0.05);
    }

    private static double lum(Color c) {
        return 0.2126 * lin(c.getRed()) + 0.7152 * lin(c.getGreen()) + 0.0722 * lin(c.getBlue());
    }

    private static double lin(double v) {
        return v <= 0.03928 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4);
    }
}
