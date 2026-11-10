package games.brennan.dungeontrain.registry;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.block.engine.EngineMenu;
import games.brennan.dungeontrain.player.FreePlayEnderChestMenu;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.inventory.MenuType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.extensions.IMenuTypeExtension;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * Container menus registered by Dungeon Train.
 *
 * <p>The Free Play Ender Chest, which needs its own type because vanilla has no
 * nine-row chest menu and the client must know how many rows (and whether to offer the expand button)
 * before the screen opens — {@link IMenuTypeExtension#create} carries that in the open-screen packet —
 * and the Drifting Engine Block's coal screen.</p>
 */
public final class ModMenuTypes {

    public static final DeferredRegister<MenuType<?>> MENU_TYPES =
        DeferredRegister.create(Registries.MENU, DungeonTrain.MOD_ID);

    public static final DeferredHolder<MenuType<?>, MenuType<FreePlayEnderChestMenu>> FREE_PLAY_ENDER_CHEST =
        MENU_TYPES.register("ender_chest", () -> IMenuTypeExtension.create(FreePlayEnderChestMenu::fromNetwork));

    /** The Drifting Engine Block's coal screen — see {@link EngineMenu}. */
    public static final DeferredHolder<MenuType<?>, MenuType<EngineMenu>> ENGINE_BLOCK =
        MENU_TYPES.register("drifting_engine_block", () -> new MenuType<>(EngineMenu::new, FeatureFlags.DEFAULT_FLAGS));

    private ModMenuTypes() {}

    public static void register(IEventBus modBus) {
        MENU_TYPES.register(modBus);
    }
}
