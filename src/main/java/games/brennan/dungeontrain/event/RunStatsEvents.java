package games.brennan.dungeontrain.event;

import com.mojang.logging.LogUtils;
import games.brennan.discordpresence.discord.DeathField;
import games.brennan.discordpresence.discord.DiscordService;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.advancement.GlobalPlayerStats;
import games.brennan.dungeontrain.config.DungeonTrainConfig;
import games.brennan.dungeontrain.discord.DeathReportFormat;
import games.brennan.dungeontrain.net.DeathStatsPacket;
import games.brennan.dungeontrain.net.DungeonTrainNet;
import games.brennan.dungeontrain.player.PlayerRunState;
import games.brennan.dungeontrain.registry.ModDataAttachments;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.entity.projectile.ThrownTrident;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.WrittenBookItem;
import net.minecraft.world.level.block.DecoratedPotBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import org.slf4j.Logger;

import java.util.List;

/**
 * Per-run stat tracking for the death-screen summary. All increments live
 * server-side on {@link PlayerRunState} and survive logout via the
 * attachment codec; they are cleared on respawn by
 * {@link AchievementEvents#onPlayerRespawn(net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerRespawnEvent)}.
 *
 * <p>Hooks:</p>
 * <ul>
 *   <li>{@link LivingDeathEvent} — increments {@code mobKills} on the killer
 *       when victim is any {@link LivingEntity} other than the killer. On
 *       the LOW-priority pass, snapshots the dying player's stats and sends
 *       them to that player via {@link DeathStatsPacket}.</li>
 *   <li>{@link PlayerTickEvent.Post} — per-tick {@code runTicks++} on
 *       server players.</li>
 *   <li>{@link BlockEvent.BreakEvent} — counts decorated-pot breaks as
 *       container opens.</li>
 *   <li>{@link PlayerInteractEvent.RightClickItem} — counts held
 *       written-book right-clicks (vanilla + narrative — narrative books
 *       are vanilla {@code written_book} items with extra NBT).</li>
 * </ul>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class RunStatsEvents {

    private static final Logger LOGGER = LogUtils.getLogger();
    /** Entity-id namespace of the bundled playermob mod (mirrors {@link PlayerMobAdvancementEvents}). */
    private static final String PLAYERMOB_NAMESPACE = "playermob";
    /** Encounter proximity-scan cadence (ticks). Matches the boarding / advancement scans. */
    private static final int ENCOUNTER_SCAN_PERIOD_TICKS = 10;
    /** Radius (blocks) within which a PlayerMob counts as "encountered". */
    private static final double ENCOUNTER_RADIUS = 16.0;
    /**
     * Upper bound on a single tracked damage event. Command / instakill sources
     * (e.g. {@code /kill} deals {@link Float#MAX_VALUE}) are already excluded by
     * the bypasses-invulnerability check; this is a defensive catch-all for any
     * other absurd magnitude so the damage stat can't blow out to ~3.4e38.
     */
    private static final float MAX_TRACKED_DAMAGE = 1_000_000.0f;

    private RunStatsEvents() {}

    @SubscribeEvent
    public static void onMobKilled(LivingDeathEvent event) {
        LivingEntity victim = event.getEntity();
        if (victim.level().isClientSide) return;
        if (!(event.getSource().getEntity() instanceof ServerPlayer killer)) return;
        // Don't credit a player for killing themselves (TNT, fall while
        // holding a damaging item, etc.). Stat is "things I killed" not
        // "deaths I caused".
        if (victim == killer) return;
        PlayerRunState run = killer.getData(ModDataAttachments.PLAYER_RUN_STATE.get());
        run.incrementMobKills();
        // PlayerMob kills are also tallied separately (the "players killed"
        // death-screen cell). mobKills still counts everything, so the two
        // overlap by design — a killed PlayerMob bumps both.
        if (isPlayerMob(victim)) run.incrementPlayerKills();
        // Attribute the kill to the weapon that actually dealt it. Arrows
        // (from bows/crossbows) and thrown tridents carry the firing weapon
        // on the projectile itself — that's the correct credit even if the
        // player swapped mainhand mid-flight. For other damage sources
        // (melee, fall damage proxies, non-firing projectiles like snowballs,
        // modded projectiles that don't expose their weapon), fall back to
        // the killer's current mainhand. Bare-hand kills become
        // {@code minecraft:air} and are filtered out of the final pick by
        // {@link PlayerRunState#mostUsedWeapon()}.
        Entity direct = event.getSource().getDirectEntity();
        ItemStack weaponStack = null;
        if (direct instanceof AbstractArrow arrow) {
            ItemStack w = arrow.getWeaponItem();
            if (w != null && !w.isEmpty()) weaponStack = w.copy();
        } else if (direct instanceof ThrownTrident trident) {
            ItemStack w = trident.getWeaponItem();
            if (w != null && !w.isEmpty()) weaponStack = w.copy();
        }
        if (weaponStack == null) {
            weaponStack = killer.getMainHandItem().copy();
        }
        run.recordWeaponKill(weaponStack);
    }

    /**
     * LOW priority so any higher-priority kill-credit / scoring handlers
     * (vanilla and other mods) finish before we snapshot the stats and
     * push them down to the client. Runs only when the victim is the
     * player itself; the kill-counter pass above already handled the
     * killer's tally.
     */
    @SubscribeEvent(priority = EventPriority.LOW)
    public static void onPlayerDeath(LivingDeathEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        PlayerRunState run = player.getData(ModDataAttachments.PLAYER_RUN_STATE.get());
        // Snapshot armor at death — the keep-inventory gamerule and respawn
        // both run AFTER LivingDeathEvent, so the equipment slots still
        // reflect what the player was wearing when they died.
        DeathStatsPacket packet = new DeathStatsPacket(
                run.mobKills(),
                run.cartsSinceDeath(),
                run.distanceBlocks(),
                run.runTicks(),
                run.containersOpened(),
                run.booksReadCount(),
                run.mostUsedWeapon(),
                player.getItemBySlot(EquipmentSlot.HEAD).copy(),
                player.getItemBySlot(EquipmentSlot.CHEST).copy(),
                player.getItemBySlot(EquipmentSlot.LEGS).copy(),
                player.getItemBySlot(EquipmentSlot.FEET).copy(),
                run.encounteredCount(),
                run.playerKills(),
                run.befriendedCount(),
                run.damageDealt(),
                run.damageTaken()
        );
        DungeonTrainNet.sendTo(player, packet);

        // Mirror the death-screen run summary to Discord via the bundled Discord Presence API.
        // Best-effort: a Discord hiccup must never disrupt the death handling above.
        if (DungeonTrainConfig.isDeathReportToDiscord()) {
            try {
                postRunSummary(player, event.getSource(), packet);
            } catch (Throwable t) {
                LOGGER.warn("[DungeonTrain] death report to Discord failed: {}", t.toString());
            }
        }
    }

    /**
     * Forward the run summary to Discord Presence's public death-report API: the death cause,
     * the same stats the death screen shows, and the most-used weapon + worn armor as the
     * composed item image. Discord Presence handles the embed, image, and posting off-thread.
     */
    private static void postRunSummary(ServerPlayer player, DamageSource source, DeathStatsPacket packet) {
        String cause = source.getLocalizedDeathMessage(player).getString();
        List<DeathField> fields = List.of(
                // Run-stats strip (top death-screen row).
                new DeathField("Distance", DeathReportFormat.distance(packet.distanceBlocks())),
                new DeathField("Time", DeathReportFormat.time(packet.runTicks())),
                new DeathField("Carts travelled", Integer.toString(packet.cartsTravelled())),
                new DeathField("Loot containers", Integer.toString(packet.containersOpened())),
                new DeathField("Books read", Integer.toString(packet.booksRead())),
                // Combat strip (death-screen combat row): PlayerMob interactions + damage.
                new DeathField("Players encountered", Integer.toString(packet.playersEncountered())),
                new DeathField("Players killed", Integer.toString(packet.playersKilled())),
                new DeathField("Players befriended", Integer.toString(packet.playersBefriended())),
                new DeathField("Mobs killed", Integer.toString(packet.mobKills())),
                new DeathField("Damage dealt", DeathReportFormat.damage(packet.damageDealt())),
                new DeathField("Damage taken", DeathReportFormat.damage(packet.damageTaken())));
        List<ItemStack> icons = List.of(
                packet.mostUsedWeapon(),
                packet.armorHead(), packet.armorChest(), packet.armorLegs(), packet.armorFeet());
        DiscordService.get().postDeathReport(player,
                "💀 " + player.getGameProfile().getName() + " — Run Ended", cause, fields, icons);
    }

    @SubscribeEvent
    public static void onPlayerTick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        player.getData(ModDataAttachments.PLAYER_RUN_STATE.get()).addRunTicks(1L);
    }

    @SubscribeEvent
    public static void onPotBreak(BlockEvent.BreakEvent event) {
        if (event.isCanceled()) return;
        if (event.getLevel().isClientSide()) return;
        if (!(event.getPlayer() instanceof ServerPlayer player)) return;
        BlockState state = event.getState();
        if (!(state.getBlock() instanceof DecoratedPotBlock)) return;
        player.getData(ModDataAttachments.PLAYER_RUN_STATE.get()).incrementContainersOpened();
    }

    @SubscribeEvent
    public static void onBookRead(PlayerInteractEvent.RightClickItem event) {
        if (event.getLevel().isClientSide) return;
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        ItemStack stack = event.getItemStack();
        if (stack.isEmpty()) return;
        if (!(stack.getItem() instanceof WrittenBookItem)) return;
        player.getData(ModDataAttachments.PLAYER_RUN_STATE.get()).incrementBooksRead();
    }

    /**
     * Accumulate per-run damage totals. {@link LivingDamageEvent.Post}
     * reports {@code getNewDamage()} — the post-mitigation damage actually
     * applied. One event credits both directions: damage the hurt entity
     * took (when it is a player) and damage a player dealt (when the source
     * entity is a player other than the victim).
     */
    @SubscribeEvent
    public static void onLivingDamage(LivingDamageEvent.Post event) {
        LivingEntity victim = event.getEntity();
        if (victim.level().isClientSide) return;
        // Exclude command / environment instakills: /kill deals Float.MAX_VALUE
        // and void death bypasses invulnerability — neither is meaningful combat,
        // and recording them blows the stat out to ~3.4e38 (overflowing the UI).
        if (event.getSource().is(DamageTypeTags.BYPASSES_INVULNERABILITY)) return;
        float amount = event.getNewDamage();
        if (!Float.isFinite(amount) || amount <= 0.0f || amount > MAX_TRACKED_DAMAGE) return;
        if (victim instanceof ServerPlayer hurt) {
            hurt.getData(ModDataAttachments.PLAYER_RUN_STATE.get()).addDamageTaken(amount);
        }
        if (event.getSource().getEntity() instanceof ServerPlayer dealer && dealer != victim) {
            dealer.getData(ModDataAttachments.PLAYER_RUN_STATE.get()).addDamageDealt(amount);
        }
    }

    /**
     * Periodic proximity scan: each player accumulates the set of distinct
     * PlayerMobs that have come within {@link #ENCOUNTER_RADIUS} blocks this
     * run (the death-screen "encountered" stat). The first time a given mob
     * is seen this run, the all-time {@link GlobalPlayerStats#addPlayersEncountered}
     * counter ticks and the "Strangers on a Train" milestone is checked.
     */
    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        if (level.getGameTime() % ENCOUNTER_SCAN_PERIOD_TICKS != 0L) return;
        List<ServerPlayer> players = level.players();
        if (players.isEmpty()) return;
        for (ServerPlayer player : players) {
            PlayerRunState run = player.getData(ModDataAttachments.PLAYER_RUN_STATE.get());
            AABB box = player.getBoundingBox().inflate(ENCOUNTER_RADIUS);
            for (Entity mob : level.getEntitiesOfClass(Entity.class, box, RunStatsEvents::isPlayerMob)) {
                if (run.recordEncounter(mob.getUUID())) {
                    long total = GlobalPlayerStats.addPlayersEncountered(player.getUUID(), 1L);
                    AchievementEvents.notifyEncounter(player, total);
                }
            }
        }
    }

    private static boolean isPlayerMob(Entity entity) {
        return entity != null
            && PLAYERMOB_NAMESPACE.equals(EntityType.getKey(entity.getType()).getNamespace());
    }
}
