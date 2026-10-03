package games.brennan.dungeontrain.advancement;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.cheat.RunIntegrity;
import games.brennan.dungeontrain.config.DungeonTrainConfig;
import games.brennan.dungeontrain.discord.MilestoneAdvancementReporter;
import games.brennan.dungeontrain.net.relay.AdvancementFirstClient;
import net.minecraft.ChatFormatting;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;

/**
 * "You're the first passenger ever to earn this." On a genuine, clean earn of any Dungeon Train
 * advancement outside the editor tab, the relay is asked whether anyone has ever earned it before
 * ({@link AdvancementFirstClient}); when the answer is no, the player hears so in chat and the
 * passenger log gets the milestone-style announcement — framed photo, toast, Brennan's @-mention —
 * under a "first ever" title.
 *
 * <p>All-time and relay-decided: a local store cannot know about other players, and the relay seeds
 * its record book from everything already earned before this existed, so only a true first is ever
 * announced. Relay unreachable → nothing (never a false first). Free Play → nothing.</p>
 */
public final class FirstEverAdvancements {

    private static final Logger LOGGER = LogUtils.getLogger();

    static final String CHAT_KEY = "chat.dungeontrain.first_ever";

    private FirstEverAdvancements() {}

    /** Does this id take part? Every {@code dungeontrain:*} advancement, the Secrete Menu included, but not the editor tab. */
    static boolean eligible(ResourceLocation id) {
        return DungeonTrain.MOD_ID.equals(id.getNamespace()) && !id.getPath().startsWith("editor/");
    }

    /** Server thread, from a genuine (non-replay) earn. Cheap when the earn cannot be a first. */
    public static void onEarn(ServerPlayer player, AdvancementHolder advancement) {
        ResourceLocation id = advancement.id();
        if (!eligible(id)) return;
        if (!DungeonTrainConfig.isWorldInfoToRelay()) return;   // the relay is what knows; no relay, no firsts
        if (RunIntegrity.isCheated(player)) return;            // Free Play earns are not feats
        MinecraftServer server = player.getServer();
        if (server == null) return;
        String name = player.getGameProfile().getName();
        AdvancementFirstClient.claim(player.getUUID(), name, id.toString(), answer -> {
            if (!answer.first()) return;
            server.execute(() -> celebrate(server, player, advancement));
        });
    }

    /** Back on the server thread with a confirmed first: tell the player, then the passenger log. */
    private static void celebrate(MinecraftServer server, ServerPlayer earner, AdvancementHolder advancement) {
        ServerPlayer player = server.getPlayerList().getPlayer(earner.getUUID());
        if (player == null) return;                            // logged out while the relay answered
        Component title = MilestoneAdvancementReporter.advancementTitle(advancement);
        player.sendSystemMessage(Component.translatable(CHAT_KEY, title).withStyle(ChatFormatting.GOLD));
        LOGGER.info("[DungeonTrain] {} is the first ever to earn {}.", player.getGameProfile().getName(),
                advancement.id());
        MilestoneAdvancementReporter.announceFirstEver(player, advancement);
    }
}
