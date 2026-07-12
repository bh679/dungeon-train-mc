package games.brennan.dungeontrain.fabric;

import com.mojang.serialization.Codec;
import games.brennan.dungeontrain.net.platform.DtModId;
import games.brennan.dungeontrain.player.PlayerBiomeProgress;
import games.brennan.dungeontrain.player.PlayerRunState;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.minecraft.resources.ResourceLocation;

/**
 * Fabric-side attachment-type registry — the Fabric mirror of the root
 * {@code ModDataAttachments}. Registers the five DT attachments via
 * fabric-data-attachment-api-v1 with the SAME codecs + {@code copyOnDeath} semantics
 * as NeoForge (the on-disk NBT container differs — Fabric stores under its own
 * attachment subtag — but each value's codec and death-copy behaviour is identical).
 *
 * <p>The per-player handles are surfaced to {@code :common} via {@code FabricAttachments}
 * (the {@code DtAttachmentsProvider}); the chunk marker is read through
 * {@code FabricChunkAttachment}. Registration must run at mod init (from
 * {@code DungeonTrainFabric}) before any world loads.</p>
 */
public final class FabricModAttachments {

    public static final AttachmentType<PlayerRunState> PLAYER_RUN_STATE =
        AttachmentRegistry.<PlayerRunState>builder()
            .initializer(PlayerRunState::new)
            .persistent(PlayerRunState.CODEC)
            .buildAndRegister(id("player_run_state"));

    public static final AttachmentType<PlayerBiomeProgress> PLAYER_BIOME_PROGRESS =
        AttachmentRegistry.<PlayerBiomeProgress>builder()
            .initializer(() -> new PlayerBiomeProgress())
            .persistent(PlayerBiomeProgress.CODEC)
            .buildAndRegister(id("player_biome_progress"));

    public static final AttachmentType<Boolean> SEEN_INTRO_CINEMATIC =
        AttachmentRegistry.<Boolean>builder()
            .initializer(() -> Boolean.FALSE)
            .persistent(Codec.BOOL)
            .copyOnDeath()
            .buildAndRegister(id("seen_intro_cinematic"));

    public static final AttachmentType<Boolean> RUN_CHEATED =
        AttachmentRegistry.<Boolean>builder()
            .initializer(() -> Boolean.FALSE)
            .persistent(Codec.BOOL)
            .copyOnDeath()
            .buildAndRegister(id("run_cheated"));

    public static final AttachmentType<Boolean> NEEDS_UPSIDE_DOWN_MIRROR =
        AttachmentRegistry.<Boolean>builder()
            .initializer(() -> Boolean.FALSE)
            .persistent(Codec.BOOL)
            .buildAndRegister(id("needs_upside_down_mirror"));

    private FabricModAttachments() {}

    /** Touch-to-init: referencing this class forces the static fields above to register. */
    public static void init() {}

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(DtModId.MOD_ID, path);
    }
}
