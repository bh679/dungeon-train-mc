package games.brennan.dungeontrain.client.builder;

/**
 * Pure geometry for the {@link TrainBuilderScreen} 2×2 tile grid.
 *
 * <p>Split out of the screen so it can be unit-tested without a Minecraft client: the grid has
 * to survive GUI scale 1 (a very wide, short viewport) through scale 4 (a small one) without
 * tiles overlapping the title, the Back button, or each other — and that is exactly the kind of
 * arithmetic that is cheap to test and annoying to eyeball in-game at four scales.</p>
 *
 * <p>Tiles keep a 16:9 aspect (the images are screenshots) and are sized by whichever of width
 * or height binds first.</p>
 */
record BuilderGridLayout(int tileWidth, int tileHeight, int originX, int originY, int gap) {

    static final int GAP = 8;
    static final int COLUMNS = 2;
    static final int ROWS = 2;

    /** Widest the grid is allowed to get, so tiles don't become billboards on an ultrawide. */
    private static final int MAX_GRID_WIDTH = 420;
    private static final int SIDE_MARGIN = 16;
    private static final int MIN_TILE_WIDTH = 64;

    /**
     * @param screenWidth  screen width in GUI pixels
     * @param screenHeight screen height in GUI pixels
     * @param topY         first Y the grid may occupy (below the title)
     * @param bottomY      first Y the grid may NOT occupy (above the Back button)
     */
    static BuilderGridLayout of(int screenWidth, int screenHeight, int topY, int bottomY) {
        int availableWidth = Math.min(screenWidth - 2 * SIDE_MARGIN, MAX_GRID_WIDTH);
        int availableHeight = Math.max(bottomY - topY, ROWS * (MIN_TILE_WIDTH * 9 / 16) + GAP);

        int tileWidth = (availableWidth - GAP * (COLUMNS - 1)) / COLUMNS;
        int tileHeight = tileWidth * 9 / 16;

        // Height binds on short viewports (GUI scale 4, or a small window) — re-derive width
        // from the height cap so the 16:9 aspect is preserved rather than squashed.
        int maxTileHeight = (availableHeight - GAP * (ROWS - 1)) / ROWS;
        if (tileHeight > maxTileHeight) {
            tileHeight = maxTileHeight;
            tileWidth = tileHeight * 16 / 9;
        }

        tileWidth = Math.max(tileWidth, MIN_TILE_WIDTH);
        tileHeight = Math.max(tileHeight, MIN_TILE_WIDTH * 9 / 16);

        int gridWidth = COLUMNS * tileWidth + GAP * (COLUMNS - 1);
        int gridHeight = ROWS * tileHeight + GAP * (ROWS - 1);
        int originX = (screenWidth - gridWidth) / 2;
        int originY = topY + Math.max(0, (bottomY - topY - gridHeight) / 2);

        return new BuilderGridLayout(tileWidth, tileHeight, originX, originY, GAP);
    }

    int xFor(int index) {
        return originX + (index % COLUMNS) * (tileWidth + gap);
    }

    int yFor(int index) {
        return originY + (index / COLUMNS) * (tileHeight + gap);
    }
}
