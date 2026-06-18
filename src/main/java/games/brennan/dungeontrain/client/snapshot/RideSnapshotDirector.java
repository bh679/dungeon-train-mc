package games.brennan.dungeontrain.client.snapshot;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.client.CinematicCameraController;
import games.brennan.dungeontrain.config.ClientDisplayConfig;
import games.brennan.playermob.entity.PlayerMobEntity;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.BookViewScreen;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.client.gui.screens.inventory.LecternScreen;
import net.minecraft.client.gui.screens.inventory.MerchantScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
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
 * and a periodic baseline ({@link SnapshotTag#SCENIC}) that <b>stops once enough
 * shots exist to cover the death pages</b>. Each category's cooldown grows with
 * how many of it were taken, so shots taper as the run progresses.</p>
 *
 * <p>Lighting + framing are enforced at render time; a request that can't find a
 * lit, clip-free, player-in-view angle is silently skipped and retried.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class RideSnapshotDirector {

    private static final double RIDE_RANGE = 24.0;
    private static final double SOCIAL_RANGE = 7.0;   // "close enough" to a player-mob
    private static final long COOLDOWN_GLOBAL = 40;   // min ticks between requests / retry back-off
    private static final int MAX_TAPER = 6;           // cooldown grows up to base × this over the run
    private static final int COVERAGE_TARGET = 6;     // periodic stops once this many total shots exist

    private static final long BASE_COMBAT = 8 * 20;
    private static final long BASE_GEAR = 10 * 20;
    private static final long BASE_LORE = 6 * 20;
    private static final long BASE_SOCIAL = 8 * 20;

    private static final EquipmentSlot[] ARMOR =
            { EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET };

    private static long lastAny = Long.MIN_VALUE;
    private static long lastScenic = Long.MIN_VALUE;
    private static long lastCombat = Long.MIN_VALUE;
    private static long lastGear = Long.MIN_VALUE;
    private static long lastLore = Long.MIN_VALUE;
    private static long lastSocial = Long.MIN_VALUE;

    private static int countScenic, countCombat, countGear, countLore, countSocial, countTotal;

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

        boolean book = mc.screen instanceof BookViewScreen || mc.screen instanceof LecternScreen;
        boolean merchant = mc.screen instanceof MerchantScreen;
        boolean container = mc.screen instanceof ContainerScreen;
        if (mc.screen != null && !book && !merchant && !container) return;

        Vec3 ppos = player.position();
        if (!NearestCarriage.playerAboardOrNear(level, ppos, RIDE_RANGE)) return;

        long now = level.getGameTime();
        if (!due(lastAny, now, COOLDOWN_GLOBAL)) return;

        Choice choice = chooseTag(level, player, ppos, now, book, merchant, container);
        if (choice == null) return;

        // Pose + lighting are decided at render time; just queue the tag.
        RideSnapshotCapture.request(choice.tag());
        lastAny = now;
        pendingReason = choice.reason();
    }

    private static Choice chooseTag(ClientLevel level, LocalPlayer player, Vec3 ppos, long now,
                                    boolean book, boolean merchant, boolean container) {
        if (merchant) {
            return due(lastSocial, now, taper(BASE_SOCIAL, countSocial)) ? new Choice(SnapshotTag.SOCIAL, "trading") : null;
        }
        if (book) {
            return due(lastLore, now, taper(BASE_LORE, countLore)) ? new Choice(SnapshotTag.LORE, "reading") : null;
        }
        if (container) {
            return due(lastGear, now, taper(BASE_GEAR, countGear)) ? new Choice(SnapshotTag.GEAR, "opened a chest") : null;
        }
        // No blocking screen open below here.
        if (socialPending) {
            socialPending = false;
            if (due(lastSocial, now, taper(BASE_SOCIAL, countSocial))) return new Choice(SnapshotTag.SOCIAL, "gave a gift");
        }
        if (due(lastSocial, now, taper(BASE_SOCIAL, countSocial))) {
            String reason = playerMobReason(level, player, ppos);
            if (reason != null) return new Choice(SnapshotTag.SOCIAL, reason);
        }
        if (combatPending) {
            combatPending = false;
            if (due(lastCombat, now, taper(BASE_COMBAT, countCombat))) return new Choice(SnapshotTag.COMBAT, "struck a foe");
        }
        if (potPending) {
            potPending = false;
            if (due(lastGear, now, taper(BASE_GEAR, countGear))) return new Choice(SnapshotTag.GEAR, "broke a pot");
        }
        if (due(lastGear, now, taper(BASE_GEAR, countGear)) && armorChanged(player)) {
            return new Choice(SnapshotTag.GEAR, "geared up");
        }
        long scenicBase = (long) ClientDisplayConfig.getRideSnapshotIntervalSeconds() * 20L;
        if (countTotal < COVERAGE_TARGET && due(lastScenic, now, taper(scenicBase, countScenic))) {
            return new Choice(SnapshotTag.SCENIC, "periodic");
        }
        return null;
    }

    /** Called by {@link RideSnapshotCapture} once a shot is actually taken (render-time success). */
    public static void onCaptureCommitted(SnapshotTag tag) {
        Minecraft mc = Minecraft.getInstance();
        long now = mc.level != null ? mc.level.getGameTime() : 0L;
        countTotal++;
        switch (tag) {
            case SCENIC -> { lastScenic = now; countScenic++; }
            case COMBAT -> { lastCombat = now; countCombat++; }
            case GEAR -> { lastGear = now; countGear++; snapshotArmor(mc.player); }
            case LORE -> { lastLore = now; countLore++; }
            case SOCIAL -> { lastSocial = now; countSocial++; }
        }
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
                .append(Component.literal("  (" + countFor(tag) + " " + name + ", " + countTotal + " total)")
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

    private static long taper(long base, int count) {
        return base * Math.min(MAX_TAPER, 1L + count);
    }

    private static boolean due(long last, long now, long cooldown) {
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

    private static int countFor(SnapshotTag tag) {
        return switch (tag) {
            case SCENIC -> countScenic;
            case COMBAT -> countCombat;
            case GEAR -> countGear;
            case LORE -> countLore;
            case SOCIAL -> countSocial;
        };
    }

    private static void reset() {
        lastAny = lastScenic = lastCombat = lastGear = lastLore = lastSocial = Long.MIN_VALUE;
        countScenic = countCombat = countGear = countLore = countSocial = countTotal = 0;
        for (int i = 0; i < lastArmor.length; i++) lastArmor[i] = ItemStack.EMPTY;
        socialPending = combatPending = potPending = false;
        pendingReason = "";
    }
}
