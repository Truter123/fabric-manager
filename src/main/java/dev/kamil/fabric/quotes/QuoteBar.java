package dev.kamil.fabric.quotes;

import dev.kamil.fabric.session.Database;
import dev.kamil.fabric.settings.AppSettings;
import java.time.Duration;
import java.time.Instant;
import java.util.function.Consumer;
import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.text.TextAlignment;

/** One quiet line with a motivating quote, shown inside the main window and changed on a timer. */
public final class QuoteBar extends HBox {

    public static final String CURRENT_ID_KEY = "quotes.currentId";
    public static final String SHOWN_AT_KEY = "quotes.shownAt";
    public static final String INTERVAL_KEY = "quotes.intervalMinutes";
    public static final int DEFAULT_INTERVAL_MINUTES = 60;

    private final QuoteStore store;
    private final AppSettings settings = AppSettings.shared();
    private final Label text = new Label();
    private final Tooltip tooltip = new Tooltip();
    private final Timeline ticker = new Timeline(new KeyFrame(javafx.util.Duration.seconds(30), e -> tick()));
    private final Consumer<String> settingsListener = key -> {
        if (INTERVAL_KEY.equals(key)) {
            Platform.runLater(this::tick);
        }
    };
    private Quote current;

    public QuoteBar(Database db) {
        store = new QuoteStore(db);
        getStyleClass().add("quote-bar");
        setAlignment(Pos.CENTER);
        text.getStyleClass().add("quote-text");
        text.setWrapText(true);
        text.setTextAlignment(TextAlignment.CENTER);
        text.setAlignment(Pos.CENTER);
        text.setMaxHeight(36);
        text.setTooltip(tooltip);
        tooltip.setWrapText(true);
        tooltip.setMaxWidth(520);
        HBox.setHgrow(text, Priority.ALWAYS);
        text.setMaxWidth(Double.MAX_VALUE);
        getChildren().add(text);

        ticker.setCycleCount(Animation.INDEFINITE);
        ticker.play();
        settings.addListener(settingsListener);
        tick();
    }

    private Duration interval() {
        return Duration.ofMinutes(Math.max(1, settings.getInt(INTERVAL_KEY, DEFAULT_INTERVAL_MINUTES)));
    }

    private Instant shownAt() {
        var millis = settings.getString(SHOWN_AT_KEY, null);
        try {
            return millis == null ? null : Instant.ofEpochMilli(Long.parseLong(millis.trim()));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private long currentId() {
        try {
            return Long.parseLong(settings.getString(CURRENT_ID_KEY, "-1").trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** Re-reads the quotes, applies the rotation for the current time and refreshes the view. */
    private void tick() {
        var result = QuoteRotation.resolve(store.all(), currentId(), shownAt(), Instant.now(), interval());
        current = result.quote();
        if (result.advanced()) {
            remember(current, Instant.now());
        }
        render();
    }

    private void remember(Quote quote, Instant shownAt) {
        if (quote == null) {
            return;
        }
        settings.setString(CURRENT_ID_KEY, Long.toString(quote.id()));
        settings.setString(SHOWN_AT_KEY, Long.toString(shownAt.toEpochMilli()));
    }

    private void render() {
        if (current == null) {
            text.setText("No quotes yet. Click \u275d Quotes in the sidebar to add one.");
            tooltip.setText("No quotes yet. Click \u275d Quotes in the sidebar to add one.");
        } else {
            var line = "\u201c" + current.text() + "\u201d"
                    + (current.author() == null || current.author().isBlank() ? "" : " \u2014 " + current.author());
            text.setText(line);
            tooltip.setText(line);
        }
    }

    /** Re-reads the quotes after the Quotes window changed them. */
    public void refresh() {
        tick();
    }

    /** Stops the timer and the settings listener. */
    public void dispose() {
        ticker.stop();
        settings.removeListener(settingsListener);
    }
}
