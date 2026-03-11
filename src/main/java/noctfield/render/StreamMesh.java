package noctfield.render;

import org.lwjgl.system.MemoryUtil;

import java.nio.FloatBuffer;

import static org.lwjgl.opengl.GL15.*;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL30.*;

/**
 * M151: Persistent streaming VBO for flat-colour transient geometry.
 *
 * Replaces the per-frame pattern of:
 *   new GpuMesh(verts)  →  glGenVAO + glGenVBO + memAlloc + glBufferData
 *   mesh.render()
 *   mesh.destroy()       →  glDeleteVBO + glDeleteVAO + memFree
 *
 * This class allocates the VAO/VBO ONCE and re-uploads vertex data each call
 * via glBufferData(GL_STREAM_DRAW), which uses buffer-orphaning so the driver
 * can serve the new data immediately with no CPU/GPU sync.
 *
 * Vertex layout: pos(3) + nrm(3) + col(3) = 9 floats per vertex.
 */
public final class StreamMesh {

    private final int vao;
    private final int vbo;
    private FloatBuffer buf;
    private int bufCapFloats;

    private static final int INIT_FLOATS = 256 * 1024; // 1 MB — covers any single pass

    public StreamMesh() {
        bufCapFloats = INIT_FLOATS;
        buf = MemoryUtil.memAllocFloat(bufCapFloats);

        vao = glGenVertexArrays();
        vbo = glGenBuffers();

        glBindVertexArray(vao);
        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        // Pre-allocate driver-side buffer with STREAM_DRAW hint
        glBufferData(GL_ARRAY_BUFFER, (long) bufCapFloats * Float.BYTES, GL_STREAM_DRAW);

        int stride = 9 * Float.BYTES;
        glEnableVertexAttribArray(0);
        glVertexAttribPointer(0, 3, GL_FLOAT, false, stride, 0L);
        glEnableVertexAttribArray(1);
        glVertexAttribPointer(1, 3, GL_FLOAT, false, stride, 3L * Float.BYTES);
        glEnableVertexAttribArray(2);
        glVertexAttribPointer(2, 3, GL_FLOAT, false, stride, 6L * Float.BYTES);

        glBindVertexArray(0);
        glBindBuffer(GL_ARRAY_BUFFER, 0);
    }

    /**
     * Upload flat-colour vertex data from a WatcherBuilder and draw it immediately.
     * @param data       raw float array from WatcherBuilder.a
     * @param floatCount number of floats to upload (WatcherBuilder.n)
     */
    public void draw(float[] data, int floatCount) {
        if (floatCount <= 0) return;
        int vertCount = floatCount / 9;
        if (vertCount == 0) return;

        // Grow CPU-side buffer if needed (rare — only on very large passes like dense rain)
        if (floatCount > bufCapFloats) {
            MemoryUtil.memFree(buf);
            bufCapFloats = floatCount + 65536;
            buf = MemoryUtil.memAllocFloat(bufCapFloats);
        }

        buf.clear();
        buf.put(data, 0, floatCount);
        buf.flip();

        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        // Buffer orphaning: glBufferData with a fresh size orphans the old store so the
        // driver can pipeline without stalling. Then we sub-upload the actual data.
        glBufferData(GL_ARRAY_BUFFER, (long) floatCount * Float.BYTES, GL_STREAM_DRAW);
        glBufferSubData(GL_ARRAY_BUFFER, 0L, buf);

        glBindVertexArray(vao);
        glDrawArrays(GL_TRIANGLES, 0, vertCount);
        glBindVertexArray(0);
    }

    public void destroy() {
        MemoryUtil.memFree(buf);
        buf = null;
        glDeleteBuffers(vbo);
        glDeleteVertexArrays(vao);
    }
}
