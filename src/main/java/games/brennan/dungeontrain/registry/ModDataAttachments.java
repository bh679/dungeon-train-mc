package games.brennan.dungeontrain.registry;
import games.brennan.dungeontrain.DtCore;

import com.mojang.serialization.Codec;

import games.brennan.dungeontrain.platform.DtAttachment;
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
 *
 * <p>Stays entirely in the root NeoForge module: {@code AttachmentType}
 * registration is NeoForge-specific API (there is no vanilla registry key for
 * it, so it cannot be routed through {@link games.brennan.dungeontrain.platform.DtRegistrar},
 * which only handles vanilla {@code Registry<T>} keys). The four Player-scoped
 * entries are additionally bound to loader-neutral {@link DtAttachment} handles
 * by {@code games.brennan.dungeontrain.platform.neoforge.NeoForgeAttachments}
 * (the ServiceLoader-provided {@code DtAttachmentsProvider}) and surfaced on
 * {@code :common}'s {@code games.brennan.dungeontrain.platform.DtAttachments}, so
 * converted {@code :common} callers never touch {@code AttachmentType} or
 * {@code Player.getData} directly. The chunk-scoped
 * {@link #NEEDS_UPSIDE_DOWN_MIRROR} has no handle: it is read via
 * {@code LevelChunk.getData/setData/hasData/removeData}, not
 * {@code Player}, which {@link DtAttachment} does not model.</p>
 */
public final class ModDataAttachments {

    public static final DeferredRegister<AttachmentType<?>> TYPES =
        DeferredRegister.create(NeoForgeRegistries.ATTACHMENT_TYPES, DtCore.MOD_ID);

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
     * Per-player run biome-exploration progress — the distinct biomes ridden
     * through this life. Codec-serialized so progress survives world reload;
     * deliberately <b>not</b> {@code copyOnDeath}, so the respawn clone starts
     * empty (the {@code AchievementEvents} respawn hook also clears it
     * explicitly). Drives the exploration advancements.
     */
    public static final Supplier<AttachmentType<PlayerBiomeProgress>> PLAYER_BIOME_PROGRESS =
        TYPES.register("player_biome_progress",
            // Explicit lambda (not a `PlayerBiomeProgress::new` method reference): the class also has a
            // 1-arg constructor (List<ResourceLocation>), which makes a bare method reference ambiguous
            // between AttachmentType.builder(Supplier) and builder(Function<IAttachmentHolder, T>).
            () -> AttachmentType.builder(() -> new PlayerBiomeProgress())
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

    /**
     * Per-chunk flag: this in-band overworld chunk still needs the upside-down mirror post-process
     * applied. The mirror is no longer run synchronously at {@code ChunkEvent.Load}; it is deferred and
     * re-applied over later ticks under a per-tick budget (see {@code WorldUpsideDownEvents} +
     * {@code TrainTickEvents}) so a streaming train doesn't spike the server tick. This marker is set the
     * moment a band chunk generates and cleared once the mirror has been applied.
     *
     * <p>Lives <b>on the chunk</b> (not a side saved-data set) so the marker and the mirrored blocks
     * persist atomically in the same {@code .mca} unit: on disk it is always "marker present ⟺ blocks not
     * yet mirrored". This makes the deferral crash-safe — a chunk saved un-mirrored keeps its marker and
     * re-enqueues on reload; one saved after applying has no marker and is never touched again. The marker
     * check-and-clear (on the single server thread) is the <b>sole</b> idempotency guarantee: {@code
     * applyMirror} re-snapshots the column each call, so a double-apply would mirror the mirror. Default
     * {@code FALSE} + {@code Codec.BOOL}; presence (via {@code hasData}) means "needs mirror". No
     * {@code copyOnDeath} (chunks don't respawn).</p>
     */
    public static final Supplier<AttachmentType<Boolean>> NEEDS_UPSIDE_DOWN_MIRROR =
        TYPES.register("needs_upside_down_mirror",
            () -> AttachmentType.<Boolean>builder(() -> Boolean.FALSE)
                .serialize(Codec.BOOL)
                .build()
        );

    // The loader-neutral per-player {@link DtAttachment} handles that :common game
    // logic reads/writes through are bound to these AttachmentType suppliers by
    // {@code games.brennan.dungeontrain.platform.neoforge.NeoForgeAttachments} (the
    // ServiceLoader-provided DtAttachmentsProvider) and surfaced on :common's
    // {@code games.brennan.dungeontrain.platform.DtAttachments}. Registration
    // (AttachmentType creation + codecs + copyOnDeath) stays entirely here.

    private ModDataAttachments() {}

    public static void register(IEventBus modBus) {
        TYPES.register(modBus);
    }
}
