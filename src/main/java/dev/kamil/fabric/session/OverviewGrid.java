package dev.kamil.fabric.session;

import dev.kamil.fabric.terminal.TerminalTab;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TabPane;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.util.Duration;

/** Overlay with one card per open terminal tab: its state and the last lines on its screen. */
public final class OverviewGrid extends StackPane {

    static final int PREVIEW_LINES = 3;

    /** What one card shows. */
    public record Card(String name, String project, String account, String context, boolean pinned, boolean unseen,
            Color dot, List<String> lines) {

        Card withLines(List<String> other) {
            return new Card(name, project, account, context, pinned, unseen, dot, other);
        }
    }

    private final TabPane tabs;
    private final Function<String, String> contexts;
    private final Runnable onClose;
    private final FlowPane grid = new FlowPane(12, 12);
    private final Label empty = new Label("No sessions. Ctrl+Shift+N to start one.");
    private final Timeline timer = new Timeline(new KeyFrame(Duration.seconds(2), e -> refresh()));
    private final List<Region> cardNodes = new ArrayList<>();
    private final Map<TerminalTab, Entry> entries = new LinkedHashMap<>();

    /** The node of one card with its reusable preview labels and what it last showed. */
    private static final class Entry {
        Card card;
        VBox box;
        final List<Label> labels = new ArrayList<>();
    }

    public OverviewGrid(TabPane tabs, Function<String, String> contexts, Runnable onClose) {
        this.tabs = tabs;
        this.contexts = contexts;
        this.onClose = onClose;
        getStyleClass().add("overview");
        grid.getStyleClass().add("overview-grid");
        grid.setPadding(new javafx.geometry.Insets(24));
        empty.getStyleClass().add("overview-empty");
        var scroll = new ScrollPane(grid);
        scroll.setFitToWidth(true);
        scroll.getStyleClass().add("overview-scroll");
        getChildren().addAll(scroll, empty);
        setVisible(false);
        timer.setCycleCount(Animation.INDEFINITE);
        addEventFilter(KeyEvent.KEY_PRESSED, this::onKey);
        // A click on the empty backdrop closes it.
        setOnMouseClicked(e -> {
            if (e.getTarget() == this || e.getTarget() == grid) {
                close();
            }
        });
    }

    public boolean isOpen() {
        return isVisible();
    }

    public void toggle() {
        if (isOpen()) {
            close();
        } else {
            open();
        }
    }

    public void open() {
        refresh();
        setVisible(true);
        timer.play();
        requestFocusFirst();
    }

    public void close() {
        timer.stop();
        setVisible(false);
        onClose.run();
    }

    private void requestFocusFirst() {
        if (cardNodes.isEmpty()) {
            requestFocus();
        } else {
            cardNodes.get(0).requestFocus();
        }
    }

    /** Rebuilds the cards, keeping the keyboard focus on the same position (FX thread). */
    public void refresh() {
        int focused = -1;
        for (int i = 0; i < cardNodes.size(); i++) {
            if (cardNodes.get(i).isFocused()) {
                focused = i;
            }
        }
        var agents = new ArrayList<AgentsPanel.Agent>();
        var byAgent = new IdentityHashMap<AgentsPanel.Agent, TerminalTab>();
        for (var t : tabs.getTabs()) {
            if (t instanceof TerminalTab tab) {
                var agent = AgentsPanel.toAgent(tab, contexts.apply(tab.sessionId()));
                agents.add(agent);
                byAgent.put(agent, tab);
            }
        }
        var live = new java.util.HashSet<TerminalTab>(byAgent.values());
        entries.keySet().removeIf(t -> !live.contains(t));
        var desired = new ArrayList<Node>();
        cardNodes.clear();
        for (var agent : AgentsPanel.sorted(agents)) {
            var tab = byAgent.get(agent);
            var next = card(agent, tab, trim(tab.screenLines(), PREVIEW_LINES));
            var entry = entries.get(tab);
            if (entry == null || !sameHeader(entry.card, next)) {
                entry = build(next, tab);
                entries.put(tab, entry);
            } else if (!entry.card.lines().equals(next.lines())) {
                updateLines(entry, next.lines());
                entry.card = next;
            }
            cardNodes.add(entry.box);
            desired.add(entry.box);
        }
        if (!grid.getChildren().equals(desired)) {
            grid.getChildren().setAll(desired);
        }
        empty.setVisible(cardNodes.isEmpty());
        if (focused >= 0 && !cardNodes.isEmpty()) {
            cardNodes.get(Math.min(focused, cardNodes.size() - 1)).requestFocus();
        } else if (isVisible() && focused < 0 && !cardNodes.isEmpty()) {
            requestFocusFirst();
        }
    }

    private static Card card(AgentsPanel.Agent agent, TerminalTab tab, List<String> lines) {
        Color dot;
        if (agent.exited()) {
            dot = Color.web("#d96b75");
        } else if (tab.cachedStatus() == TerminalTab.Status.SHELL) {
            dot = Color.web("#5c5653");
        } else {
            dot = SessionSidebar.runningColor(agent.activity());
        }
        return new Card(agent.name(), agent.project(), agent.account(), agent.context(), tab.pinned(),
                agent.unseen(), dot, lines);
    }

    private static boolean sameHeader(Card a, Card b) {
        return a.withLines(List.of()).equals(b.withLines(List.of()));
    }

    /** Sets the preview labels to the lines, growing or shrinking the fixed label set. */
    private static void updateLines(Entry entry, List<String> lines) {
        while (entry.labels.size() > lines.size()) {
            var label = entry.labels.remove(entry.labels.size() - 1);
            entry.box.getChildren().remove(label);
        }
        for (int i = 0; i < lines.size(); i++) {
            if (i == entry.labels.size()) {
                var label = new Label();
                label.getStyleClass().add("overview-line");
                label.setMaxWidth(Double.MAX_VALUE);
                label.setTextOverrun(javafx.scene.control.OverrunStyle.ELLIPSIS);
                entry.labels.add(label);
                entry.box.getChildren().add(label);
            }
            entry.labels.get(i).setText(lines.get(i));
        }
    }

    private Entry build(Card card, TerminalTab tab) {
        var name = new Label(card.name());
        name.getStyleClass().add("overview-name");
        if (card.unseen()) {
            name.getStyleClass().add("overview-unseen");
        }
        var spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        var top = new HBox(8, new Circle(5, card.dot()), name, spacer);
        top.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        if (card.pinned()) {
            top.getChildren().add(new Label("📌"));
        }
        if (card.unseen()) {
            top.getChildren().add(new Circle(4, Color.web("#ff7a3d")));
        }
        var box = new VBox(4, top);
        var meta = meta(card);
        if (!meta.isBlank()) {
            var label = new Label(meta);
            label.getStyleClass().add("overview-meta");
            box.getChildren().add(label);
        }
        box.getStyleClass().add("overview-card");
        box.setPrefSize(320, 140);
        box.setMinSize(320, 140);
        box.setMaxSize(320, 140);
        box.setFocusTraversable(true);
        box.setOnMouseClicked(e -> {
            if (e.getButton() == MouseButton.PRIMARY) {
                jump(tab);
            }
        });
        box.setUserData(tab);
        var entry = new Entry();
        entry.box = box;
        entry.card = card;
        updateLines(entry, card.lines());
        return entry;
    }

    private void jump(TerminalTab tab) {
        tabs.getSelectionModel().select(tab);
        close();
        tab.focus();
    }

    private void onKey(KeyEvent e) {
        if (!isOpen()) {
            return;
        }
        int focused = -1;
        for (int i = 0; i < cardNodes.size(); i++) {
            if (cardNodes.get(i).isFocused()) {
                focused = i;
            }
        }
        if (e.getCode() == KeyCode.ESCAPE) {
            close();
            e.consume();
            return;
        }
        int digit = keyToIndex(e.getText(), cardNodes.size());
        if (digit >= 0) {
            jump((TerminalTab) cardNodes.get(digit).getUserData());
            e.consume();
            return;
        }
        if (e.getCode() == KeyCode.ENTER && focused >= 0) {
            jump((TerminalTab) cardNodes.get(focused).getUserData());
            e.consume();
            return;
        }
        int next = move(e.getCode(), focused, cardNodes.size(), columns());
        if (next != focused && next >= 0) {
            cardNodes.get(next).requestFocus();
        }
        if (next != -2) {
            e.consume();
        }
    }

    private int columns() {
        return Math.max(1, (int) ((grid.getWidth() - 48 + 12) / (320 + 12)));
    }

    // ---- pure logic ----

    /** The last {@code n} non-blank lines, trailing spaces cut, top to bottom. */
    static List<String> trim(List<String> screen, int n) {
        var out = new ArrayList<String>();
        for (int i = screen.size() - 1; i >= 0 && out.size() < n; i--) {
            var line = screen.get(i).stripTrailing();
            if (!line.isBlank()) {
                out.add(0, line);
            }
        }
        return out;
    }

    /** "1".."9" becomes 0..8 when a card with that number exists; otherwise -1. */
    static int keyToIndex(String text, int cards) {
        if (text == null || text.length() != 1 || text.charAt(0) < '1' || text.charAt(0) > '9') {
            return -1;
        }
        int index = text.charAt(0) - '1';
        return index < cards ? index : -1;
    }

    /** Next focused index for an arrow key; -2 when the key is not an arrow, otherwise clamped to the cards. */
    static int move(KeyCode code, int current, int cards, int columns) {
        if (cards == 0) {
            return code == null ? -2 : switch (code) {
                case LEFT, RIGHT, UP, DOWN -> -1;
                default -> -2;
            };
        }
        int from = Math.max(current, 0);
        int to = switch (code) {
            case LEFT -> from - 1;
            case RIGHT -> from + 1;
            case UP -> from - columns;
            case DOWN -> from + columns;
            default -> -2;
        };
        if (to == -2) {
            return -2;
        }
        return current < 0 ? 0 : Math.max(0, Math.min(cards - 1, to));
    }

    /** "project · account · ctx 34%" without blanks or a project equal to the name. */
    static String meta(Card card) {
        var parts = new ArrayList<String>();
        if (card.project() != null && !card.project().isBlank() && !card.project().equals(card.name())) {
            parts.add(card.project());
        }
        if (card.account() != null && !card.account().isBlank()) {
            parts.add(card.account());
        }
        var ctx = AgentsPanel.percent(card.context());
        if (!ctx.isBlank()) {
            parts.add(ctx);
        }
        return String.join(" · ", parts);
    }
}
