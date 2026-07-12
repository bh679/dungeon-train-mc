package games.brennan.dungeontrain.fabric;

import games.brennan.dungeontrain.platform.DtAttachment;
import games.brennan.dungeontrain.platform.DtAttachmentsProvider;
import games.brennan.dungeontrain.platform.DtChunkAttachment;
import games.brennan.dungeontrain.player.PlayerBiomeProgress;
import games.brennan.dungeontrain.player.PlayerRunState;

/**
 * Fabric {@link DtAttachmentsProvider}: binds each {@code :common} attachment handle
 * to the matching fabric-data-attachment {@code AttachmentType} in
 * {@link FabricModAttachments}. Discovered via
 * {@code META-INF/services/games.brennan.dungeontrain.platform.DtAttachmentsProvider}.
 * Handle construction is side-effect-free (safe at {@code DtAttachments} class-init).
 */
public final class FabricAttachments implements DtAttachmentsProvider {

    @Override
    public DtAttachment<PlayerRunState> playerRunState() {
        return new FabricAttachment<>(FabricModAttachments.PLAYER_RUN_STATE, PlayerRunState::new);
    }

    @Override
    public DtAttachment<PlayerBiomeProgress> playerBiomeProgress() {
        return new FabricAttachment<>(FabricModAttachments.PLAYER_BIOME_PROGRESS, () -> new PlayerBiomeProgress());
    }

    @Override
    public DtAttachment<Boolean> seenIntroCinematic() {
        return new FabricAttachment<>(FabricModAttachments.SEEN_INTRO_CINEMATIC, () -> Boolean.FALSE);
    }

    @Override
    public DtAttachment<Boolean> runCheated() {
        return new FabricAttachment<>(FabricModAttachments.RUN_CHEATED, () -> Boolean.FALSE);
    }

    @Override
    public DtChunkAttachment<Boolean> needsUpsideDownMirror() {
        return new FabricChunkAttachment<>(FabricModAttachments.NEEDS_UPSIDE_DOWN_MIRROR, Boolean.FALSE);
    }
}
