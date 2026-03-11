package noctfield.world;

public class VoxelChunk {
    public static final int SIZE_X = 16;
    public static final int SIZE_Y = 128; // M90: doubled height — gives ~55 blocks below surface to VOIDSTONE
    public static final int SIZE_Z = 16;

    public final ChunkPos pos;
    private final byte[] blocks = new byte[SIZE_X * SIZE_Y * SIZE_Z];

    public VoxelChunk(ChunkPos pos) {
        this.pos = pos;
    }

    private static int idx(int x, int y, int z) {
        return (y * SIZE_Z + z) * SIZE_X + x;
    }

    public byte get(int x, int y, int z) {
        if (x < 0 || x >= SIZE_X || y < 0 || y >= SIZE_Y || z < 0 || z >= SIZE_Z) return BlockId.AIR;
        return blocks[idx(x, y, z)];
    }

    public void set(int x, int y, int z, byte id) {
        if (x < 0 || x >= SIZE_X || y < 0 || y >= SIZE_Y || z < 0 || z >= SIZE_Z) return;
        blocks[idx(x, y, z)] = id;
    }
}
