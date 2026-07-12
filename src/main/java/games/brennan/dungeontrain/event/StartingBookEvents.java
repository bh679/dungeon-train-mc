package games.brennan.dungeontrain.event;

import com.mojang.logging.LogUtils;

import games.brennan.dungeontrain.advancement.GlobalBookBurnStats;
import games.brennan.dungeontrain.cheat.RunIntegrity;
import games.brennan.dungeontrain.narrative.BookReadMarkerTag;
import games.brennan.dungeontrain.narrative.BurnableBookTag;
import games.brennan.dungeontrain.narrative.DeathNoteBookTag;
import games.brennan.dungeontrain.narrative.LetterBookTag;
import games.brennan.dungeontrain.narrative.NarrativeProgressData;
import games.brennan.dungeontrain.narrative.PlayerPlayedMarker;
import games.brennan.dungeontrain.narrative.PlayerWrittenBookTag;
import games.brennan.dungeontrain.narrative.RandomBookTag;
import games.brennan.dungeontrain.narrative.SharedBookFoundTag;
import games.brennan.dungeontrain.narrative.SharedBookTag;
import games.brennan.dungeontrain.narrative.StartingBookContext;
import games.brennan.dungeontrain.narrative.StartingBookFactory;
import games.brennan.dungeontrain.narrative.StartingBookRegistry;
import games.brennan.dungeontrain.narrative.StartingBookTag;
import games.brennan.dungeontrain.world.DungeonTrainWorldData;
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
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;
import org.slf4j.Logger;

import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

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
     * + the block-fire visual, short enough that it doesn't feel like the
     * book is lingering. The block-fire visual is rendered client-side only
     * via {@link ClientboundBlockUpdatePacket} (see
     * {@link #sendClientFireUpdate}) — the server never actually places a
     * {@link Blocks#FIRE} block, so the burn has no game-side effects (no
     * fire spread, no entity damage, no chunk state change).
     */
    private static final int BURN_DURATION_TICKS = 60;

    /** Tracking entry for one in-progress book burn. */
    private static final class BurnState {
        int ticksRemaining;
        /** Death Note curse → soul-fire particles + a soul sound instead of the normal fire. */
        final boolean soul;
        /** Render anchor for the client-only fire block, or {@code null} until the item settles. */
        BlockPos flameRenderPos;
        /** Dimension containing {@link #flameRenderPos} — needed to clear the visual after the entity is gone. */
        ResourceKey<Level> flameRenderLevelKey;

        BurnState(int ticks, boolean soul) {
            this.ticksRemaining = ticks;
            this.soul = soul;
            this.flameRenderPos = null;
            this.flameRenderLevelKey = null;
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

        public static void onPlayerLogin(net.minecraft.world.entity.player.Player joinedPlayer) {
        if (!(joinedPlayer instanceof ServerPlayer player)) return;
        ServerLevel overworld = overworldOf(player);
        if (overworld == null) return;
        NarrativeProgressData data = NarrativeProgressData.get(overworld);
        if (data.hasReceivedStartingBook(player.getUUID())) return;
        // Defer — see class javadoc for the timing rationale. Context resolved
        // at fire time (null here) so we see the freshest world state.
        PENDING_STRIKE.put(player.getUUID(), new PendingStrike(STRIKE_DELAY_TICKS, null));
    }

        public static void onPlayerRespawn(net.minecraft.world.entity.player.Player respawnedPlayer, boolean endConquered) {
        // Skip End-credits respawn — the player just stepped through the End
        // portal back to the overworld, didn't die. A welcome strike there
        // would be jarring.
        if (endConquered) return;
        if (!(respawnedPlayer instanceof ServerPlayer player)) return;
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
        public static void onLevelTick(net.minecraft.world.level.Level tickedLevel) {
        if (!(tickedLevel instanceof ServerLevel level)) return;
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
                    // Hold the welcome strike until the spawn intro cinematic
                    // finishes, so the lightning lands once the player has
                    // control on the train rather than mid-cinematic.
                    if (CinematicIntroService.isCinematicActive(player.getUUID())) {
                        ps.ticksRemaining = 0; // stay pending; re-check next tick
                    } else {
                        StartingBookContext ctx = ps.context != null ? ps.context : resolveLoginContext(player);
                        fireLightningAndDropBook(player, ctx, /*markSeen*/ true);
                        it.remove();
                    }
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
     *   <li>If the entity is gone (despawn / chunk unload / pickup), clear
     *       the client-only fire visual (if anchored) and drop tracking.</li>
     *   <li>Otherwise: emit the existing flame + smoke particles around the
     *       item, and (once the item has settled on a sturdy surface) send
     *       a client-only {@link Blocks#FIRE} block update at that surface
     *       each tick — the vanilla "block on fire" animation renders on
     *       clients while the server keeps the position as air, so the
     *       burn has no game-side effects.</li>
     *   <li>Decrement the timer.</li>
     *   <li>When the timer hits zero: clear the client-only fire, play the
     *       extinguish sound + smoke poof, and discard the entity.</li>
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
                clearClientFireVisual(server, state);
                it.remove();
                continue;
            }

            ServerLevel itemLevel = (ServerLevel) itemEntity.level();

            // Visual: flame burst at the item's position. Throttle to every
            // other tick so we don't flood the particle queue at long
            // distances when several books are burning simultaneously.
            if (itemEntity.tickCount % 2 == 0) {
                itemLevel.sendParticles(state.soul ? ParticleTypes.SOUL_FIRE_FLAME : ParticleTypes.FLAME,
                    itemEntity.getX(), itemEntity.getY() + 0.2, itemEntity.getZ(),
                    3, 0.12, 0.08, 0.12, 0.02);
                itemLevel.sendParticles(state.soul ? ParticleTypes.SOUL : ParticleTypes.SMOKE,
                    itemEntity.getX(), itemEntity.getY() + 0.3, itemEntity.getZ(),
                    1, 0.08, 0.05, 0.08, 0.01);
            }

            // Anchor the visual block-fire position once the item settles.
            if (itemEntity.onGround() && state.flameRenderPos == null) {
                BlockPos picked = pickFirePosFor(itemLevel, itemEntity);
                if (picked != null) {
                    state.flameRenderPos = picked;
                    state.flameRenderLevelKey = itemLevel.dimension();
                }
            }

            // Send a client-only FIRE block update each tick. The server
            // still has AIR at this position, so no fire spread, no damage,
            // no chunk state change — clients just see the vanilla "block
            // on fire" animation. Re-send each tick so any incidental chunk
            // refresh that re-asserts the server state (air) doesn't leave
            // the visual stuck off. Skip if the server has a non-air block
            // at this position (player placed something there during the
            // burn — leave their block alone).
            if (state.flameRenderPos != null
                && itemLevel.getBlockState(state.flameRenderPos).isAir()) {
                sendClientFireUpdate(itemLevel, state.flameRenderPos,
                    (state.soul ? Blocks.SOUL_FIRE : Blocks.FIRE).defaultBlockState());
            }

            // Tick down. When zero: book is "consumed", clean up.
            state.ticksRemaining--;
            if (state.ticksRemaining <= 0) {
                clearClientFireVisual(server, state);
                if (state.soul) {
                    itemLevel.playSound(null, itemEntity.getX(), itemEntity.getY(), itemEntity.getZ(),
                        SoundEvents.SOUL_ESCAPE, SoundSource.BLOCKS, 0.6f, 1.2f);
                } else {
                    itemLevel.playSound(null, itemEntity.getX(), itemEntity.getY(), itemEntity.getZ(),
                        SoundEvents.FIRE_EXTINGUISH, SoundSource.BLOCKS, 0.6f, 1.2f);
                }
                itemLevel.sendParticles(state.soul ? ParticleTypes.SOUL : ParticleTypes.LARGE_SMOKE,
                    itemEntity.getX(), itemEntity.getY() + 0.2, itemEntity.getZ(),
                    8, 0.2, 0.1, 0.2, 0.02);
                itemEntity.discard();
                it.remove();
            }
        }
    }

    /**
     * Send a client-only block update for {@code state} at {@code pos} to
     * every player tracking the chunk that contains {@code pos}. The server
     * level is unchanged — only clients' local copies of that block change.
     *
     * <p>Use to render the vanilla fire animation without placing a real
     * {@link Blocks#FIRE} block on the server: no fire spread, no damage,
     * no neighbor updates, no chunk state diff.</p>
     */
    private static void sendClientFireUpdate(ServerLevel level, BlockPos pos, BlockState state) {
        ClientboundBlockUpdatePacket packet = new ClientboundBlockUpdatePacket(pos, state);
        for (ServerPlayer player : level.getChunkSource().chunkMap.getPlayers(new ChunkPos(pos), false)) {
            player.connection.send(packet);
        }
    }

    /**
     * Clear our client-only fire visual (if anchored): tell each tracking
     * client to render the block as whatever the server actually has at
     * that position now (typically air, but could be a player-placed block
     * if the player built there during the burn).
     */
    private static void clearClientFireVisual(MinecraftServer server, BurnState state) {
        if (state.flameRenderPos == null || state.flameRenderLevelKey == null) return;
        ServerLevel level = server.getLevel(state.flameRenderLevelKey);
        if (level == null) return;
        sendClientFireUpdate(level, state.flameRenderPos,
            level.getBlockState(state.flameRenderPos));
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
     * Decide where to render the visual block-fire for a burning item entity:
     * the air block above the entity's resting position, provided the block
     * below it is a sturdy surface (so the flame layer sits on something the
     * player would expect to "catch fire").
     *
     * <p>Returns {@code null} when no suitable spot exists — the book keeps
     * burning visually (the per-item flame + smoke particles still emit),
     * but no block-fire layer is rendered.</p>
     */
    private static BlockPos pickFirePosFor(ServerLevel level, ItemEntity itemEntity) {
        BlockPos itemPos = itemEntity.blockPosition();
        // If the item is resting on a block (typical), put the flame on top
        // of that block — i.e. at the item's own block position when the
        // item sits just above ground. Use the air check to pick the highest
        // air position adjacent to the supporting surface.
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
     * Server-side entry point for {@link games.brennan.dungeontrain.net.StartingBookClosedPacket}.
     * The client just closed a held-book {@code BookViewScreen} (not a
     * lectern's) that was showing any burnable book (starting / random /
     * player-written / discovered-shared / narrative — see
     * {@link BurnableBookTag}). Find one such book in the player's
     * inventory, remove it, and drop it forward as if thrown.
     *
     * <p>The drop itself is sufficient — {@link #onEntityJoinLevel} sees the
     * resulting {@link ItemEntity}, recognises it as burnable via
     * {@link BurnableBookTag#isBurnable}, and registers it for the burn
     * lifecycle. No manual {@link #BURN_ENTITIES} {@code put} needed here.</p>
     */
    public static void handleStartingBookClosed(ServerPlayer player) {
        ItemStack stack = findAndRemoveBurnableBook(player);
        if (stack.isEmpty()) {
            // Client says we closed one, but we can't find it — probably a
            // race against another mutator (death drop, dropAll, etc.).
            // Silent no-op; the close already happened.
            return;
        }

        // Read-tracking: the player just opened (and closed) this book. If it is
        // an identity-stamped starting book, credit the exact (book, variant) in
        // the world-scoped seen-store so the "Read every starting book"
        // (all_starting_books / Inter-Reality Passenger) advancement can complete.
        // Random books and legacy boolean-only stamps return empty → safe no-op.
        StartingBookTag.read(stack).ifPresent(id -> {
            ServerLevel overworld = overworldOf(player);
            if (overworld == null) return;
            NarrativeProgressData data = NarrativeProgressData.get(overworld);
            if (data.markStartingBookVariantSeen(id.basename(), id.variantIndex())) {
                LOGGER.info("[DungeonTrain] StartingBook: world marked {} variant {} seen on read (by {})",
                    id.basename(), id.variantIndex(), player.getName().getString());
            }
            games.brennan.dungeontrain.event.AchievementEvents.notifyStoryProgress(player);
        });

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
        LOGGER.info("[DungeonTrain] BurnableBook: {} closed a burnable book — drop spawned (burn registered via EntityJoinLevelEvent)",
            player.getName().getString());
    }

    /**
     * Burn-on-drop hook. Fires every time an {@link Entity} is added to a
     * level (Q-throw, death-drop, hopper-eject, our own close-handler drop,
     * chest break, a broken lectern popping its locked book, anything that
     * calls {@code Level.addFreshEntity}). When the entity is an
     * {@link ItemEntity} carrying a burnable book ({@link BurnableBookTag} —
     * starting / random / player-written / discovered-shared / narrative),
     * register it in {@link #BURN_ENTITIES} so the burn lifecycle picks it
     * up on the next tick.
     *
     * <p>Filters that block registration:</p>
     * <ul>
     *   <li>Client side — server-only state.</li>
     *   <li>Non-{@code ItemEntity} entities.</li>
     *   <li>Stacks that aren't burnable per {@link BurnableBookTag#isBurnable}
     *       — vanilla written books, foreign items, etc.</li>
     *   <li>Entities flagged {@link #ENTITY_TAG_SPAWN_BOOK} — the lightning
     *       strike's welcome book. Without this skip, the welcome book would
     *       catch fire the moment the strike lands.</li>
     *   <li>Entities already tracked in {@link #BURN_ENTITIES} — defensive
     *       guard against double-registration if an event somehow fires twice.</li>
     * </ul>
     */
        public static void onEntityJoinLevel(net.minecraft.world.entity.Entity joiningEntity, net.minecraft.world.level.Level joinLevel, boolean loadedFromDisk) {
        if (joinLevel.isClientSide()) return;
        if (!(joiningEntity instanceof ItemEntity item)) return;
        ItemStack stack = item.getItem();
        if (!BurnableBookTag.isBurnable(stack)) return;
        if (item.getPersistentData().getBoolean(ENTITY_TAG_SPAWN_BOOK)) return;
        if (BURN_ENTITIES.containsKey(item.getUUID())) return;

        // Lock pickup for the burn window so the player can't snatch the
        // burning book mid-burn and stash it. Matches the close-handler
        // behaviour from the earlier inline-registration code path.
        // A signed "Death Note" curse book burns with the SOUL variant (ghostly soul-fire + a soul
        // sound) instead of the normal fire — see DeathNoteBookTag.
        boolean soul = DeathNoteBookTag.isDeathNote(stack);
        item.setPickUpDelay(BURN_DURATION_TICKS);
        BURN_ENTITIES.put(item.getUUID(), new BurnState(BURN_DURATION_TICKS, soul));

        // Ignition atmosphere — a quiet "whoosh" (a soul escape for a Death Note) at the drop point.
        // SOUL_ESCAPE is a Holder<SoundEvent> and FIRE_AMBIENT a plain SoundEvent, so branch rather
        // than mix them in one conditional (no single playSound-compatible type).
        ServerLevel level = (ServerLevel) item.level();
        if (soul) {
            level.playSound(null, item.getX(), item.getY(), item.getZ(),
                SoundEvents.SOUL_ESCAPE, SoundSource.PLAYERS, 0.6f, 1.5f);
        } else {
            level.playSound(null, item.getX(), item.getY(), item.getZ(),
                SoundEvents.FIRE_AMBIENT, SoundSource.PLAYERS, 0.6f, 1.5f);
        }

        LOGGER.info("[DungeonTrain] BurnableBook: detected dropped burnable book — burning entity {} ({} ticks)",
            item.getUUID(), BURN_DURATION_TICKS);

        notifyIfBurnedUnread(item, stack);
    }

    /**
     * Credits the "burned without reading" milestone when {@code stack} is a
     * starting/random/player-written/discovered-shared book (the immediate-burn
     * {@link SharedBookTag} contribution copy is excluded — burning that is the
     * intended outcome of signing, not an avoided read) that was never opened via
     * {@link BookReadMarkerTag}, and the drop was player-initiated (Q-throw, the
     * close-without-reading auto-drop, or a death-drop — anything with a
     * {@link ItemEntity#getOwner} resolving to a {@link ServerPlayer}). Incidental
     * spills with no thrower (hopper eject, chest break) don't count.
     */
    private static void notifyIfBurnedUnread(ItemEntity item, ItemStack stack) {
        if (SharedBookTag.isSharedBook(stack)) return;
        // Lectern letters burn as the intended outcome of signing (like a shared-book contribution),
        // and are spawned owner-less at the lectern anyway — never a "burned unread" avoided read.
        if (LetterBookTag.isLetter(stack)) return;
        boolean countsForMilestone = StartingBookTag.isStartingBook(stack)
                || RandomBookTag.read(stack).isPresent()
                || PlayerWrittenBookTag.isPlayerWritten(stack)
                || SharedBookFoundTag.isFound(stack);
        if (!countsForMilestone) return;
        if (BookReadMarkerTag.isOpened(stack)) return;
        if (!(item.getOwner() instanceof ServerPlayer player)) return;
        if (RunIntegrity.isCheated(player)) return; // global burn stat frozen for cheated runs

        long newTotal = GlobalBookBurnStats.addBooksBurnedUnread(player.getUUID(), 1L);
        AchievementEvents.notifyBooksBurnedUnread(player, newTotal);
    }

    /**
     * Scan the player's hands (mainhand → offhand) then full inventory for
     * the first {@link ItemStack} that is burnable per
     * {@link BurnableBookTag#isBurnable}, remove it from its slot, and
     * return a copy. Returns {@link ItemStack#EMPTY} when nothing matches.
     */
    private static ItemStack findAndRemoveBurnableBook(ServerPlayer player) {
        ItemStack mainhand = player.getMainHandItem();
        if (BurnableBookTag.isBurnable(mainhand)) {
            ItemStack copy = mainhand.copy();
            mainhand.setCount(0);  // empty the slot in-place
            return copy;
        }
        ItemStack offhand = player.getOffhandItem();
        if (BurnableBookTag.isBurnable(offhand)) {
            ItemStack copy = offhand.copy();
            offhand.setCount(0);
            return copy;
        }
        Inventory inv = player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (BurnableBookTag.isBurnable(s)) {
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
     *
     * <p>Roll source by context: RESPAWN cycles the per-world respawn pool;
     * NETHER/END cycle the per-installation dimension pool, marking the picked
     * variant seen when {@code markSeen} is true — the real login/respawn
     * caller passes true, while {@link #forceFireForTest} passes false for a
     * non-consuming preview; every other context does a plain weighted roll.</p>
     */
    private static void fireLightningAndDropBook(ServerPlayer player, StartingBookContext context, boolean markSeen) {
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
        } else if (context == StartingBookContext.NETHER || context == StartingBookContext.END) {
            // Per-installation dimension cycling: pick an unseen Nether/End
            // welcome and (on the real path) mark it seen. forceFireForTest
            // passes markSeen=false so the preview command doesn't consume.
            UUID uuid = player.getUUID();
            Set<String> seen = PlayerPlayedMarker.seenDimensionVariants(uuid);
            Consumer<String> mark = markSeen
                ? key -> PlayerPlayedMarker.markDimensionVariantSeen(uuid, key)
                : key -> { };
            bookOpt = StartingBookFactory.rollForDimension(seed, context, seen, mark);
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
        // The strike just marked a starting-book variant as seen (inside
        // rollForRespawn). Re-evaluate the all-starting-books milestone —
        // vanilla advancement dedupe ensures one-grant semantics.
        games.brennan.dungeontrain.event.AchievementEvents.notifyStoryProgress(player);

        LOGGER.info("[DungeonTrain] StartingBook: lightning strike for {} ({}) ctx={} at ({}, {}, {}) color=({}, {}, {})",
            player.getName().getString(), player.getUUID(), context,
            String.format("%.1f", landPos.x), String.format("%.1f", landPos.y), String.format("%.1f", landPos.z),
            String.format("%.2f", color.x), String.format("%.2f", color.y), String.format("%.2f", color.z));
    }

    /**
     * Resolve the welcome-strike context for a player on the login path,
     * read at strike-fire time so the world state is current.
     *
     * <p><b>Dimension routing (highest priority):</b> if the run started in
     * the Nether or the End (per {@link DungeonTrainWorldData#startingDimension()})
     * <em>and</em> this installation still has an unseen book in that
     * dimension's pool (per {@link PlayerPlayedMarker#seenDimensionVariants}),
     * return {@link StartingBookContext#NETHER} / {@link StartingBookContext#END}.
     * The dimension a run begins in is a stronger welcome signal than the
     * multiplayer/new-world lifecycle parity below — but it applies only as a
     * one-each playlist: once the player has been shown every variant in that
     * dimension's pool (or the pool is empty), this branch falls through to
     * the lifecycle logic, so a new world still yields NEW_WORLD/DEFAULT.
     * Overworld runs (and legacy worlds → OVERWORLD default) fall through too.
     * The parity counters are intentionally left untouched on this branch.</p>
     *
     * <p>Two-step decision (Overworld runs):</p>
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

        // Dimension routing wins for the welcome strike — see method javadoc.
        // A run that starts in the Nether/End gets a dimension-specific
        // welcome book ahead of the lifecycle parity below; OVERWORLD (and
        // unknown/legacy → OVERWORLD) returns empty and falls through.
        if (overworld != null) {
            String dimId = DungeonTrainWorldData.get(overworld).startingDimension().nbtId();
            Optional<StartingBookContext> dimensionContext = StartingBookContext.forDimensionNbtId(dimId);
            // Route to the dimension pool only while this installation still
            // has an unseen (book, variant) in it. Once the player has been
            // shown the whole Nether/End playlist (or it's empty), fall through
            // to the lifecycle resolution below (NEW_WORLD / DEFAULT / JOINED_WORLD).
            if (dimensionContext.isPresent()
                && StartingBookFactory.hasUnseenDimensionTuples(
                       dimensionContext.get(), PlayerPlayedMarker.seenDimensionVariants(uuid))) {
                return dimensionContext.get();
            }
        }

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
     * — testers want to fire as often as they like. For NETHER/END this is a
     * non-consuming preview: it shows the next unseen book but does NOT mark
     * the per-installation seen-set, so it won't disturb the real cycle.
     */
    public static void forceFireForTest(ServerPlayer player, StartingBookContext context) {
        fireLightningAndDropBook(player, context, /*markSeen*/ false);
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
