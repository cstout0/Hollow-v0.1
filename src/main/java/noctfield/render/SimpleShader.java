package noctfield.render;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.system.MemoryStack;

import java.nio.FloatBuffer;

import static org.lwjgl.opengl.GL20.*;

public class SimpleShader {
    public static final int MAX_LAMPS = 32;

    private final int program;
    private final int uProj, uView, uModel;
    private final int uLightDir, uAmbient, uDirect, uFogDensity, uCamPos;
    private final int uFogColor;
    private final int uLampCount;
    private final int[] uLampPos   = new int[MAX_LAMPS];
    private final int[] uLampPower = new int[MAX_LAMPS];
    private final int uLampIntensityMul, uLampAttenLinear, uLampAttenQuad;
    // M42: procedural walk animation
    private final int uAnimTime, uAnimEnabled;
    // M43: texture + specular + white eye glow
    private final int uTex, uUseTexture, uSpecular;

    public SimpleShader() {
        // ------------------------------------------------------------------
        // Vertex shader
        // ------------------------------------------------------------------
        String vs = """
                #version 330 core
                layout(location=0) in vec3 aPos;
                layout(location=1) in vec3 aNrm;
                layout(location=2) in vec3 aCol;
                layout(location=3) in vec2 aUV;   // UV coords; (0,0) default when not bound

                out vec3 vCol;
                out vec3 vNrm;
                out vec3 vPos;
                out vec2 vUV;

                uniform mat4  uProj;
                uniform mat4  uView;
                uniform mat4  uModel;

                // M42: procedural walk animation.
                // entityAnimTime grows at 1 sec/sec. Model-space Y=0 at feet, ~2 units tall.
                uniform float uAnimTime;
                uniform bool  uAnimEnabled;

                void main() {
                    vec3 modPos = aPos;

                    if (uAnimEnabled) {
                        // 1. Full-body S-wave undulation.
                        //    Each Y level is out of phase with the next → creeping S-curve.
                        //    This is visible on ANY model shape, not just bipeds.
                        float wave = sin(aPos.y * 2.8 + uAnimTime * 3.2) * 0.18;
                        modPos.z  += wave;

                        // 2. Alternating leg stride (left/right legs swing opposite phase).
                        //    sign(aPos.x): +1 right, -1 left, 0 centre-line spine (correct).
                        float side    = sign(aPos.x);
                        float legFrac = clamp(1.0 - aPos.y / 0.9, 0.0, 1.0);
                        modPos.z     += sin(uAnimTime * 2.5 + side * 3.14159265) * legFrac * 0.22;

                        // 3. Lateral body sway — amplitude grows with height (cantilever).
                        float heightFrac = clamp(aPos.y / 1.9, 0.0, 1.0);
                        modPos.x        += sin(uAnimTime * 1.25) * 0.10 * heightFrac;

                        // 4. Head/upper-body bob — top ~30% of the model.
                        float headFrac = clamp((aPos.y - 1.26) / 0.64, 0.0, 1.0);
                        modPos.y      += sin(uAnimTime * 2.5 + 0.8) * 0.06 * headFrac;
                    }

                    vec4 worldPos = uModel * vec4(modPos, 1.0);
                    vCol = aCol;
                    vNrm = normalize(mat3(uModel) * aNrm);
                    vPos = worldPos.xyz;
                    vUV  = aUV;
                    gl_Position = uProj * uView * worldPos;
                }
                """;

        // ------------------------------------------------------------------
        // Fragment shader
        // ------------------------------------------------------------------
        String fs = """
                #version 330 core
                in vec3 vCol;
                in vec3 vNrm;
                in vec3 vPos;
                in vec2 vUV;
                out vec4 FragColor;

                uniform vec3  uLightDir;
                uniform float uAmbient;
                uniform float uDirect;
                uniform float uFogDensity;
                uniform vec3  uCamPos;

                uniform int   uLampCount;
                uniform vec3  uLampPos[32];
                uniform float uLampPower[32];

                uniform float uLampIntensityMul;
                uniform float uLampAttenLinear;
                uniform float uLampAttenQuad;

                // M43: texture + specular + white eye glow
                uniform sampler2D uTex;        // texture unit 0
                uniform bool      uUseTexture; // true = sample uTex; false = use vCol
                uniform float     uSpecular;   // Blinn-Phong strength; 0 for terrain

                // M148: dynamic fog colour passed from sky (gray minimum so fog reads as mist)
                uniform vec3 uFogColor;

                void main() {
                    vec3 n    = normalize(vNrm);
                    vec3 l    = normalize(-uLightDir);
                    float diff = max(dot(n, l), 0.0);
                    // No upper clamp — Reinhard tonemapping handles HDR.
                    // Clamping here was killing strong sunlight (direct=2.2 wasted after clamp to 1.0).
                    float lit  = max(0.0, uAmbient + diff * uDirect);

                    // Base colour — texture sample or vertex colour
                    vec3 baseCol = uUseTexture ? texture(uTex, vUV).rgb : vCol;
                    // M118: keep texture contrast while avoiding camera-centered blowout.
                    vec3 texCol = uUseTexture ? pow(baseCol, vec3(1.12)) : baseCol;
                    // M133: removed player ambient floor — caves are now truly dark without a light source.
                    float litApplied = lit;
                    vec3 col     = texCol * litApplied;

                    // Eye glow: red (voxel entities) — unchanged from before
                    bool eyeRed   = (!uUseTexture) && (vCol.r > 0.95 && vCol.g < 0.35 && vCol.b < 0.40);
                    // Eye glow: white (THE THING eye mesh loaded with RGB 1,1,1)
                    bool eyeWhite = (!uUseTexture) && (vCol.r > 0.85 && vCol.g > 0.85 && vCol.b > 0.85);

                    if (eyeRed)   col += vec3(1.8, 0.15, 0.18);
                    if (eyeWhite) col += vec3(1.4, 1.4,  1.6);

                    // Blinn-Phong specular (uSpecular=0 for terrain; >0 for entity skin / eyes)
                    if (uSpecular > 0.001) {
                        vec3  viewDir = normalize(uCamPos - vPos);
                        vec3  halfDir = normalize(viewDir + l);
                        float spec    = pow(max(dot(n, halfDir), 0.0), 24.0);
                        col += vec3(spec * uSpecular);
                    }

                    // Point-light lamp contributions
                    for (int i = 0; i < uLampCount; i++) {
                        vec3  toLamp = uLampPos[i] - vPos;
                        float d      = length(toLamp);
                        if (d < 40.0) {
                            vec3  lt  = normalize(toLamp);
                            float nd  = max(dot(n, lt), 0.0);
                            float att = (uLampPower[i] * uLampIntensityMul) / (1.0 + uLampAttenLinear * d + uLampAttenQuad * d * d);
                            vec3 lampBase = uUseTexture ? mix(texCol, vec3(1.0), 0.08) : baseCol;
                            // Scalar boosted to 0.45 — Reinhard now handles HDR so we can push lamps much harder.
                            col += lampBase * nd * att * 0.45 * vec3(1.0, 0.92, 0.74);
                        }
                    }

                    // Reinhard tonemap — compresses accumulated HDR light before fog/output.
                    // Prevents crystal/lamp accumulation from clipping to pure white.
                    col = col / (col + vec3(1.0));

                    // Exponential fog — colour driven by uFogColor (sky-matched, minimum gray)
                    float dist     = length(uCamPos - vPos);
                    float fog      = 1.0 - exp(-uFogDensity * dist * dist);
                    vec3  fogColor = uFogColor;
                    bool  anyGlow  = eyeRed || eyeWhite;
                    // Glowing fragments resist fog; everything else fades normally
                    col = mix(col, fogColor, clamp(fog * (anyGlow ? 0.14 : 1.0), 0.0, 1.0));

                    FragColor = vec4(col, 1.0);
                }
                """;

        int v = compile(GL_VERTEX_SHADER,   vs);
        int f = compile(GL_FRAGMENT_SHADER, fs);

        program = glCreateProgram();
        glAttachShader(program, v);
        glAttachShader(program, f);
        glLinkProgram(program);
        if (glGetProgrami(program, GL_LINK_STATUS) == GL_FALSE) {
            throw new IllegalStateException("Shader link failed: " + glGetProgramInfoLog(program));
        }
        glDeleteShader(v);
        glDeleteShader(f);

        uProj       = glGetUniformLocation(program, "uProj");
        uView       = glGetUniformLocation(program, "uView");
        uModel      = glGetUniformLocation(program, "uModel");
        uLightDir   = glGetUniformLocation(program, "uLightDir");
        uAmbient    = glGetUniformLocation(program, "uAmbient");
        uDirect     = glGetUniformLocation(program, "uDirect");
        uFogDensity = glGetUniformLocation(program, "uFogDensity");
        uCamPos     = glGetUniformLocation(program, "uCamPos");
        uLampCount  = glGetUniformLocation(program, "uLampCount");
        for (int i = 0; i < MAX_LAMPS; i++) {
            uLampPos[i]   = glGetUniformLocation(program, "uLampPos["   + i + "]");
            uLampPower[i] = glGetUniformLocation(program, "uLampPower[" + i + "]");
        }
        uLampIntensityMul = glGetUniformLocation(program, "uLampIntensityMul");
        uLampAttenLinear  = glGetUniformLocation(program, "uLampAttenLinear");
        uLampAttenQuad    = glGetUniformLocation(program, "uLampAttenQuad");
        uAnimTime    = glGetUniformLocation(program, "uAnimTime");
        uAnimEnabled = glGetUniformLocation(program, "uAnimEnabled");
        uTex         = glGetUniformLocation(program, "uTex");
        uUseTexture  = glGetUniformLocation(program, "uUseTexture");
        uSpecular    = glGetUniformLocation(program, "uSpecular");
        uFogColor    = glGetUniformLocation(program, "uFogColor");

        // Bind the texture sampler to unit 0 once — stays constant for the lifetime of the shader
        glUseProgram(program);
        glUniform1i(uTex, 0);
        glUniform1f(uLampIntensityMul, 1.0f);
        glUniform1f(uLampAttenLinear, 0.22f);  // was 0.10 — faster falloff, stops lamps reaching far blocks
        glUniform1f(uLampAttenQuad, 0.040f);   // was 0.018
        // M148: default gray fog (overridden each frame by Renderer from sky colour)
        glUniform3f(uFogColor, 0.22f, 0.23f, 0.26f);
        glUseProgram(0);
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    private int compile(int type, String src) {
        int s = glCreateShader(type);
        glShaderSource(s, src);
        glCompileShader(s);
        if (glGetShaderi(s, GL_COMPILE_STATUS) == GL_FALSE) {
            throw new IllegalStateException("Shader compile failed: " + glGetShaderInfoLog(s));
        }
        return s;
    }

    // ------------------------------------------------------------------
    // Public API
    // ------------------------------------------------------------------

    public void bind()   { glUseProgram(program); }
    public void unbind() { glUseProgram(0); }

    public void setProj(Matrix4f m) {
        try (MemoryStack st = MemoryStack.stackPush()) {
            FloatBuffer fb = st.mallocFloat(16);
            m.get(fb);
            glUniformMatrix4fv(uProj, false, fb);
        }
    }

    public void setView(Matrix4f m) {
        try (MemoryStack st = MemoryStack.stackPush()) {
            FloatBuffer fb = st.mallocFloat(16);
            m.get(fb);
            glUniformMatrix4fv(uView, false, fb);
        }
    }

    private static final Matrix4f IDENTITY = new Matrix4f();

    /** Set the model matrix — used for OBJ entity transforms. */
    public void setModel(Matrix4f m) {
        try (MemoryStack st = MemoryStack.stackPush()) {
            FloatBuffer fb = st.mallocFloat(16);
            m.get(fb);
            glUniformMatrix4fv(uModel, false, fb);
        }
    }

    /** Reset model to identity — call before rendering world chunks. */
    public void setModelIdentity() { setModel(IDENTITY); }

    public void setLight(Vector3f dir, float ambient, float direct, float fogDensity, Vector3f camPos) {
        glUniform3f(uLightDir, dir.x, dir.y, dir.z);
        glUniform1f(uAmbient,    ambient);
        glUniform1f(uDirect,     direct);
        glUniform1f(uFogDensity, fogDensity);
        glUniform3f(uCamPos,     camPos.x, camPos.y, camPos.z);
    }

    /** M148: set fog blend colour (sky colour + gray floor so fog reads as visible mist). */
    public void setFogColor(float r, float g, float b) {
        glUniform3f(uFogColor, r, g, b);
    }

    public void setLamps(Vector3f[] pos, float[] power, int count) {
        int n = Math.max(0, Math.min(MAX_LAMPS, count));
        glUniform1i(uLampCount, n);
        for (int i = 0; i < n; i++) {
            glUniform3f(uLampPos[i],   pos[i].x, pos[i].y, pos[i].z);
            glUniform1f(uLampPower[i], power[i]);
        }
    }

    public void setLampModel(float intensityMul, float attenLinear, float attenQuad) {
        glUniform1f(uLampIntensityMul, intensityMul);
        glUniform1f(uLampAttenLinear, attenLinear);
        glUniform1f(uLampAttenQuad, attenQuad);
    }

    /** M42: enable/disable procedural walk animation and set the current time accumulator. */
    public void setAnimTime(float t, boolean enabled) {
        glUniform1f(uAnimTime,    t);
        glUniform1i(uAnimEnabled, enabled ? 1 : 0);
    }

    /** M43: switch between texture-sampled colour and vertex colour. */
    public void setUseTexture(boolean b) { glUniform1i(uUseTexture, b ? 1 : 0); }

    /** M43: Blinn-Phong specular intensity. 0 = off (terrain). ~0.4 = skin, ~6 = glossy eyes. */
    public void setSpecular(float s) { glUniform1f(uSpecular, s); }

    public void destroy() { glDeleteProgram(program); }
}
