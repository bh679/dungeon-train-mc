package games.brennan.dungeontrain.compat;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.client.EditorMirrorPlotClient;
import games.brennan.dungeontrain.editor.EditorMirror;
import games.brennan.dungeontrain.net.EditorMirrorPlotPacket;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import org.slf4j.Logger;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Adds the editor's mirror images to Effortless Building's client preview, so the ghost blocks
 * show what {@link EffortlessBuildingMirror} will write on the server.
 *
 * <p>Called at the end of EB's client {@code ModifierSystem.processBlocks} (after EB's own
 * mirror / array / radial modifiers), so EB's randomizer, constraints and max-blocks truncation see
 * the images too. Each image is built the way EB's own {@code MirrorModifier} builds one:
 * {@code new BlockEntry(pos)}, {@code copyRotationSettingsFrom(source)}, then the per-axis mirror
 * flags toggled — EB's renderer applies those flags with the same vanilla {@code Mirror} constants
 * DT's {@link EditorMirror#reflect} uses. The first entry at a position wins, as in EB.</p>
 *
 * <p>The plot comes from {@link EditorMirrorPlotClient} — the one the player stands in, or failing
 * that the one they aim at (see {@link games.brennan.dungeontrain.editor.EditorMirrorPlotSync}); only
 * cells inside it are mirrored.
 * Sidecar marker cells are not excluded here; the server skips them, so a ghost may show on one.</p>
 *
 * <p>Nothing is added while EB's build mode is {@code DISABLED}: that is the author turning EB
 * off, and a mirrored highlight then would be a preview of a build EB will not make.</p>
 *
 * <p>EB is not a compile dependency, so its {@code BlockEntry} is reached by reflection, resolved
 * once from the first entry seen. <b>Fails open</b>: any failure leaves EB's preview as it was.</p>
 */
public final class EffortlessBuildingPreviewMirror {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static Class<?> entryClass;
    private static Constructor<?> ctor;
    private static Method copyRotation;
    private static Field posField;
    private static Field mirrorX;
    private static Field mirrorY;
    private static Field mirrorZ;
    private static Object buildModes;
    private static Method getBuildMode;
    private static boolean broken;

    private EffortlessBuildingPreviewMirror() {}

    /** {@code set} is EB's {@code BlockSet}: a {@code LinkedHashMap<BlockPos, BlockEntry>}. */
    public static void addImages(Map<BlockPos, Object> set) {
        if (broken || set.isEmpty()) return;
        EditorMirrorPlotPacket plot = EditorMirrorPlotClient.get();
        if (plot == null) return;
        try {
            List<Object> sources = new ArrayList<>(set.values());
            resolve(sources.get(0).getClass());
            if (isDisabled()) return;
            BlockPos origin = plot.origin();
            Vec3i f = plot.footprint();
            for (Object src : sources) {
                BlockPos local = ((BlockPos) posField.get(src)).subtract(origin);
                if (!inBounds(local, f)) continue;
                for (EditorMirror.Image img : EditorMirror.imagesOf(local, f,
                        plot.mirrorX(), plot.mirrorY(), plot.mirrorZ())) {
                    BlockPos world = origin.offset(img.local());
                    if (set.containsKey(world)) continue;
                    set.put(world, imageOf(src, world, img));
                }
            }
        } catch (Throwable t) {
            broken = true;
            LOGGER.debug("[DungeonTrain] Effortless Building preview mirroring disabled: {}", t.toString());
        }
    }

    private static Object imageOf(Object src, BlockPos world, EditorMirror.Image img) throws ReflectiveOperationException {
        Object e = ctor.newInstance(world);
        copyRotation.invoke(e, src);
        if (img.flipX()) mirrorX.setBoolean(e, !mirrorX.getBoolean(e));
        if (img.flipY()) mirrorY.setBoolean(e, !mirrorY.getBoolean(e));
        if (img.flipZ()) mirrorZ.setBoolean(e, !mirrorZ.getBoolean(e));
        return e;
    }

    private static boolean inBounds(BlockPos l, Vec3i f) {
        return l.getX() >= 0 && l.getY() >= 0 && l.getZ() >= 0
            && l.getX() < f.getX() && l.getY() < f.getY() && l.getZ() < f.getZ();
    }

    /** EB's client build mode is {@code DISABLED} ({@code BuildModes.CLIENT.getBuildMode()}). */
    private static boolean isDisabled() throws ReflectiveOperationException {
        Object mode = getBuildMode.invoke(buildModes);
        return mode instanceof Enum<?> e && "DISABLED".equals(e.name());
    }

    private static void resolve(Class<?> cls) throws ReflectiveOperationException {
        if (cls == entryClass) return;
        Class<?> modes = Class.forName("neoforge.nl.requios.effortlessbuilding.buildmode.BuildModes",
            false, cls.getClassLoader());
        buildModes = modes.getField("CLIENT").get(null);
        getBuildMode = modes.getMethod("getBuildMode");
        ctor = cls.getConstructor(BlockPos.class);
        copyRotation = cls.getMethod("copyRotationSettingsFrom", cls);
        posField = cls.getField("blockPos");
        mirrorX = cls.getField("mirrorX");
        mirrorY = cls.getField("mirrorY");
        mirrorZ = cls.getField("mirrorZ");
        entryClass = cls;
    }
}
