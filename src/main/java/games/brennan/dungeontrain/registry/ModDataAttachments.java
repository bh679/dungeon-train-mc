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
     * World-X where this player's current life began — the origin the relay's per-death
     * {@code distanceTravelled} (signed X displacement, {@code deathX - spawnX}) is measured from.
     *
     * <p>Deliberately its own attachment rather than a {@link PlayerRunState} field: that codec's
     * {@code RecordCodecBuilder.group(...)} is already at its 16-field cap (see
     * {@code PlayerRunState.narrativeLetters}), and unlike the other overflow fields this one must
     * survive a mid-run logout — recapturing it further down the line would silently understate every
     * subsequent death's displacement.</p>
     *
     * <p>Serialized, and <b>no</b> {@code copyOnDeath}: the respawn clone starts with no value, which
     * is exactly the per-life reset this needs. <b>Presence is the "captured yet?" flag</b> (same
     * idiom as {@link #NEEDS_UPSIDE_DOWN_MIRROR}) — always test {@code hasData} before
     * {@code getData}, since {@code getData} materialises the default. Written on respawn and, for a
     * world's first life (no respawn event), lazily on the first boarded distance sample — both in
     * {@code BoardingProgressEvents}.</p>
     */
    public static final Supplier<AttachmentType<Integer>> RUN_SPAWN_X =
        TYPES.register("run_spawn_x",
            () -> AttachmentType.<Integer>builder(() -> 0)
                .serialize(Codec.INT)
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

    /**
     * Travelled-carriage reading (the same {@code effectiveTravelled} value the carts tiers use) at
     * the moment this player last opened a chest or barrel this life. The "no container" streak is
     * {@code effectiveTravelled - this}, driving the {@code no_container_100} / {@code no_container_1000}
     * advancements ("Not My Chest" / "Still Not My Chest"). Decorated pots deliberately do NOT touch it —
     * vases are fair game.
     *
     * <p>Its own attachment rather than a {@link games.brennan.dungeontrain.player.PlayerRunState} field
     * because that codec is at its 16-field cap (see {@link #RUN_SPAWN_X}). Serialized so the streak
     * survives a mid-run logout, and <b>no</b> {@code copyOnDeath}: the respawn clone starts at 0, which
     * is exactly the per-life reset wanted — the travelled counter resets with it.</p>
     */
    public static final Supplier<AttachmentType<Integer>> CARTS_AT_LAST_CONTAINER_OPEN =
        TYPES.register("carts_at_last_container_open",
            () -> AttachmentType.<Integer>builder(() -> 0)
                .serialize(Codec.INT)
                .build()
        );

    /**
     * Per-life flag: has this player opened an ender chest during the current life? Gates
     * {@code contained_loop} ("Contained Loop"), granted on death after 1000+ carriages travelled
     * without ever dipping into the (cross-life, EnderChestPersistence-backed) ender-chest stash.
     *
     * <p>Serialized so a mid-life logout can't launder the flag away, and <b>no</b> {@code copyOnDeath}
     * so every new life starts clean.</p>
     */
    public static final Supplier<AttachmentType<Boolean>> OPENED_ENDER_CHEST_THIS_LIFE =
        TYPES.register("opened_ender_chest_this_life",
            () -> AttachmentType.<Boolean>builder(() -> Boolean.FALSE)
                .serialize(Codec.BOOL)
                .build()
        );

    private ModDataAttachments() {}

    public static void register(IEventBus modBus) {
        TYPES.register(modBus);
    }
}
