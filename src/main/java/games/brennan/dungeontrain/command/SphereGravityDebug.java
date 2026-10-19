package games.brennan.dungeontrain.command;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.config.SpheresGravityConfig;
import games.brennan.dungeontrain.worldgen.SphereField;
import games.brennan.dungeontrain.worldgen.SphereGravity;
import games.brennan.dungeontrain.worldgen.SphereGravityPush;
import games.brennan.dungeontrain.worldgen.SphereGravityServer;
import games.brennan.dungeontrain.worldgen.SphereGravityShelter;
import games.brennan.dungeontrain.worldgen.SpheresBand;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * {@code /dungeontrain debug sphere-gravity [on|off]} — the spheres pulling at the caller's position
 * (centre, radius, distance, pull), the summed pull, and — for an entity caller — its train exposure.
 * Also lists the nearest placed spheres within {@link #SEARCH} blocks, so a headless RCON run can find
 * one to test beside. {@code on|off} force-toggles the server pass (mobs, items, projectiles) without
 * touching config; each client still pulls its own player. Logged at INFO for RCON runs.
 */
final class SphereGravityDebug {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final double SEARCH = 200.0;
    private static final int LIST = 5;

    private SphereGravityDebug() {}

    static int report(CommandSourceStack source) {
        ServerLevel overworld = source.getServer().overworld();
        Vec3 at = source.getPosition();
        send(source, String.format("[DungeonTrain] sphere-gravity at %.1f %.1f %.1f: enabled=%s forcedOff=%s strength=%.2f reach=%.1fr rider=%.2f",
                at.x, at.y, at.z, SpheresGravityConfig.enabled(), SphereGravityServer.isForcedOff(),
                SpheresGravityConfig.strength(), SpheresGravityConfig.reachRadii(), SpheresGravityConfig.riderFactor()),
                ChatFormatting.AQUA);
        List<SphereField.Sphere> near = new ArrayList<>(SpheresBand.spheresNear(overworld, at.x, at.y, at.z, SEARCH));
        near.sort(Comparator.comparingDouble(s -> surfaceDistance(s, at)));
        if (near.isEmpty()) {
            send(source, "  no placed sphere within " + (int) SEARCH + " blocks", ChatFormatting.GRAY);
            return 1;
        }
        for (int i = 0; i < Math.min(LIST, near.size()); i++) {
            SphereField.Sphere s = near.get(i);
            double d = centreDistance(s, at);
            double a = SphereGravity.pullAt(d, s.r(), SpheresGravityConfig.strength(), SpheresGravityConfig.reachRadii());
            send(source, String.format("  sphere %d %d %d r=%d %s: %.1f from centre, pull %.4f b/t² (%.2fx gravity)",
                    s.cx(), s.cy(), s.cz(), s.r(), s.source(), d, a, a / SphereGravity.VANILLA_G), ChatFormatting.WHITE);
        }
        List<SphereField.Sphere> inReach = SpheresBand.spheresNear(overworld, at.x, at.y, at.z, SphereGravityPush.lookupReach());
        Entity self = source.getEntity();
        SphereGravity.Pull pull = SphereGravity.pull(inReach, at.x, at.y, at.z, 0, 0, 0,
                self != null && self.onGround(), SpheresGravityConfig.strength(), SpheresGravityConfig.reachRadii());
        String exposure = "";
        if (self != null) exposure = " exposure=" + SphereGravityShelter.classify(self) + " onGround=" + self.onGround();
        send(source, String.format("  total pull (%.4f, %.4f, %.4f) |%.4f|%s",
                pull.x(), pull.y(), pull.z(), pull.length(), exposure), ChatFormatting.GOLD);
        return 1;
    }

    static int setForcedOff(CommandSourceStack source, boolean off) {
        SphereGravityServer.setForcedOff(off);
        send(source, "[DungeonTrain] sphere-gravity server pass " + (off ? "forced OFF" : "on (config)"),
                ChatFormatting.AQUA);
        return 1;
    }

    private static double centreDistance(SphereField.Sphere s, Vec3 at) {
        double dx = s.cx() + 0.5 - at.x, dy = s.cy() + 0.5 - at.y, dz = s.cz() + 0.5 - at.z;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private static double surfaceDistance(SphereField.Sphere s, Vec3 at) {
        return centreDistance(s, at) - s.r();
    }

    private static void send(CommandSourceStack source, String line, ChatFormatting colour) {
        LOGGER.info(line);
        source.sendSuccess(() -> Component.literal(line).withStyle(colour), false);
    }
}
