package games.brennan.dungeontrain.event;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.narrative.NarrativeProgressData;
import games.brennan.dungeontrain.narrative.PlayerPlayedMarker;
import games.brennan.dungeontrain.narrative.StartingBookContext;
import games.brennan.dungeontrain.narrative.StartingBookFactory;
import games.brennan.dungeontrain.narrative.StartingBookRegistry;
import games.brennan.dungeontrain.narrative.StartingBookTag;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import org.joml.Vector3f;
import org.slf4j.Logger;

import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Spawns each player a welcome book via a colored lightning strike on the
 * ground ~2 blocks in front of them, instead of dropping the book directly
 * into their inventory.
 *
 * <p>Triggers:</p>
 * <ul>
 *   <li><b>First login</b> — gated by {@link NarrativeProgressData#hasReceivedStartingBook}
 *       so a re-login doesn't dispense a second strike. The flag is set at
 *       strike-fire time, not at enqueue time, so a player who quits during
 *       the boot-delay window still gets their strike on the next login.</li>
 *   <li><b>Every death respawn</b> — unconditionally (player likely lost the
 *       previous book in their death drop). Filtered to skip End-credits
 *       respawn via {@link PlayerEvent.PlayerRespawnEvent#isEndConquered}.</li>
 * </ul>
 *
 * <p><b>Why the tick-delay queue:</b> on first login the player's final spawn
 * position isn't set when {@link PlayerEvent.PlayerLoggedInEvent} fires — the
 * {@link PlayerJoinEvents} retry loop teleports them within 1–5 ticks. Firing
 * lightning immediately would strike at the wrong location. Deferring
 * {@value #STRIKE_DELAY_TICKS} ticks lets the spawn placement settle before
 * we read {@link Player#position()}. For respawns, the delay is the same — it
 * also makes the strike feel like a beat after the world fades in.</p>
 *
 * <p>The lightning bolt is {@code setVisualOnly(true)} so it does no damage
 * and starts no fires. The "color" of the strike comes from a vertical
 * column of {@link DustParticleOptions} dust particles tinted by a per-strike
 * random vibrant RGB (HSV: random hue, full saturation, full value) — vanilla
 * doesn't expose lightning recoloring, so we composite the tint on top of the
 * white bolt.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class StartingBookEvents {

    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * Ticks to defer the strike after login / respawn. 10 ticks ≈ 0.5 s —
     * long enough for {@link PlayerJoinEvents}' placement retry loop to
     * finish teleporting first-login players, short enough that the player
     * still associates the strike with "spawning in".
     */
    private static final int STRIKE_DELAY_TICKS = 10;

    /** Horizontal distance in front of the player where the strike lands. */
    private static final double STRIKE_DISTANCE = 2.0;

    /** Pickup delay on the dropped book (ticks) — gives the strike a beat to play out. */
    private static final int BOOK_PICKUP_DELAY_TICKS = 20;

    /**
     * In-flight welcome strike. {@code context == null} means "resolve at
     * fire time" — used by the login path because the world's social state
     * (have any other players welcomed?) might change during the deferral
     * window. The respawn path knows its context up front (RESPAWN).
     */
    private static final class PendingStrike {
        int ticksRemaining;
        /** Resolved at enqueue (RESPAWN) or at fire time ({@code null} until then). */
        StartingBookContext context;

        PendingStrike(int ticks, StartingBookContext context) {
            this.ticksRemaining = ticks;
            this.context = context;
        }
    }

    /** Player UUID → pending strike (carries ticks + optional pre-resolved context). */
    private static final Map<UUID, PendingStrike> PENDING_STRIKE = new ConcurrentHashMap<>();

    /**
     * How long a closed-and-thrown starting book burns before it's consumed
     * (ticks). 60 ticks = 3 s — long enough for the player to see the flames
     * + the block fire, short enough that it doesn't feel like the book is
     * lingering. Vanilla fire on a non-flammable surface decays in ~1-2 s,
     * so the book outlives the fire only on its supporting block — which is
     * exactly what we want (the fire is "tied" to the burning book).
     */
    private static final int BURN_DURATION_TICKS = 60;

    /** Tracking entry for one in-progress book burn. */
    private static final class BurnState {
        int ticksRemaining;
        /** World pos of the fire block we set, or {@code null} if not set yet. */
        BlockPos fireBlockPos;

        BurnState(int ticks) {
            this.ticksRemaining = ticks;
            this.fireBlockPos = null;
        }
    }

    /** ItemEntity UUID → burn state. Cleared on entity death or burn completion. */
    private static final Map<UUID, BurnState> BURN_ENTITIES = new ConcurrentHashMap<>();

    /**
     * Per-{@link ItemEntity} persistent-data flag: when set on the welcome
     * book ItemEntity that the lightning strike spawns, the
     * {@link #onEntityJoinLevel} handler skips the burn registration —
     * otherwise the welcome book would catch fire the instant it spawned.
     *
     * <p>Marker is on the ENTITY (via NeoForge's {@link Entity#getPersistentData}),
     * not the ItemStack — so when the player picks the book up, the marker
     * is gone with the entity, and a subsequent Q-throw / death-drop spawns
     * a fresh ItemEntity without the marker. That fresh entity goes through
     * the burn flow normally.</p>
     */
    private static final String ENTITY_TAG_SPAWN_BOOK = "dt_starting_spawn_book";

    private StartingBookEvents() {}

    @SubscribeEvent
    public static void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        ServerLevel overworld = overworldOf(player);
        if (overworld == null) return;
        NarrativeProgressData data = NarrativeProgressData.get(overworld);
        if (data.hasReceivedStartingBook(player.getUUID())) return;
        // Defer — see class javadoc for the timing rationale. Context resolved
        // at fire time (null here) so we see the freshest world state.
        PENDING_STRIKE.put(player.getUUID(), new PendingStrike(STRIKE_DELAY_TICKS, null));
    }

    @SubscribeEvent
    public static void onPlayerRespawn(PlayerEvent.PlayerRespawnEvent event) {
        // Skip End-credits respawn — the player just stepped through the End
        // portal back to the overworld, didn't die. A welcome strike there
        // would be jarring.
        if (event.isEndConquered()) return;
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        // Respawn context is unambiguous; pre-resolve at enqueue.
        PENDING_STRIKE.put(player.getUUID(), new PendingStrike(STRIKE_DELAY_TICKS, StartingBookContext.RESPAWN));
    }

    /**
     * Once-per-server-tick processor. Anchored on the overworld tick so we
     * don't double-process across multiple dimensions — the actual strike
     * fires in the player's <em>current</em> dimension, which may be the
     * train sub-level rather than the overworld.
     *
     * <p>Drives two queues:</p>
     * <ol>
     *   <li>{@link #PENDING_STRIKE} — pending welcome strikes (login + respawn).</li>
     *   <li>{@link #BURN_ENTITIES} — in-progress book burns (close-and-throw).</li>
     * </ol>
     */
    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        if (level.dimension() != Level.OVERWORLD) return;

        MinecraftServer server = level.getServer();

        // ---- Pending strike queue (login / respawn deferred fire) ----
        if (!PENDING_STRIKE.isEmpty()) {
            Iterator<Map.Entry<UUID, PendingStrike>> it = PENDING_STRIKE.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<UUID, PendingStrike> entry = it.next();
                ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
                if (player == null) {
                    it.remove();
                    continue;
                }
                PendingStrike ps = entry.getValue();
                ps.ticksRemaining--;
                if (ps.ticksRemaining <= 0) {
                    StartingBookContext ctx = ps.context != null ? ps.context : resolveLoginContext(player);
                    fireLightningAndDropBook(player, ctx);
                    it.remove();
                }
            }
        }

        // ---- Burn lifecycle (dropped, burning, soon-extinguished books) ----
        if (!BURN_ENTITIES.isEmpty()) {
            tickBurnEntities(server);
        }
    }

    /**
     * Iterate every in-progress book burn. For each:
     * <ul>
     *   <li>Locate the {@link ItemEntity} via its UUID across all loaded
     *       server levels.</li>
     *   <li>If the entity is gone (despawn / chunk unload / pickup), drop
     *       tracking and extinguish our fire block if we set one.</li>
     *   <li>Otherwise: emit flame + smoke particles, set a fire block on the
     *       supporting surface (once), decrement the timer.</li>
     *   <li>When the timer hits zero: discard the entity and extinguish.</li>
     * </ul>
     */
    private static void tickBurnEntities(MinecraftServer server) {
        Iterator<Map.Entry<UUID, BurnState>> it = BURN_ENTITIES.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, BurnState> entry = it.next();
            UUID entityUuid = entry.getKey();
            BurnState state = entry.getValue();

            ItemEntity itemEntity = findItemEntity(server, entityUuid);
            if (itemEntity == null || itemEntity.isRemoved()) {
                // Entity is gone — clean up the fire block if we set one.
                if (state.fireBlockPos != null) {
                    extinguishIfFire(server, state.fireBlockPos);
                }
                it.remove();
                continue;
            }

            ServerLevel itemLevel = (ServerLevel) itemEntity.level();

            // Visual: flame burst at the item's position. Throttle to every
            // other tick so we don't flood the particle queue at long
            // distances when several books are burning simultaneously.
            if (itemEntity.tickCount % 2 == 0) {
                itemLevel.sendParticles(ParticleTypes.FLAME,
                    itemEntity.getX(), itemEntity.getY() + 0.2, itemEntity.getZ(),
                    3, 0.12, 0.08, 0.12, 0.02);
                itemLevel.sendParticles(ParticleTypes.SMOKE,
                    itemEntity.getX(), itemEntity.getY() + 0.3, itemEntity.getZ(),
                    1, 0.08, 0.05, 0.08, 0.01);
            }

            // Set a fire block on top of the supporting surface, once the
            // item has settled. Subsequent ticks re-check that the fire
            // block is still ours (a passing player can break / replace it).
            if (itemEntity.onGround() && state.fireBlockPos == null) {
                BlockPos firePos = pickFirePosFor(itemLevel, itemEntity);
                if (firePos != null) {
                    itemLevel.setBlockAndUpdate(firePos, Blocks.FIRE.defaultBlockState());
                    state.fireBlockPos = firePos;
                }
            }

            // Tick down. When zero: book is "consumed", clean up.
            state.ticksRemaining--;
            if (state.ticksRemaining <= 0) {
                if (state.fireBlockPos != null) {
                    extinguishIfFire(server, state.fireBlockPos);
                }
                // Sound + smoke poof for the consume moment.
                itemLevel.playSound(null, itemEntity.getX(), itemEntity.getY(), itemEntity.getZ(),
                    SoundEvents.FIRE_EXTINGUISH, SoundSource.BLOCKS, 0.6f, 1.2f);
                itemLevel.sendParticles(ParticleTypes.LARGE_SMOKE,
                    itemEntity.getX(), itemEntity.getY() + 0.2, itemEntity.getZ(),
                    8, 0.2, 0.1, 0.2, 0.02);
                itemEntity.discard();
                it.remove();
            }
        }
    }

    /**
     * Scan every loaded server level for an entity matching {@code uuid}.
     * Returns the {@link ItemEntity} if found, or {@code null} if it has
     * unloaded / despawned / been picked up.
     */
    private static ItemEntity findItemEntity(MinecraftServer server, UUID uuid) {
        for (ServerLevel level : server.getAllLevels()) {
            Entity e = level.getEntity(uuid);
            if (e instanceof ItemEntity ie) return ie;
        }
        return null;
    }

    /**
     * Decide where to place the fire block for a burning item entity:
     * the air block above the entity's resting position, provided the
     * block below it is a sturdy surface (so vanilla fire won't decay
     * before the book burns out).
     *
     * <p>Returns {@code null} when no suitable spot exists — the book
     * keeps burning visually (particles) but no block fire is set.</p>
     */
    private static BlockPos pickFirePosFor(ServerLevel level, ItemEntity itemEntity) {
        BlockPos itemPos = itemEntity.blockPosition();
        // If the item is resting on a block (typical), put fire on top of that
        // block — i.e. at the item's own block position when the item sits
        // just above ground. Use the air check to pick the highest air
        // position adjacent to the supporting surface.
        BlockPos firePos = itemPos;
        BlockState atItem = level.getBlockState(itemPos);
        if (!atItem.isAir()) {
            firePos = itemPos.above();
        }
        BlockState atFire = level.getBlockState(firePos);
        if (!atFire.isAir()) return null;
        BlockPos supportPos = firePos.below();
        BlockState support = level.getBlockState(supportPos);
        if (!support.isFaceSturdy(level, supportPos, Direction.UP)) return null;
        return firePos;
    }

    /**
     * Set the block at {@code pos} to air <em>only</em> if it's currently a
     * fire block. Guards against extinguishing fire the player started
     * elsewhere, or a fire block that's already been replaced.
     */
    private static void extinguishIfFire(MinecraftServer server, BlockPos pos) {
        for (ServerLevel level : server.getAllLevels()) {
            BlockState state = level.getBlockState(pos);
            if (state.is(Blocks.FIRE)) {
                level.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState());
                return;
            }
        }
    }

    /**
     * Server-side entry point for {@link games.brennan.dungeontrain.net.StartingBookClosedPacket}.
     * The client just closed a {@code BookViewScreen} that was showing a
     * stamped starting book. Find one such book in the player's inventory,
     * remove it, and drop it forward as if thrown.
     *
     * <p>The drop itself is sufficient — {@link #onEntityJoinLevel} sees the
     * resulting {@link ItemEntity}, recognises the {@link StartingBookTag}
     * marker, and registers it for the burn lifecycle. No manual
     * {@link #BURN_ENTITIES} {@code put} needed here.</p>
     */
    public static void handleStartingBookClosed(ServerPlayer player) {
        ItemStack stack = findAndRemoveStartingBook(player);
        if (stack.isEmpty()) {
            // Client says we closed one, but we can't find it — probably a
            // race against another mutator (death drop, dropAll, etc.).
            // Silent no-op; the close already happened.
            return;
        }
        ItemEntity dropped = player.drop(stack, /*dropAround*/ false, /*includeThrowerName*/ false);
        if (dropped == null) {
            // Player.drop returned null (e.g. dropped in creative with
            // drop-in-creative gamerule off, or no spawn slot found).
            // Recreate manually so the burn flow can still run.
            Vec3 eye = player.getEyePosition();
            Vec3 dir = player.getLookAngle();
            dropped = new ItemEntity(player.serverLevel(),
                eye.x + dir.x * 0.3, eye.y - 0.3, eye.z + dir.z * 0.3, stack);
            dropped.setDeltaMovement(dir.scale(0.3));
            player.serverLevel().addFreshEntity(dropped);
        }
        LOGGER.info("[DungeonTrain] StartingBook: {} closed a starting book — drop spawned (burn registered via EntityJoinLevelEvent)",
            player.getName().getString());
    }

    /**
     * Burn-on-drop hook. Fires every time an {@link Entity} is added to a
     * level (Q-throw, death-drop, hopper-eject, our own close-handler drop,
     * anything that calls {@code Level.addFreshEntity}). When the entity is
     * an {@link ItemEntity} carrying a stamped starting book, register it
     * in {@link #BURN_ENTITIES} so the burn lifecycle picks it up on the
     * next tick.
     *
     * <p>Filters that block registration:</p>
     * <ul>
     *   <li>Client side — server-only state.</li>
     *   <li>Non-{@code ItemEntity} entities.</li>
     *   <li>Stacks without the {@link StartingBookTag} marker.</li>
     *   <li>Entities flagged {@link #ENTITY_TAG_SPAWN_BOOK} — the lightning
     *       strike's welcome book. Without this skip, the welcome book would
     *       catch fire the moment the strike lands.</li>
     *   <li>Entities already tracked in {@link #BURN_ENTITIES} — defensive
     *       guard against double-registration if an event somehow fires twice.</li>
     * </ul>
     */
    @SubscribeEvent
    public static void onEntityJoinLevel(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide()) return;
        if (!(event.getEntity() instanceof ItemEntity item)) return;
        ItemStack stack = item.getItem();
        if (!StartingBookTag.isStartingBook(stack)) return;
        if (item.getPersistentData().getBoolean(ENTITY_TAG_SPAWN_BOOK)) return;
        if (BURN_ENTITIES.containsKey(item.getUUID())) return;

        // Lock pickup for the burn window so the player can't snatch the
        // burning book mid-burn and stash it. Matches the close-handler
        // behaviour from the earlier inline-registration code path.
        item.setPickUpDelay(BURN_DURATION_TICKS);
        BURN_ENTITIES.put(item.getUUID(), new BurnState(BURN_DURATION_TICKS));

        // Ignition atmosphere — quiet "whoosh" at the drop point.
        ServerLevel level = (ServerLevel) item.level();
        level.playSound(null, item.getX(), item.getY(), item.getZ(),
            SoundEvents.FIRE_AMBIENT, SoundSource.PLAYERS, 0.6f, 1.5f);

        LOGGER.info("[DungeonTrain] StartingBook: detected dropped starting book — burning entity {} ({} ticks)",
            item.getUUID(), BURN_DURATION_TICKS);
    }

    /**
     * Scan the player's hands (mainhand → offhand) then full inventory for
     * the first {@link ItemStack} carrying the {@link StartingBookTag} marker,
     * remove it from its slot, and return a copy. Returns
     * {@link ItemStack#EMPTY} when nothing matches.
     */
    private static ItemStack findAndRemoveStartingBook(ServerPlayer player) {
        ItemStack mainhand = player.getMainHandItem();
        if (StartingBookTag.isStartingBook(mainhand)) {
            ItemStack copy = mainhand.copy();
            mainhand.setCount(0);  // empty the slot in-place
            return copy;
        }
        ItemStack offhand = player.getOffhandItem();
        if (StartingBookTag.isStartingBook(offhand)) {
            ItemStack copy = offhand.copy();
            offhand.setCount(0);
            return copy;
        }
        Inventory inv = player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (StartingBookTag.isStartingBook(s)) {
                inv.setItem(i, ItemStack.EMPTY);
                return s;
            }
        }
        return ItemStack.EMPTY;
    }

    /**
     * Roll a book from {@link StartingBookRegistry} for {@code context},
     * compute a strike point {@value #STRIKE_DISTANCE} blocks in front of
     * the player along their view direction (XZ-only), and spawn:
     * <ol>
     *   <li>A colored particle column (visual tint for the strike).</li>
     *   <li>A {@code setVisualOnly} {@link LightningBolt} (screen flash + sound).</li>
     *   <li>An {@link ItemEntity} containing the book, with a small upward
     *       impulse so it pops out of the strike crater.</li>
     * </ol>
     *
     * <p>After the strike fires the player is recorded in two places:
     * {@link NarrativeProgressData#markStartingBookReceived} (per-world,
     * suppresses next plain-login give) and
     * {@link PlayerPlayedMarker#markPlayed} (per-installation, switches the
     * next-world context from DEFAULT to NEW_WORLD). Both calls are
     * idempotent so respawn-path re-fires are safe.</p>
     */
    private static void fireLightningAndDropBook(ServerPlayer player, StartingBookContext context) {
        if (StartingBookRegistry.count() == 0) {
            LOGGER.warn("[DungeonTrain] StartingBook: pool is empty — skipping strike for {}",
                player.getName().getString());
            return;
        }
        long seed = player.serverLevel().getGameTime()
            ^ player.getUUID().getLeastSignificantBits()
            ^ (player.getUUID().getMostSignificantBits() << 1);
        // Respawn cycles through the RESPAWN pool until exhausted, then
        // widens to RESPAWN + DEFAULT — needs world state for the
        // seen-set. Other contexts just roll their (context, fallback)
        // pair.
        Optional<ItemStack> bookOpt;
        if (context == StartingBookContext.RESPAWN) {
            ServerLevel overworld = overworldOf(player);
            if (overworld != null) {
                bookOpt = StartingBookFactory.rollForRespawn(seed, NarrativeProgressData.get(overworld));
            } else {
                bookOpt = StartingBookFactory.rollFromPool(seed, context);
            }
        } else {
            bookOpt = StartingBookFactory.rollFromPool(seed, context);
        }
        if (bookOpt.isEmpty()) {
            LOGGER.warn("[DungeonTrain] StartingBook: rollFromPool empty for context {} (zero total weight in pool + default fallback?) — skipping strike for {}",
                context, player.getName().getString());
            return;
        }
        ItemStack book = bookOpt.get();
        ServerLevel level = player.serverLevel();

        Vec3 landPos = computeStrikePos(player);
        Vector3f color = randomVibrantColor(level.random);

        spawnColoredLightningColumn(level, landPos, color);
        spawnVisualLightning(level, landPos);
        spawnBookEntity(level, landPos, book);

        // Both marks are idempotent — safe on respawn-path re-fires.
        ServerLevel overworld = overworldOf(player);
        if (overworld != null) {
            NarrativeProgressData.get(overworld).markStartingBookReceived(player.getUUID());
        }
        PlayerPlayedMarker.markPlayed(player.getUUID());

        LOGGER.info("[DungeonTrain] StartingBook: lightning strike for {} ({}) ctx={} at ({}, {}, {}) color=({}, {}, {})",
            player.getName().getString(), player.getUUID(), context,
            String.format("%.1f", landPos.x), String.format("%.1f", landPos.y), String.format("%.1f", landPos.z),
            String.format("%.2f", color.x), String.format("%.2f", color.y), String.format("%.2f", color.z));
    }

    /**
     * Resolve the welcome-strike context for a player on the login path,
     * read at strike-fire time so the world state is current.
     *
     * <p>Two-step decision:</p>
     * <ol>
     *   <li><b>Scenario detection</b> — read the world's
     *       {@code startingBookReceived} set (others welcomed?) and the
     *       player's gamedir marker (played before?). Yields one of:
     *       JOINED-scenario, NEW-WORLD-scenario, or first-ever.</li>
     *   <li><b>Alternating filter</b> — apply parity rules to the
     *       per-player counters (see {@link PlayerPlayedMarker}):
     *       <ul>
     *         <li>JOINED scenario: count is even → JOINED_WORLD, odd → DEFAULT.</li>
     *         <li>NEW-WORLD scenario: count is odd → NEW_WORLD, even → DEFAULT.</li>
     *       </ul>
     *       Counter is incremented in both branches — the parity is a
     *       property of the world-creation / world-join event, not of
     *       which pool actually rolled.</li>
     * </ol>
     *
     * <p>First-ever players (no marker) → always DEFAULT and no counters
     * touched. The post-fire {@link PlayerPlayedMarker#markPlayed} write
     * flips them out of first-ever for next time.</p>
     */
    public static StartingBookContext resolveLoginContext(ServerPlayer player) {
        UUID uuid = player.getUUID();
        ServerLevel overworld = overworldOf(player);
        boolean othersHere = false;
        if (overworld != null) {
            othersHere = NarrativeProgressData.get(overworld).anyOtherPlayerReceivedStartingBook(uuid);
        }

        if (othersHere) {
            int count = PlayerPlayedMarker.joinedWorldCount(uuid);
            PlayerPlayedMarker.incrementJoinedWorldCount(uuid);
            return (count % 2 == 0) ? StartingBookContext.JOINED_WORLD : StartingBookContext.DEFAULT;
        }
        if (PlayerPlayedMarker.hasPlayed(uuid)) {
            int count = PlayerPlayedMarker.newWorldCount(uuid);
            PlayerPlayedMarker.incrementNewWorldCount(uuid);
            return (count % 2 == 1) ? StartingBookContext.NEW_WORLD : StartingBookContext.DEFAULT;
        }
        return StartingBookContext.DEFAULT;
    }

    /**
     * Test entry point — fire the welcome strike immediately with an
     * explicit context, bypassing the deferral queue and the per-player
     * gate. Used by the {@code /narrative startingbook fire <context>}
     * command. NOT subject to {@link NarrativeProgressData#hasReceivedStartingBook}
     * — testers want to fire as often as they like.
     */
    public static void forceFireForTest(ServerPlayer player, StartingBookContext context) {
        fireLightningAndDropBook(player, context);
    }

    /**
     * Strike position = player position + {@link #STRIKE_DISTANCE} blocks
     * along their view direction projected to the XZ plane. Y is the player's
     * current Y — works in all surface cases (first-login spawn, bed
     * respawn, on-train respawn) without needing height-map lookups that
     * would mis-target on Sable ships or under cover.
     */
    private static Vec3 computeStrikePos(ServerPlayer player) {
        Vec3 view = player.getViewVector(1.0f);
        double horizLen = Math.sqrt(view.x * view.x + view.z * view.z);
        Vec3 forward;
        if (horizLen < 1e-4) {
            // Player looking straight up / down — fall back to body facing.
            float yawRad = (float) Math.toRadians(player.getYRot());
            forward = new Vec3(-Math.sin(yawRad), 0, Math.cos(yawRad));
        } else {
            forward = new Vec3(view.x / horizLen, 0, view.z / horizLen);
        }
        Vec3 raw = player.position().add(forward.scale(STRIKE_DISTANCE));
        // Center on the block grid so the lightning and book land predictably.
        int bx = Mth.floor(raw.x);
        int bz = Mth.floor(raw.z);
        return new Vec3(bx + 0.5, player.getY(), bz + 0.5);
    }

    /**
     * Sample a vibrant RGB color: HSV(random hue, 1.0, 1.0) → RGB. Avoids
     * dull / dark colors by pinning saturation and value.
     */
    private static Vector3f randomVibrantColor(RandomSource rng) {
        float hue = rng.nextFloat();
        float h6 = hue * 6f;
        int sector = ((int) h6) % 6;
        float f = h6 - (int) h6;
        float v = 1.0f;
        float p = 0.0f;
        float q = 1.0f - f;
        float t = f;
        return switch (sector) {
            case 0 -> new Vector3f(v, t, p);
            case 1 -> new Vector3f(q, v, p);
            case 2 -> new Vector3f(p, v, t);
            case 3 -> new Vector3f(p, q, v);
            case 4 -> new Vector3f(t, p, v);
            default -> new Vector3f(v, p, q);
        };
    }

    /**
     * Send a vertical column + ground burst of tinted {@link DustParticleOptions}
     * particles from the strike point. Vanilla lightning is hardcoded white;
     * the tinted dust column composites the requested color on top.
     */
    private static void spawnColoredLightningColumn(ServerLevel level, Vec3 pos, Vector3f color) {
        DustParticleOptions dust = new DustParticleOptions(color, 1.6f);

        // Vertical column from ground to ~25 blocks up.
        for (int dy = 0; dy < 25; dy++) {
            level.sendParticles(dust,
                pos.x + (level.random.nextDouble() - 0.5) * 0.6,
                pos.y + dy,
                pos.z + (level.random.nextDouble() - 0.5) * 0.6,
                3,
                0.25, 0.15, 0.25,
                0.0);
        }

        // Ground burst — radial spray of tinted dust.
        for (int n = 0; n < 24; n++) {
            double angle = level.random.nextDouble() * Math.PI * 2.0;
            double r = level.random.nextDouble() * 2.0;
            level.sendParticles(dust,
                pos.x + Math.cos(angle) * r,
                pos.y + 0.4,
                pos.z + Math.sin(angle) * r,
                1,
                0.0, 0.1, 0.0,
                0.05);
        }
    }

    /**
     * Spawn a visual-only {@link LightningBolt} for the screen flash and the
     * thunder sound. {@code setVisualOnly(true)} suppresses damage, fire,
     * and block damage — pure cosmetic.
     */
    private static void spawnVisualLightning(ServerLevel level, Vec3 pos) {
        LightningBolt bolt = new LightningBolt(EntityType.LIGHTNING_BOLT, level);
        bolt.moveTo(pos.x, pos.y, pos.z);
        bolt.setVisualOnly(true);
        level.addFreshEntity(bolt);
    }

    /**
     * Spawn the welcome book as a free-floating {@link ItemEntity} half a
     * block above the strike point, with a small upward impulse so it pops
     * out of the crater and is visually associated with the strike. Pickup
     * is delayed {@value #BOOK_PICKUP_DELAY_TICKS} ticks so the player has
     * a moment to register the effect before grabbing it.
     *
     * <p>The entity is stamped with {@link #ENTITY_TAG_SPAWN_BOOK} on its
     * persistent data so the {@link #onEntityJoinLevel} burn-on-drop handler
     * skips it — without this, the welcome book would catch fire the moment
     * the strike lands.</p>
     */
    private static void spawnBookEntity(ServerLevel level, Vec3 pos, ItemStack book) {
        ItemEntity entity = new ItemEntity(level, pos.x, pos.y + 0.5, pos.z, book);
        // Small upward pop + tiny horizontal jitter so the book doesn't sit
        // perfectly still inside the particle column.
        entity.setDeltaMovement(
            (level.random.nextDouble() - 0.5) * 0.08,
            0.30,
            (level.random.nextDouble() - 0.5) * 0.08);
        entity.setPickUpDelay(BOOK_PICKUP_DELAY_TICKS);
        // Mark before addFreshEntity — EntityJoinLevelEvent fires synchronously
        // inside addFreshEntity, and the handler reads this flag.
        entity.getPersistentData().putBoolean(ENTITY_TAG_SPAWN_BOOK, true);
        level.addFreshEntity(entity);
    }

    private static ServerLevel overworldOf(Player player) {
        MinecraftServer server = player.getServer();
        if (server == null) return null;
        return server.overworld();
    }
}
