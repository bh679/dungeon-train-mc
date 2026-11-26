package games.brennan.dungeontrain.compat.mixinguard;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static games.brennan.dungeontrain.compat.mixinguard.MixinTargetRequirement.invokes;
import static games.brennan.dungeontrain.compat.mixinguard.MixinTargetRequirement.method;

/**
 * Pins the position-readout hooks ({@code mixin.client.positionreadout}, {@code JmLocationFormatMixin})
 * to the mod builds DT compiles against. Every hook is {@code require = 0}, so a mod update that moves a
 * target would silently show the real position to everyone running that mod; here it fails instead,
 * naming what moved. The jars come from {@code dungeontrain.test.readoutModJars} (build.gradle), read as
 * files so none of the mods loads into the test JVM.
 */
class PositionReadoutTargetsTest {

    private static final String BLOCK_POS = "Lnet/minecraft/core/BlockPos;";
    private static final String XAERO_INFO_LAMBDA = "(Lxaero/hud/minimap/info/InfoDisplay;"
        + "Lxaero/hud/minimap/info/render/compile/InfoDisplayCompiler;Lxaero/hud/minimap/module/MinimapSession;I"
        + BLOCK_POS + ")V";
    private static final String MC = "(Lnet/minecraft/client/Minecraft;)V";

    /** Target class → what the hooks on it need. */
    private static final Map<String, List<MixinTargetRequirement>> TARGETS = Map.of(
        "journeymap.client.ui.option.LocationFormat$LocationFormatKeys", List.of(
            method("format", "(ZIIII)Ljava/lang/String;")),
        "xaero.hud.minimap.info.BuiltInInfoDisplays", List.of(
            // coordinates, overworld-scaled coordinates, chunk coordinates: they print the pos and nothing else
            method("lambda$static$0", XAERO_INFO_LAMBDA),
            invokes("lambda$static$0", "net/minecraft/core/BlockPos", "getY", "()I"),
            method("lambda$static$2", XAERO_INFO_LAMBDA),
            invokes("lambda$static$2", "net/minecraft/world/level/dimension/DimensionType", "coordinateScale", "()D"),
            method("lambda$static$3", XAERO_INFO_LAMBDA),
            invokes("lambda$static$3", "net/minecraft/core/BlockPos", "getX", "()I"),
            // dimension line
            invokes("lambda$static$6", "net/minecraft/client/multiplayer/ClientLevel", "dimension",
                "()Lnet/minecraft/resources/ResourceKey;"),
            invokes("lambda$static$6", "net/minecraft/resources/ResourceKey", "location",
                "()Lnet/minecraft/resources/ResourceLocation;")),
        "xaero.map.gui.GuiMap", List.of(
            invokes("render", "xaero/map/graphics/MapRenderHelper", "drawCenteredStringWithBackground",
                "(Lnet/minecraft/client/gui/GuiGraphics;Lnet/minecraft/client/gui/Font;Ljava/lang/String;IIIFFFF"
                    + "Lcom/mojang/blaze3d/vertex/VertexConsumer;)V")),
        "me.cominixo.betterf3.modules.CoordsModule", List.of(
            method("update", MC),
            invokes("update", "net/minecraft/world/entity/Entity", "getX", "()D"),
            invokes("update", "net/minecraft/world/entity/Entity", "getY", "()D"),
            invokes("update", "net/minecraft/world/entity/Entity", "getZ", "()D"),
            invokes("update", "net/minecraft/world/entity/Entity", "blockPosition", "()" + BLOCK_POS)),
        "me.cominixo.betterf3.modules.TargetModule", List.of(
            method("update", MC),
            invokes("update", "net/minecraft/core/BlockPos", "getX", "()I")),
        "me.cominixo.betterf3.modules.LocationModule", List.of(
            method("update", MC),
            invokes("update", "net/minecraft/resources/ResourceKey", "location",
                "()Lnet/minecraft/resources/ResourceLocation;")),
        "snownee.jade.addon.core.DistanceProvider", List.of(
            invokes("append", "snownee/jade/addon/core/DistanceProvider", "xyz",
                "(Lsnownee/jade/api/ITooltip;Lnet/minecraft/core/Vec3i;)V")),
        "me.flashyreese.mods.sodiumextra.client.gui.SodiumExtraHud", List.of(
            method("onStartTick", MC),
            invokes("onStartTick", "net/minecraft/client/player/LocalPlayer", "position",
                "()Lnet/minecraft/world/phys/Vec3;")));

    @Test
    @DisplayName("every hook's target is where the pinned mod builds have it")
    void pinnedModsHaveEveryTarget() throws IOException {
        List<String> misses = new ArrayList<>();
        for (Map.Entry<String, List<MixinTargetRequirement>> e : TARGETS.entrySet()) {
            ClassNode node = readClass(e.getKey());
            if (node == null) {
                misses.add(e.getKey() + " (class not in any pinned jar)");
                continue;
            }
            MixinTargetCheck.missing(node, e.getValue()).forEach(m -> misses.add(e.getKey() + ": " + m));
        }
        assertTrue(misses.isEmpty(), "Pinned mods no longer match the position-readout hooks:\n  "
            + String.join("\n  ", misses));
    }

    @Test
    @DisplayName("Jade: the absolute coordinates are the second xyz call, the relative ones the first")
    void jadeCallOrder() throws IOException {
        List<MethodInsnNode> calls = callsIn("snownee.jade.addon.core.DistanceProvider", "append", "xyz");
        assertEquals(2, calls.size(), "JadeCoordinatesMixin shifts ordinal 1; Jade's call count changed");
        AbstractInsnNode before = calls.get(0).getPrevious();
        while (before != null && !(before instanceof MethodInsnNode)) before = before.getPrevious();
        assertTrue(before instanceof MethodInsnNode m && m.name.equals("subtract"),
            "the first xyz call should be the relative one (BlockPos.subtract just before it)");
    }

    @Test
    @DisplayName("BetterF3: the XYZ line reads the camera before the velocity lines do")
    void betterF3ReadOrder() throws IOException {
        // The hook moves only ordinal 0 of each; the later reads work out velocity and must stay real.
        assertEquals(2, callsIn("me.cominixo.betterf3.modules.CoordsModule", "update", "getX").stream()
            .filter(c -> c.owner.equals("net/minecraft/world/entity/Entity")).count());
    }

    @Test
    @DisplayName("Sodium Extra: position() is read once, for the coordinates line")
    void sodiumExtraReadsOnce() throws IOException {
        assertEquals(1, callsIn("me.flashyreese.mods.sodiumextra.client.gui.SodiumExtraHud", "onStartTick", "position").size());
    }

    @Test
    @DisplayName("BetterF3: ResourceKey.location() is read once, for the dimension line")
    void betterF3LocationOnce() throws IOException {
        assertEquals(1, callsIn("me.cominixo.betterf3.modules.LocationModule", "update", "location").size());
    }

    private static List<MethodInsnNode> callsIn(String className, String methodName, String callName) throws IOException {
        ClassNode node = readClass(className);
        assertFalse(node == null, className + " not in any pinned jar");
        List<MethodInsnNode> out = new ArrayList<>();
        for (MethodNode m : node.methods) {
            if (!m.name.equals(methodName)) continue;
            for (AbstractInsnNode insn : m.instructions) {
                if (insn instanceof MethodInsnNode call && call.name.equals(callName)) out.add(call);
            }
        }
        return out;
    }

    private static ClassNode readClass(String className) throws IOException {
        String entry = className.replace('.', '/') + ".class";
        String jars = System.getProperty("dungeontrain.test.readoutModJars", "");
        for (String path : jars.split(File.pathSeparator)) {
            if (path.isBlank()) continue;
            try (ZipFile zip = new ZipFile(path)) {
                ZipEntry e = zip.getEntry(entry);
                if (e == null) continue;
                try (InputStream in = zip.getInputStream(e)) {
                    ClassNode node = new ClassNode();
                    new ClassReader(in).accept(node, 0);
                    return node;
                }
            }
        }
        return null;
    }
}
