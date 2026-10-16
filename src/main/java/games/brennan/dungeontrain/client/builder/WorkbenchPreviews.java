package games.brennan.dungeontrain.client.builder;

import games.brennan.dungeontrain.editor.TemplateCells;
import games.brennan.dungeontrain.editor.TemplateLoot;
import games.brennan.dungeontrain.editor.workbench.WorkbenchStagingStore;
import games.brennan.dungeontrain.train.CarriageSnapshotTemplate;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Pictures of the builds on the Workbench shelf, baked from their staged snapshots.
 *
 * <p>A staged build is in no template store, so {@link TemplateArt} cannot name it and the roster's
 * usual file lookup finds nothing — and the preview went blank while standing in one. The shelf's
 * {@code blocks.nbt} is a {@code CarriageBlockSnapshot} tag, the same shape the relay sends, so it is
 * converted the same way ({@link CarriageSnapshotTemplate#toTemplateTag}) and baked with the same
 * mesh as every other tile. Read off disk, which the client can do only when it runs the server
 * (singleplayer, the editor's own case); on a dedicated server the caller falls back to the relay's
 * picture of the same build by relay id.</p>
 *
 * <p>Re-baked when the file changes, so a Save in the Workbench shows up on the next look.</p>
 */
public final class WorkbenchPreviews {

    private record Entry(BuilderTileMesh mesh, TemplateSummary summary, long modified) {}

    private static final Map<String, Entry> CACHE = new HashMap<>();

    private WorkbenchPreviews() {}

    /** Draw the staged build's model into the box; false when there is no readable local file. */
    public static boolean draw(GuiGraphics g, String stagedId, int x, int y, int w, int h, float yaw, float fill) {
        Entry entry = resolve(stagedId);
        if (entry == null || entry.mesh() == null) return false;
        BuilderTileModelRenderer.render(g, entry.mesh(), x, y, w, h, yaw, fill);
        return true;
    }

    /** The staged build's data-sheet numbers, or null when it cannot be read here. */
    public static TemplateSummary summary(String stagedId) {
        Entry entry = resolve(stagedId);
        return entry == null || entry.summary() == TemplateSummary.NONE ? null : entry.summary();
    }

    /** Drop every baked mesh — on the way out of the screen. Render thread: it closes vertex buffers. */
    public static void clear() {
        for (Entry e : CACHE.values()) {
            if (e.mesh() != null) e.mesh().close();
        }
        CACHE.clear();
    }

    private static Entry resolve(String stagedId) {
        if (stagedId == null || stagedId.isEmpty()) return null;
        String key = stagedId.toLowerCase(Locale.ROOT);
        Path file = WorkbenchStagingStore.folderFor(key).resolve("blocks.nbt");
        long modified;
        try {
            modified = Files.isRegularFile(file) ? Files.getLastModifiedTime(file).toMillis() : -1L;
        } catch (IOException e) {
            modified = -1L;
        }
        if (modified < 0) return null;
        Entry cached = CACHE.get(key);
        if (cached != null && cached.modified() == modified) return cached;
        if (cached != null && cached.mesh() != null) cached.mesh().close();
        Entry baked = bake(key, modified);
        CACHE.put(key, baked);
        return baked;
    }

    private static Entry bake(String stagedId, long modified) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return new Entry(null, TemplateSummary.NONE, modified);
        HolderGetter<Block> blocks = mc.level.registryAccess().lookupOrThrow(Registries.BLOCK);
        Optional<CompoundTag> snapshot = WorkbenchStagingStore.readBlocks(stagedId);
        if (snapshot.isEmpty()) return new Entry(null, TemplateSummary.NONE, modified);
        try {
            CompoundTag tag = CarriageSnapshotTemplate.toTemplateTag(snapshot.get());
            StructureTemplate template = new StructureTemplate();
            template.load(blocks, tag);
            Map<BlockPos, BlockState> cells = TemplateCells.of(template);
            TemplateCells.NbtTally tally = TemplateCells.tallyBlockEntities(template);
            return new Entry(cells.isEmpty() ? null : BuilderTileMesh.bake(cells),
                new TemplateSummary(cells.size(), template.getSize(), tally.blockEntities(), tally.containers(),
                    TemplateCells.entityCount(tag), TemplateCells.lights(cells), TemplateLoot.of(template)),
                modified);
        } catch (RuntimeException e) {
            // A snapshot this build cannot read keeps a blank slate rather than taking the screen down.
            return new Entry(null, TemplateSummary.NONE, modified);
        }
    }
}
