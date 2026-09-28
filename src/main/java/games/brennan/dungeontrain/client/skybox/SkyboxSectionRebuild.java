package games.brennan.dungeontrain.client.skybox;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.block.SkyboxBlock;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.slf4j.Logger;

/**
 * Re-mesh the sections whose look depends on whether Skybox Blocks are "there" — and nothing else.
 *
 * <p>A Skybox Block that is switched off neither draws nor occludes ({@link SkyboxBlock#getOcclusionShape});
 * a test session puts it back. Either flip changes what the standing meshes cull, so they have to be
 * rebuilt. The first cut did that with {@code LevelRenderer.allChanged()} — every section in view,
 * discarded and rebuilt. On a machine that can only spare Sodium one build worker that is a
 * twenty-second stall, and on the way <em>out</em> of a test it lands in the same tick as the
 * teleport home and a million-block sweep of the test window — the player whose log this was written
 * from never got their render thread back.</p>
 *
 * <p>Only a section that <b>contains</b> a Skybox Block can mesh differently, plus its six
 * neighbours, whose faces against that block are what the occlusion shape culls. So this walks the
 * chunks in render distance, palette-tests each section the way {@code SkyboxBlockIndex} does, and
 * marks just those dirty through {@link LevelRenderer#setSectionDirty(int, int, int)} — the vanilla
 * entry point Sodium overrides with its own scheduler, so one call serves both renderers. A section
 * the renderer has not built yet is skipped by both, and is built with the current answer whenever the
 * walk reaches it.</p>
 *
 * <p><b>Main world only.</b> Skybox Blocks aboard a Sable carriage are meshed by Sable's own chunk
 * renderer in plot space, which this does not reach. A test room is world blocks under the plots, so
 * that is the case a test flips; a carriage-resident wall keeps its previous mesh until its next
 * rebuild, which is cosmetic and self-healing.</p>
 */
public final class SkyboxSectionRebuild {

    private static final Logger LOGGER = LogUtils.getLogger();

    private SkyboxSectionRebuild() {}

    /** Mark dirty every section in render distance that holds a Skybox Block, and its neighbours. */
    public static void rebuildAround(Minecraft mc) {
        ClientLevel level = mc.level;
        LevelRenderer renderer = mc.levelRenderer;
        Entity camera = mc.getCameraEntity() != null ? mc.getCameraEntity() : mc.player;
        if (level == null || renderer == null || camera == null) return;

        int radius = mc.options.getEffectiveRenderDistance();
        int centreX = SectionPos.blockToSectionCoord(camera.getBlockX());
        int centreZ = SectionPos.blockToSectionCoord(camera.getBlockZ());
        LongSet dirty = new LongOpenHashSet();

        for (int cx = centreX - radius; cx <= centreX + radius; cx++) {
            for (int cz = centreZ - radius; cz <= centreZ + radius; cz++) {
                LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
                if (chunk == null) continue;
                collectSkyboxSections(chunk, dirty);
            }
        }

        for (long packed : dirty) {
            renderer.setSectionDirty(SectionPos.x(packed), SectionPos.y(packed), SectionPos.z(packed));
        }
        LOGGER.info("[DungeonTrain] Skybox Blocks flipped — {} section(s) marked for rebuild", dirty.size());
    }

    /** Every section of {@code chunk} whose palette can hold a Skybox Block, with its six neighbours. */
    private static void collectSkyboxSections(LevelChunk chunk, LongSet out) {
        LevelChunkSection[] sections = chunk.getSections();
        int minSectionY = chunk.getMinSection();
        int cx = chunk.getPos().x;
        int cz = chunk.getPos().z;
        for (int i = 0; i < sections.length; i++) {
            LevelChunkSection section = sections[i];
            if (section == null || section.hasOnlyAir()) continue;
            if (!section.maybeHas(state -> state.getBlock() instanceof SkyboxBlock)) continue;
            int sy = minSectionY + i;
            out.add(SectionPos.asLong(cx, sy, cz));
            for (Direction dir : Direction.values()) {
                out.add(SectionPos.asLong(cx + dir.getStepX(), sy + dir.getStepY(), cz + dir.getStepZ()));
            }
        }
    }
}
