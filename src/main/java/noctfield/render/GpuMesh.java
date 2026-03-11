package noctfield.render;

import java.nio.FloatBuffer;

import static org.lwjgl.opengl.GL30.*;
import static org.lwjgl.system.MemoryUtil.memAllocFloat;
import static org.lwjgl.system.MemoryUtil.memFree;

public class GpuMesh {
    private final int vao;
    private final int vbo;
    private final int verts;

    /**
     * M43: textured variant — interleaved [pos(3), nrm(3), uv(2)] = 8 floats per vertex.
     * Attributes: loc 0 = pos, loc 1 = nrm, loc 3 = uv.
     * Loc 2 (colour) is intentionally NOT bound; the shader reads the texture instead.
     */
    public GpuMesh(float[] interleavedPosNrmUV, boolean textured) {
        if (!textured) throw new IllegalArgumentException("Use single-arg ctor for flat-colour");
        this.verts = interleavedPosNrmUV.length / 8;

        vao = glGenVertexArrays();
        vbo = glGenBuffers();

        glBindVertexArray(vao);
        glBindBuffer(GL_ARRAY_BUFFER, vbo);

        FloatBuffer fb = memAllocFloat(interleavedPosNrmUV.length);
        fb.put(interleavedPosNrmUV).flip();
        glBufferData(GL_ARRAY_BUFFER, fb, GL_STATIC_DRAW);
        memFree(fb);

        int stride = 8 * Float.BYTES;
        glEnableVertexAttribArray(0); // pos
        glVertexAttribPointer(0, 3, GL_FLOAT, false, stride, 0);
        glEnableVertexAttribArray(1); // nrm
        glVertexAttribPointer(1, 3, GL_FLOAT, false, stride, 3L * Float.BYTES);
        glEnableVertexAttribArray(3); // uv  (location 3 — loc 2 = colour left unbound)
        glVertexAttribPointer(3, 2, GL_FLOAT, false, stride, 6L * Float.BYTES);

        glBindVertexArray(0);
    }

    /** Flat-colour variant — interleaved [pos(3), nrm(3), col(3)] = 9 floats per vertex. */
    public GpuMesh(float[] interleavedPosNrmColor) {
        this.verts = interleavedPosNrmColor.length / 9;

        vao = glGenVertexArrays();
        vbo = glGenBuffers();

        glBindVertexArray(vao);
        glBindBuffer(GL_ARRAY_BUFFER, vbo);

        FloatBuffer fb = memAllocFloat(interleavedPosNrmColor.length);
        fb.put(interleavedPosNrmColor).flip();
        glBufferData(GL_ARRAY_BUFFER, fb, GL_STATIC_DRAW);
        memFree(fb);

        int stride = 9 * Float.BYTES;
        glEnableVertexAttribArray(0);
        glVertexAttribPointer(0, 3, GL_FLOAT, false, stride, 0);
        glEnableVertexAttribArray(1);
        glVertexAttribPointer(1, 3, GL_FLOAT, false, stride, 3L * Float.BYTES);
        glEnableVertexAttribArray(2);
        glVertexAttribPointer(2, 3, GL_FLOAT, false, stride, 6L * Float.BYTES);

        glBindVertexArray(0);
    }

    public void render() {
        glBindVertexArray(vao);
        glDrawArrays(GL_TRIANGLES, 0, verts);
        glBindVertexArray(0);
    }

    public void destroy() {
        glDeleteBuffers(vbo);
        glDeleteVertexArrays(vao);
    }
}
