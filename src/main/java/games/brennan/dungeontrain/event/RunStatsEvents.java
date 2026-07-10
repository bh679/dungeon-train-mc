package games.brennan.dungeontrain.event;

import com.mojang.logging.LogUtils;
import games.brennan.discordpresence.discord.DeathField;
import games.brennan.discordpresence.discord.DiscordService;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.advancement.GlobalPlayerStats;
import games.brennan.dungeontrain.cheat.RunIntegrity;
import games.brennan.dungeontrain.compat.EchoIdentity;
import games.brennan.dungeontrain.config.DungeonTrainConfig;
import games.brennan.dungeontrain.discord.DeathDetailReporter;
import games.brennan.dungeontrain.discord.DeathEquipmentReporter;
import games.brennan.dungeontrain.discord.DeathInventoryReporter;
import games.brennan.dungeontrain.discord.DeathReporter;
import games.brennan.dungeontrain.discord.RunSummaryReporter;
import games.brennan.dungeontrain.discord.DeathManifestFormat;
import games.brennan.dungeontrain.discord.DeathReportFormat;
import games.brennan.dungeontrain.narrative.DeathLoreStore;
import games.brennan.dungeontrain.net.AbandonRunPacket;
import games.brennan.dungeontrain.net.DeathNarrative;
import games.brennan.dungeontrain.net.DeathStatsPacket;
import games.brennan.dungeontrain.net.DungeonTrainNet;
import games.brennan.dungeontrain.player.PlayerMobAppearance;
import games.brennan.dungeontrain.player.PlayerRunState;
import games.brennan.dungeontrain.registry.ModDataAttachments;
import games.brennan.dungeontrain.util.SecondPersonDeathMessage;
import games.brennan.playermob.entity.PlayerMobEntity;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.resources.ResourceLocation;
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
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

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
    /** Radius (blocks) within which another passenger counts toward "Others?". */
    private static final double PROXIMITY_RADIUS = 4.0;
    /** A PlayerMob whose feeling (0–10 scale, default 5) toward the player exceeds this is the death-screen "friend" (portrait). */
    private static final float FRIEND_FEELING_MIN = 6.0f;
    /**
     * Upper bound on a single tracked damage event. Command / instakill sources
     * (e.g. {@code /kill} deals {@link Float#MAX_VALUE}) are already excluded by
     * the bypasses-invulnerability check; this is a defensive catch-all for any
     * other absurd magnitude so the damage stat can't blow out to ~3.4e38.
     */
    private static final float MAX_TRACKED_DAMAGE = 1_000_000.0f;
    /**
     * An abandoned run (pause-menu "Abandon This Run") that reached fewer than this many
     * carriages is kept OFF the public death feed — a player who steps off after a handful
     * of carriages shouldn't broadcast a trivial "Run Ended" manifest. See
     * {@link #suppressPublicAbandon(boolean, int)}.
     */
    private static final int MIN_CARRIAGES_FOR_PUBLIC_ABANDON = 20;

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
        // death-screen cell) and the victim's look is snapshotted for the
        // death-screen portrait (most-recent kill wins). mobKills still counts
        // everything, so the two overlap by design — a killed PlayerMob bumps both.
        if (victim instanceof PlayerMobEntity pm) {
            run.incrementPlayerKills();
            run.setKilledAppearance(PlayerMobAppearance.capture(pm));
        }
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
        if (event.isCanceled()) return;
        if (!(event.getEntity() instanceof ServerPlayer player)) return;

        // Accumulate this run's per-life totals into the cross-world lifetime
        // counters BEFORE respawn resets PlayerRunState. trainTicks already
        // accrues live (BoardingProgressEvents), so it is NOT re-added here.
        PlayerRunState run = player.getData(ModDataAttachments.PLAYER_RUN_STATE.get());
        UUID id = player.getUUID();
        // Cheated runs don't accrue the cross-world lifetime counters. The death
        // screen still renders below — it reads the per-run state plus the current
        // (un-incremented) totals — so a cheating player still sees their summary.
        boolean cheated = RunIntegrity.isCheated(player);
        long lifeDeaths;
        if (cheated) {
            lifeDeaths = GlobalPlayerStats.totalDeaths(id);
        } else {
            GlobalPlayerStats.addCarriages(id, run.cartsSinceDeath());
            GlobalPlayerStats.addDistance(id, run.distanceBlocks());
            GlobalPlayerStats.addBooks(id, run.booksReadCount());
            GlobalPlayerStats.addFriends(id, run.befriendedCount());
            // All-lives icon-row lifetime totals. (playersEncountered + echos already accrue live in
            // the encounter scan; advancements are counted point-in-time in buildPacket — neither here.)
            GlobalPlayerStats.addBooksWritten(id, run.booksWrittenCount());
            GlobalPlayerStats.addContainers(id, run.containersOpened());
            GlobalPlayerStats.addMobKills(id, run.mobKills());
            GlobalPlayerStats.addPlayerKills(id, run.playerKills());
            GlobalPlayerStats.addDamageDealt(id, run.damageDealt());
            GlobalPlayerStats.addDamageTaken(id, run.damageTaken());
            lifeDeaths = GlobalPlayerStats.addDeaths(id, 1L);
        }

        // Roll the per-death narrative from the data-driven pool, keyed on the
        // killer + this run's depth / social / lifetime context. On a Free Play
        // run we don't persist the death increment, but the narrative should still
        // reflect the death that just happened — otherwise a first creative death
        // rolls with deaths=0 and no platform lore entry matches (blank last page).
        long narrativeDeaths = cheated ? lifeDeaths + 1 : lifeDeaths;
        DeathNarrative narrative = rollNarrative(player, event.getSource(), run, narrativeDeaths);

        // The second-person death cause ("You fell from a high place"), shown as the
        // fall-page title. Built from the same DamageSource the Discord report uses.
        String deathCause = secondPersonCause(player, event.getSource());

        // Snapshot armor at death — the keep-inventory gamerule and respawn both run AFTER
        // LivingDeathEvent, so the equipment slots still reflect what the player died wearing.
        DeathStatsPacket packet = buildPacket(player,
                lifeDeaths,
                GlobalPlayerStats.totalCarriages(id),
                GlobalPlayerStats.totalDistance(id),
                GlobalPlayerStats.totalFriends(id),
                GlobalPlayerStats.totalBooks(id),
                GlobalPlayerStats.trainTicks(id),
                narrative,
                deathCause);
        DungeonTrainNet.sendTo(player, packet);

        // Relay the worn armor + held item to dp-relay for the data explorer's player cards.
        // Independent of the Discord toggle below — gated on its own inside the reporter.
        DeathEquipmentReporter.report(player,
                packet.armorHead(), packet.armorChest(), packet.armorLegs(), packet.armorFeet());

        // Relay this life's run summary (duration + carriage + distance) to dp-relay for the data
        // explorer's per-life playtime — same gate/pattern as the equipment reporter above.
        RunSummaryReporter.report(player, packet);

        // Relay a first-class per-death record so the explorer counts EVERY death, not only the ones
        // that post a Discord death report below. Fires independent of isDeathReportToDiscord() (Free
        // Play / short-abandon / report-disabled deaths still count) — gated only on worldInfoToRelay.
        DeathReporter.report(player, packet);

        // Relay the full paginated narrative + death-screen stats, and the full hotbar/main-inventory
        // + offhand, so the data explorer's per-death detail view can show everything the death screen
        // did. Same gate/pattern as the reporters above.
        DeathDetailReporter.report(player, packet);
        DeathInventoryReporter.report(player);

        // Mirror the death-screen run summary to Discord via the bundled Discord Presence API.
        // Posts even on a Free Play run (the death screen renders for cheated runs too); only the
        // cross-world stat accrual above is frozen in Free Play, not the Discord report.
        // Best-effort: a Discord hiccup must never disrupt the death handling above.
        if (DungeonTrainConfig.isDeathReportToDiscord()) {
            try {
                postRunSummary(player, event.getSource(), packet, cheated);
            } catch (Throwable t) {
                LOGGER.warn("[DungeonTrain] death report to Discord failed: {}", t.toString());
            }
        }
    }

    /**
     * Roll the data-driven death narrative for {@code player}: build the
     * {@link DeathLoreStore.Context} from the killer entity type and this run's
     * depth / social counters (plus the freshly-incremented lifetime death
     * count), then let {@link DeathLoreStore} pick + substitute one line per
     * page. Empty slots come back when no entry matches a page.
     */
    private static DeathNarrative rollNarrative(ServerPlayer player, DamageSource source,
                                                PlayerRunState run, long lifeDeaths) {
        ResourceLocation cause = source.getEntity() != null
                ? EntityType.getKey(source.getEntity().getType()) : null;
        int hearts = (int) Math.round(run.damageTaken() / 2.0);
        DeathLoreStore.Context ctx = new DeathLoreStore.Context(
                cause,
                run.cartsSinceDeath(),
                run.befriendedCount(),
                run.booksReadCount(),
                run.mobKills(),
                run.encounteredCount(),
                run.playerKills(),
                hearts,
                run.distanceBlocks(),
                lifeDeaths,
                run.containersOpened());
        return DeathLoreStore.buildNarrative(ctx, player.serverLevel().getRandom());
    }

    /**
     * Build the fall-page title: the player's death message rewritten in the second person.
     * Vanilla and modded death messages always lead with the victim's display name (e.g.
     * "Brennan was slain by Zombie") — swap that name for "You", then fix the dominant
     * "was …" verb agreement ("You was" → "You were"). If the message doesn't begin with
     * the player's name (an unusual modded format), it is returned unchanged.
     */
    private static String secondPersonCause(ServerPlayer player, DamageSource source) {
        String msg = source.getLocalizedDeathMessage(player).getString();
        String name = player.getDisplayName().getString();
        return SecondPersonDeathMessage.rewrite(msg, name);
    }

    /**
     * Forward the run summary to Discord Presence's public death-report API: the death cause,
     * the same stats the death screen shows, and the most-used weapon + worn armor as the
     * composed item image. Discord Presence handles the embed, image, and posting off-thread.
     */
    private static void postRunSummary(ServerPlayer player, DamageSource source, DeathStatsPacket packet,
                                       boolean cheated) {
        String cause = source.getLocalizedDeathMessage(player).getString();
        String title = "💀 " + player.getGameProfile().getName() + " — Run Ended";
        List<DeathField> fields = runFields(packet);
        List<ItemStack> icons = runIcons(packet);
        DiscordService.get().postDeathReport(player, title, cause, fields, icons);
        // Also post the redesigned "manifest" report OUTSIDE the player's thread — the public death feed:
        // the rolled fall narration, each section headed by the line the player saw, de-duped stat
        // strips, and this run's ride photo. Routes to the PUBLIC channel on release (main) builds and
        // to the dev channel on dev builds (manifestWebhookOverride: null on dev → default cap). On
        // RELEASE builds, Free Play (cheated) runs are EXCLUDED from the public feed — they still get
        // the basic threaded report above. Dev/test builds DO post Free Play runs (to the dev channel)
        // so the report stays testable in creative — see DungeonTrain.isDevBuild().
        //
        // Short abandoned runs are ALSO withheld from the public feed: a player who taps "Abandon This
        // Run" before reaching MIN_CARRIAGES_FOR_PUBLIC_ABANDON carriages shouldn't broadcast a trivial
        // manifest. The threaded report above still posts. Applies on dev + release — noise in either.
        boolean shortAbandon = suppressPublicAbandon(
                AbandonRunPacket.isAbandonCause(source), packet.cartsTravelled());
        if (shortAbandon) {
            LOGGER.debug("[DungeonTrain] {} abandoned at {} carriages (< {}) — skipping public death feed",
                    player.getGameProfile().getName(), packet.cartsTravelled(), MIN_CARRIAGES_FOR_PUBLIC_ABANDON);
        }
        if ((!cheated || DungeonTrain.isDevBuild()) && !shortAbandon) {
            List<String> advTitles = resolveAdvancementTitles(player, packet.earnedAdvancements());
            String manifestTitle = DeathManifestFormat.title(
                    player.getGameProfile().getName(), packet.cartsTravelled());
            String manifestDesc = DeathManifestFormat.description(
                    packet.narrative(), packet.playersKilled(), packet.playersBefriended(),
                    packet.containersOpened());
            List<DeathField> manifestFields = DeathManifestFormat.fields(
                    packet.deathCause(),
                    packet.distanceBlocks(), packet.runTicks(), packet.damageDealt(), packet.damageTaken(),
                    packet.containersOpened(), packet.booksRead(), advTitles,
                    packet.playersEncountered(), packet.playersBefriended(), packet.playersKilled());
            // Buffer the top-level report until the client sends this run's scenic ride photo
            // (DeathPhotoPacket); a 5s timeout posts it with the gear composite if the photo never comes.
            DeathReportBuffer.await(player, manifestTitle, manifestDesc, manifestFields, icons,
                    DungeonTrain.manifestWebhookOverride());
        }
    }

    /**
     * Whether this run's public ("manifest") death report should be withheld because it was a
     * <em>short abandoned run</em>: the player ended it via the pause-menu "Abandon This Run"
     * ({@code abandoned}) before reaching {@link #MIN_CARRIAGES_FOR_PUBLIC_ABANDON} carriages.
     * Pure (no Minecraft types) so it is unit-testable; the threaded per-player report is
     * unaffected — only the public feed gates on this.
     */
    static boolean suppressPublicAbandon(boolean abandoned, int cartsTravelled) {
        return abandoned && cartsTravelled < MIN_CARRIAGES_FOR_PUBLIC_ABANDON;
    }

    /**
     * Resolve each earned advancement id to its display title (server-side), skipping ids the server
     * can't resolve or that have no display. Used for the manifest report's "the cargo" segment.
     */
    private static List<String> resolveAdvancementTitles(ServerPlayer player, List<ResourceLocation> ids) {
        List<String> out = new ArrayList<>();
        var advancements = player.server.getAdvancements();
        for (ResourceLocation id : ids) {
            AdvancementHolder h = advancements.get(id);
            if (h == null) continue;
            h.value().display().ifPresent(d -> out.add(d.getTitle().getString()));
        }
        return out;
    }

    /**
     * Point-in-time count of Dungeon Train advancements this player has completed — the all-lives
     * "advancements earned" icon. Counted live from the player's advancement progress (not a running
     * counter) so the login-replay re-fire of {@code AdvancementEarnEvent} can't inflate it. Vanilla
     * advancement completion is permanent per-player, so this is a true across-all-lives total.
     */
    private static long countDungeonTrainAdvancements(ServerPlayer player) {
        var manager = player.server.getAdvancements();
        var progress = player.getAdvancements();
        long count = 0;
        for (AdvancementHolder h : manager.getAllAdvancements()) {
            if (!DungeonTrain.MOD_ID.equals(h.id().getNamespace())) continue;
            if (progress.getOrStartProgress(h).isDone()) count++;
        }
        return count;
    }

    /**
     * Mirror the run summary to Discord when a player leaves the game ALIVE (e.g. quits to the main
     * menu): the SAME stats + gear image as the death report, but via Discord Presence's grey "left
     * the game" report instead of the red death one. A player who leaves DEAD is skipped — their
     * death already posted the summary, and the run stats reset on respawn. Gated by the same
     * {@code deathReportToDiscord} toggle. Best-effort; a Discord hiccup never blocks logout.
     */
    @SubscribeEvent
    public static void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (player.isDeadOrDying()) return;
        // Free Play runs still post the "left the game" summary — only cross-world stat accrual
        // (elsewhere) is frozen in Free Play, not the Discord report.
        if (!DungeonTrainConfig.isDeathReportToDiscord()) return;
        try {
            UUID id = player.getUUID();
            DeathStatsPacket packet = buildPacket(player,
                    GlobalPlayerStats.totalDeaths(id),
                    GlobalPlayerStats.totalCarriages(id),
                    GlobalPlayerStats.totalDistance(id),
                    GlobalPlayerStats.totalFriends(id),
                    GlobalPlayerStats.totalBooks(id),
                    GlobalPlayerStats.trainTicks(id),
                    DeathNarrative.EMPTY, "");
            DiscordService.get().postDisconnectReport(player,
                    "👋 " + player.getGameProfile().getName() + " left the game", "",
                    runFields(packet), runIcons(packet));
        } catch (Throwable t) {
            LOGGER.warn("[DungeonTrain] disconnect report to Discord failed: {}", t.toString());
        }
    }

    /**
     * Snapshot the player's current run stats + worn armor into a {@link DeathStatsPacket}, shared by
     * the death summary and the alive-logout summary. The equipment slots reflect the player's gear
     * right now (at death — before respawn/keep-inventory; at logout — their live gear).
     */
    private static DeathStatsPacket buildPacket(ServerPlayer player,
            long lifeDeaths, long lifeCarriages, double lifeDistance,
            long lifeFriends, long lifeBooks, long lifeTrainTicks,
            DeathNarrative narrative, String deathCause) {
        PlayerRunState run = player.getData(ModDataAttachments.PLAYER_RUN_STATE.get());
        UUID id = player.getUUID();
        // All-lives icon-row lifetime totals — the current cross-world totals (accrued just above at
        // death, or live for encountered/echos), plus a point-in-time count of earned DT advancements.
        long lifeBooksWritten = GlobalPlayerStats.totalBooksWritten(id);
        long lifeContainers = GlobalPlayerStats.totalContainers(id);
        long lifeMobKills = GlobalPlayerStats.totalMobKills(id);
        long lifePlayersKilled = GlobalPlayerStats.totalPlayerKills(id);
        long lifePlayersEncountered = GlobalPlayerStats.playersEncountered(id);
        long lifeEchos = GlobalPlayerStats.totalEchos(id);
        long lifeAdvancements = countDungeonTrainAdvancements(player);
        double lifeDamageDealt = GlobalPlayerStats.totalDamageDealt(id);
        double lifeDamageTaken = GlobalPlayerStats.totalDamageTaken(id);
        // Death-screen portrait subject: prefer a befriended mob (drawn left),
        // else the most-recent killed mob (drawn right), else none.
        byte side;
        PlayerMobAppearance portrait;
        if (run.friendAppearance() != null) {
            side = 1;
            portrait = run.friendAppearance();
        } else if (run.killedAppearance() != null) {
            side = 2;
            portrait = run.killedAppearance();
        } else {
            side = 0;
            portrait = null;
        }
        return new DeathStatsPacket(
                run.mobKills(),
                run.cartsSinceDeath(),
                run.distanceBlocks(),
                run.runTicks(),
                run.containersOpened(),
                run.booksReadCount(),
                run.booksWrittenCount(),
                run.mostUsedWeapon(),
                player.getItemBySlot(EquipmentSlot.HEAD).copy(),
                player.getItemBySlot(EquipmentSlot.CHEST).copy(),
                player.getItemBySlot(EquipmentSlot.LEGS).copy(),
                player.getItemBySlot(EquipmentSlot.FEET).copy(),
                run.encounteredCount(),
                run.playerKills(),
                run.befriendedCount(),
                run.damageDealt(),
                run.damageTaken(),
                lifeDeaths, lifeCarriages, lifeDistance, lifeFriends, lifeBooks, lifeTrainTicks,
                lifeBooksWritten, lifeContainers, lifeMobKills, lifePlayersKilled,
                lifePlayersEncountered, lifeEchos, lifeAdvancements, lifeDamageDealt, lifeDamageTaken,
                narrative,
                deathCause,
                side,
                portrait,
                run.earnedAdvancements()
        );
    }

    /** The death-screen run-summary fields, shared by the death report and the logout report. */
    private static List<DeathField> runFields(DeathStatsPacket packet) {
        return List.of(
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
    }

    /** Most-used weapon + worn armor, for the report's composed item image. */
    private static List<ItemStack> runIcons(DeathStatsPacket packet) {
        return List.of(
                packet.mostUsedWeapon(),
                packet.armorHead(), packet.armorChest(), packet.armorLegs(), packet.armorFeet());
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
            boolean cheated = RunIntegrity.isCheated(player);
            AABB box = player.getBoundingBox().inflate(ENCOUNTER_RADIUS);
            for (Entity mob : level.getEntitiesOfClass(Entity.class, box, RunStatsEvents::isPlayerMob)) {
                // Per-run encounter set still records (death-screen stat); the
                // cross-world lifetime counter + milestone freeze for cheated runs.
                if (run.recordEncounter(mob.getUUID()) && !cheated) {
                    long total = GlobalPlayerStats.addPlayersEncountered(player.getUUID(), 1L);
                    AchievementEvents.notifyEncounter(player, total);
                    // Echoes (reincarnations of a fallen player) are a distinct subset of encountered
                    // PlayerMobs — tally them separately for the all-lives "Echos come across" icon.
                    if (EchoIdentity.sourcePlayer(mob).isPresent()) {
                        GlobalPlayerStats.addEchos(player.getUUID(), 1L);
                    }
                }
                // "Others?" — within 4 blocks of any PlayerMob. Earns regardless of
                // game mode / cheat state (advancements earn live in Free Play too) and
                // without an on-train gate: PlayerMobs live on the train anyway, and the
                // AABB gate was unreliable on Sable ships. Idempotent, so firing each
                // scan is harmless.
                if (player.distanceTo(mob) <= PROXIMITY_RADIUS) {
                    AchievementEvents.notifyProximityOnTrain(player);
                }
                // Death-screen "friends": any PlayerMob that likes this player above
                // the threshold counts toward the friends tally (distinct), and the
                // warmest of them is the friend portrait. Evaluated here while the mob
                // is loaded near the player.
                if (mob instanceof PlayerMobEntity pm) {
                    float feeling = pm.feelingToward(player);
                    if (feeling > FRIEND_FEELING_MIN) {
                        run.recordBefriended(mob.getUUID());
                        if (feeling > run.friendFeeling()) {
                            run.captureFriendAppearance(PlayerMobAppearance.capture(pm), feeling);
                        }
                    }
                }
            }
            // "Others?" also counts another real player within 4 blocks (the
            // PlayerMob query above excludes players).
            for (ServerPlayer other : players) {
                if (other == player) continue;
                if (player.distanceTo(other) <= PROXIMITY_RADIUS) {
                    AchievementEvents.notifyProximityOnTrain(player);
                    break;
                }
            }
        }
    }

    private static boolean isPlayerMob(Entity entity) {
        return entity != null
            && PLAYERMOB_NAMESPACE.equals(EntityType.getKey(entity.getType()).getNamespace());
    }
}
