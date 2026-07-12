package games.brennan.dungeontrain.platform.event;

/**
 * Loader-neutral form of NeoForge's {@code SelectMusicEvent} (client game bus).
 * Fires when the client picks the next music track. DT's two handlers override the
 * track via {@link DtMusicSelection} (never cancel). {@code Music} is vanilla, so a
 * Fabric bridge can supply the selection from its music hooks.
 */
@FunctionalInterface
public interface DtSelectMusicCallback {

    void onSelectMusic(DtMusicSelection selection);
}
