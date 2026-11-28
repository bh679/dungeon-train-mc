package games.brennan.dungeontrain.event;

import com.mojang.authlib.GameProfile;
import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.advancement.ModAdvancementTriggers;
import games.brennan.dungeontrain.train.CarriageContentsPlacer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.animal.Cat;
import net.minecraft.world.entity.animal.Parrot;
import net.minecraft.world.entity.animal.Wolf;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import org.slf4j.Logger;

import java.nio.file.Files;
import java.util.UUID;

/**
 * A pet that drifted in from another world, whose owner has never played in this one, can be tamed
 * again by anyone, with the vanilla item and at vanilla's odds.
 *
 * <p>A sitting pet travels with its drifting carriage ({@code CarriagePets}). In a world its owner
 * has joined, it stays theirs: their right-click stands it up, and nobody else can take it. In a
 * world they never play in, vanilla would leave it owned by someone who can never come for it, and
 * nobody else could do anything with it. So there, a taming item treats it as wild: the pet is
 * untamed for the length of vanilla's own interaction, and if vanilla tames it, it belongs to the
 * clicker. If the roll fails, it goes back to its old owner, sitting as it was, and the item is spent,
 * as any failed vanilla tame spends it.</p>
 *
 * <p>Only pets that came from a carriage (they carry the {@link CarriageContentsPlacer#DT_CONTENTS_TAG_PREFIX}
 * tag), only wolves, cats and parrots (the vanilla pets tamed by an item), and only their own taming
 * item. Everything else is vanilla.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class DriftedPetRetameEvents {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Advancement action id ({@code dungeon_train/come_along_with_me}). */
    static final String RETAMED_ACTION_ID = "retamed_drifted_pet";

    private DriftedPetRetameEvents() {}

    /**
     * The rule. Pure.
     *
     * @param ownerInWorld the owner is online, has saved data in this world, or is its singleplayer host
     */
    static boolean mayRetame(boolean hasOwner, boolean ownerIsClicker, boolean fromCarriage,
                             boolean ownerInWorld, boolean tamingItem) {
        return hasOwner && !ownerIsClicker && fromCarriage && !ownerInWorld && tamingItem;
    }

    @SubscribeEvent
    public static void onEntityInteract(PlayerInteractEvent.EntityInteract event) {
        if (event.isCanceled() || event.getLevel().isClientSide()) return;
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (!(event.getTarget() instanceof TamableAnimal pet)) return;
        UUID owner = pet.getOwnerUUID();
        ItemStack held = event.getItemStack();
        if (owner == null || owner.equals(player.getUUID())) return; // cheap checks before the disk read
        if (!mayRetame(true, false, fromCarriage(pet), ownerInWorld(player.server, owner),
                isTamingItem(pet, held))) return;

        InteractionResult result = tameAsWild(pet, player, event, owner);
        event.setCanceled(true);
        event.setCancellationResult(result);
    }

    /** Untame, let vanilla try, and put the old owner back unless the clicker won it. */
    private static InteractionResult tameAsWild(TamableAnimal pet, ServerPlayer player,
                                                PlayerInteractEvent.EntityInteract event, UUID owner) {
        boolean sat = pet.isOrderedToSit();
        boolean sitPose = pet.isInSittingPose();
        pet.setTame(false, false);
        pet.setOwnerUUID(null);
        InteractionResult result;
        try {
            result = pet.interact(player, event.getHand());
        } catch (Throwable t) {
            LOGGER.warn("[DungeonTrain] Retaming drifted pet {} failed: {}", pet.getType().toShortString(), t.toString());
            result = InteractionResult.PASS;
        }
        if (pet.isOwnedBy(player)) {
            LOGGER.info("[DungeonTrain] Drifted pet {} retamed by {} (its owner {} has never played here)",
                    pet.getType().toShortString(), player.getGameProfile().getName(), owner);
            ModAdvancementTriggers.GAMEPLAY_ACTION.get().trigger(player, RETAMED_ACTION_ID);
            return result;
        }
        pet.setTame(true, false);
        pet.setOwnerUUID(owner);
        pet.setOrderedToSit(sat);
        pet.setInSittingPose(sitPose);
        return result;
    }

    private static boolean fromCarriage(TamableAnimal pet) {
        for (String tag : pet.getTags()) {
            if (tag.startsWith(CarriageContentsPlacer.DT_CONTENTS_TAG_PREFIX)) return true;
        }
        return false;
    }

    /** Vanilla's taming item for the pets an item tames; nothing for any other kind. */
    private static boolean isTamingItem(TamableAnimal pet, ItemStack held) {
        if (held.isEmpty()) return false;
        if (pet instanceof Wolf) return held.is(Items.BONE);
        if (pet instanceof Cat cat) return cat.isFood(held);
        if (pet instanceof Parrot) return held.is(ItemTags.PARROT_FOOD);
        return false;
    }

    /** Online, saved in this world, or its singleplayer host (whose data lives in level.dat). */
    private static boolean ownerInWorld(MinecraftServer server, UUID owner) {
        if (server.getPlayerList().getPlayer(owner) != null) return true;
        GameProfile host = server.getSingleplayerProfile();
        if (host != null && owner.equals(host.getId())) return true;
        try {
            return Files.exists(server.getWorldPath(LevelResource.PLAYER_DATA_DIR).resolve(owner + ".dat"));
        } catch (Exception e) {
            LOGGER.warn("[DungeonTrain] Could not check saved data for pet owner {}: {}", owner, e.toString());
            return true; // unknown → treat as present: never hand someone's pet away on a failed read
        }
    }
}
