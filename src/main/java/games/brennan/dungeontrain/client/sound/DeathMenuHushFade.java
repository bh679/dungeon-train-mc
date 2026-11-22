package games.brennan.dungeontrain.client.sound;

import net.minecraft.sounds.SoundSource;

/**
 * The maths behind {@link DeathMenuHush}, kept free of the client so it can be tested: which sound
 * categories go quiet, and how the volume factor eases between full and the hush floor.
 */
public final class DeathMenuHushFade {

    /**
     * The quietest the hush goes. Never 0: vanilla's {@code SoundEngine#updateCategoryVolume} stops a
     * channel whose gain reaches 0, which would end looping sounds (wind, ambience) for good instead
     * of letting them come back when the hush lifts. -80 dB is inaudible.
     */
    public static final float FLOOR = 1.0E-4F;
    /** Ticks to go from full volume to the floor as the menu opens. */
    public static final int DOWN_TICKS = 10;
    /** Ticks to come back from the floor to full once the menu hands over to the tribute room. */
    public static final int UP_TICKS = 20;

    private DeathMenuHushFade() {}

    /**
     * Whether the hush applies to {@code source}. MASTER carries the menu's own UI sounds
     * ({@code SimpleSoundInstance.forUI}), MUSIC the soundtrack, VOICE the live stream mic: those keep
     * playing. Everything the world makes goes quiet.
     */
    public static boolean hushes(SoundSource source) {
        return source != null && source != SoundSource.MASTER && source != SoundSource.MUSIC
            && source != SoundSource.VOICE;
    }

    /** One tick's step of the factor toward the floor ({@code hushed}) or back to full. */
    public static float step(float factor, boolean hushed) {
        float next = hushed
            ? factor - (1.0F - FLOOR) / DOWN_TICKS
            : factor + (1.0F - FLOOR) / UP_TICKS;
        return Math.max(FLOOR, Math.min(1.0F, next));
    }

    /** {@code volume} as the world hears it under {@code factor}: a perceptual (squared) ease. */
    public static float apply(float volume, float factor) {
        if (factor >= 1.0F) return volume;
        float eased = Math.max(FLOOR, factor * factor);
        return volume * eased;
    }
}
