package noctfield.render;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * Minimal OBJ loader for silhouette rendering.
 * Parses triangulated OBJ (v / vn / f with v/vt/vn face tokens).
 * UVs are ignored — caller supplies a flat base colour baked into each vertex.
 * Output format: [x,y,z, nx,ny,nz, r,g,b] per vertex, ready for GpuMesh.
 */
public final class ObjMesh {

    private ObjMesh() {}

    /**
     * @param path  path to the .obj file
     * @param colR  base red   (silhouette colour, usually very dark)
     * @param colG  base green
     * @param colB  base blue
     * @return interleaved float[] or empty array on failure
     */
    public static float[] load(String path, float colR, float colG, float colB) {
        // Use java.nio — avoids java.io.File.exists() failures on OneDrive/symlink Desktop paths
        Path filePath = Paths.get(path).toAbsolutePath().normalize();
        System.out.println("[ObjMesh] CWD=" + System.getProperty("user.dir"));
        System.out.println("[ObjMesh] Loading: " + filePath + "  exists=" + Files.exists(filePath));
        if (!Files.exists(filePath)) {
            System.out.println("[ObjMesh] FILE NOT FOUND — check path above");
            return new float[0];
        }

        List<float[]> positions = new ArrayList<>(32768);
        List<float[]> normals   = new ArrayList<>(32768);
        // each entry: [pi0, ni0, pi1, ni1, pi2, ni2] (0-based indices)
        List<int[]>   faces     = new ArrayList<>(40000);

        try (BufferedReader br = Files.newBufferedReader(filePath)) {
            String line;
            while ((line = br.readLine()) != null) {
                if (line.length() < 2) continue;
                char c0 = line.charAt(0);

                if (c0 == 'v' && line.charAt(1) == ' ') {
                    // vertex position
                    String[] t = line.split(" +");
                    positions.add(new float[]{
                            Float.parseFloat(t[1]),
                            Float.parseFloat(t[2]),
                            Float.parseFloat(t[3])
                    });

                } else if (c0 == 'v' && line.charAt(1) == 'n') {
                    // vertex normal
                    String[] t = line.split(" +");
                    normals.add(new float[]{
                            Float.parseFloat(t[1]),
                            Float.parseFloat(t[2]),
                            Float.parseFloat(t[3])
                    });

                } else if (c0 == 'f' && line.charAt(1) == ' ') {
                    // face — expect exactly 3 tokens (triangulated OBJ)
                    String[] t = line.split(" +");
                    if (t.length < 4) continue;
                    int[] v0 = parseFaceVert(t[1]);
                    int[] v1 = parseFaceVert(t[2]);
                    int[] v2 = parseFaceVert(t[3]);
                    faces.add(new int[]{ v0[0], v0[1], v1[0], v1[1], v2[0], v2[1] });
                }
            }
        } catch (Exception e) {
            System.out.println("[ObjMesh] PARSE ERROR in " + path + ": " + e);
            return new float[0];
        }

        // Compute actual bounding box so we can shift the mesh: lowest vertex → y=0
        float minY = Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
        for (float[] p : positions) {
            if (p[1] < minY) minY = p[1];
            if (p[1] > maxY) maxY = p[1];
        }
        float yShift     = -minY;           // shift so feet land at y=0
        float modelHeight= maxY - minY;
        System.out.printf("[ObjMesh] Bounds: Y=[%.3f, %.3f]  height=%.3f  yShift=%.3f%n",
                minY, maxY, modelHeight, yShift);

        float[] out = new float[faces.size() * 3 * 9];
        int idx = 0;
        float[] fallbackNorm = {0f, 1f, 0f};

        for (int[] face : faces) {
            for (int i = 0; i < 3; i++) {
                int pi = face[i * 2];
                int ni = face[i * 2 + 1];

                float[] p = positions.get(pi);
                float[] n = (ni >= 0 && ni < normals.size()) ? normals.get(ni) : fallbackNorm;

                out[idx++] = p[0];
                out[idx++] = p[1] + yShift; // feet at y=0
                out[idx++] = p[2];
                out[idx++] = n[0];
                out[idx++] = n[1];
                out[idx++] = n[2];
                out[idx++] = colR;
                out[idx++] = colG;
                out[idx++] = colB;
            }
        }

        System.out.printf("[ObjMesh] Loaded %s — %d tris, %d verts, %d normals%n",
                path, faces.size(), positions.size(), normals.size());
        return out;
    }

    /** Parse a face-vertex token of the form "vi", "vi/ti", or "vi/ti/ni" (1-based). */
    private static int[] parseFaceVert(String token) {
        String[] parts = token.split("/", -1);
        int pi = Integer.parseInt(parts[0]) - 1;
        int ni = -1;
        if (parts.length >= 3 && !parts[2].isEmpty()) {
            ni = Integer.parseInt(parts[2]) - 1;
        }
        return new int[]{ pi, ni };
    }

    // ------------------------------------------------------------------
    // M43: textured load — output [x,y,z, nx,ny,nz, u,v] per vertex (8 floats)
    // ------------------------------------------------------------------

    /**
     * Loads a triangulated OBJ and captures UV texture coordinates.
     * Output format: [x,y,z, nx,ny,nz, u,v] per vertex = 8 floats.
     * Falls back to UV (0,0) for any vertex missing texture data.
     * Vertex Y is shifted so the lowest vertex lands at y=0 (same as load()).
     */
    public static float[] loadTextured(String path) {
        Path filePath = Paths.get(path).toAbsolutePath().normalize();
        System.out.println("[ObjMesh] Loading textured: " + filePath + "  exists=" + Files.exists(filePath));
        if (!Files.exists(filePath)) {
            System.out.println("[ObjMesh] FILE NOT FOUND (textured) — check path above");
            return new float[0];
        }

        List<float[]> positions = new ArrayList<>(32768);
        List<float[]> normals   = new ArrayList<>(32768);
        List<float[]> uvs       = new ArrayList<>(32768);
        // each face entry: [pi0,ni0,ti0, pi1,ni1,ti1, pi2,ni2,ti2]
        List<int[]>   faces     = new ArrayList<>(40000);

        try (BufferedReader br = Files.newBufferedReader(filePath)) {
            String line;
            while ((line = br.readLine()) != null) {
                if (line.length() < 2) continue;
                char c0 = line.charAt(0);
                char c1 = line.charAt(1);

                if (c0 == 'v' && c1 == ' ') {
                    String[] t = line.split(" +");
                    positions.add(new float[]{ Float.parseFloat(t[1]), Float.parseFloat(t[2]), Float.parseFloat(t[3]) });

                } else if (c0 == 'v' && c1 == 't') {
                    String[] t = line.split(" +");
                    uvs.add(new float[]{ Float.parseFloat(t[1]), Float.parseFloat(t[2]) });

                } else if (c0 == 'v' && c1 == 'n') {
                    String[] t = line.split(" +");
                    normals.add(new float[]{ Float.parseFloat(t[1]), Float.parseFloat(t[2]), Float.parseFloat(t[3]) });

                } else if (c0 == 'f' && c1 == ' ') {
                    String[] t = line.split(" +");
                    if (t.length < 4) continue;
                    int[] v0 = parseFaceVertFull(t[1]);
                    int[] v1 = parseFaceVertFull(t[2]);
                    int[] v2 = parseFaceVertFull(t[3]);
                    faces.add(new int[]{ v0[0],v0[1],v0[2], v1[0],v1[1],v1[2], v2[0],v2[1],v2[2] });
                }
            }
        } catch (Exception e) {
            System.out.println("[ObjMesh] PARSE ERROR (textured) in " + path + ": " + e);
            return new float[0];
        }

        float minY = Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
        for (float[] p : positions) {
            if (p[1] < minY) minY = p[1];
            if (p[1] > maxY) maxY = p[1];
        }
        float yShift = -minY;
        System.out.printf("[ObjMesh] Textured bounds: Y=[%.3f, %.3f]  height=%.3f%n", minY, maxY, maxY - minY);

        float[] out = new float[faces.size() * 3 * 8];
        int idx = 0;
        float[] fallbackNorm = { 0f, 1f, 0f };
        float[] fallbackUV   = { 0f, 0f };

        for (int[] face : faces) {
            for (int i = 0; i < 3; i++) {
                int pi = face[i * 3];
                int ni = face[i * 3 + 1];
                int ti = face[i * 3 + 2];

                float[] p  = positions.get(pi);
                float[] n  = (ni >= 0 && ni < normals.size()) ? normals.get(ni) : fallbackNorm;
                float[] uv = (ti >= 0 && ti < uvs.size())     ? uvs.get(ti)     : fallbackUV;

                out[idx++] = p[0];
                out[idx++] = p[1] + yShift;
                out[idx++] = p[2];
                out[idx++] = n[0];
                out[idx++] = n[1];
                out[idx++] = n[2];
                out[idx++] = uv[0];
                out[idx++] = uv[1];
            }
        }

        System.out.printf("[ObjMesh] Loaded textured %s — %d tris, %d uvs%n", path, faces.size(), uvs.size());
        return out;
    }

    /**
     * Same as loadTextured() but does NOT apply a Y-shift.
     * Use for arm/part meshes whose OBJ origin is the pivot joint, not the floor.
     * Returns interleaved [x,y,z, nx,ny,nz, u,v] per vertex — 8 floats.
     */
    public static float[] loadTexturedRaw(String path) {
        Path filePath = Paths.get(path).toAbsolutePath().normalize();
        System.out.println("[ObjMesh] Loading textured (raw/no-shift): " + filePath
                + "  exists=" + Files.exists(filePath));
        if (!Files.exists(filePath)) {
            System.out.println("[ObjMesh] FILE NOT FOUND (raw) — check path");
            return new float[0];
        }

        List<float[]> positions = new ArrayList<>(32768);
        List<float[]> normals   = new ArrayList<>(32768);
        List<float[]> uvs       = new ArrayList<>(32768);
        List<int[]>   faces     = new ArrayList<>(40000);

        try (BufferedReader br = Files.newBufferedReader(filePath)) {
            String line;
            while ((line = br.readLine()) != null) {
                if (line.length() < 2) continue;
                char c0 = line.charAt(0), c1 = line.charAt(1);
                if      (c0=='v' && c1==' ') { String[] t=line.split(" +"); positions.add(new float[]{Float.parseFloat(t[1]),Float.parseFloat(t[2]),Float.parseFloat(t[3])}); }
                else if (c0=='v' && c1=='t') { String[] t=line.split(" +"); uvs.add(new float[]{Float.parseFloat(t[1]),Float.parseFloat(t[2])}); }
                else if (c0=='v' && c1=='n') { String[] t=line.split(" +"); normals.add(new float[]{Float.parseFloat(t[1]),Float.parseFloat(t[2]),Float.parseFloat(t[3])}); }
                else if (c0=='f' && c1==' ') {
                    String[] t=line.split(" +"); if(t.length<4) continue;
                    int[] v0=parseFaceVertFull(t[1]),v1=parseFaceVertFull(t[2]),v2=parseFaceVertFull(t[3]);
                    faces.add(new int[]{v0[0],v0[1],v0[2],v1[0],v1[1],v1[2],v2[0],v2[1],v2[2]});
                }
            }
        } catch (Exception e) { System.out.println("[ObjMesh] PARSE ERROR (raw): " + e); return new float[0]; }

        float minY = Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
        for (float[] p : positions) { if(p[1]<minY)minY=p[1]; if(p[1]>maxY)maxY=p[1]; }
        System.out.printf("[ObjMesh] Raw bounds: Y=[%.3f, %.3f]  (no shift applied)%n", minY, maxY);

        float[] out = new float[faces.size() * 3 * 8];
        int idx = 0;
        float[] fn = {0f,1f,0f}, fu = {0f,0f};
        for (int[] face : faces) {
            for (int i=0; i<3; i++) {
                int pi=face[i*3], ni=face[i*3+1], ti=face[i*3+2];
                float[] p=positions.get(pi);
                float[] n=(ni>=0&&ni<normals.size())?normals.get(ni):fn;
                float[] uv=(ti>=0&&ti<uvs.size())?uvs.get(ti):fu;
                out[idx++]=p[0]; out[idx++]=p[1]; out[idx++]=p[2]; // NO y-shift
                out[idx++]=n[0]; out[idx++]=n[1]; out[idx++]=n[2];
                out[idx++]=uv[0]; out[idx++]=uv[1];
            }
        }
        System.out.printf("[ObjMesh] Loaded raw %s — %d tris%n", path, faces.size());
        return out;
    }

    /** Parse "vi/ti/ni" — returns [pi, ni, ti] (all 0-based; -1 if absent). */
    private static int[] parseFaceVertFull(String token) {
        String[] parts = token.split("/", -1);
        int pi = Integer.parseInt(parts[0]) - 1;
        int ti = -1, ni = -1;
        if (parts.length >= 2 && !parts[1].isEmpty()) ti = Integer.parseInt(parts[1]) - 1;
        if (parts.length >= 3 && !parts[2].isEmpty()) ni = Integer.parseInt(parts[2]) - 1;
        return new int[]{ pi, ni, ti };
    }
}
