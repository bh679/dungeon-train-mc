package games.brennan.dungeontrain.template;

/**
 * Outcome of a {@link TemplateStore#save} call and of every editor's own {@code save}: the
 * config-dir write either succeeded silently or threw, and the source-tree write is opt-in via
 * {@code EditorDevMode} and reported separately ({@code editor.EditorSave} builds it).
 *
 * <p>{@link #sourceAttempted} is true when devmode was on at save time and
 * the store tried to write through to {@code src/main/resources/...}.
 * {@link #sourceWritten} is true on success; {@link #sourceError} carries
 * the failure message when the source-tree write was attempted but failed
 * (typically because the source tree isn't writable in a packaged-jar
 * install).</p>
 */
public record SaveResult(boolean sourceAttempted, boolean sourceWritten, String sourceError) {

    public static SaveResult skipped() { return new SaveResult(false, false, null); }
    public static SaveResult written() { return new SaveResult(true, true, null); }
    public static SaveResult failed(String error) { return new SaveResult(true, false, error); }
}
