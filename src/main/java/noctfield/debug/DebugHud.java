package noctfield.debug;

import noctfield.render.Renderer;
import noctfield.world.BlockId;
import noctfield.world.FreeCamera;
import noctfield.world.Raycast;
import org.joml.Vector3f;

import static org.lwjgl.glfw.GLFW.glfwSetWindowTitle;

public class DebugHud {
    private float acc = 0f;
    private int frames = 0;
    private float fps = 0f;

    private final char[] spark = new char[32];
    private int sparkIdx = 0;

    public DebugHud() {
        for (int i = 0; i < spark.length; i++) spark[i] = '▁';
    }

    public void render(long window, FreeCamera camera, Renderer renderer, Raycast.Hit hit, byte selectedPlace, int selectedCount, int health, String overlayText, float dt) {
        acc += dt;
        frames++;

        spark[sparkIdx] = bucket(dt * 1000f);
        sparkIdx = (sparkIdx + 1) % spark.length;

        if (acc >= 0.25f || overlayText != null) {
            fps = frames / acc;
            frames = 0;
            acc = 0f;

            Vector3f p = camera.position();
            String graph = sparkline();
            String hitTxt = (hit == null) ? "hit:-" : ("hit:" + hit.x() + "," + hit.y() + "," + hit.z());
            String title = String.format(
                    "NOCTFIELD v2 M29[%s] | HP %d | FPS %.1f | ft %.1fms | chunks %d/%d rq%d up%d inF%d q%d | r%d rb%d ub%d max%d %s | edits %d | sel %s x%d | L[a%.2f d%.2f f%.4f->%.4f %s] | SKY[%s t%.2f n%.2f clr:%.3f/%.3f/%.3f] | W[%s ev:%s next:%.1fs calm:%.1fs x%d] | FIG[%s m%.2f] | D[%.2f %s] | C[t%.2f y%d-%d] | M[floats %d fb %d] | up %.2fms render %.2fms | %s %s%s | %s%s",
                    renderer.worldId(),
                    health,
                    fps,
                    1000f / Math.max(1f, fps),
                    renderer.visibleChunkCount(), renderer.loadedChunkCount(),
                    renderer.lastRequestedChunks(), renderer.lastUploadedChunks(), renderer.inFlight(), renderer.queueSize(),
                    renderer.radius(), renderer.requestBudget(), renderer.uploadBudget(), renderer.maxLoadedChunks(), renderer.lowMemoryMode() ? "LOWMEM" : "",
                    renderer.editCount(),
                    BlockId.nameOf(selectedPlace), selectedCount,
                    renderer.ambient(), renderer.direct(), renderer.fogDensity(), renderer.fogApplied(), renderer.nightMode() ? "NIGHT" : "DAY",
                    renderer.skyModeName(), renderer.timeOfDay01(), renderer.nightFactor(), renderer.clearR(), renderer.clearG(), renderer.clearB(),
                    renderer.watcherState(), renderer.watcherEvent(), renderer.watcherNextEventIn(), renderer.watcherCalmTimer(), renderer.watcherCount(),
                    renderer.figureStateName(), renderer.figureMorphT(),
                    renderer.distortionIntensity(), renderer.distortionEnabled() ? "ON" : "OFF",
                    noctfield.world.ChunkGenerator.caveThreshold(), noctfield.world.ChunkGenerator.caveBandMinY(), noctfield.world.ChunkGenerator.caveBandMaxY(),
                    renderer.lastMesherFloats(), renderer.mesherFallbackCount(),
                    renderer.lastUploadMs(), renderer.lastRenderMs(),
                    hitTxt,
                    camera.noclip() ? "[NOCLIP]" : "",
                    camera.onGround() ? "[G]" : "",
                    graph,
                    (overlayText == null ? "" : " | " + overlayText)
            );
            glfwSetWindowTitle(window, title);
        }
    }

    private static char bucket(float ms) {
        if (ms < 8f) return '▁';
        if (ms < 11f) return '▂';
        if (ms < 14f) return '▃';
        if (ms < 17f) return '▄';
        if (ms < 21f) return '▅';
        if (ms < 26f) return '▆';
        if (ms < 33f) return '▇';
        return '█';
    }

    private String sparkline() {
        StringBuilder sb = new StringBuilder(spark.length);
        for (int i = 0; i < spark.length; i++) {
            int idx = (sparkIdx + i) % spark.length;
            sb.append(spark[idx]);
        }
        return sb.toString();
    }
}
