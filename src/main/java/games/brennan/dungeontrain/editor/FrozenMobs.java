package games.brennan.dungeontrain.editor;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.SpawnEggItem;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.util.TriState;
import net.neoforged.neoforge.event.entity.player.AttackEntityEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import org.jetbrains.annotations.Nullable;

/**
 * The editor's <b>Mobs</b> setting (Settings → Mobs | Blocks | Live), default Blocks.
 *
 * <h2>Blocks</h2>
 * <p>A spawn egg used inside an editor plot places the mob the way a block is placed: on the clicked
 * cell, facing the author, and <b>frozen</b> — {@code NoAI}, silent, invulnerable, persistent, and
 * carrying the {@link #TAG} scoreboard tag. It renders exactly like the real mob standing still, does
 * not wander off, and does not burn in the editor's daylight. One left-click removes it, the way a
 * creative-mode block breaks. Live is the vanilla egg — a wandering mob — which is what every world
 * did before the setting existed.</p>
 *
 * <h2>Why the real entity and not a block</h2>
 * <p>{@code TemplateDecor} already captures every living entity in a plot and puts it back on every
 * stamp, so a frozen mob rides the save/load pipeline unchanged and comes back frozen (the flags live
 * in its NBT). The one new obligation is play-side: the flags must be <b>thawed</b> when the template
 * is stamped into a real world, or the train would carry a statue. {@link #prepareForSpawn} does that
 * at every template → entity restore site, gated on the target not being an editor plot — the same
 * position scoping {@link EditorObservers#isMuted} uses, so a Train Editor stamp keeps its statues and
 * a train never gets one.</p>
 *
 * <p>The setting also drives the variant-cell mob ghosts ({@code EditorMobGhostRenderer}): a cell
 * whose variant pool holds a mob entry draws that mob in the same frozen pose while Blocks is on.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class FrozenMobs {

    /** Scoreboard tag marking an editor-frozen mob; what {@link #thaw} keys on. */
    public static final String TAG = "dungeontrain_frozen";

    private static final String NBT_NO_AI = "NoAI";
    private static final String NBT_SILENT = "Silent";
    private static final String NBT_INVULNERABLE = "Invulnerable";
    private static final String NBT_TAGS = "Tags";

    private FrozenMobs() {}

    // --- Setting --------------------------------------------------------------------------------

    /** True while the world's Mobs setting is Blocks (the default). */
    public static boolean isBlocksMode(ServerLevel level) {
        return !DungeonTrainWorldData.get(level).isEditorMobsLive();
    }

    /**
     * True when {@code pos} is somewhere a frozen mob belongs: the Train Editor void world, or inside
     * an editor plot of a play world. Everywhere else a frozen mob must be thawed on spawn.
     */
    public static boolean isEditorSpace(ServerLevel level, BlockPos pos) {
        if (EditorWorldLayout.isEditorWorld(level)) return true;
        return EditorCategory.locateAt(pos, DungeonTrainWorldData.get(level).dims()).isPresent();
    }

    // --- Freeze / thaw --------------------------------------------------------------------------

    /** Stand the mob still: no AI, no sound, no damage, never despawns, and the {@link #TAG}. */
    public static void freeze(Mob mob, float yaw) {
        mob.setNoAi(true);
        mob.setSilent(true);
        mob.setInvulnerable(true);
        mob.setPersistenceRequired();
        mob.addTag(TAG);
        mob.setYRot(yaw);
        mob.setYBodyRot(yaw);
        mob.setYHeadRot(yaw);
        mob.yRotO = yaw;
        mob.setDeltaMovement(0, 0, 0);
    }

    public static boolean isFrozen(Entity entity) {
        return entity.getTags().contains(TAG);
    }

    /**
     * Strip the freeze from an entity's saved NBT before it is created. Returns a new tag when
     * {@code nbt} carries the {@link #TAG}; returns {@code nbt} itself, untouched, otherwise — an
     * author's own {@code NoAI} egg is theirs to keep.
     */
    public static CompoundTag thaw(CompoundTag nbt) {
        if (!carriesTag(nbt)) return nbt;
        CompoundTag out = nbt.copy();
        out.remove(NBT_NO_AI);
        out.remove(NBT_SILENT);
        out.remove(NBT_INVULNERABLE);
        ListTag tags = out.getList(NBT_TAGS, Tag.TAG_STRING);
        ListTag kept = new ListTag();
        for (Tag t : tags) {
            if (!TAG.equals(t.getAsString())) kept.add(StringTag.valueOf(t.getAsString()));
        }
        if (kept.isEmpty()) out.remove(NBT_TAGS); else out.put(NBT_TAGS, kept);
        return out;
    }

    /**
     * What every template → entity restore site calls: the NBT to create the entity from. Frozen
     * NBT stays frozen inside editor space and is thawed anywhere else.
     */
    public static CompoundTag prepareForSpawn(CompoundTag nbt, ServerLevel level, BlockPos at) {
        if (!carriesTag(nbt)) return nbt;
        return isEditorSpace(level, at) ? nbt : thaw(nbt);
    }

    private static boolean carriesTag(CompoundTag nbt) {
        if (!nbt.contains(NBT_TAGS, Tag.TAG_LIST)) return false;
        for (Tag t : nbt.getList(NBT_TAGS, Tag.TAG_STRING)) {
            if (TAG.equals(t.getAsString())) return true;
        }
        return false;
    }

    // --- Placing --------------------------------------------------------------------------------

    /**
     * A spawn egg used on a block inside editor space while Blocks is on places a frozen mob. Runs
     * after {@link VariantBlockInteractions}: the Z-held gesture still adds a mob <i>variant</i>, and
     * cancels the event before this sees it.
     */
    @SubscribeEvent
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (event.isCanceled()) return;
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (event.getHand() != InteractionHand.MAIN_HAND) return;
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        ItemStack held = event.getItemStack();
        if (!(held.getItem() instanceof SpawnEggItem egg)) return;
        if (VariantHotkeyState.isHeld(player)) return;
        if (!isBlocksMode(level)) return;

        BlockPos clicked = event.getPos();
        if (level.getBlockState(clicked).is(Blocks.SPAWNER)) return; // vanilla: egg retypes the spawner
        BlockPos cell = level.getBlockState(clicked).getCollisionShape(level, clicked).isEmpty()
            ? clicked : clicked.relative(event.getFace() == null ? Direction.UP : event.getFace());
        if (!isEditorSpace(level, cell)) return;

        EntityType<?> type = egg.getType(held);
        if (type == null) return;
        Entity spawned = type.spawn(level, held, player, cell, MobSpawnType.SPAWN_EGG, true, false);
        if (spawned == null) {
            player.displayClientMessage(
                Component.literal("Could not place " + type.getDescription().getString() + " here.")
                    .withStyle(ChatFormatting.YELLOW), true);
            suppressVanilla(event);
            return;
        }
        if (spawned instanceof Mob mob) freeze(mob, yawFacing(player));
        if (!player.getAbilities().instabuild) held.shrink(1);
        level.gameEvent(player, net.minecraft.world.level.gameevent.GameEvent.ENTITY_PLACE, cell);
        suppressVanilla(event);
    }

    /** The yaw a placed mob takes: towards the author, snapped to the nearest quarter turn. */
    static float yawFacing(ServerPlayer player) {
        float towards = player.getYRot() + 180.0F;
        return Mth.wrapDegrees(Math.round(towards / 90.0F) * 90.0F);
    }

    private static void suppressVanilla(PlayerInteractEvent.RightClickBlock event) {
        event.setUseBlock(TriState.FALSE);
        event.setUseItem(TriState.FALSE);
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.SUCCESS);
    }

    // --- Breaking -------------------------------------------------------------------------------

    /** One swing at a frozen mob removes it — a block breaking in creative, not a fight. */
    @SubscribeEvent
    public static void onAttackEntity(AttackEntityEvent event) {
        if (event.isCanceled()) return;
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        Entity target = event.getTarget();
        if (!isFrozen(target)) return;
        target.discard();
        event.setCanceled(true);
        player.displayClientMessage(
            Component.literal("Removed " + target.getType().getDescription().getString())
                .withStyle(ChatFormatting.GRAY), true);
    }
}
