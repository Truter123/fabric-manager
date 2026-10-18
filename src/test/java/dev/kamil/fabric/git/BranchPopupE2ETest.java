package dev.kamil.fabric.git;

import static dev.kamil.fabric.FxHarness.await;
import static dev.kamil.fabric.FxHarness.fx;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import dev.kamil.fabric.FxHarness;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.stage.Popup;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Shows the real branch popup over a throwaway repository; skipped without a display. */
class BranchPopupE2ETest {

    @TempDir
    Path tmp;

    private Stage owner;

    @BeforeAll
    static void startFx() {
        FxHarness.start();
    }

    @AfterEach
    void close() throws Exception {
        fx(() -> {
            popups().forEach(Popup::hide);
            if (owner != null) {
                owner.hide();
            }
            return null;
        });
    }

    private static void git(Path dir, String... args) throws Exception {
        var command = new ArrayList<String>(List.of("git", "-C", dir.toString()));
        command.addAll(List.of(args));
        var process = new ProcessBuilder(command).redirectErrorStream(true).start();
        var out = new String(process.getInputStream().readAllBytes());
        assertEquals(0, process.waitFor(), "git " + String.join(" ", args) + ": " + out);
    }

    private static List<Popup> popups() {
        return Window.getWindows().stream()
                .filter(w -> w instanceof Popup p && p.isShowing())
                .map(w -> (Popup) w)
                .toList();
    }

    private Node showPopup() throws Exception {
        var dir = Files.createDirectories(tmp.resolve("work"));
        git(dir, "init", "-b", "main");
        git(dir, "config", "user.name", "Test");
        git(dir, "config", "user.email", "test@example.com");
        git(dir, "config", "commit.gpgsign", "false");
        Files.writeString(dir.resolve("a.txt"), "one\n");
        git(dir, "add", "-A");
        git(dir, "commit", "-m", "init");
        var repo = GitRepo.of(dir).orElseThrow();
        var content = fx(() -> {
            owner = new Stage();
            var anchor = new Button("branch");
            var scene = new Scene(new StackPane(anchor), 300, 200);
            // like the main window: a popup takes its -fm-* colours from the stylesheets of its owner's scene
            scene.getStylesheets().add(BranchPopup.class.getResource("/app.css").toExternalForm());
            owner.setScene(scene);
            owner.show();
            new BranchPopup(repo, null, () -> { }).show(anchor);
            return popups().get(0).getContent().get(0);
        });
        await(() -> !((ListView<?>) content.lookup(".branch-list")).getItems().isEmpty());
        return content;
    }

    @Test
    void footerHintListsTheKeysInTheMutedColour() throws Exception {
        var content = showPopup();

        var hints = fx(() -> {
            content.applyCss();
            return content.lookupAll(".key-hint").stream().map(n -> (Label) n).toList();
        });

        assertEquals(1, hints.size());
        assertEquals("↑↓ choose   Enter switch   Esc close", hints.get(0).getText());
        assertNotNull(hints.get(0).getTextFill());
        assertEquals(Color.web("#9a9390"), fx(() -> hints.get(0).getTextFill()));
    }
}
