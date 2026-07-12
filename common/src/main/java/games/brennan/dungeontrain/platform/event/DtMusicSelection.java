package games.brennan.dungeontrain.platform.event;

import net.minecraft.sounds.Music;

/**
 * Mutable carrier for {@link DtSelectMusicCallback}, mirroring NeoForge's
 * {@code SelectMusicEvent}: read the vanilla-selected track and override it. The
 * bridge backs it with the live event so {@link #setMusic} has the same effect as
 * the former {@code event.setMusic(...)}.
 */
public interface DtMusicSelection {

    /** The music vanilla would otherwise play (may be {@code null}). */
    Music getOriginalMusic();

    /** Override the track to play (the former {@code event.setMusic}). */
    void setMusic(Music music);
}
