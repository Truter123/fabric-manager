package dev.kamil.fabric.git;

import dev.kamil.fabric.Dialogs;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.OptionalDouble;
import java.util.function.BiConsumer;
import java.util.regex.Pattern;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.event.ActionEvent;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.input.Clipboard;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.Window;

/** Dialog that clones a git repository into a parent folder, showing live progress. */
public final class CloneDialog extends Dialog<Path> {

    private static final Pattern PERCENT = Pattern.compile("^(Receiving objects|Resolving deltas):\\s+(\\d+)%");
    private static final int MAX_LOG_LINES = 300;

    private final TextField urlField = new TextField();
    private final TextField parentField = new TextField();
    private final Button choose = new Button("Choose…");
    private final Label target = new Label();
    private final Label status = new Label();
    private final ProgressBar bar = new ProgressBar(0);
    private final TextArea log = new TextArea();
    private final SimpleBooleanProperty running = new SimpleBooleanProperty(false);
    private final List<String> lines = new ArrayList<>();
    private boolean lastWasProgress;
    private volatile Process process;
    private volatile boolean cancelled;
    private volatile boolean createdByUs;
    private Path cloneTarget;

    public CloneDialog(Window owner, Path defaultParent) {
        initOwner(owner);
        setTitle("Clone repository");
        var pane = getDialogPane();
        Dialogs.style(pane);
        var clone = new ButtonType("Clone", ButtonBar.ButtonData.OK_DONE);
        pane.getButtonTypes().addAll(clone, ButtonType.CANCEL);

        urlField.setPromptText("https://github.com/owner/repo.git or git@github.com:owner/repo.git");
        clipboardUrl().ifPresent(urlField::setText);
        parentField.setText(defaultParent(defaultParent).toString());
        HBox.setHgrow(parentField, Priority.ALWAYS);
        choose.setOnAction(e -> choose());
        var parentRow = new HBox(8, parentField, choose);
        parentRow.setAlignment(Pos.CENTER_LEFT);

        target.getStyleClass().add("project-path");
        status.getStyleClass().add("clone-error");
        status.setWrapText(true);
        bar.setMaxWidth(Double.MAX_VALUE);
        bar.setVisible(false);
        bar.setManaged(false);
        log.setEditable(false);
        log.setPrefRowCount(6);
        log.getStyleClass().add("clone-log");
        log.setVisible(false);
        log.setManaged(false);

        var content = new VBox(8, new Label("Repository URL"), urlField, new Label("Parent folder"), parentRow,
                target, bar, log, status);
        content.setPadding(new Insets(12));
        content.setPrefWidth(520);
        pane.setContent(content);

        urlField.textProperty().addListener((o, a, b) -> refresh());
        parentField.textProperty().addListener((o, a, b) -> refresh());
        refresh();

        var cloneButton = pane.lookupButton(clone);
        cloneButton.disableProperty().bind(Bindings.createBooleanBinding(
                () -> running.get() || urlField.getText().isBlank() || !validParent() || blocked(),
                running, urlField.textProperty(), parentField.textProperty()));
        urlField.disableProperty().bind(running);
        parentField.disableProperty().bind(running);
        choose.disableProperty().bind(running);

        cloneButton.addEventFilter(ActionEvent.ACTION, e -> {
            e.consume();
            start();
        });
        setOnHidden(e -> cancelClone());
        setOnShown(e -> urlField.requestFocus());
        setResultConverter(button -> null);
    }

    // ---- UI helpers

    private static java.util.Optional<String> clipboardUrl() {
        try {
            var text = Clipboard.getSystemClipboard().getString();
            if (text != null && looksLikeGitUrl(text.trim())) {
                return java.util.Optional.of(text.trim());
            }
        } catch (RuntimeException ignored) {
            // no clipboard available
        }
        return java.util.Optional.empty();
    }

    private static Path defaultParent(Path hint) {
        if (hint != null && Files.isDirectory(hint)) {
            return hint;
        }
        var home = Path.of(System.getProperty("user.home"));
        var projects = home.resolve("projects");
        return Files.isDirectory(projects) ? projects : home;
    }

    private void choose() {
        var chooser = new DirectoryChooser();
        chooser.setTitle("Parent folder");
        if (validParent()) {
            chooser.setInitialDirectory(Path.of(parentField.getText().trim()).toFile());
        }
        var dir = chooser.showDialog(getDialogPane().getScene().getWindow());
        if (dir != null) {
            parentField.setText(dir.toString());
        }
    }

    private boolean validParent() {
        try {
            return Files.isDirectory(Path.of(parentField.getText().trim()));
        } catch (RuntimeException e) {
            return false;
        }
    }

    private Path targetPath() {
        var name = GitRepo.repoName(urlField.getText().trim());
        if (name == null || name.isBlank() || !validParent()) {
            return null;
        }
        return Path.of(parentField.getText().trim()).resolve(name);
    }

    private boolean blocked() {
        var t = targetPath();
        return t != null && existsNonEmpty(t);
    }

    static boolean existsNonEmpty(Path dir) {
        if (!Files.exists(dir)) {
            return false;
        }
        if (!Files.isDirectory(dir)) {
            return true;
        }
        try (var s = Files.list(dir)) {
            return s.findAny().isPresent();
        } catch (IOException e) {
            return true;
        }
    }

    private void refresh() {
        if (running.get()) {
            return;
        }
        status.setText("");
        if (urlField.getText().isBlank()) {
            target.setText("Will clone into: …");
            return;
        }
        var t = targetPath();
        target.setText("Will clone into: " + (t != null ? t : parentField.getText().trim()));
        if (t != null && existsNonEmpty(t)) {
            status.setText("Folder already exists");
        }
    }

    // ---- cloning

    private void start() {
        var t = targetPath();
        if (t == null || running.get()) {
            return;
        }
        if (looksLikeOption(urlField.getText())) {
            status.setText("URL must not start with \"-\"");
            return;
        }
        cloneTarget = t;
        createdByUs = !Files.exists(t);
        cancelled = false;
        lines.clear();
        lastWasProgress = false;
        log.clear();
        status.setText("");
        bar.setProgress(ProgressBar.INDETERMINATE_PROGRESS);
        bar.setVisible(true);
        bar.setManaged(true);
        log.setVisible(true);
        log.setManaged(true);
        getDialogPane().getScene().getWindow().sizeToScene();
        running.set(true);
        var url = urlField.getText().trim();
        var thread = new Thread(() -> runClone(url, t), "git-clone");
        thread.setDaemon(true);
        thread.start();
    }

    private void runClone(String url, Path t) {
        try {
            var p = GitRepo.startClone(url, t);
            process = p;
            var code = readOutput(p, (line, cr) -> Platform.runLater(() -> onLine(line, cr)));
            Platform.runLater(() -> finished(code, t));
        } catch (IOException | RuntimeException e) {
            Platform.runLater(() -> failed("Clone failed: " + e.getMessage(), ""));
        }
    }

    private void onLine(String line, boolean progressLine) {
        if (cancelled) {
            return;
        }
        var fraction = progress(line);
        if (fraction.isPresent()) {
            bar.setProgress(fraction.getAsDouble());
        }
        if (line.isBlank()) {
            return;
        }
        if (lastWasProgress && !lines.isEmpty()) {
            lines.set(lines.size() - 1, line);
        } else {
            lines.add(line);
        }
        lastWasProgress = progressLine;
        while (lines.size() > MAX_LOG_LINES) {
            lines.remove(0);
        }
        log.setText(String.join("\n", lines));
        log.positionCaret(log.getLength());
        log.setScrollTop(Double.MAX_VALUE);
    }

    private void finished(int code, Path t) {
        if (cancelled) {
            return;
        }
        if (code == 0) {
            running.set(false);
            setResult(t);
            close();
        } else {
            failed("Clone failed (exit " + code + ")", String.join("\n", lines));
        }
    }

    private void failed(String message, String output) {
        running.set(false);
        process = null;
        bar.setProgress(0);
        var text = message;
        if (needsAuthHint(output)) {
            text += "\nPrivate repo? Set up an SSH key or a credential helper, then try again.";
        }
        status.setText(text);
        if (createdByUs && cloneTarget != null && Files.isDirectory(cloneTarget)) {
            deleteQuietly(cloneTarget);
        }
    }

    /** Stops a running clone and removes a folder the clone created. */
    private void cancelClone() {
        var p = process;
        if (!running.get() || p == null) {
            return;
        }
        cancelled = true;
        running.set(false);
        var folder = cloneTarget;
        var remove = createdByUs;
        var thread = new Thread(() -> {
            p.destroy();
            try {
                if (!p.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) {
                    p.destroyForcibly();
                }
                p.waitFor();
            } catch (InterruptedException e) {
                p.destroyForcibly();
                Thread.currentThread().interrupt();
            }
            if (remove && folder != null) {
                deleteQuietly(folder);
            }
        }, "git-clone-cancel");
        thread.setDaemon(true);
        thread.start();
    }

    private static void deleteQuietly(Path dir) {
        try (var walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // best effort
                }
            });
        } catch (IOException ignored) {
            // best effort
        }
    }

    // ---- pure helpers (unit tested)

    /**
     * Reads process output by chars, splitting on '\r' and '\n', and waits for exit.
     * The callback gets each non-empty segment and whether it ended in '\r' (a progress line
     * that the next segment replaces). Returns the exit code.
     */
    static int readOutput(Process process, BiConsumer<String, Boolean> onLine) throws IOException {
        var sb = new StringBuilder();
        try (var in = new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8)) {
            int c;
            while ((c = in.read()) != -1) {
                if (c == '\r' || c == '\n') {
                    if (!sb.isEmpty()) {
                        onLine.accept(sb.toString(), c == '\r');
                        sb.setLength(0);
                    }
                } else {
                    sb.append((char) c);
                }
            }
            if (!sb.isEmpty()) {
                onLine.accept(sb.toString(), false);
            }
        }
        try {
            return process.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            return -1;
        }
    }

    /** Overall fraction for a git progress line: receiving is 0..0.8, resolving 0.8..1. */
    static OptionalDouble progress(String line) {
        var m = PERCENT.matcher(line.strip());
        if (!m.find()) {
            return OptionalDouble.empty();
        }
        var pct = Integer.parseInt(m.group(2)) / 100.0;
        return OptionalDouble.of(m.group(1).startsWith("Receiving") ? pct * 0.8 : 0.8 + pct * 0.2);
    }

    static boolean looksLikeOption(String url) {
        return url.trim().startsWith("-");
    }

    static boolean looksLikeGitUrl(String text) {
        if (text == null) {
            return false;
        }
        var s = text.strip();
        if (s.isEmpty() || s.contains("\n") || s.contains(" ")) {
            return false;
        }
        return s.startsWith("https://") || s.startsWith("http://") || s.startsWith("git@")
                || s.startsWith("ssh://") || s.endsWith(".git");
    }

    static boolean needsAuthHint(String output) {
        if (output == null) {
            return false;
        }
        return output.contains("Authentication failed") || output.contains("could not read Username")
                || output.contains("Permission denied (publickey)") || output.contains("terminal prompts disabled");
    }
}
