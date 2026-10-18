package dev.kamil.fabric.bodydouble;

import dev.kamil.fabric.Dialogs;
import dev.kamil.fabric.session.Database;
import dev.kamil.fabric.settings.AppSettings;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.Slider;
import javafx.scene.control.TextInputControl;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.media.Media;
import javafx.scene.media.MediaPlayer;
import javafx.scene.media.MediaView;
import javafx.scene.control.TextField;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.stage.Window;
import javafx.util.Duration;

/** Small always-on-top window that plays a pasted YouTube video next to the work. */
public final class BodyDoubleWindow {

    /** Video volume in percent, 0..100. */
    public static final String VOLUME_KEY = "video.volume";
    private static final int RECENT = 10;
    private static final String PLAY = "▶";
    private static final String PAUSE = "❚❚";
    private static final double SEEK_SECONDS = 5;
    private static final double[] REMEMBERED = {Double.NaN, Double.NaN, 400, 260};

    private static BodyDoubleWindow instance;
    /** Creates the downloader of a new window; tests replace it with a stubbed one. */
    static java.util.function.Supplier<VideoDownloader> downloaders = VideoDownloader::new;

    private final Stage window = new Stage();
    private final BodyDoubleStore store;
    private final VideoDownloader downloader = downloaders.get();
    private final TextField urlField = new TextField();
    private final ComboBox<Video> recent = new ComboBox<>();
    private final MediaView view = new MediaView();
    private final Label status = new Label();
    private final Button playPause = new Button(PAUSE);
    private final ToggleButton mute = new ToggleButton("♪");
    private final ToggleButton loop = new ToggleButton("↻");
    private final Slider volume = new Slider(0, 100, 100);
    private final Slider opacity = new Slider(0.3, 1.0, 1.0);
    private final Timeline saver = new Timeline(new KeyFrame(Duration.seconds(10), e -> savePosition()));
    private final AtomicInteger generation = new AtomicInteger();
    private MediaPlayer player;
    private String currentUrl;
    /** True once the current link's cached file was dropped and fetched again after a playback failure. */
    private boolean redownloaded;
    private long lastPositionMs;
    private boolean updatingRecent;

    /** The open window, or null; for tests. */
    static Stage openStage() {
        return instance == null ? null : instance.window;
    }

    /** Shows the window, or hides it when it is already open. */
    public static void toggle(Window owner, Database db) {
        if (instance != null) {
            instance.window.hide();
            return;
        }
        var created = new BodyDoubleWindow(owner, db);
        instance = created;
        created.window.setOnHidden(e -> {
            created.close();
            instance = null;
        });
        created.window.show();
        created.urlField.requestFocus();
    }

    private BodyDoubleWindow(Window owner, Database db) {
        store = new BodyDoubleStore(db);
        window.initOwner(owner);
        window.initStyle(StageStyle.UTILITY);
        window.setAlwaysOnTop(true);
        window.setTitle("Body double");

        urlField.setPromptText("Paste a YouTube link, press Enter");
        urlField.setOnAction(e -> load(urlField.getText().strip()));
        HBox.setHgrow(urlField, Priority.ALWAYS);
        recent.setPromptText("Recent");
        recent.setPrefWidth(110);
        recent.setCellFactory(v -> new VideoCell());
        recent.setButtonCell(new VideoCell());
        recent.setOnAction(e -> {
            if (!updatingRecent && recent.getValue() != null) {
                urlField.setText(recent.getValue().url());
                load(recent.getValue().url());
            }
        });
        var top = new HBox(6, urlField, recent);
        top.setPadding(new Insets(6));
        top.setAlignment(Pos.CENTER_LEFT);

        status.getStyleClass().add("empty-hint");
        status.setWrapText(true);
        status.setMouseTransparent(true);
        status.setText("Paste a link above.");
        view.setPreserveRatio(true);
        var center = new StackPane(view, status);
        center.setStyle("-fx-background-color: black;");
        center.setMinSize(0, 0);
        view.fitWidthProperty().bind(center.widthProperty());
        view.fitHeightProperty().bind(center.heightProperty());

        playPause.setOnAction(e -> togglePlay());
        var back = new Button("◀◀");
        back.setOnAction(e -> seekBy(-SEEK_SECONDS));
        var forward = new Button("▶▶");
        forward.setOnAction(e -> seekBy(SEEK_SECONDS));
        mute.setOnAction(e -> applyMute());
        loop.setSelected(true);
        loop.setOnAction(e -> applyLoop());
        volume.setValue(Math.clamp(AppSettings.shared().getInt(VOLUME_KEY, 100), 0, 100));
        volume.setPrefWidth(80);
        volume.valueProperty().addListener((o, was, now) -> applyVolume());
        volume.valueChangingProperty().addListener((o, was, changing) -> {
            if (!changing) {
                saveVolume();
            }
        });
        opacity.setPrefWidth(80);
        opacity.valueProperty().addListener((o, was, now) -> window.setOpacity(now.doubleValue()));
        var spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        playPause.setTooltip(new Tooltip("Play / pause (Space)"));
        back.setTooltip(new Tooltip("Back 5 s (Left)"));
        forward.setTooltip(new Tooltip("Forward 5 s (Right)"));
        mute.setTooltip(new Tooltip("Mute"));
        loop.setTooltip(new Tooltip("Loop"));
        volume.setTooltip(new Tooltip("Volume"));
        opacity.setTooltip(new Tooltip("Window opacity"));
        volume.setMinWidth(40);
        opacity.setMinWidth(40);
        var opacityIcon = new Label("◐");
        opacityIcon.setTooltip(new Tooltip("Window opacity"));
        var controls = new HBox(6, playPause, back, forward, loop, spacer, mute, volume, opacityIcon, opacity);
        controls.setPadding(new Insets(6));
        controls.setAlignment(Pos.CENTER_LEFT);
        for (var node : controls.getChildren()) {
            if (node instanceof Region region && !(node instanceof Slider) && region != spacer) {
                region.setMinWidth(Region.USE_PREF_SIZE);
            }
            node.setFocusTraversable(false);
        }
        recent.setFocusTraversable(false);

        var root = new BorderPane(center);
        root.setTop(top);
        root.setBottom(new VBox(controls,
                Dialogs.keyHint("Space play/pause   ← back 5 s   → forward 5 s   Esc close")));
        var scene = new Scene(root, REMEMBERED[2], REMEMBERED[3]);
        scene.getStylesheets().add(BodyDoubleWindow.class.getResource("/app.css").toExternalForm());
        scene.addEventFilter(KeyEvent.KEY_PRESSED, this::onKey);
        window.setScene(scene);
        Dialogs.closeOnEscape(scene, window);
        Dialogs.escapeKeepsText(urlField);
        if (!Double.isNaN(REMEMBERED[0])) {
            window.setX(REMEMBERED[0]);
            window.setY(REMEMBERED[1]);
        }
        saver.setCycleCount(Animation.INDEFINITE);
        refreshRecent();
    }

    private void onKey(KeyEvent e) {
        if (window.getScene().getFocusOwner() instanceof TextInputControl) {
            return;
        }
        switch (e.getCode()) {
            case SPACE -> togglePlay();
            case LEFT -> seekBy(-SEEK_SECONDS);
            case RIGHT -> seekBy(SEEK_SECONDS);
            default -> {
                return;
            }
        }
        e.consume();
    }

    private void load(String pageUrl) {
        if (pageUrl.isEmpty()) {
            return;
        }
        savePosition();
        disposePlayer();
        currentUrl = pageUrl;
        redownloaded = false;
        Video video = null;
        try {
            video = store.upsert(pageUrl, null);
        } catch (RuntimeException e) {
            System.err.println(e.getMessage());
        }
        lastPositionMs = video == null ? 0 : video.positionMs();
        refreshRecent();
        downloadAndPlay(video == null || video.title() == null);
    }

    /** Fetches the video into the cache off the FX thread, then starts playing at lastPositionMs. */
    private void downloadAndPlay(boolean fetchTitle) {
        var page = currentUrl;
        int mine = generation.incrementAndGet();
        show("Loading…");
        if (fetchTitle) {
            Thread.startVirtualThread(() -> {
                var title = downloader.title(page);
                if (title != null) {
                    Dialogs.logFailure(() -> store.setTitle(page, title));
                    Platform.runLater(this::refreshRecent);
                }
            });
        }
        Thread.startVirtualThread(() -> {
            Path file;
            try {
                file = downloader.fetch(page,
                        percent -> Platform.runLater(() -> {
                            if (generation.get() == mine) {
                                show("Downloading… " + percent + "%");
                            }
                        }),
                        () -> generation.get() != mine);
            } catch (VideoDownloader.ResolveException e) {
                Platform.runLater(() -> {
                    if (generation.get() == mine) {
                        show(e.getMessage());
                    }
                });
                return;
            }
            Platform.runLater(() -> {
                if (generation.get() == mine) {
                    play(file);
                }
            });
        });
    }

    private void play(Path file) {
        disposePlayer();
        try {
            var created = new MediaPlayer(new Media(file.toUri().toString()));
            player = created;
            view.setMediaPlayer(created);
            applyMute();
            applyVolume();
            applyLoop();
            created.currentTimeProperty().addListener((o, was, now) -> lastPositionMs = (long) now.toMillis());
            created.setOnReady(() -> {
                if (lastPositionMs > 0 && lastPositionMs < created.getTotalDuration().toMillis() - 2000) {
                    created.seek(Duration.millis(lastPositionMs));
                }
                status.setText("");
                created.play();
            });
            created.setOnPlaying(() -> playPause.setText(PAUSE));
            created.setOnPaused(() -> playPause.setText(PLAY));
            created.setOnError(() -> onPlayerError(created));
            saver.play();
        } catch (RuntimeException e) {
            playbackFailed("Cannot play: " + e.getMessage());
        }
    }

    /**
     * A cached file that cannot play is dropped and fetched once more; if the fresh file fails too,
     * the machine cannot play at all, so the error is shown and the file kept. The failed player is dropped either
     * way: JavaFX reports some unplayable files only after the player is on the view.
     */
    private void playbackFailed(String message) {
        if (redownloaded) {
            disposePlayer();
            show(message);
            return;
        }
        redownloaded = true;
        disposePlayer();
        downloader.deleteCached(currentUrl);
        downloadAndPlay(false);
    }

    private void onPlayerError(MediaPlayer failed) {
        if (failed != player) {
            return;
        }
        var message = failed.getError() == null ? "unknown error" : failed.getError().getMessage();
        playbackFailed("Playback failed: " + message);
    }

    private void togglePlay() {
        if (player == null) {
            return;
        }
        if (player.getStatus() == MediaPlayer.Status.PLAYING) {
            player.pause();
        } else {
            player.play();
        }
    }

    private void seekBy(double seconds) {
        if (player == null) {
            return;
        }
        var target = player.getCurrentTime().add(Duration.seconds(seconds));
        var total = player.getTotalDuration();
        if (target.lessThan(Duration.ZERO)) {
            target = Duration.ZERO;
        } else if (total != null && !total.isUnknown() && target.greaterThan(total)) {
            target = total;
        }
        player.seek(target);
    }

    private void applyMute() {
        if (player != null) {
            player.setMute(mute.isSelected());
        }
    }

    private void applyVolume() {
        if (player != null) {
            player.setVolume(volume.getValue() / 100);
        }
    }

    private void saveVolume() {
        var percent = (int) Math.round(volume.getValue());
        Dialogs.logFailure(() -> AppSettings.shared().setInt(VOLUME_KEY, percent));
    }

    private void applyLoop() {
        if (player != null) {
            player.setCycleCount(loop.isSelected() ? MediaPlayer.INDEFINITE : 1);
        }
    }

    private void show(String message) {
        status.setText(message);
    }

    private void savePosition() {
        if (currentUrl != null) {
            var url = currentUrl;
            var position = lastPositionMs;
            Dialogs.logFailure(() -> store.savePosition(url, position));
        }
    }

    private void refreshRecent() {
        updatingRecent = true;
        recent.getItems().setAll(store.recent(RECENT));
        recent.setValue(null);
        updatingRecent = false;
    }

    private void disposePlayer() {
        saver.stop();
        view.setMediaPlayer(null);
        if (player != null) {
            player.dispose();
            player = null;
        }
    }

    private void close() {
        REMEMBERED[0] = window.getX();
        REMEMBERED[1] = window.getY();
        REMEMBERED[2] = window.getScene().getWidth();
        REMEMBERED[3] = window.getScene().getHeight();
        generation.incrementAndGet();
        saveVolume();
        savePosition();
        disposePlayer();
    }

    private static final class VideoCell extends ListCell<Video> {
        @Override
        protected void updateItem(Video video, boolean empty) {
            super.updateItem(video, empty);
            setText(empty || video == null ? null : video.label());
        }
    }
}
