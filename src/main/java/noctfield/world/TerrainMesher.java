package noctfield.world;

import java.util.Arrays;

public final class TerrainMesher {
    private TerrainMesher() {}

    private static final class FloatBuilder {
        float[] a = new float[4096];
        int n = 0;
        void add(float v) { if (n >= a.length) a = Arrays.copyOf(a, a.length * 2); a[n++] = v; }
        void v(float x, float y, float z, float r, float g, float b) {
            add(x); add(y); add(z); add(r); add(g); add(b);
        }
        float[] toArray() { return Arrays.copyOf(a, n); }
    }

    public static float[] buildChunkMesh(int cx, int cz) {
        FloatBuilder fb = new FloatBuilder();
        int baseX = cx * Chunk.SIZE;
        int baseZ = cz * Chunk.SIZE;

        // Column height cache with 1-cell border for neighbor lookups.
        int[][] h = new int[Chunk.SIZE + 2][Chunk.SIZE + 2];
        for (int z = -1; z <= Chunk.SIZE; z++) {
            for (int x = -1; x <= Chunk.SIZE; x++) {
                h[x + 1][z + 1] = heightAt(baseX + x, baseZ + z);
            }
        }

        // Greedy top meshing: merge adjacent cells with same height.
        boolean[][] used = new boolean[Chunk.SIZE][Chunk.SIZE];
        for (int z = 0; z < Chunk.SIZE; z++) {
            for (int x = 0; x < Chunk.SIZE; x++) {
                if (used[x][z]) continue;
                int y = h[x + 1][z + 1];

                int w = 1;
                while (x + w < Chunk.SIZE && !used[x + w][z] && h[x + w + 1][z + 1] == y) w++;

                int d = 1;
                outer:
                while (z + d < Chunk.SIZE) {
                    for (int xx = 0; xx < w; xx++) {
                        if (used[x + xx][z + d] || h[x + xx + 1][z + d + 1] != y) break outer;
                    }
                    d++;
                }

                for (int dz = 0; dz < d; dz++) {
                    for (int dx = 0; dx < w; dx++) used[x + dx][z + dz] = true;
                }

                addTopRect(fb, baseX + x, y, baseZ + z, w, d);
            }
        }

        // Side cliffs using cached heights.
        for (int z = 0; z < Chunk.SIZE; z++) {
            for (int x = 0; x < Chunk.SIZE; x++) {
                int wx = baseX + x;
                int wz = baseZ + z;
                int y = h[x + 1][z + 1];

                int yn;
                yn = h[x + 2][z + 1]; if (yn < y) addSideX(fb, wx + 1, yn, y, wz, true);
                yn = h[x][z + 1];     if (yn < y) addSideX(fb, wx, yn, y, wz, false);
                yn = h[x + 1][z + 2]; if (yn < y) addSideZ(fb, wx, yn, y, wz + 1, true);
                yn = h[x + 1][z];     if (yn < y) addSideZ(fb, wx, yn, y, wz, false);
            }
        }

        return fb.toArray();
    }

    private static void addTopRect(FloatBuilder fb, float x, float y, float z, int w, int d) {
        float shade = Math.max(0f, Math.min(1f, (y + 8f) / 28f));
        float r = 0.14f + shade * 0.08f;
        float g = 0.32f + shade * 0.16f;
        float b = 0.18f + shade * 0.08f;

        float x1 = x + w;
        float z1 = z + d;

        fb.v(x,  y + 1, z,  r, g, b);
        fb.v(x1, y + 1, z,  r, g, b);
        fb.v(x1, y + 1, z1, r, g, b);
        fb.v(x,  y + 1, z,  r, g, b);
        fb.v(x1, y + 1, z1, r, g, b);
        fb.v(x,  y + 1, z1, r, g, b);
    }

    private static void addSideX(FloatBuilder fb, float x, int y0, int y1, float z, boolean plusX) {
        float r = 0.22f, g = 0.18f, b = 0.15f;
        for (int y = y0 + 1; y <= y1; y++) {
            if (plusX) {
                fb.v(x, y, z, r, g, b); fb.v(x, y + 1, z, r, g, b); fb.v(x, y + 1, z + 1, r, g, b);
                fb.v(x, y, z, r, g, b); fb.v(x, y + 1, z + 1, r, g, b); fb.v(x, y, z + 1, r, g, b);
            } else {
                fb.v(x, y, z + 1, r, g, b); fb.v(x, y + 1, z + 1, r, g, b); fb.v(x, y + 1, z, r, g, b);
                fb.v(x, y, z + 1, r, g, b); fb.v(x, y + 1, z, r, g, b); fb.v(x, y, z, r, g, b);
            }
        }
    }

    private static void addSideZ(FloatBuilder fb, float x, int y0, int y1, float z, boolean plusZ) {
        float r = 0.22f, g = 0.18f, b = 0.15f;
        for (int y = y0 + 1; y <= y1; y++) {
            if (plusZ) {
                fb.v(x, y, z, r, g, b); fb.v(x + 1, y, z, r, g, b); fb.v(x + 1, y + 1, z, r, g, b);
                fb.v(x, y, z, r, g, b); fb.v(x + 1, y + 1, z, r, g, b); fb.v(x, y + 1, z, r, g, b);
            } else {
                fb.v(x + 1, y, z, r, g, b); fb.v(x, y, z, r, g, b); fb.v(x, y + 1, z, r, g, b);
                fb.v(x + 1, y, z, r, g, b); fb.v(x, y + 1, z, r, g, b); fb.v(x + 1, y + 1, z, r, g, b);
            }
        }
    }

    public static int heightAt(int x, int z) {
        float n = valueNoise(x * 0.022f, z * 0.022f) - 0.5f;
        float n2 = valueNoise(x * 0.007f + 200f, z * 0.007f - 120f) - 0.5f;
        return 8 + Math.round(n * 8f + n2 * 10f);
    }

    private static float valueNoise(float x, float z) {
        int x0 = fastFloor(x), z0 = fastFloor(z);
        int x1 = x0 + 1, z1 = z0 + 1;
        float tx = x - x0, tz = z - z0;
        float u = tx * tx * (3f - 2f * tx);
        float v = tz * tz * (3f - 2f * tz);

        float h00 = hash01(x0, z0);
        float h10 = hash01(x1, z0);
        float h01 = hash01(x0, z1);
        float h11 = hash01(x1, z1);

        float hx0 = h00 + (h10 - h00) * u;
        float hx1 = h01 + (h11 - h01) * u;
        return hx0 + (hx1 - hx0) * v;
    }

    private static int fastFloor(float v) {
        int i = (int) v;
        return (v < i) ? (i - 1) : i;
    }

    private static float hash01(int x, int z) {
        int h = x * 374761393 ^ z * 668265263;
        h = (h ^ (h >>> 13)) * 1274126177;
        h ^= (h >>> 16);
        return (h & 0x00ffffff) / (float) 0x01000000;
    }
}
