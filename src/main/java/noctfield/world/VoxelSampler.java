package noctfield.world;

@FunctionalInterface
public interface VoxelSampler {
    byte getBlock(int wx, int wy, int wz);
}
