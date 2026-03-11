package noctfield.world;

import noctfield.core.InputState;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import static org.lwjgl.glfw.GLFW.*;

public class FreeCamera {
    private static final float HALF_W = 0.30f;
    private static final float HEIGHT = 1.80f;
    private static final float EYE = 1.62f;

    private final Vector3f pos = new Vector3f(0f, 24f, 8f); // eye position
    private float yaw = (float) Math.toRadians(-90);
    private float pitch = (float) Math.toRadians(-20);

    private float velY = 0f;
    private boolean onGround = false;
    private boolean noclip = false;
    private boolean spawnResolved = false;

    private final Matrix4f view = new Matrix4f();
    private final Matrix4f proj = new Matrix4f();
    private float fovOffsetDeg = 0f;
    private float baseFov       = 70f; // M213: user-adjustable base FOV
    private float jitterX = 0f;
    private float jitterY = 0f;
    private float moveSpeedMultiplier = 1f;
    private boolean sprintEnabled = true; // M38: disabled when stamina depleted

    public void update(InputState input, VoxelSampler sampler, float dt) {
        resolveSpawnIfNeeded(sampler);
        nudgeOutOfTerrain(sampler);

        var md = input.mouseDelta();
        float sens = 0.0022f;
        yaw += md.x * sens;
        pitch -= md.y * sens;
        float maxPitch = (float) Math.toRadians(88);
        if (pitch > maxPitch) pitch = maxPitch;
        if (pitch < -maxPitch) pitch = -maxPitch;

        if (input.wasPressed(GLFW_KEY_F5)) noclip = !noclip;

        Vector3f fwd = forward();
        Vector3f flatFwd = new Vector3f(fwd.x, 0f, fwd.z);
        if (flatFwd.lengthSquared() > 0f) flatFwd.normalize();
        Vector3f right = new Vector3f(flatFwd).cross(0, 1, 0).normalize();

        float speed = (sprintEnabled && input.isDown(GLFW_KEY_LEFT_SHIFT) ? 8f : 5f) * moveSpeedMultiplier;
        Vector3f move = new Vector3f();
        if (input.isDown(GLFW_KEY_W)) move.add(flatFwd);
        if (input.isDown(GLFW_KEY_S)) move.sub(flatFwd);
        if (input.isDown(GLFW_KEY_D)) move.add(right);
        if (input.isDown(GLFW_KEY_A)) move.sub(right);

        if (noclip) {
            if (input.isDown(GLFW_KEY_SPACE)) move.y += 1f;
            if (input.isDown(GLFW_KEY_LEFT_CONTROL)) move.y -= 1f;
            if (move.lengthSquared() > 0f) pos.add(move.normalize().mul(speed * dt));
            velY = 0f;
            onGround = false;
            return;
        }

        // grounded controller
        if (move.lengthSquared() > 0f) {
            move.normalize().mul(speed * dt);
            moveHorizontal(sampler, move.x, move.z);
        }

        if (onGround && input.wasPressed(GLFW_KEY_SPACE)) {
            velY = 7.0f;
            onGround = false;
        }

        velY += -18.0f * dt;
        if (velY < -30f) velY = -30f;

        moveVertical(sampler, velY * dt);
    }

    private void moveHorizontal(VoxelSampler s, float dx, float dz) {
        final float STEP_UP = 0.45f; // allow small lip smoothing, not full-block auto climb

        if (dx != 0f) {
            if (!collidesAt(s, pos.x + dx, pos.y, pos.z)) {
                pos.x += dx;
            } else if (onGround && !collidesAt(s, pos.x + dx, pos.y + STEP_UP, pos.z)) {
                pos.y += STEP_UP;
                pos.x += dx;
                onGround = false;
            }
        }
        if (dz != 0f) {
            if (!collidesAt(s, pos.x, pos.y, pos.z + dz)) {
                pos.z += dz;
            } else if (onGround && !collidesAt(s, pos.x, pos.y + STEP_UP, pos.z + dz)) {
                pos.y += STEP_UP;
                pos.z += dz;
                onGround = false;
            }
        }
    }

    private void moveVertical(VoxelSampler s, float dy) {
        if (dy == 0f) return;
        if (!collidesAt(s, pos.x, pos.y + dy, pos.z)) {
            pos.y += dy;
            onGround = false;
        } else {
            if (dy < 0f) onGround = true;
            velY = 0f;
        }
    }

    private boolean collidesAt(VoxelSampler s, float eyeX, float eyeY, float eyeZ) {
        float minX = eyeX - HALF_W;
        float maxX = eyeX + HALF_W;
        float minY = eyeY - EYE;
        float maxY = minY + HEIGHT;
        float minZ = eyeZ - HALF_W;
        float maxZ = eyeZ + HALF_W;

        int x0 = (int)Math.floor(minX);
        int x1 = (int)Math.floor(maxX);
        int y0 = (int)Math.floor(minY);
        int y1 = (int)Math.floor(maxY);
        int z0 = (int)Math.floor(minZ);
        int z1 = (int)Math.floor(maxZ);

        for (int y = y0; y <= y1; y++) {
            for (int z = z0; z <= z1; z++) {
                for (int x = x0; x <= x1; x++) {
                    byte b = s.getBlock(x, y, z);
                    if (BlockId.isMovementBlocker(b)) return true;
                }
            }
        }
        return false;
    }

    private void resolveSpawnIfNeeded(VoxelSampler s) {
        if (spawnResolved) return;
        spawnResolved = true;

        int wx = (int)Math.floor(pos.x);
        int wz = (int)Math.floor(pos.z);

        // Find highest solid in column and place player safely above it.
        int top = -1;
        for (int y = VoxelChunk.SIZE_Y - 1; y >= 0; y--) {
            if (BlockId.isMovementBlocker(s.getBlock(wx, y, wz))) {
                top = y;
                break;
            }
        }
        if (top >= 0) pos.y = top + EYE + 0.08f;
    }

    private void nudgeOutOfTerrain(VoxelSampler s) {
        if (!collidesAt(s, pos.x, pos.y, pos.z)) return;
        // push upward until free (small bounded recovery)
        for (int i = 0; i < 20; i++) {
            pos.y += 0.25f;
            if (!collidesAt(s, pos.x, pos.y, pos.z)) {
                velY = 0f;
                break;
            }
        }
    }

    public boolean isInWater() { return false; } // M175: water removed
    public void setFovOffsetDeg(float v) { this.fovOffsetDeg = v; }
    public float getBaseFov()            { return baseFov; }
    public void  setBaseFov(float v)     { this.baseFov = Math.max(60f, Math.min(110f, v)); }
    public void setMoveSpeedMultiplier(float v) { this.moveSpeedMultiplier = Math.max(0.65f, Math.min(1.25f, v)); }
    public void setSprintEnabled(boolean v) { this.sprintEnabled = v; } // M38
    public boolean isSprintEnabled() { return sprintEnabled; }

    /** Teleport to an explicit world position and skip the spawn resolver. */
    public void setPosition(float x, float y, float z) {
        pos.set(x, y, z);
        spawnResolved = true; // don't let resolveSpawnIfNeeded overwrite this
        velY = 0f;
    }

    /** Set camera look direction (radians). */
    public void setLook(float yawRad, float pitchRad) {
        this.yaw   = yawRad;
        this.pitch  = pitchRad;
    }

    public float getYaw()   { return yaw; }
    public float getPitch() { return pitch; }

    public Matrix4f projection(int width, int height) {
        float aspect = Math.max(1f, width) / Math.max(1f, height);
        float fov = baseFov + fovOffsetDeg;
        if (fov < 55f) fov = 55f;
        if (fov > 120f) fov = 120f;
        return proj.identity().perspective((float) Math.toRadians(fov), aspect, 0.05f, 1000f);
    }

    public void setJitter(float x, float y) { this.jitterX = x; this.jitterY = y; }

    public Matrix4f view() {
        Vector3f f = forward();
        Vector3f eye = new Vector3f(pos).add(jitterX, jitterY, 0f);
        return view.identity().lookAt(eye, new Vector3f(eye).add(f), new Vector3f(0, 1, 0));
    }

    public Vector3f position() { return new Vector3f(pos); }
    public boolean onGround() { return onGround; }
    public boolean noclip() { return noclip; }
    public void setNoclip(boolean v) { noclip = v; }

    public boolean intersectsBlock(int bx, int by, int bz) {
        float minX = pos.x - HALF_W;
        float maxX = pos.x + HALF_W;
        float minY = pos.y - EYE;
        float maxY = minY + HEIGHT;
        float minZ = pos.z - HALF_W;
        float maxZ = pos.z + HALF_W;

        float bMinX = bx;
        float bMaxX = bx + 1f;
        float bMinY = by;
        float bMaxY = by + 1f;
        float bMinZ = bz;
        float bMaxZ = bz + 1f;

        return minX < bMaxX && maxX > bMinX
                && minY < bMaxY && maxY > bMinY
                && minZ < bMaxZ && maxZ > bMinZ;
    }

    public Vector3f forward() {
        float cp = (float) Math.cos(pitch);
        return new Vector3f(
                (float) (Math.cos(yaw) * cp),
                (float) Math.sin(pitch),
                (float) (Math.sin(yaw) * cp)
        ).normalize();
    }
}
