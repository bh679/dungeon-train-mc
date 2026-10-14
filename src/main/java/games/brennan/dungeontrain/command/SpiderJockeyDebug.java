package games.brennan.dungeontrain.command;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.editor.VariantRotation;
import games.brennan.dungeontrain.editor.VariantState;
import games.brennan.dungeontrain.ship.CarriageDeck;
import games.brennan.dungeontrain.train.CarriageContentsPlacer;
import games.brennan.dungeontrain.train.CarriageDims;
import games.brennan.dungeontrain.train.Trains;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.joml.primitives.AABBdc;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code /dungeontrain debug spider-jockey}: spawns plain spiders through the real carriage
 * variant-mob path ({@link CarriageContentsPlacer#spawnVariantMobEntity}) at the cell the player
 * stands on, until vanilla's 1-in-100 skeleton jockey roll hits, and reports whether the rider made
 * it into the level with the carriage contents tag. Off a carriage (or from RCON) it uses the interior
 * centre of the nearest carriage. Riderless spiders are discarded. Regression probe
 * for the ghost-passenger bug: a plain {@code addFreshEntity} left the skeleton out of the level.
 */
final class SpiderJockeyDebug {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int MAX_TRIES = 1000;
    private static final ResourceLocation SPIDER = ResourceLocation.withDefaultNamespace("spider");

    private SpiderJockeyDebug() {}

    static int run(CommandSourceStack source) {
        ServerLevel level = source.getLevel();
        List<Trains.Carriage> carriages = new ArrayList<>();
        Trains.byTrainId(level).values().forEach(carriages::addAll);
        // The cell under a player standing in a carriage; otherwise (RCON, or off the train) the
        // interior centre of the carriage nearest the command source.
        ServerPlayer player = source.getPlayer();
        Trains.Carriage carriage = player == null ? null : CarriageDeck.carriageUnder(carriages, player);
        BlockPos cell;
        if (carriage != null) {
            cell = CarriageDeck.shipLocal(carriage.ship(), player.blockPosition());
        } else {
            carriage = nearest(carriages, source.getPosition());
            if (carriage == null) {
                send(source, "[DungeonTrain] spider-jockey: no loaded carriage", ChatFormatting.RED);
                return 0;
            }
            CarriageDims dims = carriage.provider().dims();
            cell = carriage.provider().getShipyardOrigin().offset(dims.length() / 2, 1, dims.width() / 2);
        }
        int pIdx = carriage.provider().getPIdx();
        VariantState spider = new VariantState(Blocks.AIR.defaultBlockState(), null, 1,
            VariantRotation.NONE, null, SPIDER);

        for (int i = 1; i <= MAX_TRIES; i++) {
            Entity spawned = CarriageContentsPlacer.spawnVariantMobEntity(
                level, cell, spider, pIdx, level.getSeed(), /*asAuthored*/ true);
            if (spawned == null) {
                send(source, "[DungeonTrain] spider-jockey: spawn rejected on try " + i, ChatFormatting.RED);
                return 0;
            }
            if (spawned.getPassengers().isEmpty()) {
                spawned.discard();
                continue;
            }
            for (Entity rider : spawned.getPassengers()) {
                boolean ok = rider.isAddedToLevel() && level.getEntity(rider.getUUID()) == rider
                    && rider.getTags().contains(CarriageContentsPlacer.contentsTagFor(pIdx));
                send(source, "[DungeonTrain] spider-jockey: try " + i + " pIdx=" + pIdx
                        + " rider=" + rider.getType().getDescriptionId() + " uuid=" + rider.getUUID()
                        + " inLevel=" + rider.isAddedToLevel() + " tags=" + rider.getTags()
                        + (ok ? " OK" : " GHOST"),
                    ok ? ChatFormatting.GREEN : ChatFormatting.RED);
            }
            return 1;
        }
        send(source, "[DungeonTrain] spider-jockey: no jockey in " + MAX_TRIES + " tries", ChatFormatting.YELLOW);
        return 0;
    }

    private static Trains.Carriage nearest(List<Trains.Carriage> carriages, Vec3 from) {
        Trains.Carriage best = null;
        double bestDist = Double.MAX_VALUE;
        for (Trains.Carriage c : carriages) {
            AABBdc bb = c.ship().worldAABB();
            double dx = (bb.minX() + bb.maxX()) / 2 - from.x;
            double dz = (bb.minZ() + bb.maxZ()) / 2 - from.z;
            double d = dx * dx + dz * dz;
            if (d < bestDist) { bestDist = d; best = c; }
        }
        return best;
    }

    private static void send(CommandSourceStack source, String line, ChatFormatting colour) {
        LOGGER.info(line);
        source.sendSuccess(() -> Component.literal(line).withStyle(colour), false);
    }
}
