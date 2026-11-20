package games.brennan.dungeontrain.client;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.registry.ModMobEffects;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.GatherEffectScreenTooltipsEvent;

/**
 * The {@code Test} effect's inventory hover tooltip: what Test mode is. Folds the infinite duration
 * onto the title row as {@link FreePlayTooltip} does, then one line on what the mode means.
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class TestModeTooltip {

    private TestModeTooltip() {}

    @SubscribeEvent
    public static void onGatherEffectTooltips(GatherEffectScreenTooltipsEvent event) {
        if (!event.getEffectInstance().getEffect().is(ModMobEffects.TEST_MODE.getId())) return;
        FreePlayTooltip.foldDurationIntoTitle(event.getTooltip());
        event.getTooltip().add(Component.translatable("effect.dungeontrain.test_mode.desc.1")
            .withStyle(ChatFormatting.GRAY));
    }
}
