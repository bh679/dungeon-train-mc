package games.brennan.dungeontrain.net.platform;
import games.brennan.dungeontrain.DtCore;

/**
 * Loader-neutral copy of {@code DtCore.MOD_ID}. The root module's main
 * class is {@code @Mod}-annotated (a NeoForge type) so it can never live in
 * {@code :common}; payload records only need the id string for their
 * {@code CustomPacketPayload.Type} resource location, so they depend on this
 * tiny constant instead — same value, never renamed (packet ids are stable
 * across versions, same rule as {@code DungeonTrainNet.PROTOCOL_VERSION}).
 */
public final class DtModId {

    public static final String MOD_ID = "dungeontrain";

    private DtModId() {}
}
