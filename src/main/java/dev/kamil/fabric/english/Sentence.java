package dev.kamil.fabric.english;

/** One everyday sentence block. {@code watch} keeps its {@code **bold**} markers; {@code say} has no backticks. */
public record Sentence(String date, String english, String pl, String say, String watch) {

    /** The Watch text without bold markers, for display and search. */
    public String watchPlain() {
        return watch.replace("**", "");
    }
}
