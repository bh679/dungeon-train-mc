package games.brennan.dungeontrain.platform;

/**
 * Loader-neutral snapshot of one loaded mod's identity, as returned by
 * {@link DtPlatform#getLoadedMods()}. Fields mirror the three properties DT
 * actually reads off NeoForge's {@code IModInfo} today (mod id, version string,
 * display name), so a Fabric implementation can populate the same shape from
 * {@code ModContainer.getMetadata()} without leaking either loader's types.
 *
 * @param id          the mod id (e.g. {@code "sable"})
 * @param version     the mod version string (e.g. {@code "2.0.2+mc1.21.1"})
 * @param displayName the human-readable name (e.g. {@code "Sable"})
 */
public record DtModInfo(String id, String version, String displayName) {}
