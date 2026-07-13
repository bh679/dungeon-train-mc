package games.brennan.dungeontrain.command;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.logging.LogUtils;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.plot.LevelPlot;
import dev.ryanhcode.sable.sublevel.plot.PlotChunkHolder;
import games.brennan.dungeontrain.debug.CarriageDebug;
import games.brennan.dungeontrain.debug.DebugFlags;
import games.brennan.dungeontrain.editor.ChiseledBookshelfSync;
import games.brennan.dungeontrain.editor.ContainerContentsPool;
import games.brennan.dungeontrain.editor.ContainerContentsRoller;
import games.brennan.dungeontrain.editor.LootPrefabStore;
import games.brennan.dungeontrain.ship.ManagedShip;
import games.brennan.dungeontrain.ship.Shipyards;
import games.brennan.dungeontrain.ship.sable.PhysicsFreezeController;
import games.brennan.dungeontrain.ship.sable.SableManagedShip;
import games.brennan.dungeontrain.train.CarriageDims;
import games.brennan.dungeontrain.train.TrainAssembler;
import games.brennan.dungeontrain.train.TrainTransformProvider;
import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;
import org.joml.primitives.AABBdc;
import org.slf4j.Logger;

import java.util.Map;
import java.util.Optional;

/**
 * {@code /dungeontrain debug scan} — walks every active carriage index of
 * every loaded Dungeon Train and reports sliver candidates (non-air blocks
 * outside the canonical footprint) via {@link CarriageDebug}.
 */
public final class DebugCommand {

    private static final Logger LOGGER = LogUtils.getLogger();

    private DebugCommand() {}

    public static LiteralArgumentBuilder<CommandSourceStack> build() {
        return Commands.literal("debug")
            .then(Commands.literal("scan").executes(ctx -> runScan(ctx.getSource())))
            // /dungeontrain debug physicsfreeze <on|off|status> — toggles the #646 physics-freeze
            // of untracked carriages. `off` restores every frozen body next tick. Drives the Gate 2
            // matched-toggle A/B (freeze off vs on, same seed/path — chunk-gen noise cancels).
            .then(Commands.literal("physicsfreeze")
                .then(Commands.literal("on").executes(ctx -> setPhysicsFreeze(ctx.getSource(), true)))
                .then(Commands.literal("off").executes(ctx -> setPhysicsFreeze(ctx.getSource(), false)))
                .then(Commands.literal("status").executes(ctx -> physicsFreezeStatus(ctx.getSource()))))
            .then(Commands.literal("pair")
                .executes(ctx -> runPair(ctx.getSource(), 0.0))
                .then(Commands.argument("velocity", DoubleArgumentType.doubleArg())
                    .executes(ctx -> runPair(ctx.getSource(), DoubleArgumentType.getDouble(ctx, "velocity")))))
            // /dungeontrain debug wireframes <type|all> on|off — each of
            // the five wireframes has its own toggle. {@code all} flips
            // every flag at once. Legacy {@code wireframes on|off}
            // (without a type literal) is kept as an alias for
            // {@code wireframes all on|off} so muscle memory survives.
            .then(Commands.literal("wireframes")
                .then(Commands.literal("on").executes(ctx -> setAllWireframes(ctx.getSource(), true)))
                .then(Commands.literal("off").executes(ctx -> setAllWireframes(ctx.getSource(), false)))
                .then(Commands.literal("all")
                    .then(Commands.literal("on").executes(ctx -> setAllWireframes(ctx.getSource(), true)))
                    .then(Commands.literal("off").executes(ctx -> setAllWireframes(ctx.getSource(), false))))
                .then(Commands.literal("gap-cubes")
                    .then(Commands.literal("on").executes(ctx -> setGapCubes(ctx.getSource(), true)))
                    .then(Commands.literal("off").executes(ctx -> setGapCubes(ctx.getSource(), false))))
                .then(Commands.literal("gap-line")
                    .then(Commands.literal("on").executes(ctx -> setGapLine(ctx.getSource(), true)))
                    .then(Commands.literal("off").executes(ctx -> setGapLine(ctx.getSource(), false))))
                .then(Commands.literal("next-spawn")
                    .then(Commands.literal("on").executes(ctx -> setNextSpawn(ctx.getSource(), true)))
                    .then(Commands.literal("off").executes(ctx -> setNextSpawn(ctx.getSource(), false))))
                .then(Commands.literal("collision")
                    .then(Commands.literal("on").executes(ctx -> setCollision(ctx.getSource(), true)))
                    .then(Commands.literal("off").executes(ctx -> setCollision(ctx.getSource(), false))))
                .then(Commands.literal("hud-distance")
                    .then(Commands.literal("on").executes(ctx -> setHudDistance(ctx.getSource(), true)))
                    .then(Commands.literal("off").executes(ctx -> setHudDistance(ctx.getSource(), false)))))
            // /dungeontrain debug chatlogs <type|all> on|off — gates the
            // in-game CHAT broadcasts emitted by the spawn / collision
            // codepaths. Independent of the wireframes sub-tree so a
            // player can keep visual overlays on while silencing chat
            // (or vice versa). All flags default off.
            .then(Commands.literal("chatlogs")
                .then(Commands.literal("all")
                    .then(Commands.literal("on").executes(ctx -> setAllChatLogs(ctx.getSource(), true)))
                    .then(Commands.literal("off").executes(ctx -> setAllChatLogs(ctx.getSource(), false))))
                .then(Commands.literal("train-spawn")
                    .then(Commands.literal("on").executes(ctx -> setChatTrainSpawn(ctx.getSource(), true)))
                    .then(Commands.literal("off").executes(ctx -> setChatTrainSpawn(ctx.getSource(), false))))
                .then(Commands.literal("stall")
                    .then(Commands.literal("on").executes(ctx -> setChatStallTrain(ctx.getSource(), true)))
                    .then(Commands.literal("off").executes(ctx -> setChatStallTrain(ctx.getSource(), false))))
                .then(Commands.literal("collision")
                    .then(Commands.literal("on").executes(ctx -> setChatCollision(ctx.getSource(), true)))
                    .then(Commands.literal("off").executes(ctx -> setChatCollision(ctx.getSource(), false)))))
            // /dungeontrain debug spawnmode auto|manual — switches the
            // appender between auto-spawn and J-keybind manual spawn.
            .then(Commands.literal("spawnmode")
                .then(Commands.literal("auto").executes(ctx -> setSpawnMode(ctx.getSource(), false)))
                .then(Commands.literal("manual").executes(ctx -> setSpawnMode(ctx.getSource(), true))))
            // /dungeontrain debug contents-entities on|off — gates verbose
            // lifecycle logging for contents entities (per-entity JOIN /
            // LEAVE with stack trace, plus per-entity spawn lines). Off by
            // default — flip on when investigating entity-disappearance
            // regressions. The kill-ahead exemption that prevents the
            // original disappearance bug doesn't depend on this flag; it
            // uses the entity tag alone, which is always set.
            .then(Commands.literal("contents-entities")
                .then(Commands.literal("on").executes(ctx -> setLogContentsEntities(ctx.getSource(), true)))
                .then(Commands.literal("off").executes(ctx -> setLogContentsEntities(ctx.getSource(), false))))
            // /dungeontrain debug loot-rolls on|off — gates the per-furnace-roll
            // diagnostic in ContainerContentsRoller. Off by default; turn on when
            // troubleshooting why a furnace came out empty or wrong.
            .then(Commands.literal("loot-rolls")
                .then(Commands.literal("on").executes(ctx -> setLogLootRolls(ctx.getSource(), true)))
                .then(Commands.literal("off").executes(ctx -> setLogLootRolls(ctx.getSource(), false))))
            // /dungeontrain debug seamgap-trace on|off — opt-in backward-seam-gap
            // diagnostic probes ([seamgap]/[bwd-place]/[anchor-div]/[capture-lag]).
            // Off by default; turn on for a backward-ride session to capture the
            // per-seam world-gap-vs-pIdx time series that diagnoses the growing
            // backward-gap regression. Server-side logging only.
            .then(Commands.literal("seamgap-trace")
                .then(Commands.literal("on").executes(ctx -> setSeamGapTrace(ctx.getSource(), true)))
                .then(Commands.literal("off").executes(ctx -> setSeamGapTrace(ctx.getSource(), false))))
            // /dungeontrain debug reroll <prefabId> — scan every loaded ship's
            // bounding box for blocks whose state matches the prefab's source
            // block, then re-roll their NBT through the current pool. Fixes
            // already-spawned carriage containers whose contents were rolled
            // before a template edit landed.
            .then(Commands.literal("reroll")
                .then(Commands.argument("prefabId", StringArgumentType.string())
                    .executes(ctx -> runReroll(ctx.getSource(), StringArgumentType.getString(ctx, "prefabId")))));
    }

    private static int setPhysicsFreeze(CommandSourceStack source, boolean on) {
        PhysicsFreezeController.ENABLED = on;
        source.sendSuccess(() -> Component.literal(
            "[DungeonTrain] Physics-freeze " + (on ? "ON" : "OFF — all bodies restored next tick")
        ).withStyle(on ? ChatFormatting.GREEN : ChatFormatting.GRAY), true);
        return 1;
    }

    private static int physicsFreezeStatus(CommandSourceStack source) {
        source.sendSuccess(() -> Component.literal(String.format(
            "[DungeonTrain] Physics-freeze %s — resident=%d active=%d frozen=%d",
            PhysicsFreezeController.ENABLED ? "ON" : "OFF",
            PhysicsFreezeController.lastResident(), PhysicsFreezeController.lastActive(),
            PhysicsFreezeController.lastFrozen())), false);
        return 1;
    }

    private static int setAllWireframes(CommandSourceStack source, boolean enabled) {
        DebugFlags.setAllWireframes(source.getServer(), enabled);
        source.sendSuccess(() -> Component.literal(
            "[DungeonTrain] Wireframes (all) " + (enabled ? "ON" : "OFF")
        ).withStyle(enabled ? ChatFormatting.GREEN : ChatFormatting.GRAY), true);
        return 1;
    }

    private static int setGapCubes(CommandSourceStack source, boolean enabled) {
        DebugFlags.setGapCubes(source.getServer(), enabled);
        source.sendSuccess(() -> Component.literal(
            "[DungeonTrain] Wireframe gap-cubes " + (enabled ? "ON" : "OFF")
        ).withStyle(enabled ? ChatFormatting.GREEN : ChatFormatting.GRAY), true);
        return 1;
    }

    private static int setGapLine(CommandSourceStack source, boolean enabled) {
        DebugFlags.setGapLine(source.getServer(), enabled);
        source.sendSuccess(() -> Component.literal(
            "[DungeonTrain] Wireframe gap-line " + (enabled ? "ON" : "OFF")
        ).withStyle(enabled ? ChatFormatting.GREEN : ChatFormatting.GRAY), true);
        return 1;
    }

    private static int setNextSpawn(CommandSourceStack source, boolean enabled) {
        DebugFlags.setNextSpawn(source.getServer(), enabled);
        source.sendSuccess(() -> Component.literal(
            "[DungeonTrain] Wireframe next-spawn " + (enabled ? "ON" : "OFF")
        ).withStyle(enabled ? ChatFormatting.GREEN : ChatFormatting.GRAY), true);
        return 1;
    }

    private static int setCollision(CommandSourceStack source, boolean enabled) {
        DebugFlags.setCollision(source.getServer(), enabled);
        source.sendSuccess(() -> Component.literal(
            "[DungeonTrain] Wireframe collision " + (enabled ? "ON" : "OFF")
        ).withStyle(enabled ? ChatFormatting.GREEN : ChatFormatting.GRAY), true);
        return 1;
    }

    private static int setHudDistance(CommandSourceStack source, boolean enabled) {
        DebugFlags.setHudDistance(source.getServer(), enabled);
        source.sendSuccess(() -> Component.literal(
            "[DungeonTrain] Wireframe hud-distance " + (enabled ? "ON" : "OFF")
        ).withStyle(enabled ? ChatFormatting.GREEN : ChatFormatting.GRAY), true);
        return 1;
    }

    private static int setSpawnMode(CommandSourceStack source, boolean manual) {
        DebugFlags.setManualSpawnMode(source.getServer(), manual);
        source.sendSuccess(() -> Component.literal(
            "[DungeonTrain] Spawn mode: " + (manual ? "MANUAL (press J)" : "AUTO")
        ).withStyle(manual ? ChatFormatting.YELLOW : ChatFormatting.GREEN), true);
        return 1;
    }

    private static int setAllChatLogs(CommandSourceStack source, boolean enabled) {
        DebugFlags.setAllChatLogs(source.getServer(), enabled);
        source.sendSuccess(() -> Component.literal(
            "[DungeonTrain] Chat-logs (all) " + (enabled ? "ON" : "OFF")
        ).withStyle(enabled ? ChatFormatting.GREEN : ChatFormatting.GRAY), true);
        return 1;
    }

    private static int setChatTrainSpawn(CommandSourceStack source, boolean enabled) {
        DebugFlags.setChatTrainSpawn(source.getServer(), enabled);
        source.sendSuccess(() -> Component.literal(
            "[DungeonTrain] Chat-log train-spawn " + (enabled ? "ON" : "OFF")
        ).withStyle(enabled ? ChatFormatting.GREEN : ChatFormatting.GRAY), true);
        return 1;
    }

    private static int setChatCollision(CommandSourceStack source, boolean enabled) {
        DebugFlags.setChatCollision(source.getServer(), enabled);
        source.sendSuccess(() -> Component.literal(
            "[DungeonTrain] Chat-log collision " + (enabled ? "ON" : "OFF")
        ).withStyle(enabled ? ChatFormatting.GREEN : ChatFormatting.GRAY), true);
        return 1;
    }

    private static int setChatStallTrain(CommandSourceStack source, boolean enabled) {
        DebugFlags.setChatStallTrain(source.getServer(), enabled);
        source.sendSuccess(() -> Component.literal(
            "[DungeonTrain] Chat-log stall " + (enabled ? "ON" : "OFF")
        ).withStyle(enabled ? ChatFormatting.GREEN : ChatFormatting.GRAY), true);
        return 1;
    }

    private static int setLogContentsEntities(CommandSourceStack source, boolean enabled) {
        DebugFlags.setLogContentsEntities(source.getServer(), enabled);
        source.sendSuccess(() -> Component.literal(
            "[DungeonTrain] Contents-entity logging " + (enabled ? "ON" : "OFF")
        ).withStyle(enabled ? ChatFormatting.GREEN : ChatFormatting.GRAY), true);
        return 1;
    }

    private static int setLogLootRolls(CommandSourceStack source, boolean enabled) {
        DebugFlags.setLogLootRolls(source.getServer(), enabled);
        source.sendSuccess(() -> Component.literal(
            "[DungeonTrain] Loot-roll logging " + (enabled ? "ON" : "OFF")
        ).withStyle(enabled ? ChatFormatting.GREEN : ChatFormatting.GRAY), true);
        return 1;
    }

    private static int setSeamGapTrace(CommandSourceStack source, boolean enabled) {
        games.brennan.dungeontrain.train.TrainCarriageAppender.setSeamGapTraceEnabled(enabled);
        LOGGER.info("[DungeonTrain] seamgap-trace diagnostic {}", enabled ? "ENABLED" : "DISABLED");
        source.sendSuccess(() -> Component.literal(
            "[DungeonTrain] Seam-gap trace " + (enabled ? "ON" : "OFF")
                + (enabled ? " — grep [seamgap]/[bwd-place]/[anchor-div]/[capture-lag] in latest.log" : "")
        ).withStyle(enabled ? ChatFormatting.GREEN : ChatFormatting.GRAY), true);
        return 1;
    }

    private static int runScan(CommandSourceStack source) {
        ServerLevel level = source.getLevel();
        int totalStrays = 0;
        int carriagesScanned = 0;

        // Per-carriage architecture: each carriage is its own sub-level
        // with its own shipyardOrigin. Scan each one individually for
        // sliver candidates outside its canonical footprint.
        java.util.Map<java.util.UUID, java.util.List<games.brennan.dungeontrain.train.Trains.Carriage>> trainsById =
            games.brennan.dungeontrain.train.Trains.byTrainId(level);

        for (java.util.Map.Entry<java.util.UUID, java.util.List<games.brennan.dungeontrain.train.Trains.Carriage>> entry : trainsById.entrySet()) {
            java.util.UUID trainId = entry.getKey();
            java.util.List<games.brennan.dungeontrain.train.Trains.Carriage> train = entry.getValue();
            int trainStrays = 0;
            for (games.brennan.dungeontrain.train.Trains.Carriage c : train) {
                CarriageDims dims = c.provider().dims();
                BlockPos carriageOrigin = c.provider().getShipyardOrigin();
                int strays = CarriageDebug.scanForStrays(level, carriageOrigin,
                    "debug-cmd shipId=" + c.ship().id() + " trainId=" + trainId + " pIdx=" + c.provider().getPIdx(),
                    dims);
                trainStrays += strays;
                carriagesScanned++;
            }
            final int fTrainStrays = trainStrays;
            final java.util.UUID fTrainId = trainId;
            final int fCarriageCount = train.size();
            source.sendSuccess(() -> Component.literal(
                "Train " + fTrainId + " — " + fTrainStrays + " stray(s) across "
                    + fCarriageCount + " carriage(s)"
            ), false);
            totalStrays += trainStrays;
        }

        if (carriagesScanned == 0) {
            source.sendFailure(Component.literal("No Dungeon Train carriages loaded in this level."));
            return 0;
        }
        final int fTotal = totalStrays;
        final int fTrains = trainsById.size();
        final int fCarriages = carriagesScanned;
        source.sendSuccess(() -> Component.literal(
            "Debug scan complete: " + fTotal + " total stray(s) across "
                + fCarriages + " carriage(s) in " + fTrains + " train(s). "
                + "See server log for offsets."
        ).withStyle(fTotal == 0 ? ChatFormatting.GREEN : ChatFormatting.YELLOW), false);
        LOGGER.info("[DungeonTrain][DEBUG] /dungeontrain debug scan: {} strays across {} carriage(s) in {} train(s)",
            totalStrays, carriagesScanned, trainsById.size());
        return 1;
    }

    /**
     * Probe entry point — see {@code plans/wild-leaping-taco.md} (Gate A).
     *
     * <p>Spawns two single-carriage Sable sub-levels side by side along +X at
     * the player's current world position, both attached to the kinematic
     * driver with identical {@code velocity} (0 = static probe; positive =
     * moving probe). Both providers get {@code appenderDisabled = true} so
     * the rolling appender does NOT extend the probe ships — they stay
     * exactly the size they were assembled at, which keeps the seam
     * geometry visible.</p>
     *
     * <p>The user walks across the seam between A and B and observes
     * Sable's player↔sub-level handoff. Outcome decides whether to commit
     * to the per-group sub-level refactor.</p>
     */
    /**
     * Iterate every loaded {@link ManagedShip} across all server levels, walk
     * each ship's world AABB block-by-block, and re-roll any BE whose state
     * matches the prefab's source block. Picks the current pool from
     * {@link LootPrefabStore} so menu-bumped fillMax / weight changes apply.
     *
     * <p>One-shot fix for already-spawned carriages whose contents were rolled
     * by an older code path or against an older pool. New rolls go through the
     * current {@link ContainerContentsRoller#roll} which respects fillMax and
     * the priority-cascade slot semantics.</p>
     */
    private static int runReroll(CommandSourceStack source, String prefabId) {
        Optional<LootPrefabStore.Data> loaded = LootPrefabStore.load(prefabId);
        if (loaded.isEmpty()) {
            source.sendFailure(Component.literal("Unknown loot prefab '" + prefabId + "'"));
            return 0;
        }
        ContainerContentsPool pool = loaded.get().pool();
        ResourceLocation sourceBlockId = loaded.get().sourceBlock();
        Block sourceBlock = BuiltInRegistries.BLOCK.get(sourceBlockId);
        if (pool.isEmpty()) {
            source.sendFailure(Component.literal("Prefab '" + prefabId + "' has an empty pool — nothing to roll"));
            return 0;
        }

        int touched = 0;
        int ships = 0;
        // Distinct salt per command invocation so re-rolling the same chest
        // twice produces fresh contents rather than re-deriving the prior
        // roll's items.
        int salt = (int) (System.nanoTime() & 0x7FFFFFFFL);

        MinecraftServer server = source.getServer();
        for (ServerLevel level : server.getAllLevels()) {
            long worldSeed = level.getSeed();
            for (ManagedShip ship : Shipyards.of(level).findAll()) {
                ships++;
                // Sable stores each ship's blocks in a LevelPlot inside the
                // parent level (offset to a far chunk). worldAABB() returns
                // the rendered post-transform position, not the storage
                // coords — iterate the plot's loaded chunks instead so we
                // hit the real BE positions.
                if (!(ship instanceof SableManagedShip sableShip)) continue;
                ServerSubLevel subLevel = sableShip.subLevel();
                LevelPlot plot = subLevel.getPlot();
                int beInShip = 0;
                int matched = 0;
                for (PlotChunkHolder holder : plot.getLoadedChunks()) {
                    LevelChunk chunk = holder.getChunk();
                    if (chunk == null) continue;
                    for (Map.Entry<BlockPos, BlockEntity> entry : chunk.getBlockEntities().entrySet()) {
                        BlockPos pos = entry.getKey();
                        BlockEntity be = entry.getValue();
                        beInShip++;
                        BlockState state = be.getBlockState();
                        if (!state.is(sourceBlock)) continue;
                        matched++;
                        CompoundTag baseNbt = be.saveWithFullMetadata(level.registryAccess());
                        CompoundTag rolled = ContainerContentsRoller.roll(
                            pool, state, pos, worldSeed, salt, baseNbt,
                            level.registryAccess(), level);
                        if (rolled == null) continue;
                        be.loadCustomOnly(rolled, level.registryAccess());
                        be.setChanged();
                        ChiseledBookshelfSync.syncIfNeeded(level, pos);
                        touched++;
                    }
                }
                LOGGER.info("[DT-reroll] ship subLevelId={} plot.getLoadedChunks={} beTotal={} matched={}",
                    ship.subLevelId(), plot.getLoadedChunks().size(), beInShip, matched);
            }
        }

        final int finalTouched = touched;
        final int finalShips = ships;
        source.sendSuccess(() -> Component.literal(
            "Re-rolled " + finalTouched + " " + sourceBlockId + " BE(s) across " + finalShips
                + " loaded ship(s) with prefab '" + prefabId + "'"
        ).withStyle(ChatFormatting.GREEN), true);
        LOGGER.info("[DungeonTrain] /debug reroll {}: touched={} ships={}", prefabId, touched, ships);
        return touched;
    }

    private static int runPair(CommandSourceStack source, double velocity) {
        ServerPlayer player;
        try {
            player = source.getPlayerOrException();
        } catch (Exception e) {
            source.sendFailure(Component.literal("This command must be run by a player."));
            return 0;
        }

        ServerLevel level = source.getLevel();
        Vec3 pp = player.position();
        // Spawn the carriage with its floor at the player's feet block. The
        // floor is at carriage.dy=0, so origin.y == player feet => player
        // visibly steps onto the carriage by 1 block.
        BlockPos shipAOrigin = BlockPos.containing(pp.x, pp.y, pp.z);
        CarriageDims dims = DungeonTrainWorldData.get(source.getServer().overworld()).dims();
        BlockPos shipBOrigin = shipAOrigin.offset(dims.length(), 0, 0);

        Vector3d vel = new Vector3d(velocity, 0.0, 0.0);

        LOGGER.info("[DungeonTrain][probe] /dt debug pair velocity={} dims={}x{}x{} player={} → shipA origin={} shipB origin={}",
            velocity, dims.length(), dims.height(), dims.width(), pp, shipAOrigin, shipBOrigin);

        try {
            // The probe spawns 2 separate single-carriage sub-levels — bypass
            // the production spawnTrain path (which would create a single
            // train with groupSize-many carriages) and call spawnGroup
            // directly with groupSize=1. Each ship gets its own trainId so
            // they're truly independent for the handoff test.
            TrainAssembler.deleteAllTrains(level);
            java.util.UUID trainIdA = java.util.UUID.randomUUID();
            java.util.UUID trainIdB = java.util.UUID.randomUUID();
            ManagedShip shipA = TrainAssembler.spawnGroup(level, shipAOrigin, vel, 0, 1, dims, trainIdA);
            ManagedShip shipB = TrainAssembler.spawnGroup(level, shipBOrigin, vel, 0, 1, dims, trainIdB);

            // Disable appender on both — the probe must stay exactly 1+1
            // carriages so the seam geometry remains observable.
            if (shipA.getKinematicDriver() instanceof TrainTransformProvider providerA) {
                providerA.setAppenderDisabled(true);
            }
            if (shipB.getKinematicDriver() instanceof TrainTransformProvider providerB) {
                providerB.setAppenderDisabled(true);
            }

            long shipAId = shipA.id();
            long shipBId = shipB.id();
            AABBdc aabbA = shipA.worldAABB();
            AABBdc aabbB = shipB.worldAABB();
            int seamWorldX = shipBOrigin.getX();

            LOGGER.info("[DungeonTrain][probe] shipA id={} aabb=[{}, {}, {} -> {}, {}, {}]",
                shipAId,
                String.format("%.2f", aabbA.minX()), String.format("%.2f", aabbA.minY()), String.format("%.2f", aabbA.minZ()),
                String.format("%.2f", aabbA.maxX()), String.format("%.2f", aabbA.maxY()), String.format("%.2f", aabbA.maxZ()));
            LOGGER.info("[DungeonTrain][probe] shipB id={} aabb=[{}, {}, {} -> {}, {}, {}]",
                shipBId,
                String.format("%.2f", aabbB.minX()), String.format("%.2f", aabbB.minY()), String.format("%.2f", aabbB.minZ()),
                String.format("%.2f", aabbB.maxX()), String.format("%.2f", aabbB.maxY()), String.format("%.2f", aabbB.maxZ()));
            LOGGER.info("[DungeonTrain][probe] seam world x={} — walk across this boundary to test handoff", seamWorldX);

            source.sendSuccess(() -> Component.literal(
                "Probe pair spawned: shipA=" + shipAId + " at " + shipAOrigin
                    + ", shipB=" + shipBId + " at " + shipBOrigin
                    + ", velocity=" + velocity + " m/s. "
                    + "Seam at world x=" + seamWorldX + ". Walk across and observe."
            ).withStyle(ChatFormatting.GREEN), true);
            return 1;
        } catch (Throwable t) {
            LOGGER.error("[DungeonTrain][probe] /dt debug pair failed", t);
            source.sendFailure(Component.literal(
                "pair probe failed: " + t.getClass().getSimpleName() + ": " + t.getMessage()
            ).withStyle(ChatFormatting.RED));
            return 0;
        }
    }
}
