package games.brennan.dungeontrain.compat.photo.album;

/**
 * A player has two albums. Which one a "your album" opens is decided by the run it is opened in: a
 * clean run shows the {@link #LIVE} album — the one the relay carries and other players can find —
 * and a Free Play run the {@link #FREE_PLAY} album, which never leaves this computer.
 */
public enum AlbumKind {
    LIVE("live"),
    FREE_PLAY("free_play");

    private final String fileName;

    AlbumKind(String fileName) {
        this.fileName = fileName;
    }

    /** The album's file name in the store ({@code <uuid>/<name>.json}). Never change it: saves are keyed on it. */
    public String fileName() {
        return fileName;
    }

    public static AlbumKind forRun(boolean freePlay) {
        return freePlay ? FREE_PLAY : LIVE;
    }
}
