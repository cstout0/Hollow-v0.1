package noctfield.render;

import org.lwjgl.assimp.AIAnimation;
import org.lwjgl.assimp.AIFace;
import org.lwjgl.assimp.AIMesh;
import org.lwjgl.assimp.AIScene;
import org.lwjgl.assimp.AIVector3D;

import java.util.ArrayList;
import java.util.List;

import static org.lwjgl.assimp.Assimp.*;

/**
 * Loads an FBX (or OBJ/GLTF — anything Assimp supports) into the
 * interleaved [x,y,z, nx,ny,nz, u,v] = 8-float-per-vertex format
 * expected by {@link GpuMesh#GpuMesh(float[], boolean)}.
 *
 * All sub-meshes are merged into one flat triangle list.
 * Y is shifted so the lowest vertex sits at y = 0 (feet on ground).
 */
public final class FbxLoader {

    private FbxLoader() {}

    /**
     * @param path absolute path to the model file (FBX, OBJ, etc.)
     * @return interleaved float[] or empty array on failure
     */
    public static float[] load(String path) {
        int flags = aiProcess_Triangulate
                  | aiProcess_GenSmoothNormals
                  | aiProcess_FlipUVs          // flip V so 0=top matches OpenGL convention
                  | aiProcess_JoinIdenticalVertices
                  | aiProcess_CalcTangentSpace;

        AIScene scene = aiImportFile(path, flags);
        if (scene == null) {
            System.err.println("[FbxLoader] Assimp error loading '" + path + "': " + aiGetErrorString());
            return new float[0];
        }

        int numMeshes = scene.mNumMeshes();
        if (numMeshes == 0) {
            System.err.println("[FbxLoader] No meshes in '" + path + "'");
            aiFreeScene(scene);
            return new float[0];
        }

        // Collect expanded (non-indexed) triangles from every sub-mesh
        List<float[]> verts = new ArrayList<>(65536);

        for (int mi = 0; mi < numMeshes; mi++) {
            AIMesh mesh = AIMesh.create(scene.mMeshes().get(mi));

            AIVector3D.Buffer positions = mesh.mVertices();
            AIVector3D.Buffer normals   = mesh.mNormals();      // non-null after GenSmoothNormals
            AIVector3D.Buffer uvs       = mesh.mTextureCoords(0); // first UV channel; may be null

            int faceCount = mesh.mNumFaces();
            AIFace.Buffer  faces     = mesh.mFaces();

            for (int fi = 0; fi < faceCount; fi++) {
                AIFace face = faces.get(fi);
                if (face.mNumIndices() != 3) continue; // skip non-triangles (shouldn't happen post-Triangulate)

                for (int vi = 0; vi < 3; vi++) {
                    int idx = face.mIndices().get(vi);

                    AIVector3D p = positions.get(idx);
                    float nx = 0f, ny = 1f, nz = 0f;
                    if (normals != null && idx < normals.limit()) {
                        AIVector3D n = normals.get(idx);
                        nx = n.x(); ny = n.y(); nz = n.z();
                    }
                    float u = 0f, v = 0f;
                    if (uvs != null && idx < uvs.limit()) {
                        AIVector3D uv = uvs.get(idx);
                        u = uv.x(); v = uv.y();
                    }

                    verts.add(new float[]{ p.x(), p.y(), p.z(), nx, ny, nz, u, v });
                }
            }
        }

        aiFreeScene(scene);

        if (verts.isEmpty()) {
            System.err.println("[FbxLoader] Zero vertices extracted from '" + path + "'");
            return new float[0];
        }

        // Y-shift so feet (min Y) sit at y = 0
        float minY = Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
        for (float[] v : verts) {
            if (v[1] < minY) minY = v[1];
            if (v[1] > maxY) maxY = v[1];
        }

        float[] out = new float[verts.size() * 8];
        int idx = 0;
        for (float[] v : verts) {
            out[idx++] = v[0];
            out[idx++] = v[1] - minY;   // shift feet to y=0
            out[idx++] = v[2];
            out[idx++] = v[3];
            out[idx++] = v[4];
            out[idx++] = v[5];
            out[idx++] = v[6];
            out[idx++] = v[7];
        }

        System.out.printf("[FbxLoader] '%s' — %d sub-meshes, %d tris, Y range [%.3f … %.3f] (shifted %.3f)%n",
                path, numMeshes, verts.size() / 3, minY, maxY, -minY);
        return out;
    }

    /**
     * Prints diagnostic info about bones and animations in the file.
     * Call once at startup so you know what the model contains.
     */
    public static void diagnose(String path) {
        AIScene scene = aiImportFile(path, aiProcess_Triangulate);
        if (scene == null) { System.err.println("[FbxLoader.diagnose] Failed: " + aiGetErrorString()); return; }

        System.out.println("=== FBX DIAGNOSTIC: " + path + " ===");
        System.out.println("  Meshes    : " + scene.mNumMeshes());
        System.out.println("  Animations: " + scene.mNumAnimations());

        // Per-mesh bone counts
        for (int mi = 0; mi < scene.mNumMeshes(); mi++) {
            AIMesh m = AIMesh.create(scene.mMeshes().get(mi));
            System.out.printf("  Mesh[%d] verts=%d  tris=%d  bones=%d  uvChannels=%d%n",
                    mi, m.mNumVertices(), m.mNumFaces(), m.mNumBones(), m.mNumUVComponents().get(0));
        }

        // Animation clip details
        if (scene.mNumAnimations() > 0) {
            for (int ai = 0; ai < scene.mNumAnimations(); ai++) {
                AIAnimation anim = AIAnimation.create(scene.mAnimations().get(ai));
                System.out.printf("  Anim[%d] '%s'  duration=%.1f ticks  ticksPerSec=%.1f  channels=%d%n",
                        ai, anim.mName().dataString(), anim.mDuration(), anim.mTicksPerSecond(), anim.mNumChannels());
            }
        } else {
            System.out.println("  >>> NO animations found — model is a static T-pose mesh.");
        }
        System.out.println("==========================================");
        aiFreeScene(scene);
    }
}
