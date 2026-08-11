package games.brennan.dungeontrain.builder;

/**
 * Turn an authored id into something worth reading on a button.
 *
 * <p>{@code deep_nether} → {@code Deep Nether}, {@code flatbed_2} → {@code Flatbed 2}. Ids are
 * lower-case and underscored because they're filenames and command arguments; that's the right
 * shape on disk and the wrong shape in a menu aimed at someone who has never opened the editor.</p>
 *
 * <p>Display only — nothing here ever reaches the wire or a file. The New screen's name field still
 * takes and validates a raw id.</p>
 */
public final class BuilderLabels {

    private BuilderLabels() {}

    /**
     * {@code id} with separators as spaces and each word capitalised. Null and blank come back
     * unchanged rather than as {@code "null"}, since a picker with nothing in it should look empty
     * rather than broken.
     */
    public static String pretty(String id) {
        if (id == null || id.isBlank()) {
            return id == null ? "" : id;
        }
        StringBuilder out = new StringBuilder(id.length());
        boolean startOfWord = true;
        for (int i = 0; i < id.length(); i++) {
            char c = id.charAt(i);
            if (c == '_' || c == '-') {
                out.append(' ');
                startOfWord = true;
                continue;
            }
            // A digit can start a "word" (it just isn't capitalisable), so the flag has to clear on
            // any non-separator — otherwise `car_2b` would render as `Car 2B`.
            out.append(startOfWord ? Character.toUpperCase(c) : c);
            startOfWord = false;
        }
        return out.toString();
    }
}
