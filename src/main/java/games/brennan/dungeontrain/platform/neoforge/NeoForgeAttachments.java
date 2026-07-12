package games.brennan.dungeontrain.platform.neoforge;

import games.brennan.dungeontrain.platform.DtAttachment;
import games.brennan.dungeontrain.platform.DtAttachmentsProvider;
import games.brennan.dungeontrain.player.PlayerBiomeProgress;
import games.brennan.dungeontrain.player.PlayerRunState;
import games.brennan.dungeontrain.registry.ModDataAttachments;

/**
 * NeoForge {@link DtAttachmentsProvider}: binds each {@code :common}
 * {@link DtAttachment} handle to the matching {@code AttachmentType} supplier
 * declared in {@link ModDataAttachments}.
 *
 * <p>Registration of the {@code AttachmentType}s themselves (codecs,
 * {@code copyOnDeath}) stays entirely in {@link ModDataAttachments} — this
 * class only adapts them to the loader-neutral handle contract and is published
 * to {@code :common} via
 * {@code META-INF/services/games.brennan.dungeontrain.platform.DtAttachmentsProvider}.</p>
 *
 * <p>Handle construction is side-effect-free — {@link NeoForgeAttachment} merely
 * captures the {@code AttachmentType} supplier and resolves it lazily on first
 * {@code get/set/has} — so this provider is safe for {@code DtAttachments} to
 * resolve at class-init, before {@code ModDataAttachments.register} runs.</p>
 */
public final class NeoForgeAttachments implements DtAttachmentsProvider {

    @Override
    public DtAttachment<PlayerRunState> playerRunState() {
        return new NeoForgeAttachment<>(ModDataAttachments.PLAYER_RUN_STATE);
    }

    @Override
    public DtAttachment<PlayerBiomeProgress> playerBiomeProgress() {
        return new NeoForgeAttachment<>(ModDataAttachments.PLAYER_BIOME_PROGRESS);
    }

    @Override
    public DtAttachment<Boolean> seenIntroCinematic() {
        return new NeoForgeAttachment<>(ModDataAttachments.SEEN_INTRO_CINEMATIC);
    }

    @Override
    public DtAttachment<Boolean> runCheated() {
        return new NeoForgeAttachment<>(ModDataAttachments.RUN_CHEATED);
    }
}
