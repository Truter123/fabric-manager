package dev.kamil.fabric;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.kamil.fabric.notes.NoteStore;
import dev.kamil.fabric.notes.NotesWindow;
import dev.kamil.fabric.quotes.QuoteStore;
import dev.kamil.fabric.quotes.QuotesWindow;
import dev.kamil.fabric.session.Account;
import dev.kamil.fabric.session.Database;
import dev.kamil.fabric.settings.AppSettings;
import dev.kamil.fabric.settings.SettingsWindow;
import dev.kamil.fabric.tasks.TaskStore;
import dev.kamil.fabric.tasks.TasksWindow;
import java.awt.image.BufferedImage;
import java.io.File;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import javafx.application.Platform;
import javafx.css.PseudoClass;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Control;
import javafx.scene.control.IndexedCell;
import javafx.scene.control.Labeled;
import javafx.scene.control.ListView;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.layout.Background;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.paint.Paint;
import javafx.scene.text.Text;
import javafx.stage.Stage;
import javafx.stage.Window;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Guard: a selected row of every list, table and tree must stay readable (WCAG contrast >= 4.5) whether the control
 * is focused or not. Real windows are opened where a public seam exists; the scoped style contexts of app.css
 * (history, picker, branch popup, settings groups, claude settings, combo popup) are rebuilt with the same classes.
 * Writes snapshots to /tmp/fm-selection for a visual check.
 */
class SelectionContrastE2ETest {

    private static final double MIN = 4.5;
    private static final PseudoClass FOCUSED = PseudoClass.getPseudoClass("focused");
    private static final Path SNAPSHOTS = Path.of("/tmp/fm-selection");

    @TempDir
    Path dir;

    private final List<String> report = new ArrayList<>();
    private final List<String> failures = new ArrayList<>();

    @BeforeAll
    static void startFx() {
        Assumptions.assumeTrue(FxHarness.displayAvailable(),
                "no display");
        try {
            Platform.startup(() -> { });
        } catch (IllegalStateException alreadyStarted) {
            // another test class started it
        }
        Platform.setImplicitExit(false);
    }

    @AfterEach
    void closeAll() throws Exception {
        fx(() -> {
            for (var w : new ArrayList<>(Window.getWindows())) {
                w.hide();
            }
            return null;
        });
        report.forEach(System.out::println);
    }

    // ---- real windows

    @Test
    void realWindows() throws Exception {
        var home = dir.resolve("home");
        java.nio.file.Files.createDirectories(home);
        var db = Database.at(dir.resolve("db")).migrate();
        var tasks = new TaskStore(db);
        tasks.add("one", LocalDate.now());
        tasks.add("two", LocalDate.now().plusDays(1));
        tasks.add("three", LocalDate.now().minusDays(1));
        var quotes = new QuoteStore(db);
        quotes.add("First quote", "Ann");
        quotes.add("Second quote", "Bob");
        quotes.add("Third quote", "Cy");
        var notes = new NoteStore(db);
        notes.create("Alpha", "body one", false);
        notes.create("Beta", "body two", false);
        notes.add("inbox item");

        checkWindow("Tasks", () -> TasksWindow.open(null, db));
        checkWindow("Quotes", () -> QuotesWindow.open(null, db, () -> { }));
        checkWindow("Notes", () -> NotesWindow.open(null, db));
        checkWindow("Settings", () -> SettingsWindow.open(null, new AppSettings(db), dir.resolve("settings"),
                Account.personal(home)));
        finish();
    }

    // ---- scoped contexts of app.css

    @Test
    void scopedContexts() throws Exception {
        checkContext("plain-list", null, c -> list(8));
        checkContext("plain-table", null, c -> table());
        checkContext("plain-tree", null, c -> tree());
        for (var scope : List.of("history", "picker", "branch-popup", "settings-groups", "claude-settings",
                "agents-panel", "sidebar")) {
            checkContext(scope + "-list", scope, c -> list(8));
        }
        for (var scope : List.of("history", "claude-settings", "picker")) {
            checkContext(scope + "-table", scope, c -> table());
            checkContext(scope + "-tree", scope, c -> tree());
        }
        finish();
    }

    @Test
    void comboPopup() throws Exception {
        for (var scope : new String[] {null, "history", "picker"}) {
            var name = "combo-popup" + (scope == null ? "" : "-" + scope);
            fx(() -> {
                var box = new ComboBox<String>();
                box.getItems().addAll("alpha", "beta", "gamma", "delta");
                box.getSelectionModel().select(0);
                var stage = stage(scope, new VBox(box));
                stage.show();
                box.show();
                return null;
            });
            pulse();
            var found = fx(() -> {
                for (var w : new ArrayList<>(Window.getWindows())) {
                    if (!(w instanceof Stage) && w.isShowing() && w.getScene() != null) {
                        for (var n : w.getScene().getRoot().lookupAll(".list-view")) {
                            if (n instanceof ListView<?> lv) {
                                check(name, lv);
                                return true;
                            }
                        }
                    }
                }
                return false;
            });
            assertTrue(found, "no popup list for " + name);
            fx(() -> {
                for (var w : new ArrayList<>(Window.getWindows())) {
                    w.hide();
                }
                return null;
            });
        }
        finish();
    }

    @Test
    void textFieldSelectionHighlight() throws Exception {
        for (var scope : new String[] {null, "history", "picker", "branch-popup"}) {
            var name = "textfield" + (scope == null ? "" : "-" + scope);
            fx(() -> {
                var field = new TextField("selected text");
                var stage = stage(scope, new VBox(field));
                stage.show();
                field.selectAll();
                field.applyCss();
                field.layout();
                var skin = field.getSkin();
                var fill = paint(skin, "highlightFill");
                var text = paint(skin, "highlightTextFill");
                record(name, "-", fill, text, ratio(fill, text));
                return null;
            });
        }
        finish();
    }

    // ---- checking

    private void checkWindow(String name, Runnable open) throws Exception {
        var before = fx(() -> new ArrayList<>(Window.getWindows()));
        fx(() -> {
            open.run();
            return null;
        });
        pulse();
        fx(() -> {
            Window win = null;
            for (var w : Window.getWindows()) {
                if (w.isShowing() && !before.contains(w) && w.getScene() != null) {
                    win = w;
                }
            }
            if (win == null) {
                failures.add(name + ": window did not open");
                return null;
            }
            var root = win.getScene().getRoot();
            root.applyCss();
            root.layout();
            var controls = new ArrayList<Control>();
            for (var sel : List.of(".list-view", ".table-view", ".tree-view")) {
                for (var n : root.lookupAll(sel)) {
                    if (n instanceof Control c) {
                        controls.add(c);
                    }
                }
            }
            int i = 0;
            for (var c : controls) {
                check(name + (controls.size() > 1 ? "#" + (++i) : ""), c);
            }
            if (controls.isEmpty()) {
                failures.add(name + ": no list/table/tree found");
            }
            win.hide();
            return null;
        });
    }

    private void checkContext(String name, String scope, java.util.function.Function<Void, Control> make)
            throws Exception {
        fx(() -> {
            var control = make.apply(null);
            var stage = stage(scope, new BorderPane(control));
            stage.show();
            check(name, control);
            stage.hide();
            return null;
        });
    }

    private Stage stage(String scope, Parent content) {
        var holder = new BorderPane(content);
        if (scope != null) {
            holder.getStyleClass().add(scope);
        }
        var scene = new Scene(holder, 420, 320);
        scene.getStylesheets().add(SelectionContrastE2ETest.class.getResource("/app.css").toExternalForm());
        var stage = new Stage();
        stage.setScene(scene);
        return stage;
    }

    private static ListView<String> list(int n) {
        var lv = new ListView<String>();
        for (int i = 0; i < n; i++) {
            lv.getItems().add("Row number " + i);
        }
        return lv;
    }

    private static TableView<String> table() {
        var tv = new TableView<String>();
        var col = new TableColumn<String, String>("Name");
        col.setCellValueFactory(c -> new javafx.beans.property.ReadOnlyStringWrapper(c.getValue()));
        col.setPrefWidth(200);
        var col2 = new TableColumn<String, String>("Other");
        col2.setCellValueFactory(c -> new javafx.beans.property.ReadOnlyStringWrapper("x " + c.getValue()));
        tv.getColumns().add(col);
        tv.getColumns().add(col2);
        for (int i = 0; i < 8; i++) {
            tv.getItems().add("Row " + i);
        }
        return tv;
    }

    private static TreeView<String> tree() {
        var root = new TreeItem<>("root");
        for (int i = 0; i < 6; i++) {
            var child = new TreeItem<>("Child " + i);
            child.getChildren().add(new TreeItem<>("Leaf " + i));
            root.getChildren().add(child);
        }
        root.setExpanded(true);
        var tv = new TreeView<>(root);
        tv.setShowRoot(true);
        return tv;
    }

    /** On the FX thread, control inside a shown scene: selects row 0, checks focused and unfocused. */
    private void check(String name, Control control) {
        var scene = control.getScene();
        var root = scene.getRoot();
        int rows = rowCount(control);
        if (control.getWidth() == 0 || control.getHeight() == 0) {
            report.add(String.format("%-28s skipped: control not laid out (hidden pane)", name));
            return;
        }
        if (rows == 0) {
            failures.add(name + ": no rows to select");
            return;
        }
        select(control, 0);
        double[] ratios = new double[2];
        for (int state = 0; state < 2; state++) {
            boolean focused = state == 0;
            applyFocus(control, focused);
            root.applyCss();
            root.layout();
            applyFocus(control, focused);
            root.applyCss();
            root.layout();
            var cell = selectedCell(control);
            if (cell == null) {
                failures.add(name + ": no selected cell " + control.getClass().getSimpleName() + " " + control.getStyleClass()
                        + " visible=" + control.isVisible() + " size=" + control.getWidth() + "x" + control.getHeight());
                return;
            }
            var worst = measure(cell);
            ratios[state] = worst.ratio();
            record(name, focused ? "focused" : "unfocused", worst.bg(), worst.fg(), worst.ratio());
            if (worst.ratio() < MIN) {
                failures.add(name + " " + (focused ? "focused" : "unfocused") + " selected ratio "
                        + String.format("%.2f", worst.ratio()));
            }
            snapshot(name + (focused ? "" : "-unfocused"), control);
            // normal rows: row 1 and 2 (even/odd) while row 0 stays selected
            for (int r = 1; r <= 2 && r < rows; r++) {
                var normal = cellAt(control, r);
                if (normal != null) {
                    var m = measure(normal);
                    if (m.ratio() < MIN) {
                        failures.add(name + " normal row " + r + " ratio " + String.format("%.2f", m.ratio()));
                    }
                    if (state == 0 && r == 1) {
                        record(name, "normal", m.bg(), m.fg(), m.ratio());
                    }
                }
            }
        }
    }

    private static void applyFocus(Control control, boolean focused) {
        control.pseudoClassStateChanged(FOCUSED, focused);
        var cell = selectedCell(control);
        if (cell != null) {
            cell.pseudoClassStateChanged(FOCUSED, focused);
        }
    }

    private static int rowCount(Control c) {
        if (c instanceof ListView<?> l) {
            return l.getItems().size();
        }
        if (c instanceof TableView<?> t) {
            return t.getItems().size();
        }
        if (c instanceof TreeView<?> t) {
            return t.getExpandedItemCount();
        }
        return 0;
    }

    private static void select(Control c, int i) {
        if (c instanceof ListView<?> l) {
            l.getSelectionModel().clearAndSelect(i);
        } else if (c instanceof TableView<?> t) {
            t.getSelectionModel().clearAndSelect(i);
        } else if (c instanceof TreeView<?> t) {
            t.getSelectionModel().clearAndSelect(i);
        }
        c.applyCss();
        c.layout();
    }

    private static IndexedCell<?> selectedCell(Control c) {
        for (var cell : cells(c)) {
            if (cell.isSelected() && !cell.isEmpty()) {
                return cell;
            }
        }
        return null;
    }

    private static IndexedCell<?> cellAt(Control c, int index) {
        for (var cell : cells(c)) {
            if (cell.getIndex() == index && !cell.isEmpty()) {
                return cell;
            }
        }
        return null;
    }

    private static List<IndexedCell<?>> cells(Control c) {
        var out = new ArrayList<IndexedCell<?>>();
        for (var sel : List.of(".list-cell", ".table-row-cell", ".tree-cell")) {
            for (var n : c.lookupAll(sel)) {
                if (n instanceof IndexedCell<?> cell) {
                    out.add(cell);
                }
            }
        }
        return out;
    }

    private record Measure(Color bg, Color fg, double ratio) { }

    /** Worst contrast over every text inside the cell (and the cell's own text), on its effective background. */
    private static Measure measure(IndexedCell<?> cell) {
        Measure worst = null;
        var texts = new ArrayList<Node>();
        for (var n : cell.lookupAll(".text")) {
            if (n instanceof Text t && t.isVisible() && !t.getText().isBlank()) {
                texts.add(t);
            }
        }
        for (var n : cell.lookupAll(".label")) {
            if (n instanceof Labeled l && l.isVisible() && l.getText() != null && !l.getText().isBlank()
                    && l.lookup(".text") == null) {
                texts.add(l);
            }
        }
        for (var n : texts) {
            Paint p = n instanceof Text t ? t.getFill() : ((Labeled) n).getTextFill();
            if (!(p instanceof Color fg)) {
                continue;
            }
            var bg = effectiveBackground(n);
            var over = over(fg, bg);
            var m = new Measure(bg, over, ratio(bg, over));
            if (worst == null || m.ratio() < worst.ratio()) {
                worst = m;
            }
        }
        if (worst == null) {
            throw new AssertionError("no text in cell " + cell.getIndex());
        }
        return worst;
    }

    private static Color effectiveBackground(Node node) {
        var layers = new ArrayList<Color>();
        for (Node n = node; n != null; n = n.getParent()) {
            if (n instanceof Region r && r.getBackground() != null) {
                Background b = r.getBackground();
                for (int i = b.getFills().size() - 1; i >= 0; i--) {
                    if (b.getFills().get(i).getFill() instanceof Color c) {
                        layers.add(c);
                        if (c.getOpacity() >= 0.999) {
                            return composite(layers);
                        }
                    }
                }
            }
        }
        layers.add(Color.web("#141211"));
        return composite(layers);
    }

    /** layers: topmost first; the last one is opaque. */
    private static Color composite(List<Color> layers) {
        Color acc = layers.get(layers.size() - 1);
        for (int i = layers.size() - 2; i >= 0; i--) {
            acc = over(layers.get(i), acc);
        }
        return acc;
    }

    private static Color over(Color top, Color below) {
        double a = top.getOpacity();
        return new Color(top.getRed() * a + below.getRed() * (1 - a), top.getGreen() * a + below.getGreen() * (1 - a),
                top.getBlue() * a + below.getBlue() * (1 - a), 1);
    }

    private static double lum(Color c) {
        return 0.2126 * lin(c.getRed()) + 0.7152 * lin(c.getGreen()) + 0.0722 * lin(c.getBlue());
    }

    private static double lin(double v) {
        return v <= 0.03928 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4);
    }

    private static double ratio(Paint a, Paint b) {
        double l1 = lum((Color) a);
        double l2 = lum((Color) b);
        return (Math.max(l1, l2) + 0.05) / (Math.min(l1, l2) + 0.05);
    }

    private static Paint paint(Object skin, String field) {
        for (Class<?> k = skin.getClass(); k != null; k = k.getSuperclass()) {
            try {
                Field f = k.getDeclaredField(field);
                f.setAccessible(true);
                var prop = (javafx.beans.value.ObservableValue<?>) f.get(skin);
                return (Paint) prop.getValue();
            } catch (NoSuchFieldException e) {
                // keep climbing
            } catch (ReflectiveOperationException | RuntimeException e) {
                throw new AssertionError("cannot read " + field, e);
            }
        }
        throw new AssertionError("no field " + field);
    }

    private void record(String name, String state, Paint bg, Paint fg, double ratio) {
        report.add(String.format("%-28s %-9s bg=%s fg=%s ratio=%.2f", name, state, hex(bg), hex(fg), ratio));
        if ("-".equals(state) && ratio < MIN) {
            failures.add(name + " highlight ratio " + String.format("%.2f", ratio));
        }
    }

    private static String hex(Paint p) {
        return p instanceof Color c ? c.toString() : String.valueOf(p);
    }

    private void snapshot(String name, Control control) {
        try {
            var image = control.getScene().getRoot().snapshot(null, null);
            var w = (int) image.getWidth();
            var h = (int) image.getHeight();
            var out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
            var reader = image.getPixelReader();
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    out.setRGB(x, y, reader.getArgb(x, y));
                }
            }
            java.nio.file.Files.createDirectories(SNAPSHOTS);
            ImageIO.write(out, "png", new File(SNAPSHOTS.toFile(), name.replace('#', '-') + ".png"));
        } catch (Exception e) {
            failures.add(name + ": snapshot failed " + e);
        }
    }

    private void finish() {
        report.forEach(System.out::println);
        report.clear();
        assertTrue(failures.isEmpty(), "contrast failures:\n" + String.join("\n", failures));
        assertFalse(false);
    }

    // ---- harness

    private static <T> T fx(Callable<T> action) throws Exception {
        var future = new CompletableFuture<T>();
        Platform.runLater(() -> {
            try {
                future.complete(action.call());
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        });
        return future.get(20, TimeUnit.SECONDS);
    }

    private static void pulse() throws Exception {
        fx(() -> null);
        fx(() -> null);
        Thread.sleep(150);
        fx(() -> null);
    }
}
