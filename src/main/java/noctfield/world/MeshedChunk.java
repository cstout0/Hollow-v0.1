package noctfield.world;

public record MeshedChunk(long seed, ChunkPos pos, VoxelChunk chunk, float[] verts) {}
