package noctfield.world;

import noctfield.render.TerrainAtlas;

import java.util.Arrays;

public final class VoxelMesher {
    private VoxelMesher() {}

    public static final int MAX_FLOATS_PER_CHUNK = 2_400_000; // ~266k vertices at 9 floats/vertex
    private static volatile int lastFloatCount = 0;
    private static volatile int fallbackCount = 0;

    private static final int[][] DIR = {{1,0,0},{-1,0,0},{0,1,0},{0,-1,0},{0,0,1},{0,0,-1}};
    private static final float[][] NRM = {{1,0,0},{-1,0,0},{0,1,0},{0,-1,0},{0,0,1},{0,0,-1}};

    private static final float[][] POS = {
            {1,0,0, 1,1,0, 1,1,1, 1,0,0, 1,1,1, 1,0,1},
            {0,0,1, 0,1,1, 0,1,0, 0,0,1, 0,1,0, 0,0,0},
            {0,1,1, 1,1,1, 1,1,0, 0,1,1, 1,1,0, 0,1,0},
            {0,0,0, 1,0,0, 1,0,1, 0,0,0, 1,0,1, 0,0,1},
            {0,0,1, 1,0,1, 1,1,1, 0,0,1, 1,1,1, 0,1,1},
            {1,0,0, 0,0,0, 0,1,0, 1,0,0, 0,1,0, 1,1,0}
    };

    private static final class FloatBuilder {
        float[] a = new float[8192];
        int n = 0;
        void add(float v){ if(n>=a.length) a = Arrays.copyOf(a, a.length*2); a[n++]=v; }
        void v(float x,float y,float z,float nx,float ny,float nz,float r,float g,float b){
            add(x);add(y);add(z); add(nx);add(ny);add(nz); add(r);add(g);add(b);
        }
        float[] toArray(){ return Arrays.copyOf(a,n);}    }

    public static int lastFloatCount() { return lastFloatCount; }
    public static int fallbackCount() { return fallbackCount; }

    public static float[] mesh(VoxelChunk c) {
        FloatBuilder fb = new FloatBuilder();
        int baseX = c.pos.x() * Chunk.SIZE;
        int baseZ = c.pos.z() * Chunk.SIZE;

        for (int y = 0; y < VoxelChunk.SIZE_Y; y++) {
            for (int z = 0; z < Chunk.SIZE; z++) {
                for (int x = 0; x < Chunk.SIZE; x++) {
                    byte id = c.get(x,y,z);
                    if (!BlockId.isSolid(id)) continue;

                    float[] col = colorFor(id, y, baseX + x, baseZ + z);
                    for (int f = 0; f < 6; f++) {
                        int nx = x + DIR[f][0], ny = y + DIR[f][1], nz = z + DIR[f][2];
                        byte nid = c.get(nx, ny, nz);
                        if (BlockId.isSolid(nid)) continue;

                        float[] p = POS[f];
                        float[] n = NRM[f];
                        if (fb.n + 54 > MAX_FLOATS_PER_CHUNK) {
                            fallbackCount++;
                            lastFloatCount = fb.n;
                            return fb.toArray();
                        }
                        for (int i=0;i<6;i++) {
                            int pi = i*3;
                            fb.v(baseX + x + p[pi], y + p[pi+1], baseZ + z + p[pi+2], n[0], n[1], n[2], col[0], col[1], col[2]);
                        }
                    }
                }
            }
        }
        lastFloatCount = fb.n;
        return fb.toArray();
    }

    /** M111: textured terrain mesh format [pos(3), nrm(3), uv(2)] */
    public static float[] meshTextured(VoxelChunk c) {
        FloatBuilder fb = new FloatBuilder();
        int baseX = c.pos.x() * Chunk.SIZE;
        int baseZ = c.pos.z() * Chunk.SIZE;

        // uv order matches each face triangle layout in POS[]
        final float[] UV = {
                0f,0f, 1f,0f, 1f,1f,
                0f,0f, 1f,1f, 0f,1f
        };

        for (int y = 0; y < VoxelChunk.SIZE_Y; y++) {
            for (int z = 0; z < Chunk.SIZE; z++) {
                for (int x = 0; x < Chunk.SIZE; x++) {
                    byte id = c.get(x, y, z);
                    if (!BlockId.isSolid(id)) continue;

                    for (int f = 0; f < 6; f++) {
                        int nx = x + DIR[f][0], ny = y + DIR[f][1], nz = z + DIR[f][2];
                        byte nid = c.get(nx, ny, nz);
                        if (BlockId.isSolid(nid)) continue;

                        int tile = TerrainAtlas.tileFor(id, f);
                        float[] r = TerrainAtlas.uvRect(tile);
                        float u0 = r[0], v0 = r[1], u1 = r[2], v1 = r[3];

                        float[] p = POS[f];
                        float[] n = NRM[f];

                        if (fb.n + 48 > MAX_FLOATS_PER_CHUNK) {
                            fallbackCount++;
                            lastFloatCount = fb.n;
                            return fb.toArray();
                        }
                        for (int i = 0; i < 6; i++) {
                            int pi = i * 3;
                            int ui = i * 2;
                            float uu = (UV[ui] == 0f) ? u0 : u1;
                            float vv = (UV[ui + 1] == 0f) ? v0 : v1;
                            fb.add(baseX + x + p[pi]);
                            fb.add(y + p[pi + 1]);
                            fb.add(baseZ + z + p[pi + 2]);
                            fb.add(n[0]); fb.add(n[1]); fb.add(n[2]);
                            fb.add(uu); fb.add(vv);
                        }
                    }
                }
            }
        }
        lastFloatCount = fb.n;
        return fb.toArray();
    }

    private static float[] colorFor(byte id, int y, int wx, int wz) {
        if (id == BlockId.GRASS) {
            float t = Math.max(0f, Math.min(1f, (y - 6f) / 30f));
            float n = hash01(wx, wz);
            float r = 0.14f + t * 0.07f + n * 0.03f;
            float g = 0.30f + t * 0.17f + n * 0.12f;
            float b = 0.16f + t * 0.06f + n * 0.03f;
            return new float[]{r, g, b};
        }
        if (id == BlockId.DIRT) return new float[]{0.34f, 0.25f, 0.19f};
        if (id == BlockId.MUD) return new float[]{0.22f, 0.20f, 0.17f};
        if (id == BlockId.WOOD) return new float[]{0.45f, 0.31f, 0.20f};
        if (id == BlockId.LEAVES) {
            float n = hash01(wx, wz);
            return new float[]{0.16f + n * 0.05f, 0.40f + n * 0.10f, 0.18f + n * 0.04f};
        }
        if (id == BlockId.LANTERN)  return new float[]{1.00f, 0.82f, 0.42f};
        if (id == BlockId.RELIC)    return new float[]{0.72f, 0.52f, 0.18f};
        if (id == BlockId.JOURNAL)  return new float[]{0.30f, 0.34f, 0.30f}; // mossy dark stone
        if (id == BlockId.CAMPFIRE)   return new float[]{0.96f, 0.42f, 0.06f}; // M45: bright ember orange
        if (id == BlockId.BONES)      return new float[]{0.82f, 0.78f, 0.62f}; // M49: pale off-white/tan
        if (id == BlockId.BLOODSTAIN) return new float[]{0.30f, 0.04f, 0.04f}; // M49: dark crimson
        if (id == BlockId.COBWEB)     return new float[]{0.72f, 0.72f, 0.76f}; // M49: pale blue-grey
        if (id == BlockId.FUNGUS)     return new float[]{0.18f, 0.72f, 0.54f}; // M84: bioluminescent blue-green
        if (id == BlockId.VOIDSTONE)  return new float[]{0.10f, 0.06f, 0.14f}; // M88: near-black with deep purple tint
        if (id == BlockId.WATER)      return new float[]{0.08f, 0.28f, 0.62f}; // M91: deep cave pool blue
        if (id == BlockId.CRYSTAL)    return new float[]{0.70f, 0.92f, 1.00f}; // M94: bright icy cyan crystal
        return new float[]{0.42f, 0.42f, 0.45f};
    }

    private static float hash01(int x, int z) {
        int h = x * 374761393 ^ z * 668265263;
        h = (h ^ (h >>> 13)) * 1274126177;
        h ^= (h >>> 16);
        return (h & 0x00ffffff) / (float)0x01000000;
    }
}
