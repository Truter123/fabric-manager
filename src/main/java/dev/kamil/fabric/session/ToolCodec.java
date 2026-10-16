package dev.kamil.fabric.session;

/** Text form of a {@link Tool} in the database: its name, or {@code custom:<command>} for a custom command. */
final class ToolCodec {

    private static final String CUSTOM_PREFIX = "custom:";

    private ToolCodec() {
    }

    static String encode(Tool tool) {
        if (tool == null) {
            return null;
        }
        return "custom".equals(tool.name()) ? CUSTOM_PREFIX + tool.command() : tool.name();
    }

    static Tool decode(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        if (value.startsWith(CUSTOM_PREFIX) && value.length() > CUSTOM_PREFIX.length()) {
            return Tool.custom(value.substring(CUSTOM_PREFIX.length()));
        }
        return Tool.byName(value).orElse(null);
    }
}
