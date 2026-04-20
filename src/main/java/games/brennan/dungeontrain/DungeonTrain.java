package games.brennan.dungeontrain;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.train.TrainForcesInducer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;
import org.valkyrienskies.core.api.VsCoreApi;
import org.valkyrienskies.core.api.attachment.AttachmentRegistration;
import org.valkyrienskies.mod.common.ValkyrienSkiesMod;

/**
 * Dungeon Train — Minecraft port of the itch.io game.
 * Entry point for the Forge mod. Registries are wired here.
 */
@Mod(DungeonTrain.MOD_ID)
public class DungeonTrain {
    public static final String MOD_ID = "dungeontrain";
    private static final Logger LOGGER = LogUtils.getLogger();

    public DungeonTrain() {
        IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();

        modBus.addListener(this::commonSetup);
        modBus.addListener(this::clientSetup);

        MinecraftForge.EVENT_BUS.register(this);

        LOGGER.info("Dungeon Train constructor — mod loading");
    }

    private void commonSetup(final FMLCommonSetupEvent event) {
        LOGGER.info("Dungeon Train common setup");
        event.enqueueWork(DungeonTrain::registerVsAttachments);
    }

    private static void registerVsAttachments() {
        VsCoreApi core = ValkyrienSkiesMod.getVsCore();
        AttachmentRegistration<TrainForcesInducer> reg = core
            .newAttachmentRegistrationBuilder(TrainForcesInducer.class)
            .useTransientSerializer()
            .build();
        core.registerAttachment(reg);
        LOGGER.info("Dungeon Train: registered TrainForcesInducer attachment (transient)");
    }

    private void clientSetup(final FMLClientSetupEvent event) {
        LOGGER.info("Dungeon Train client setup");
    }
}
