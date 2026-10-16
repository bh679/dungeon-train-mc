package games.brennan.dungeontrain.editor;

import java.util.List;

/**
 * A signed book carried by a {@link ContainerContentsEntry}: the exact book an author added from
 * their hand, so the chest spawns that book rather than a blank {@code minecraft:written_book}.
 *
 * <p>Each page is the page's text component as JSON, so formatting survives the round trip through
 * the store files. Turning it back into a stack is {@link ContainerContentsBooks#applyStoredBook}.</p>
 */
public record StoredBook(String title, String author, int generation, List<String> pages) {

    /** Vanilla's title cap for a signed book. */
    public static final int MAX_TITLE_LENGTH = 32;
    /** Vanilla's page cap for a signed book. */
    public static final int MAX_PAGES = 100;
    /** Vanilla's highest generation — "tattered". */
    public static final int MAX_GENERATION = 3;

    public StoredBook {
        title = title == null ? "" : title;
        if (title.length() > MAX_TITLE_LENGTH) title = title.substring(0, MAX_TITLE_LENGTH);
        author = author == null ? "" : author;
        generation = Math.max(0, Math.min(MAX_GENERATION, generation));
        pages = pages == null ? List.of()
            : List.copyOf(pages.size() > MAX_PAGES ? pages.subList(0, MAX_PAGES) : pages);
    }
}
