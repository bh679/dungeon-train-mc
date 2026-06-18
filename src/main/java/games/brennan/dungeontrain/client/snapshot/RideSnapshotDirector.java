package games.brennan.dungeontrain.client.snapshot;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.client.CinematicCameraController;
import games.brennan.dungeontrain.client.VersionHudOverlay;
import games.brennan.dungeontrain.config.ClientDisplayConfig;
import games.brennan.playermob.entity.PlayerMobEntity;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.BookViewScreen;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.gui.screens.inventory.LecternScreen;
import net.minecraft.client.gui.screens.inventory.MerchantScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.DecoratedPotBlock;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.event.entity.player.AttackEntityEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

/**
 * Decides <em>when</em> a ride snapshot is taken (it does not build the pose —
 * that happens at render time in {@link RideSnapshotCapture}, so the camera lands
 * in the right coordinate space for a player riding a Sable ship). Runs each
 * client tick while the player is aboard / near the train.
 *
 * <p>Triggers: trading / player-mob (gift, crouch-greet, proximity)
 * ({@link SnapshotTag#SOCIAL}); first strike on a mob ({@link SnapshotTag#COMBAT});
 * opening a chest, breaking a decorated pot, or equipping armour
 * ({@link SnapshotTag#GEAR}); reading a narrative book ({@link SnapshotTag#LORE});
 * and a periodic baseline ({@link SnapshotTag#SCENIC}).</p>
 *
 * <p>The FIRST shot of each category fires as soon as its trigger occurs (no
 * wait); taking it starts that category's cooldown ({@link SnapshotCooldowns}).
 * Every later shot is a per-tag <b>dual gate</b>: it fires only once BOTH an
 * escalating wall-clock cooldown (1 unit, 2 units, … — 1 min for context tags,
 * the scenic interval for SCENIC) AND an escalating carriage-progress cooldown
 * (1 carriage, then ×1.5 + 2) have elapsed since that tag's last shot. So each
 * run captures early, then shots spread out and taper as it progresses.</p>
 *
 * <p>Captures are also gated on performance ({@link SnapshotPerformanceGate}): a shot is
 * taken only when client FPS — and, in single-player, server TPS — clear the configured
 * thresholds. A shot blocked this way holds its tag off for 20s
 * ({@link SnapshotCooldowns#onSkipped}) rather than burning the normal cooldown; after 5
 * skips in a row, the next open menu (inventory / chat / chest) gets one shot anyway,
 * resetting the streak — so a sustained-lag run still collects some photos.</p>
 *
 * <p>Lighting + framing are enforced at render time; a request that can't find a
 * lit, clip-free, player-in-view angle is silently skipped and retried.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class RideSnapshotDirector {

    private static final double RIDE_RANGE = 24.0;
    private static final double SOCIAL_RANGE = 7.0;   // "close enough" to a player-mob
    private static final long COOLDOWN_GLOBAL = 40;   // min ticks between requests / retry back-off
    /** Context tags (everything but SCENIC) use a 1-minute cooldown unit (1 min, 2 min, …). */
    private static final long CONTEXT_UNIT_TICKS = SnapshotCooldowns.ONE_MINUTE_TICKS;
    /** Consecutive perf-skips required before the low-perf menu fallback may fire. */
    private static final int SKIP_FALLBACK_THRESHOLD = 5;
    /** Clamp for {@link #skipsInARow} — no need to keep counting past the threshold. */
    private static final int SKIP_CAP = 5;

    private static final EquipmentSlot[] ARMOR =
            { EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET };

    /** Per-category dual-gate cadence (time + carriage progress), both escalating. */
    private static final SnapshotCooldowns COOLDOWNS = new SnapshotCooldowns();

    private static long lastAny = Long.MIN_VALUE;
    private static int countTotal;
    /** Consecutive shots blocked by the perf gate; resets on any committed capture (and run reset). */
    private static int skipsInARow;

    private static final ItemStack[] lastArmor = { ItemStack.EMPTY, ItemStack.EMPTY, ItemStack.EMPTY, ItemStack.EMPTY };
    private static volatile boolean socialPending; // gift / interaction with a player-mob
    private static volatile boolean combatPending; // first strike on a mob
    private static volatile boolean potPending;    // hit a decorated pot
    private static String pendingReason = "";

    private RideSnapshotDirector() {}

    private record Choice(SnapshotTag tag, String reason) {}

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        if (!ClientDisplayConfig.isRideSnapshotsEnabled()) return;

        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        LocalPlayer player = mc.player;
        if (level == null || player == null) return;
        if (CinematicCameraController.isActive()) return;
        if (RideSnapshotCapture.hasPending()) return;

        Screen screen = mc.screen;
        boolean book = screen instanceof BookViewScreen || screen instanceof LecternScreen;
        boolean merchant = screen instanceof MerchantScreen;
        boolean container = screen instanceof ContainerScreen;
        // Contexts that capture during normal (healthy-perf) play: in-world, or one of these screens.
        boolean normalScreenOk = screen == null || book || merchant || container;
        // Any "menu open" moment the low-perf fallback may piggy-back on — adds the e-menu + chat.
        boolean fallbackMenuOpen = book || merchant || container
                || screen instanceof InventoryScreen
                || screen instanceof ChatScreen;

        Vec3 ppos = player.position();
        if (!NearestCarriage.playerAboardOrNear(level, ppos, RIDE_RANGE)) return;

        long now = level.getGameTime();
        if (!dueSince(lastAny, now, COOLDOWN_GLOBAL)) return;

        boolean perfOk = performanceOk(mc);

        // Low-perf fallback: once several shots in a row have been skipped for poor performance,
        // grab one the next time a menu is open — a quiet moment where the capture hitch is least
        // noticeable — then let the streak reset on commit. Bypasses both the perf and due gates.
        if (!perfOk && fallbackMenuOpen && skipsInARow >= SKIP_FALLBACK_THRESHOLD) {
            RideSnapshotCapture.request(SnapshotTag.SCENIC);
            lastAny = now;
            pendingReason = "low-perf menu fallback";
            return;
        }

        if (!normalScreenOk) return; // e-menu / chat alone are not normal-capture contexts

        int progress = VersionHudOverlay.travelledCarriageIndex();
        Choice choice = chooseTag(level, player, ppos, now, progress, book, merchant, container);
        if (choice == null) return;

        if (perfOk) {
            // Pose + lighting are decided at render time; just queue the tag.
            RideSnapshotCapture.request(choice.tag());
            lastAny = now;
            pendingReason = choice.reason();
        } else {
            // Game is struggling — skip this shot, hold the tag off for 20s, and count the skip
            // toward the menu fallback. The escalating cooldown is untouched (no commit happened).
            COOLDOWNS.onSkipped(choice.tag(), now);
            skipsInARow = Math.min(skipsInARow + 1, SKIP_CAP);
            lastAny = now;
        }
    }

    /** Is the client (and, in single-player, the integrated server) running well enough to spend a capture? */
    private static boolean performanceOk(Minecraft mc) {
        IntegratedServer server = mc.getSingleplayerServer(); // null on a multiplayer/dedicated server
        boolean tpsKnown = server != null;
        double tps = tpsKnown
                ? SnapshotPerformanceGate.tpsFromTickNanos(server.getAverageTickTimeNanos())
                : SnapshotPerformanceGate.MAX_TPS;
        return SnapshotPerformanceGate.ok(
                mc.getFps(), ClientDisplayConfig.getRideSnapshotMinFps(),
                tps, ClientDisplayConfig.getRideSnapshotMinTps(), tpsKnown);
    }

    private static Choice chooseTag(ClientLevel level, LocalPlayer player, Vec3 ppos, long now, int progress,
                                    boolean book, boolean merchant, boolean container) {
        if (merchant) {
            return due(SnapshotTag.SOCIAL, now, progress) ? new Choice(SnapshotTag.SOCIAL, "trading") : null;
        }
        if (book) {
            return due(SnapshotTag.LORE, now, progress) ? new Choice(SnapshotTag.LORE, "reading") : null;
        }
        if (container) {
            return due(SnapshotTag.GEAR, now, progress) ? new Choice(SnapshotTag.GEAR, "opened a chest") : null;
        }
        // No blocking screen open below here.
        if (socialPending) {
            socialPending = false;
            if (due(SnapshotTag.SOCIAL, now, progress)) return new Choice(SnapshotTag.SOCIAL, "gave a gift");
        }
        if (due(SnapshotTag.SOCIAL, now, progress)) {
            String reason = playerMobReason(level, player, ppos);
            if (reason != null) return new Choice(SnapshotTag.SOCIAL, reason);
        }
        if (combatPending) {
            combatPending = false;
            if (due(SnapshotTag.COMBAT, now, progress)) return new Choice(SnapshotTag.COMBAT, "struck a foe");
        }
        if (potPending) {
            potPending = false;
            if (due(SnapshotTag.GEAR, now, progress)) return new Choice(SnapshotTag.GEAR, "broke a pot");
        }
        if (due(SnapshotTag.GEAR, now, progress) && armorChanged(player)) {
            return new Choice(SnapshotTag.GEAR, "geared up");
        }
        if (due(SnapshotTag.SCENIC, now, progress)) {
            return new Choice(SnapshotTag.SCENIC, "periodic");
        }
        return null;
    }

    /** Called by {@link RideSnapshotCapture} once a shot is actually taken (render-time success). */
    public static void onCaptureCommitted(SnapshotTag tag) {
        Minecraft mc = Minecraft.getInstance();
        long now = mc.level != null ? mc.level.getGameTime() : 0L;
        int progress = VersionHudOverlay.travelledCarriageIndex();
        countTotal++;
        skipsInARow = 0; // a committed shot breaks the perf-skip streak (resets the menu fallback)
        COOLDOWNS.onCommitted(tag, now, progress);
        if (tag == SnapshotTag.GEAR) snapshotArmor(mc.player);
        chatLog(tag, pendingReason);
    }

    private static void chatLog(SnapshotTag tag, String reason) {
        if (!ClientDisplayConfig.isRideSnapshotChatLogEnabled()) return;
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) return;
        String name = tag.name().toLowerCase();
        Component msg = Component.literal("[Ride Snapshot] ").withStyle(ChatFormatting.DARK_AQUA)
                .append(Component.literal(tag.name()).withStyle(ChatFormatting.AQUA))
                .append(Component.literal(" — " + reason).withStyle(ChatFormatting.GRAY))
                .append(Component.literal("  (" + COOLDOWNS.count(tag) + " " + name + ", " + countTotal + " total)")
                        .withStyle(ChatFormatting.DARK_GRAY));
        player.displayClientMessage(msg, false);
    }

    // ── Event-driven triggers (robust to the Sable tick/render coordinate split) ──

    @SubscribeEvent
    public static void onEntityInteract(PlayerInteractEvent.EntityInteract event) {
        if (!event.getLevel().isClientSide() || !ClientDisplayConfig.isRideSnapshotsEnabled()) return;
        if (event.getTarget() instanceof PlayerMobEntity) socialPending = true; // gift / interaction
    }

    @SubscribeEvent
    public static void onAttackEntity(AttackEntityEvent event) {
        if (!event.getEntity().level().isClientSide() || !ClientDisplayConfig.isRideSnapshotsEnabled()) return;
        if (event.getTarget() instanceof Mob) combatPending = true; // first strike
    }

    @SubscribeEvent
    public static void onLeftClickBlock(PlayerInteractEvent.LeftClickBlock event) {
        if (!event.getLevel().isClientSide() || !ClientDisplayConfig.isRideSnapshotsEnabled()) return;
        if (event.getLevel().getBlockState(event.getPos()).getBlock() instanceof DecoratedPotBlock) potPending = true;
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        RideSnapshotGallery.clear();
        RideSnapshotCapture.disposeTarget();
        reset();
    }

    // ── helpers ──────────────────────────────────────────────────────────

    /** Per-category due check (first shot immediate, then escalating time + carriage) via {@link SnapshotCooldowns}. */
    private static boolean due(SnapshotTag tag, long now, int progress) {
        return COOLDOWNS.due(tag, now, progress, unitTicks(tag));
    }

    /** Per-shot cooldown unit: SCENIC uses the configurable interval; context tags use 1 min. */
    private static long unitTicks(SnapshotTag tag) {
        return tag == SnapshotTag.SCENIC
                ? (long) ClientDisplayConfig.getRideSnapshotIntervalSeconds() * 20L
                : CONTEXT_UNIT_TICKS;
    }

    /** Simple elapsed-ticks gate, used for the global one-request/retry back-off. */
    private static boolean dueSince(long last, long now, long cooldown) {
        return last == Long.MIN_VALUE || now - last >= cooldown;
    }

    /** "greeting a traveler" (crouching) / "near a traveler" if a player-mob is close, else null. */
    private static String playerMobReason(ClientLevel level, LocalPlayer player, Vec3 ppos) {
        AABB area = player.getBoundingBox().inflate(SOCIAL_RANGE);
        double rangeSq = SOCIAL_RANGE * SOCIAL_RANGE;
        for (PlayerMobEntity pm : level.getEntitiesOfClass(PlayerMobEntity.class, area)) {
            if (pm.isAlive() && pm.distanceToSqr(ppos) <= rangeSq) {
                return player.isCrouching() ? "greeting a traveler" : "near a traveler";
            }
        }
        return null;
    }

    private static boolean armorChanged(LocalPlayer player) {
        for (int i = 0; i < ARMOR.length; i++) {
            ItemStack cur = player.getItemBySlot(ARMOR[i]);
            if (!cur.isEmpty() && !ItemStack.isSameItem(cur, lastArmor[i])) return true;
        }
        return false;
    }

    private static void snapshotArmor(LocalPlayer player) {
        if (player == null) return;
        for (int i = 0; i < ARMOR.length; i++) {
            lastArmor[i] = player.getItemBySlot(ARMOR[i]).copy();
        }
    }

    private static void reset() {
        lastAny = Long.MIN_VALUE;
        countTotal = 0;
        skipsInARow = 0;
        COOLDOWNS.reset();
        for (int i = 0; i < lastArmor.length; i++) lastArmor[i] = ItemStack.EMPTY;
        socialPending = combatPending = potPending = false;
        pendingReason = "";
    }
}
