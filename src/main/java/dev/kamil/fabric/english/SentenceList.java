package dev.kamil.fabric.english;

import java.nio.file.Path;

/**
 * The one list of sentences: a Markdown file in the {@link SentenceFile} format, its removed file and its progress
 * file, all in the english folder: {@code my-list.md}, {@code my-list.removed.md} and {@code my-list.progress.csv}.
 * The files need not exist; the list is then empty and Save creates them.
 */
record SentenceList(Path sentences, Path removed, Path progress) {

    /** The file name of the list without its extension. */
    static final String NAME = "my-list";

    static SentenceList of(Path dir) {
        return new SentenceList(dir.resolve(NAME + ".md"), dir.resolve(NAME + ".removed.md"),
                dir.resolve(NAME + ".progress.csv"));
    }
}
