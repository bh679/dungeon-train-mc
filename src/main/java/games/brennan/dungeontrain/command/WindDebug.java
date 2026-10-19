package games.brennan.dungeontrain.command;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.ship.ManagedShip;
import games.brennan.dungeontrain.ship.sable.SableManagedShip;
import games.brennan.dungeontrain.train.Trains;
import games.brennan.dungeontrain.wind.TrainExposure;
import games.brennan.dungeontrain.wind.WindProfile;
import games.brennan.dungeontrain.wind.WindSample;
import games.brennan.dungeontrain.wind.WindServer;
import games.brennan.dungeontrain.worldgen.CycleLayout;
import games.brennan.dungeontrain.worldgen.WorldGenCycle;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.neoforged.fml.loading.FMLPaths;
import org.joml.primitives.AABBdc;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * {@code /dungeontrain debug wind [reload|export|on|off]} — the train wind at your feet, live reload of the
 * tuned profile, and the band edges the tuner ({@code scripts/wind/}) draws. Also logged at INFO for RCON runs.
 */
final class WindDebug {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String BANDS_FILE = "wind-bands.json";
    private static final String DEV_BANDS = "scripts/wind/bands.json";
    private static final int EXPORT_RUNS = 2;

    private WindDebug() {}

    static LiteralArgumentBuilder<CommandSourceStack> build() {
        return Commands.literal("wind")
                .executes(ctx -> here(ctx.getSource()))
                .then(Commands.literal("reload").executes(ctx -> reload(ctx.getSource())))
                .then(Commands.literal("export").executes(ctx -> export(ctx.getSource())))
                .then(Commands.literal("on").executes(ctx -> toggle(ctx.getSource(), false)))
                .then(Commands.literal("off").executes(ctx -> toggle(ctx.getSource(), true)));
    }

    private static int here(CommandSourceStack source) {
        WindServer.State s = WindServer.state();
        if (s.startX() == Long.MIN_VALUE) {
            send(source, "[DungeonTrain] wind: no Upside-down band in this world, so no wind", ChatFormatting.YELLOW);
            return 1;
        }
        Entity e = source.getEntity();
        double x = e != null ? e.getX() : source.getPosition().x;
        ServerLevel level = source.getLevel();
        long tick = level.getGameTime();
        WindSample w = s.sampleAt(x, tick);
        String state = s.forcedOff() ? "OFF (debug)" : (s.profile().enabled() ? "on" : "disabled in profile");
        send(source, String.format("[DungeonTrain] wind %s: start X %d, you are %.0f blocks past it",
                state, s.startX(), x - s.startX()), ChatFormatting.AQUA);
        send(source, String.format("  push %.4f (gust %.0f%%) toward %.0f° (0=+X, 90=+Z)",
                w.strength(), w.gust01() * 100.0, Math.toDegrees(w.headingRad())), ChatFormatting.WHITE);
        if (e != null) {
            send(source, "  you are " + exposure(level, e, w), ChatFormatting.WHITE);
        }
        return 1;
    }

    private static String exposure(ServerLevel level, Entity e, WindSample w) {
        for (Trains.Carriage c : Trains.allCarriages(level)) {
            ManagedShip ship = c.ship();
            AABBdc bb = ship.worldAABB();
            if (e.getX() < bb.minX() - 1 || e.getX() > bb.maxX() + 1) continue;
            if (e.getZ() < bb.minZ() - 1 || e.getZ() > bb.maxZ() + 1) continue;
            if (e.getY() < bb.minY() - 1 || e.getY() > bb.maxY() + 4) continue;
            if (ship instanceof SableManagedShip sable && sable.subLevel() != null) {
                return TrainExposure.isExposed(sable.subLevel(), e, w.dirX(), w.dirZ())
                        ? "EXPOSED (on top of carriage " + c.provider().getPIdx() + ")"
                        : "sheltered (inside, under cover or in a lee, carriage " + c.provider().getPIdx() + ")";
            }
        }
        return "not on the train";
    }

    private static int reload(CommandSourceStack source) {
        send(source, "[DungeonTrain] " + WindServer.reload(source.getServer()), ChatFormatting.GREEN);
        return 1;
    }

    private static int toggle(CommandSourceStack source, boolean off) {
        WindServer.setForcedOff(source.getServer(), off);
        send(source, "[DungeonTrain] wind " + (off ? "OFF until restart or 'on'" : "ON"), ChatFormatting.GREEN);
        return 1;
    }

    /** Band edges as blocks past the Upside-down end, for the tuner's band strip. */
    private static int export(CommandSourceStack source) {
        WindServer.State s = WindServer.state();
        WorldGenCycle cycle = WorldGenCycle.fromConfig();
        if (s.startX() == Long.MIN_VALUE || !cycle.hasLayout()) {
            send(source, "[DungeonTrain] wind export: needs an ordered cycle layout with an Upside-down band",
                    ChatFormatting.YELLOW);
            return 0;
        }
        CycleLayout layout = cycle.layout();
        JsonArray bands = new JsonArray();
        for (int k = 0; k < EXPORT_RUNS; k++) {
            for (int i = 0; i < layout.count(); i++) {
                long from = cycle.worldXOfBase(k, layout.start(i)) - s.startX();
                long to = cycle.worldXOfBase(k, layout.start(i) + layout.length(i)) - s.startX();
                if (to <= 0) continue;
                CycleLayout.Slot slot = layout.slot(i);
                CycleLayout.Style style = slot.styleOnRun(k);
                JsonObject b = new JsonObject();
                b.addProperty("label", slot.type().name().toLowerCase()
                        + (style == CycleLayout.Style.VANILLA ? "" : " " + style.name().toLowerCase()));
                b.addProperty("from", Math.max(0, from));
                b.addProperty("to", to);
                b.addProperty("run", k);
                bands.add(b);
            }
        }
        JsonObject out = new JsonObject();
        out.addProperty("udEndX", s.startX());
        out.addProperty("note", "from/to are blocks past the end of the first Upside-down band");
        out.add("bands", bands);
        String json = new GsonBuilder().setPrettyPrinting().create().toJson(out) + "\n";
        try {
            Path file = FMLPaths.GAMEDIR.get().resolve(BANDS_FILE);
            Files.writeString(file, json, StandardCharsets.UTF_8);
            String where = file.toString();
            Path parent = FMLPaths.GAMEDIR.get().toAbsolutePath().getParent();
            if (DungeonTrain.isDevBuild() && parent != null && Files.isDirectory(parent.resolve("scripts/wind"))) {
                Files.writeString(parent.resolve(DEV_BANDS), json, StandardCharsets.UTF_8);
                where += " and " + DEV_BANDS;
            }
            send(source, "[DungeonTrain] wind export: " + bands.size() + " bands → " + where, ChatFormatting.GREEN);
            return 1;
        } catch (IOException ex) {
            send(source, "[DungeonTrain] wind export failed: " + ex.getMessage(), ChatFormatting.RED);
            return 0;
        }
    }

    private static void send(CommandSourceStack source, String line, ChatFormatting colour) {
        source.sendSystemMessage(Component.literal(line).withStyle(colour));
        LOGGER.info(line);
    }
}
