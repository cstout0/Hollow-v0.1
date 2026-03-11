package noctfield.render;

import noctfield.world.BlockId;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL12.*;
import static org.lwjgl.opengl.GL30.glGenerateMipmap;

/** M111: lightweight texture atlas foundation for voxel terrain. */
public final class TerrainAtlas {
    private TerrainAtlas() {}

    public static final int TILE = 16;
    public static final int COLS = 8;
    public static final int ROWS = 8;
    public static final int SIZE = TILE * COLS;

    public static int textureId = 0;

    // Simple tile ids in atlas grid.
    public static final int T_GRASS_TOP = 0;
    public static final int T_GRASS_SIDE = 1;
    public static final int T_DIRT = 2;
    public static final int T_STONE = 3;
    public static final int T_WOOD = 4;
    public static final int T_LEAVES = 5;
    public static final int T_MUD = 6;
    public static final int T_LANTERN = 7;
    public static final int T_RELIC = 8;
    public static final int T_JOURNAL = 9;
    public static final int T_CAMPFIRE = 10;
    public static final int T_BONES = 11;
    public static final int T_BLOOD = 12;
    public static final int T_COBWEB = 13;
    public static final int T_FUNGUS = 14;
    public static final int T_VOIDSTONE = 15;
    public static final int T_WATER = 16;
    public static final int T_CRYSTAL = 17;
    public static final int T_TORCH = 18;
    public static final int T_CRAFT_TABLE = 19;
    public static final int T_WOOD_PLANK  = 20;
    public static final int T_CRAFT_TABLE_TOP = 21;
    public static final int T_COAL            = 22; // M149: coal ore vein — dark stone with black flecks

    public static int tileFor(byte id, int face) {
        boolean top = face == 2;
        boolean bottom = face == 3;
        return switch (id) {
            case BlockId.GRASS -> top ? T_GRASS_TOP : (bottom ? T_DIRT : T_GRASS_SIDE);
            case BlockId.DIRT -> T_DIRT;
            case BlockId.STONE -> T_STONE;
            case BlockId.WOOD -> T_WOOD;
            case BlockId.LEAVES -> T_LEAVES;
            case BlockId.MUD -> T_MUD;
            case BlockId.LANTERN -> T_LANTERN;
            case BlockId.RELIC -> T_RELIC;
            case BlockId.JOURNAL -> T_JOURNAL;
            case BlockId.CAMPFIRE -> T_CAMPFIRE;
            case BlockId.BONES -> T_BONES;
            case BlockId.BLOODSTAIN -> T_BLOOD;
            case BlockId.COBWEB -> T_COBWEB;
            case BlockId.FUNGUS -> T_FUNGUS;
            case BlockId.VOIDSTONE -> T_VOIDSTONE;
            case BlockId.WATER -> T_WATER;
            case BlockId.CRYSTAL -> T_CRYSTAL;
            case BlockId.CRAFTING_TABLE -> top ? T_CRAFT_TABLE_TOP : T_CRAFT_TABLE;
            case BlockId.WOOD_PLANK     -> T_WOOD_PLANK;
            case BlockId.COAL           -> T_COAL;
            default -> T_STONE;
        };
    }

    public static float[] uvRect(int tile) {
        int tx = tile % COLS;
        int ty = tile / COLS;
        float u0 = tx / (float)COLS;
        float u1 = (tx + 1f) / (float)COLS;
        // Upload path flips image rows; invert V here so tiles map to intended atlas rows.
        float v0 = 1f - (ty + 1f) / (float)ROWS;
        float v1 = 1f - ty / (float)ROWS;
        return new float[]{u0, v0, u1, v1};
    }

    public static boolean ensureLoaded() {
        try {
            File out = new File("textures/terrain_atlas.png");
            out.getParentFile().mkdirs();

            // M120: always regenerate so atlas updates are guaranteed to appear.
            BufferedImage generated = buildDefaultAtlas();
            ImageIO.write(generated, "png", out);

            BufferedImage img = ImageIO.read(out);
            if (img == null) return false;
            textureId = upload(img);
            return textureId != 0;
        } catch (Exception e) {
            System.err.println("[TerrainAtlas] load failed: " + e.getMessage());
            textureId = 0;
            return false;
        }
    }

    private static BufferedImage buildDefaultAtlas() {
        BufferedImage img = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();

        // M120: hand-authored palettes per block (multiple fixed colors each).
        paintPaletteTile(g, T_GRASS_TOP,   new Color[]{c(58,118,44), c(72,138,54), c(88,160,62), c(108,184,74)}, 0.45f);
        paintPaletteTile(g, T_GRASS_SIDE,  new Color[]{c(74,96,50), c(88,110,56), c(102,124,62), c(118,142,72)}, 0.30f);
        paintPaletteTile(g, T_DIRT,        new Color[]{c(78,54,36), c(96,66,44), c(114,78,52), c(132,90,60)}, 0.30f);
        paintPaletteTile(g, T_STONE,       new Color[]{c(84,88,96), c(104,108,116), c(122,126,136), c(144,148,156)}, 0.42f);
        paintPaletteTile(g, T_WOOD,        new Color[]{c(80,54,30), c(98,66,36), c(118,80,44), c(142,96,52)}, 0.34f);
        paintPaletteTile(g, T_LEAVES,      new Color[]{c(34,86,36), c(46,106,44), c(58,126,52), c(74,148,64)}, 0.42f);
        paintPaletteTile(g, T_MUD,         new Color[]{c(52,44,38), c(64,54,46), c(76,64,54), c(90,74,62)}, 0.24f);
        paintPaletteTile(g, T_LANTERN,     new Color[]{c(138,98,38), c(172,126,48), c(214,164,62), c(244,206,92)}, 0.18f);
        paintPaletteTile(g, T_RELIC,       new Color[]{c(96,66,28), c(126,88,36), c(156,112,44), c(186,138,56)}, 0.22f);
        paintPaletteTile(g, T_JOURNAL,     new Color[]{c(58,66,58), c(72,80,70), c(88,96,84), c(104,114,96)}, 0.20f);
        paintPaletteTile(g, T_CAMPFIRE,    new Color[]{c(116,44,20), c(152,58,24), c(194,74,26), c(228,98,34)}, 0.34f);
        paintPaletteTile(g, T_BONES,       new Color[]{c(138,132,112), c(162,156,132), c(188,182,156), c(214,208,182)}, 0.24f);
        paintPaletteTile(g, T_BLOOD,       new Color[]{c(44,8,8), c(66,12,12), c(88,16,16), c(112,20,20)}, 0.28f);
        paintPaletteTile(g, T_COBWEB,      new Color[]{c(120,124,138), c(144,148,162), c(168,172,186), c(196,200,214)}, 0.30f);
        paintPaletteTile(g, T_FUNGUS,      new Color[]{c(24,92,82), c(32,118,106), c(44,148,132), c(66,178,156)}, 0.34f);
        paintPaletteTile(g, T_VOIDSTONE,   new Color[]{c(12,8,20), c(22,14,34), c(34,22,48), c(48,34,66)}, 0.40f);
        paintPaletteTile(g, T_WATER,       new Color[]{c(18,42,98), c(24,58,128), c(36,74,156), c(52,96,186)}, 0.36f);
        paintPaletteTile(g, T_CRYSTAL,     new Color[]{c(42,116,132), c(62,150,168), c(86,188,208), c(116,224,240)}, 0.38f);
        paintPaletteTile(g, T_TORCH,       new Color[]{c(94,60,30), c(128,84,38), c(188,132,42), c(246,214,102)}, 0.26f);
        paintPaletteTile(g, T_CRAFT_TABLE, new Color[]{c(74,46,24), c(96,62,32), c(126,82,42), c(158,106,54)}, 0.32f);
        paintPaletteTile(g, T_CRAFT_TABLE_TOP, new Color[]{c(86,56,28), c(108,72,38), c(136,94,52), c(166,118,68)}, 0.28f);
        // Coal ore: dark stone base with near-black coal seam flecks
        paintPaletteTile(g, T_COAL, new Color[]{c(28,28,32), c(40,40,46), c(56,56,62), c(74,74,82)}, 0.50f);
        // Overlay bright specular flecks so coal reads as a mineral, not just dark stone
        int coalTx = T_COAL % COLS * TILE, coalTy = T_COAL / COLS * TILE;
        g.setColor(c(108,108,124));
        for (int fi = 0; fi < 6; fi++) {
            int fx = (fi * 73 + 3) % (TILE - 2) + 1, fy = (fi * 47 + 7) % (TILE - 2) + 1;
            g.fillRect(coalTx + fx, coalTy + fy, 2, 1);
        }
        // Wood plank: lighter warm tan with horizontal grain lines
        paintPaletteTile(g, T_WOOD_PLANK,  new Color[]{c(160,112,60), c(182,134,78), c(204,154,92), c(218,170,106)}, 0.22f);
        // draw plank lines across the tile
        int px = T_WOOD_PLANK % COLS * TILE, py = T_WOOD_PLANK / COLS * TILE;
        g.setColor(c(136,90,44));
        g.drawLine(px, py + 4,  px + TILE - 1, py + 4);
        g.drawLine(px, py + 9,  px + TILE - 1, py + 9);
        g.drawLine(px, py + 13, px + TILE - 1, py + 13);

        // Craft table top: unique workbench look (cross braces + inset work area)
        int cx = T_CRAFT_TABLE_TOP % COLS * TILE, cy = T_CRAFT_TABLE_TOP / COLS * TILE;
        g.setColor(c(58,36,20));
        g.drawRect(cx + 1, cy + 1, TILE - 3, TILE - 3);
        g.setColor(c(128,92,54));
        g.fillRect(cx + 3, cy + 3, TILE - 6, TILE - 6);
        g.setColor(c(78,54,30));
        g.drawLine(cx + 3, cy + 3, cx + TILE - 4, cy + TILE - 4);
        g.drawLine(cx + TILE - 4, cy + 3, cx + 3, cy + TILE - 4);
        g.setColor(c(168,126,76));
        g.drawLine(cx + 2, cy + TILE/2, cx + TILE - 3, cy + TILE/2);
        g.drawLine(cx + TILE/2, cy + 2, cx + TILE/2, cy + TILE - 3);

        g.dispose();
        return img;
    }

    private static Color c(int r, int g, int b) { return new Color(r, g, b); }

    private static void paintPaletteTile(Graphics2D g, int tile, Color[] pal, float contrast) {
        int tx = (tile % COLS) * TILE;
        int ty = (tile / COLS) * TILE;

        g.setColor(pal[0]);
        g.fillRect(tx, ty, TILE, TILE);

        for (int py = 0; py < TILE; py++) {
            for (int px = 0; px < TILE; px++) {
                int n = hash(tile, px, py) & 255;
                int idx = (n < 64) ? 0 : (n < 128) ? 1 : (n < 200) ? 2 : 3;

                // carve some directional streaks / veins for recognisable style
                if ((tile == T_WOOD || tile == T_GRASS_SIDE) && ((px + tile) % 4 == 0)) idx = Math.max(0, idx - 1);
                if ((tile == T_STONE || tile == T_VOIDSTONE) && ((px + py + tile) % 7 == 0)) idx = Math.min(3, idx + 1);
                if (tile == T_WATER && ((px + py) % 5 == 0)) idx = Math.min(3, idx + 1);
                if (tile == T_CRYSTAL && (Math.abs(px - 8) + Math.abs(py - 8) < 4)) idx = 3;

                Color cc = pal[idx];
                g.setColor(cc);
                g.fillRect(tx + px, ty + py, 1, 1);
            }
        }

        // bevel + border to preserve block readability at distance
        Color hi = tint(pal[3], +(int)(22 * contrast));
        Color lo = tint(pal[0], -(int)(26 * contrast));
        g.setColor(hi);
        g.drawLine(tx, ty + TILE - 1, tx + TILE - 1, ty + TILE - 1);
        g.drawLine(tx, ty + TILE - 2, tx + TILE - 2, ty + TILE - 2);
        g.setColor(lo);
        g.drawLine(tx + TILE - 1, ty, tx + TILE - 1, ty + TILE - 1);
        g.drawLine(tx + TILE - 2, ty, tx + TILE - 2, ty + TILE - 2);
    }

    private static int hash(int tile, int x, int y) {
        int h = tile * 92821 + x * 68917 + y * 31337;
        h = (h ^ (h >>> 13)) * 1274126177;
        return h ^ (h >>> 16);
    }

    private static Color tint(Color c, int delta) {
        int r = Math.max(0, Math.min(255, c.getRed() + delta));
        int g = Math.max(0, Math.min(255, c.getGreen() + delta));
        int b = Math.max(0, Math.min(255, c.getBlue() + delta));
        return new Color(r, g, b);
    }

    private static int upload(BufferedImage img) {
        int w = img.getWidth(), h = img.getHeight();
        int[] pixels = new int[w * h];
        img.getRGB(0, 0, w, h, pixels, 0, w);

        java.nio.ByteBuffer buf = org.lwjgl.system.MemoryUtil.memAlloc(w * h * 3);
        for (int y = h - 1; y >= 0; y--) {
            for (int x = 0; x < w; x++) {
                int argb = pixels[y * w + x];
                buf.put((byte)((argb >> 16) & 0xFF));
                buf.put((byte)((argb >> 8) & 0xFF));
                buf.put((byte)(argb & 0xFF));
            }
        }
        buf.flip();

        int tex = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, tex);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGB, w, h, 0, GL_RGB, GL_UNSIGNED_BYTE, buf);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        // M121: keep atlas crisp; mipmaps were averaging tiles into flat colours at distance.
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        org.lwjgl.system.MemoryUtil.memFree(buf);
        glBindTexture(GL_TEXTURE_2D, 0);
        return tex;
    }
}
