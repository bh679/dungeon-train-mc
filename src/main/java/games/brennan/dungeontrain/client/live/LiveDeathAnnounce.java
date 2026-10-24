package games.brennan.dungeontrain.client.live;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.JsonOps;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.compat.vista.TvItems;
import games.brennan.dungeontrain.net.relay.LiveFeedClient;
import games.brennan.dungeontrain.net.relay.LiveFeedClient.ChatLine;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.language.ClientLanguage;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.contents.PlainTextContents;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.world.entity.EquipmentSlot;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.util.List;

/**
 * A TV watcher's death, told to the stream's chat room: "Steve was slain by Zombie while watching the stream".
 *
 * <p>{@code ClientPacketListenerInstantRespawnMixin} hands over the local player's combat-kill packet. The
 * server sends it at the start of {@code ServerPlayer.die}, before the loot drops, so the TV is still on the
 * head and {@link LiveChatBridge} still connected: only a player who is in the room through a worn TV (Live
 * Chat on, not Kid mode, someone else on air) is announced, once a life. The streamer's own death is the
 * broadcast's story, not this.</p>
 *
 * <p>The relay gets vanilla's death message twice: rendered in English for the website, Discord and Twitch,
 * and as component JSON (styles stripped) so each game client in the room prints it in its own language
 * ({@link #line}). Fire-and-forget; an older relay's 404 is ignored.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class LiveDeathAnnounce {

    private static final Logger LOGGER = LogUtils.getLogger();
    public static final String VIEWER_DIED_KEY = "chat.dungeontrain.live.viewer_died";
    private static final String ENGLISH = "en_us";

    private static boolean sentThisLife;
    private static boolean failureLogged;
    @Nullable private static ClientLanguage english;

    private LiveDeathAnnounce() {}

    /** Whether this death is told to the room: in it through a worn TV, not streaming, not already told. */
    static boolean shouldAnnounce(boolean connected, boolean wearingTv, boolean streaming, boolean watchingWithEye,
                                  boolean alreadySent) {
        return connected && wearingTv && !streaming && !watchingWithEye && !alreadySent;
    }

    /** A combat-kill packet; both the netty pass and the client-thread pass arrive, only the latter acts. */
    public static void onCombatKill(int deadEntityId, Component message) {
        Minecraft mc = Minecraft.getInstance();
        if (!mc.isSameThread()) return;
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null || mc.getUser() == null || mc.level.getEntity(deadEntityId) != player) return;
        LiveStreamController stream = LiveStreamController.get();
        boolean wearingTv = TvItems.isTv(player.getItemBySlot(EquipmentSlot.HEAD));
        if (!shouldAnnounce(LiveChatBridge.connected(), wearingTv, stream.streaming(), stream.watchingWithEye(), sentThisLife)) {
            return;
        }
        sentThisLife = true;
        String name = mc.getUser().getName();
        String text = LiveChatRules.clean(inEnglish(mc, message), LiveChatRules.MAX_CONTENT);
        if (text.isBlank()) text = name + " died";
        JsonObject json = toJson(mc, plain(message));
        LOGGER.info("[DungeonTrain] live chat: telling the room {} died ({})", name, text);
        LiveFeedClient.chatDied(LiveChatBridge.base(false), mc.getUser().getProfileId(), name, text, json)
            .thenAccept(r -> {
                if (r.ok() || r.status() == 404 || r.status() == 409 || failureLogged) return;
                failureLogged = true;
                LOGGER.warn("[DungeonTrain] live chat death notice failed (status {}); further failures this session are silent",
                    r.status());
            });
    }

    /** The death line in this reader's language: the relay's message JSON, or its English text when that won't read. */
    public static Component line(Minecraft mc, ChatLine line) {
        String cause = "";
        if (line.death() != null && line.death().message() != null) {
            Component decoded = fromJson(mc, line.death().message());
            if (decoded != null) cause = LiveChatRules.clean(decoded.getString(), LiveChatRules.MAX_CONTENT);
        }
        if (cause.isBlank()) cause = LiveChatRules.deathFallback(line.content());
        return LiveChatRules.deathLine(VIEWER_DIED_KEY, cause);
    }

    /** {@code message} rendered with the English language file, whatever this client reads in. Render thread. */
    private static String inEnglish(Minecraft mc, Component message) {
        if (ENGLISH.equals(mc.options.languageCode)) return message.getString();
        try {
            if (english == null) english = ClientLanguage.loadFrom(mc.getResourceManager(), List.of(ENGLISH), false);
            Language current = Language.getInstance();
            Language.inject(english);
            try {
                return message.getString();
            } finally {
                Language.inject(current);
            }
        } catch (RuntimeException e) {
            LOGGER.debug("[DungeonTrain] live chat: no English death message ({})", e.toString());
            return message.getString();
        }
    }

    /** The message with every style (click, hover, colour) dropped: only its words and their keys travel. */
    static MutableComponent plain(Component c) {
        MutableComponent out;
        if (c.getContents() instanceof TranslatableContents t) {
            Object[] args = t.getArgs();
            Object[] bare = new Object[args.length];
            for (int i = 0; i < args.length; i++) {
                bare[i] = args[i] instanceof Component arg ? plain(arg) : String.valueOf(args[i]);
            }
            out = Component.translatableWithFallback(t.getKey(), t.getFallback(), bare);
        } else if (c.getContents() instanceof PlainTextContents p) {
            out = Component.literal(p.text());
        } else {
            return Component.literal(c.getString()); // anything exotic (score, selector, nbt) travels as the words it shows
        }
        for (Component sibling : c.getSiblings()) out.append(plain(sibling));
        return out;
    }

    @Nullable
    private static JsonObject toJson(Minecraft mc, Component message) {
        try {
            JsonElement el = ComponentSerialization.CODEC.encodeStart(ops(mc), message).result().orElse(null);
            if (el == null || !el.isJsonObject() || el.toString().length() > LiveFeedClient.MAX_DEATH_MESSAGE) return null;
            return el.getAsJsonObject();
        } catch (RuntimeException e) {
            return null;
        }
    }

    @Nullable
    static Component fromJson(Minecraft mc, String json) {
        try {
            JsonElement el = JsonParser.parseString(json);
            return ComponentSerialization.CODEC.parse(ops(mc), el).result().orElse(null);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static DynamicOps<JsonElement> ops(Minecraft mc) {
        return mc.level != null ? mc.level.registryAccess().createSerializationContext(JsonOps.INSTANCE) : JsonOps.INSTANCE;
    }

    @SubscribeEvent
    public static void onRespawn(ClientPlayerNetworkEvent.Clone event) {
        sentThisLife = false;
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        sentThisLife = false;
        failureLogged = false;
    }
}
