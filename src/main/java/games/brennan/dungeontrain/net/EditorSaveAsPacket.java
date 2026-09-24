package games.brennan.dungeontrain.net;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.editor.EditorSaveAs;
import games.brennan.dungeontrain.editor.EditorTemplateAddress;
import games.brennan.dungeontrain.template.Template;
import net.minecraft.ChatFormatting;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.slf4j.Logger;

import java.util.Optional;

/**
 * Client → server: the player's answer to {@link EditorSaveAsPromptPacket}.
 *
 * <p>OP-only, like every other editor write — the same permission level as
 * {@link EditorPlotActionPacket}. A non-OP answer is dropped.</p>
 *
 * @param address the template the prompt was about
 * @param choice  save as a new template, or keep the edit on this install over the shipped one
 * @param name    the new template's name; ignored for {@link Choice#LOCAL}
 */
public record EditorSaveAsPacket(EditorTemplateAddress address, Choice choice, String name)
        implements CustomPacketPayload {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Matches EditorCommand.requiresPermissions. */
    private static final int PERMISSION_LEVEL = 2;

    public enum Choice { NEW, LOCAL }

    public static final Type<EditorSaveAsPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "editor_save_as"));

    public static final StreamCodec<FriendlyByteBuf, EditorSaveAsPacket> STREAM_CODEC =
        StreamCodec.of(
            (buf, packet) -> {
                writeAddress(buf, packet.address);
                buf.writeVarInt(packet.choice.ordinal());
                buf.writeUtf(packet.name, 64);
            },
            buf -> {
                EditorTemplateAddress address = readAddress(buf);
                int idx = buf.readVarInt();
                // An unknown choice reads as LOCAL: the one that writes nothing new and moves nothing.
                Choice choice = idx == Choice.NEW.ordinal() ? Choice.NEW : Choice.LOCAL;
                return new EditorSaveAsPacket(address, choice, buf.readUtf(64));
            }
        );

    static void writeAddress(FriendlyByteBuf buf, EditorTemplateAddress address) {
        buf.writeUtf(address.type(), 16);
        buf.writeUtf(address.sub(), 32);
        buf.writeUtf(address.name(), 64);
    }

    static EditorTemplateAddress readAddress(FriendlyByteBuf buf) {
        return new EditorTemplateAddress(buf.readUtf(16), buf.readUtf(32), buf.readUtf(64));
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(EditorSaveAsPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer player)) return;
            if (!player.hasPermissions(PERMISSION_LEVEL)) {
                LOGGER.debug("[DungeonTrain] EditorSaveAs: dropping non-OP answer from {}",
                    player.getName().getString());
                return;
            }
            Optional<Template> model = packet.address.resolve();
            if (model.isEmpty()) {
                player.sendSystemMessage(Component.translatable("chat.dungeontrain.save_as.gone",
                        packet.address.name()).withStyle(ChatFormatting.RED));
                return;
            }
            switch (packet.choice) {
                case NEW -> EditorSaveAs.saveAsNew(player, model.get(), packet.name);
                case LOCAL -> EditorSaveAs.keepLocal(player, model.get());
            }
        });
    }
}
