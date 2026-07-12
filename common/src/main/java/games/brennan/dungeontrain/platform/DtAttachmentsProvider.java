package games.brennan.dungeontrain.platform;

import games.brennan.dungeontrain.player.PlayerBiomeProgress;
import games.brennan.dungeontrain.player.PlayerRunState;

/**
 * Loader-provided factory for the per-player {@link DtAttachment} handles.
 *
 * <p>The NeoForge module supplies an implementation (backed by
 * {@code games.brennan.dungeontrain.registry.ModDataAttachments}'
 * {@code AttachmentType} suppliers) via
 * {@code META-INF/services/games.brennan.dungeontrain.platform.DtAttachmentsProvider}.
 * {@code :common} game logic never sees the provider directly — it reads the
 * resolved handles off {@link DtAttachments}.</p>
 *
 * <p>Handles returned here only <em>wrap</em> the loader's attachment references;
 * the actual {@code AttachmentType} REGISTRATION stays loader-side. Creating a
 * handle must therefore be side-effect-free and safe to call at class-init
 * (before registration completes) — each handle resolves its underlying
 * attachment lazily, on first {@code get/set/has}.</p>
 */
public interface DtAttachmentsProvider {

    /** Handle for the per-player run-state attachment (codec-persisted). */
    DtAttachment<PlayerRunState> playerRunState();

    /** Handle for the per-player biome-progress attachment (codec-persisted, cleared on death). */
    DtAttachment<PlayerBiomeProgress> playerBiomeProgress();

    /** Handle for the per-player "seen intro cinematic" flag (codec-persisted, copyOnDeath). */
    DtAttachment<Boolean> seenIntroCinematic();

    /** Handle for the per-player "run cheated" flag (codec-persisted, copyOnDeath, sticky). */
    DtAttachment<Boolean> runCheated();
}
