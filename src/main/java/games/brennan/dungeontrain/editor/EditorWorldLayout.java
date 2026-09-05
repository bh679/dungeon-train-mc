package games.brennan.dungeontrain.editor;

import games.brennan.dungeontrain.DungeonTrain;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.dimension.DimensionType;

/**
 * The world the title screen's Train Editor button creates — see
 * {@code data/dungeontrain/worldgen/world_preset/dungeon_train_editor.json}.
 *
 * <p>Pure void on a flat generator with no layers, overworld only, the same recipe as the Train
 * Builder's world and for the same reason: nothing in it exists until the editor stamps it, so
 * generating terrain under the plots is pure cost. What it keeps from a full Dungeon Train world is
 * the <em>vertical</em> shape:</p>
 *
 * <pre>
 *   y=320  build ceiling        — {@link EditorLayout#PLOT_Y} + a 90-tall portal room fits under it
 *   y=230  plots                — {@link EditorLayout#PLOT_Y}
 *   y=0    terrain floor        — a flat generator's {@code getMinY()} is always 0
 *   y=-96  world bottom         — 96 blocks of basement for {@code /dt portal test}, as in every DT preset
 * </pre>
 *
 * <p>So {@code dimension_type/editor.json} is {@code min_y: -96, height: 416}. The builder's own
 * type ({@code min_y: 0, height: 96}) cannot host the plots and has no basement.</p>
 *
 * <p>Identified by dimension <b>type</b>, not dimension key — the world's only dimension sits in
 * the {@code minecraft:overworld} slot, exactly like the builder's.</p>
 */
public final class EditorWorldLayout {

    /** The editor world's dimension type — see {@code data/dungeontrain/dimension_type/editor.json}. */
    public static final ResourceKey<DimensionType> EDITOR_DIMENSION_TYPE = ResourceKey.create(
            Registries.DIMENSION_TYPE,
            ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "editor"));

    /**
     * Where a player stands before the editor command lifts them onto a plot: one block above the
     * first carriage plot's floor, in the same chunk column vanilla already prepares as spawn. There
     * is nothing to stand on in a void world, so joins pair this with {@code BuilderSpawn.startFlying}.
     */
    public static final BlockPos SPAWN = new BlockPos(0, EditorLayout.PLOT_Y + 1, 0);

    private EditorWorldLayout() {}

    /** Whether {@code level} is the Train Editor's void world. */
    public static boolean isEditorWorld(ServerLevel level) {
        return isEditorWorld((net.minecraft.world.level.Level) level);
    }

    /**
     * The same question asked of any level, so the client can ask it too — the authoring hotkeys
     * need to know they are in the editor world while the player is standing between plots, where
     * the server's per-plot status HUD has nothing to say.
     */
    public static boolean isEditorWorld(net.minecraft.world.level.Level level) {
        return level != null && level.dimensionTypeRegistration().is(EDITOR_DIMENSION_TYPE);
    }
}
