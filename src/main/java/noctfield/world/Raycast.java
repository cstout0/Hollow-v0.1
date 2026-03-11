package noctfield.world;

import org.joml.Vector3f;

public final class Raycast {
    private Raycast() {}

    public record Hit(int x, int y, int z, int placeX, int placeY, int placeZ) {}

    public static Hit cast(VoxelSampler sampler, Vector3f origin, Vector3f dir, float maxDist) {
        Vector3f d = new Vector3f(dir).normalize();
        float step = 0.05f;
        float t = 0f;

        int lastX = Integer.MIN_VALUE, lastY = Integer.MIN_VALUE, lastZ = Integer.MIN_VALUE;
        int prevX = Integer.MIN_VALUE, prevY = Integer.MIN_VALUE, prevZ = Integer.MIN_VALUE;

        while (t <= maxDist) {
            float px = origin.x + d.x * t;
            float py = origin.y + d.y * t;
            float pz = origin.z + d.z * t;

            int bx = (int)Math.floor(px);
            int by = (int)Math.floor(py);
            int bz = (int)Math.floor(pz);

            if (bx != lastX || by != lastY || bz != lastZ) {
                prevX = lastX; prevY = lastY; prevZ = lastZ;
                lastX = bx; lastY = by; lastZ = bz;

                if (BlockId.isTargetable(sampler.getBlock(bx, by, bz))) {
                    int pxAir = (prevX == Integer.MIN_VALUE) ? bx : prevX;
                    int pyAir = (prevY == Integer.MIN_VALUE) ? by : prevY;
                    int pzAir = (prevZ == Integer.MIN_VALUE) ? bz : prevZ;
                    return new Hit(bx, by, bz, pxAir, pyAir, pzAir);
                }
            }

            t += step;
        }
        return null;
    }
}
