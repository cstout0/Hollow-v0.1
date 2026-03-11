package noctfield.world;

import java.util.Set;
import java.util.concurrent.*;

public class AsyncChunkPipeline {
    private final ExecutorService workers;
    private final ConcurrentLinkedQueue<MeshedChunk> ready = new ConcurrentLinkedQueue<>();
    private final Set<ChunkPos> inFlight = ConcurrentHashMap.newKeySet();

    private volatile long worldSeed = 1337L;
    private volatile boolean texturedTerrain = false;

    public AsyncChunkPipeline() {
        int n = 2; // keep memory bounded while meshing gets heavier (caves/lighting)
        workers = Executors.newFixedThreadPool(n, r -> {
            Thread t = new Thread(r, "noctfield-chunk-worker");
            t.setDaemon(true);
            return t;
        });
    }

    public void setWorldSeed(long seed) {
        this.worldSeed = seed;
        clearReady();
        inFlight.clear();
    }

    public void setTexturedTerrain(boolean v) {
        this.texturedTerrain = v;
        clearReady();
        inFlight.clear();
    }

    public void request(ChunkPos pos) {
        if (!inFlight.add(pos)) return;
        final long seed = worldSeed;
        final boolean textured = texturedTerrain;
        workers.submit(() -> {
            try {
                VoxelChunk chunk = ChunkGenerator.generate(pos, seed);
                float[] verts = textured ? VoxelMesher.meshTextured(chunk) : VoxelMesher.mesh(chunk);
                ready.add(new MeshedChunk(seed, pos, chunk, verts));
            } finally {
                inFlight.remove(pos);
            }
        });
    }

    public MeshedChunk pollReady() { return ready.poll(); }
    public int inFlightCount() { return inFlight.size(); }
    public int readyCount() { return ready.size(); }
    public void clearReady() { ready.clear(); }

    public void shutdown() {
        workers.shutdownNow();
    }
}
