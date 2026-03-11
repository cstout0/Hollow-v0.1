package noctfield.render;

import org.lwjgl.stb.STBImage;
import org.lwjgl.system.MemoryStack;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL12.GL_CLAMP_TO_EDGE;
import static org.lwjgl.opengl.GL30.glGenerateMipmap;

/**
 * Loads a JPEG/PNG/etc. image from disk (via STBImage) and uploads it as an
 * OpenGL 2D texture.  Returns the GL texture ID, or 0 on failure.
 */
public final class TextureLoader {

    private TextureLoader() {}

    /**
     * @param path absolute path to the image file
     * @return OpenGL texture ID (GL_TEXTURE_2D), or 0 on failure
     */
    public static int load(String path) {
        ByteBuffer imgData;
        int width, height;

        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer w   = stack.mallocInt(1);
            IntBuffer h   = stack.mallocInt(1);
            IntBuffer ch  = stack.mallocInt(1);

            // Force RGBA to keep things simple
            STBImage.stbi_set_flip_vertically_on_load(false); // FBX UVs already flipped by aiProcess_FlipUVs
            imgData = STBImage.stbi_load(path, w, h, ch, 4);
            if (imgData == null) {
                System.err.println("[TextureLoader] STBImage failed on '" + path + "': "
                        + STBImage.stbi_failure_reason());
                return 0;
            }
            width  = w.get(0);
            height = h.get(0);
        }

        int texId = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, texId);

        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR_MIPMAP_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);

        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, width, height, 0, GL_RGBA, GL_UNSIGNED_BYTE, imgData);
        glGenerateMipmap(GL_TEXTURE_2D);

        STBImage.stbi_image_free(imgData);
        glBindTexture(GL_TEXTURE_2D, 0);

        System.out.printf("[TextureLoader] Loaded '%s' — %dx%d, texId=%d%n", path, width, height, texId);
        return texId;
    }
}
