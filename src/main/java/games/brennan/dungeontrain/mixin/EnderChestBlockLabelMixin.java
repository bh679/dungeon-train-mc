package games.brennan.dungeontrain.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.cheat.RunIntegrity;
import games.brennan.dungeontrain.compat.EnderChestLockBridge;
import games.brennan.dungeontrain.player.EnderChestExpansion;
import games.brennan.dungeontrain.player.FreePlayEnderChestMenu;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.level.block.EnderChestBlock;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.OptionalInt;

/**
 * Titles the Ender Chest with the profile it belongs to whenever that isn't the default one — the
 * per-game-mode chest EnderChestPersistence keeps, the per-difficulty one
 * {@code DifficultyPartition} adds, or a Free Play run's locked chest. See
 * {@code EnderChestLabel} for what the title says and why.
 *
 * <p>A Free Play run additionally gets DT's own chest menu instead of vanilla's three-row one — the
 * same live container, but at whatever size the player has expanded it to, with the expand button when
 * they haven't yet. See {@link EnderChestExpansion}. That path uses the open-screen variant that carries
 * extra data, so it replaces the wrapped call rather than delegating to it.</p>
 *
 * <p>Vanilla builds the title as a constant inside {@code useWithoutItem} and hands it straight to
 * {@code openMenu}, so there is no event or data-driven seam to reach it — the one call is wrapped
 * instead, replacing only the provider's display name and delegating the menu construction untouched.
 * {@code WrapOperation} rather than {@code @Redirect} so other mods injecting at the same call still
 * compose (same reason as {@code ChunkMapMixin}).</p>
 *
 * <p>Server-side: the title travels in the open-screen packet, so a vanilla client shows it and a
 * dedicated server needs nothing extra. Fail-open — any fault leaves vanilla's title in place rather
 * than stopping a player from opening their chest.</p>
 */
@Mixin(EnderChestBlock.class)
public abstract class EnderChestBlockLabelMixin {

    private static final Logger LOGGER = LogUtils.getLogger();

    @WrapOperation(
        method = "useWithoutItem",
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/player/Player;openMenu(Lnet/minecraft/world/MenuProvider;)Ljava/util/OptionalInt;"))
    private OptionalInt dungeontrain$labelEnderChestMenu(
            Player player, MenuProvider provider, Operation<OptionalInt> original) {
        MenuProvider labelled = labelled(player, provider);
        OptionalInt freePlay = openFreePlayChest(player, labelled);
        return freePlay != null ? freePlay : original.call(player, labelled);
    }

    /**
     * Open the Free Play chest menu for a Free Play run, or return null to fall through to vanilla's.
     * Fail-open like the label: a fault here means the vanilla three-row menu, never a chest that won't open.
     */
    private static OptionalInt openFreePlayChest(Player player, MenuProvider labelled) {
        try {
            if (!(player instanceof ServerPlayer serverPlayer)
                    || !EnderChestExpansion.available()
                    || !RunIntegrity.isCheated(serverPlayer)) {
                return null;
            }
            FreePlayEnderChestMenu.open(serverPlayer, labelled.getDisplayName());
            return OptionalInt.of(serverPlayer.containerMenu.containerId);
        } catch (Throwable t) {
            LOGGER.warn("[DungeonTrain] Free Play Ender Chest menu unavailable; using vanilla's", t);
            return null;
        }
    }

    /** {@code provider} with a profile-named display name, or unchanged for the default profile. */
    private static MenuProvider labelled(Player player, MenuProvider provider) {
        try {
            if (!(player instanceof ServerPlayer serverPlayer)) {
                return provider;
            }
            Component title = EnderChestLockBridge.titleFor(serverPlayer, provider.getDisplayName())
                .orElse(null);
            if (title == null) {
                return provider; // default chest — vanilla's own title, untouched
            }
            return new MenuProvider() {
                @Override
                public Component getDisplayName() {
                    return title;
                }

                @Override
                public AbstractContainerMenu createMenu(int containerId, Inventory inventory, Player p) {
                    return provider.createMenu(containerId, inventory, p);
                }
            };
        } catch (Throwable t) {
            // Includes the NoClassDefFoundError path if EnderChestPersistence is somehow absent.
            LOGGER.warn("[DungeonTrain] Ender Chest label unavailable; using the default title", t);
            return provider;
        }
    }
}
