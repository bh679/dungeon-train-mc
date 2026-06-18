package games.brennan.dungeontrain.registry;

import com.mojang.serialization.Codec;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.player.PlayerBiomeProgress;
import games.brennan.dungeontrain.player.PlayerRunState;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

import java.util.function.Supplier;

/**
 * Mod-side AttachmentType registry. Wires {@link PlayerRunState} onto
 * {@link net.minecraft.world.entity.player.Player} so per-player streak
 * state survives world save/reload.
 *
 * <p>Registered from {@link DungeonTrain}'s constructor on the mod-event
 * bus, mirroring {@link ModItems#register(IEventBus)}.</p>
 */
public final class ModDataAttachments {

    public static final DeferredRegister<AttachmentType<?>> TYPES =
        DeferredRegister.create(NeoForgeRegistries.ATTACHMENT_TYPES, DungeonTrain.MOD_ID);

    /**
     * Per-player run state. Codec-serialized so the streak persists across
     * world reload; the {@code AchievementEvents} respawn hook explicitly
     * clears it on player death.
     */
    public static final Supplier<AttachmentType<PlayerRunState>> PLAYER_RUN_STATE =
        TYPES.register("player_run_state",
            () -> AttachmentType.builder(PlayerRunState::new)
                .serialize(PlayerRunState.CODEC)
                .build()
        );

    /**
     * Per-player run biome-exploration progress — the distinct biomes and biome
     * families ridden through this life. Codec-serialized so progress survives
     * world reload; deliberately <b>not</b> {@code copyOnDeath}, so the respawn
     * clone starts empty (the {@code AchievementEvents} respawn hook also clears
     * it explicitly). Drives the exploration advancements.
     */
    public static final Supplier<AttachmentType<PlayerBiomeProgress>> PLAYER_BIOME_PROGRESS =
        TYPES.register("player_biome_progress",
            () -> AttachmentType.builder(PlayerBiomeProgress::new)
                .serialize(PlayerBiomeProgress.CODEC)
                .build()
        );

    /**
     * Per-player flag: has this player already seen the fly-up spawn intro
     * cinematic in this world? Set once on first login and serialized so it
     * survives logout / world reload. {@code copyOnDeath} preserves it across
     * the respawn clone so a death never re-triggers the intro (it only fires
     * on login via {@code PlayerJoinEvents}).
     */
    public static final Supplier<AttachmentType<Boolean>> SEEN_INTRO_CINEMATIC =
        TYPES.register("seen_intro_cinematic",
            () -> AttachmentType.<Boolean>builder(() -> Boolean.FALSE)
                .serialize(Codec.BOOL)
                .copyOnDeath()
                .build()
        );

    /**
     * Per-player "this world's run has been cheated" flag. Set the moment a
     * cheat is detected — a switch to creative/spectator/cinematographer, or any
     * non-allowlisted command (see
     * {@link games.brennan.dungeontrain.cheat.RunIntegrity} /
     * {@link games.brennan.dungeontrain.event.CheatDetectionEvents}). While set,
     * advancements still earn live but are NOT written to the cross-world
     * profile, and global lifetime stats stop accruing.
     *
     * <p>Sticky per-world: serialized so it survives relog, and
     * {@code copyOnDeath} so it survives the respawn clone (a cheated world stays
     * cheated). A brand-new world / run starts {@code false}. Deliberately has
     * <b>no</b> reset hook (unlike {@code PLAYER_BIOME_PROGRESS}).</p>
     */
    public static final Supplier<AttachmentType<Boolean>> RUN_CHEATED =
        TYPES.register("run_cheated",
            () -> AttachmentType.<Boolean>builder(() -> Boolean.FALSE)
                .serialize(Codec.BOOL)
                .copyOnDeath()
                .build()
        );

    private ModDataAttachments() {}

    public static void register(IEventBus modBus) {
        TYPES.register(modBus);
    }
}
