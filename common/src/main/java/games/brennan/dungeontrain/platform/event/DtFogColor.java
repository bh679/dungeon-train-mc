package games.brennan.dungeontrain.platform.event;

/**
 * Mutable RGB carrier for {@link DtFogColorCallback}, mirroring the get/set fog
 * colour channels of NeoForge's {@code ViewportEvent.ComputeFogColor}. The bridge
 * backs it with the live event so a handler's writes take effect exactly as the
 * former {@code event.setRed/Green/Blue} calls did.
 */
public interface DtFogColor {

    float getRed();

    float getGreen();

    float getBlue();

    void setRed(float red);

    void setGreen(float green);

    void setBlue(float blue);
}
