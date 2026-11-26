package games.brennan.dungeontrain.client.farcopies;

/**
 * One flat-coloured box of a far room copy, in blocks relative to the room's base-tile origin.
 * Max corners are exclusive (a single block at the origin is {@code 0,0,0 .. 1,1,1}).
 *
 * @param rgb {@code 0xRRGGBB}
 */
public record FarBox(float minX, float minY, float minZ, float maxX, float maxY, float maxZ, int rgb) {

    /** This box moved by whole blocks. */
    public FarBox offset(float dx, float dy, float dz) {
        return new FarBox(minX + dx, minY + dy, minZ + dz, maxX + dx, maxY + dy, maxZ + dz, rgb);
    }
}
