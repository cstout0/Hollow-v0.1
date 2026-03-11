package noctfield.render;

import noctfield.world.*;
import org.joml.FrustumIntersection;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import javax.imageio.ImageIO;

import org.lwjgl.system.MemoryUtil;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL12.*;  // GL_CLAMP_TO_EDGE
import static org.lwjgl.opengl.GL13.*;  // GL_TEXTURE0, glActiveTexture
import static org.lwjgl.opengl.GL30.*;  // glGenerateMipmap

public class Renderer {
    private enum SkyMode { STATIC_NIGHT, CYCLE }
    private final SimpleShader shader = new SimpleShader();
    private final StreamMesh streamMesh = new StreamMesh(); // M151: persistent streaming VBO
    private final AsyncChunkPipeline pipeline = new AsyncChunkPipeline();
    private final Map<ChunkPos, GpuMesh> chunkMeshes = new HashMap<>();
    private final Map<ChunkPos, VoxelChunk> chunkData = new HashMap<>();
    private final Map<Long, Byte> worldEdits  = new HashMap<>();
    /** M237: per-door facing — 0=Z-axis (N/S), 1=X-axis (E/W). Keyed same as worldEdits. */
    private final Map<Long, Byte> doorFacing  = new HashMap<>();

    private int radius = 4;
    private int requestBudget = 4;
    private int uploadBudget = 3;
    private int maxLoadedChunks = 240;
    private boolean lowMemoryMode = false;

    private int lastVisibleChunks = 0;
    private int lastRequestedChunks = 0;
    private int lastUploadedChunks = 0;
    private float lastUploadMs = 0f;
    private float lastRenderMs = 0f;

    private final Vector3f lightDir = new Vector3f(-0.5f, -1.0f, -0.25f).normalize();
    private final Vector3f skyLightDir = new Vector3f(-0.5f, -1.0f, -0.25f).normalize();
    private final Vector3f[] lampPos = new Vector3f[SimpleShader.MAX_LAMPS];
    private final float[] lampPower = new float[SimpleShader.MAX_LAMPS];
    // M231: dynamic lighting toggle (when false, lamp system is skipped for flat, cheaper lighting)
    private boolean dynamicLighting    = true;
    public  boolean isDynamicLighting()           { return dynamicLighting; }
    public  void    setDynamicLighting(boolean v) { dynamicLighting = v; lampCacheDirty = true; }

    // M151: lamp scan cache  - " skip the full chunk scan every frame
    private int     lampCacheCount     = 0;
    private boolean lampCacheDirty     = true;
    private float   lampCacheLastX     = Float.MAX_VALUE, lampCacheLastZ = Float.MAX_VALUE;
    private int     lampCacheChunkSize = 0; // M152 fix: detect new chunks loading in post-spawn
    // M151: torch/door geometry cache  - " rebuilt only when blocks change
    private float[] torchCacheVerts     = new float[0];
    private int     torchCacheCount     = 0;
    private float[] torchGlowCacheVerts = new float[0]; // M232: emissive flame tips — drawn with HDR shader
    private int     torchGlowCacheCount = 0;
    private boolean torchCacheDirty = true;
    private float ambient = 0.38f;
    private float direct = 0.90f;

    // M116: lantern strength presets
    private enum LanternPreset { NORMAL, STRONG, HORROR_BRIGHT }
    private LanternPreset lanternPreset = LanternPreset.NORMAL;
    private LanternPreset lastLanternPreset = null; // M151: skip redundant setLampModel calls
    private float fogDensity = 0.0018f;
    private boolean heldTorchActive = false;
    private final Vector3f heldTorchPos = new Vector3f();
    private boolean fogAutoByRenderDistance = true;
    private float fogUserMultiplier = 1.0f;
    private float fogApplied = 0.0018f;
    private boolean nightMode = false;
    private float clearR = 0.03f, clearG = 0.04f, clearB = 0.06f;
    private float biomeBaseClearR = 0.03f, biomeBaseClearG = 0.04f, biomeBaseClearB = 0.06f;
    private float biomeBaseFog = 0.0018f;
    private SkyMode skyMode = SkyMode.CYCLE;
    private float timeOfDay01 = 0.45f; // start in daytime by default
    private float dayLengthSeconds = 600f; // 10-minute full cycle (300s day + 300s night)
    private float nightFactor = 0.6f;
    private float cloudDrift = 0f;
    private long lastSkyNs = 0L;
    private boolean paused = false;
    private float biomeAggroMul = 1.0f;
    private float biomePineW = 1f, biomeDeadW = 0f, biomeSwampW = 0f;

    private String worldId = "default";
    private long worldSeed = 1337L;
    private Path editsFile = Path.of("worlds", "default-edits.txt");

    private final ArrayList<Vector3f> fogWatchers = new ArrayList<>();
    private float behindYouExtraTimer = 0f; // M159: extra sentinel slot for "behind you" jumpscare
    private final Random fogRng = new Random(1337);
    // M148: physics-based dropped item entities
    private final java.util.List<DroppedItemData> droppedItems = new java.util.ArrayList<>();
    // M150: pig entities
    private static final int PIG_MAX = 5;
    private final java.util.List<PigEntity>      pigs      = new java.util.ArrayList<>();
    private final java.util.List<ScreamerEntity> screamers = new java.util.ArrayList<>();
    private boolean  screamerSoundPending  = false;
    private boolean  hideSeekCaughtPending = false; // M218: set when hide-seek screamer spots player
    private float[]  screamerSoundXYZ     = {0f, 0f, 0f};
    private float    screamerSpawnTimer   = 90f; // M199: delay before first screamer appears
    // M201: THE NUN
    private final java.util.List<NunEntity> nuns    = new java.util.ArrayList<>();
    private float   nunSpawnTimer   = 120f;
    private boolean nunHitPending   = false;
    private boolean nunStepPending  = false;
    private float[] nunStepXYZ      = {0f, 0f, 0f};
    private float   pigSpawnTimer          = 6f;
    private boolean spectralPigDespawned    = false; // M172: consumed by GameApp for audio

    // M152: horror progression + blood rain + thick fog + hallucinations
    private float   horrorProgression  = 0f;
    private boolean bloodRainMode      = false;
    private float   thickFogBurst      = 0f;
    private float   thickFogEventTimer = 45f;
    private boolean thickFogEventReady = false;
    // M152: DEAD_FOG event â€” near-blackout horror fog that rolls in at high progression
    private float   deadFogCooldown   = 120f;  // time before first possible DEAD_FOG
    private boolean deadFogEventReady = false;  // GameApp can consume this to show a message

    private static final int HALLU_MAX = 8; // M152: up from 4 â€” more figures at high horror
    private static final class HallucinationEntity {
        float x, y, z;
        float vx = 0, vz = 0;     // M152: fog figures drift toward player
        float facing;
        float lookTimer = 0f;
        float lifetime;
        float fadeOut   = 0f;
        boolean fading  = false;
        boolean fogFigure = false; // M152: spawned from fog events â€” moves, doesn't fade on look
    }
    private final java.util.List<HallucinationEntity> hallucinations = new java.util.ArrayList<>();
    private float halluSpawnTimer = 60f;  // M173: slower start

    // M156: Subtle psychological horror â€” things that make the player doubt their senses
    private float   psychTimer          = 45f;  // time until next psych event
    private int     psychPhase          = 0;    // cycles through event categories
    private float   flashEdgeTimer      = 0f;   // peripheral dark edge flash
    private boolean flashEdgeLeft       = false;// which side the flash appears on
    private float   torchFlickerTimer   = 0f;   // momentary torch-light dropout
    private boolean torchFlickerActive  = false;
    private int     ghostHalluFrames    = 0;    // 1-3 frame ghost silhouette at screen periphery
    private float   ghostHalluX         = 0f, ghostHalluZ = 0f; // world pos
    private int     psychAudioEvent     = 0;    // consumed by GameApp: 1=ghost footstep, 2=overhead creak
    public  int  consumePsychAudioEvent() { int e = psychAudioEvent; psychAudioEvent = 0; return e; }
    public  float flashEdgeAlpha()   { return Math.max(0f, flashEdgeTimer / 0.09f); }
    public  boolean flashEdgeLeft()  { return flashEdgeLeft; }
    public  boolean torchFlicker()   { return torchFlickerActive; }
    public  boolean hasGhostFlash()  { return ghostHalluFrames > 0; }
    public  float ghostFlashX()      { return ghostHalluX; }
    public  float ghostFlashZ()      { return ghostHalluZ; }

    private float watcherDirectorTimer = 0f;
    private float watcherVisibleTimer = 0f;
    private float watcherLookAccum = 0f;
    private float watcherDebugForceTimer = 0f;
    private float watcherNightElapsed = 0f;
    private float watcherNextEventIn = 6f;
    private float watcherCalmTimer = 0f;
    private float sanity01 = 1.0f; // hidden gameplay input (1.0 sane -> 0.0 unstable)
    // AI #1 (passive observer): NIGHT_SENTINEL
    private int pendingPlayerHits = 0;
    private float playerHitImmunity = 0f;

    private boolean distortionEnabled = true;
    private float distortionIntensity = 0f;
    private float distortionTarget = 0f;
    private float distortionPulseTimer = 0f;
    private boolean liminalZoneMode = false; // M225: kept for compat; use liminalZoneId
    private int liminalZoneId = 0;           // M229: 0=overworld 1=meadow 2=darkroom
    private String watcherState = "IDLE";
    private String watcherEvent = "NONE";
    private float entityAnimTime  = 0f;   // M32: drives sway/jitter animation

    // M36: relic escalation
    private int relicLevel = 0;
    private final Vector3f lastCamPos = new Vector3f();

    // M37: underground atmosphere
    private boolean underground = false;
    private float   undergroundAmbientOffset = 0f; // smooth transition
    private float   undergroundDirectMul     = 1f; // multiplier: 1.0 surface Ã¢â€ ' 0.0 deep underground

    // M39: weather
    private enum WeatherState { CLEAR, RAIN, DEAD_FOG } // M152: DEAD_FOG = near-blackout horror event
    private enum ThingState   { SEEK_COVER, MOVE_TO_COVER, PEEK, CLOSE_APPROACH, RETREAT, DRAG }
    private WeatherState weatherState   = WeatherState.CLEAR;
    private float weatherTimer          = 20f + (float)(Math.random() * 60f); // M167: randomised first event
    private float lightningTimer        = 0f;
    private float lightningFlashTimer   = 0f;
    private boolean lightningFlashReady = false; // consumed once per flash
    // M54: The Figure  - " friendly wanderer that morphs into a monster at close range
    private enum FigureState { WANDER, NOTICE, TRANSFORM, MONSTER, RETREATING }
    private final Vector3f figurePos    = new Vector3f();
    private final Vector3f figureTarget = new Vector3f();
    private FigureState    figureState  = FigureState.WANDER;
    private float figureFacing          = 0f;
    private float figureMorphT          = 0f;   // 0=friendly 1=monster
    private float figureStateTimer      = 0f;
    private float figureSpawnTimer      = 360f; // M173: slower start
    private boolean figureActive        = false;

    // M166: THE DEEP â€” blind cave floor predator, hunts by sound
    private enum DeepState { DORMANT, HUNTING }
    private final Vector3f deepPos         = new Vector3f();
    private float          deepFacing      = 0f;
    private DeepState      deepState       = DeepState.DORMANT;
    private float          deepSpawnTimer  = 420f; // M173: 7 min first spawn
    private float          deepStateTimer  = 0f;
    private boolean        deepActive      = false;
    private boolean        deepHitPlayer   = false;
    private float          deepHitCooldown = 0f;
    private boolean        playerMoving    = false;
    private boolean        relicsComplete  = false; // M169: show void beacon when all relics found

    // M86: Ceiling Lurker  - " cave ceiling predator, drops onto player when approached
    private enum LurkerState { CRAWLING, HANGING, DROPPING, HUNTING, RETREATING }
    private final Vector3f lurkerPos     = new Vector3f();
    private LurkerState    lurkerState   = LurkerState.HANGING;
    private float          lurkerFacing  = 0f;
    private float          lurkerStateTimer = 0f;
    private float          lurkerSpawnTimer = 90f;  // M173: first check 90s
    private boolean        lurkerActive  = false;
    private float          lurkerCeilY   = 0f;  // ceiling Y where it hangs
    private float          lurkerLandY   = 0f;  // terrain Y where it lands
    private boolean        lurkerHitPlayer = false; // set true when damage should fire (read by GameApp)

    // M59: Figure smoke burst  - " emitted on hit, figure immediately despawns
    private static final int SMOKE_MAX   = 24;
    private final float[][] smokeParts   = new float[SMOKE_MAX][7]; // x,y,z, vx,vy,vz, ttl
    private int smokeCount               = 0;

    // M51: rain particles + wind + lightning world illumination
    private static final int RAIN_DROPS = 260; // M152: increased pool; blood rain uses all 260
    private final float[][] rainDrops   = new float[RAIN_DROPS][3]; // [x, y, z] world-space
    private boolean rainInitialized     = false;
    private float windX = 0f, windZ = 0f;          // world-space wind velocity
    private float lightningWorldFlash   = 0f;       // decays after a strike, boosts ambient
    // ---- THE THING  - " OBJ-mesh entity, relentless approach, ignores gaze ----
    private static final String THING_PATH         = "models/thing/output.obj";
    private static final float  THING_SCALE        = 1.30f;   // scale factor applied to OBJ model
    private static final float  THING_MODEL_Y_OFF  = 0.0f;    // feet already at y=0 (baked by ObjMesh auto-shift)
    private static final float  THING_FACING_OFF   = 0f;      // tweak if model faces wrong direction (radians)
    private static final float  THING_APPROACH_SPD = 1.80f;   // m/s walking speed
    private static final float  THING_RETREAT_SPD  = 5.50f;   // m/s retreat after hit

    private GpuMesh    thingMesh         = null;  // flat-colour fallback
    private GpuMesh    thingMeshTextured = null;  // M43: UV textured variant
    private GpuMesh    thingEyesMesh     = null;  // M43: white eye mesh (optional)
    private int        thingTexId        = -1;    // M43: OpenGL texture object
    private boolean    thingMeshLoaded   = false;

    // M207: THE NUN â€” rigid body-part model (body + arm as separate OBJs)
    private static final String NUN_BODY_PATH   = "models/nun/body.obj";
    private static final String NUN_ARM_PATH    = "models/nun/arm_right.obj";
    private static final String NUN_TEX_PATH    = "models/nun/textured_mesh.jpg";
    private static final float  NUN_SCALE       = 1.0f;       // model is ~2 units tall â€” matches game scale
    private static final float  NUN_FACING_OFF  = (float)Math.PI; // M209: flip 180Â° so nun faces player
    private static final float  NUN_Y_OFF       = 0.0f;       // foot height correction if needed
    // Shoulder joint position in body-local space (from blender_separate.py output)
    private static final float  NUN_SHOULDER_X  = 0.300f;   // right shoulder X (re-exported ARM_Z_MIN=0.60)
    private static final float  NUN_SHOULDER_Y  = 1.600f;   // shoulder height (Blender Z=0.60 + 1.0 shift)
    private static final float  NUN_SHOULDER_Z  = 0.000f;
    private GpuMesh  nunBodyMesh    = null;   // body/habit/head/veil mesh
    private GpuMesh  nunArmMesh     = null;   // right arm (animated, pivots at shoulder)
    private int      nunModelTex    = -1;     // shared OpenGL texture id
    private boolean  nunModelLoaded = false;  // load-once flag
    private boolean    thingActive       = false;
    private boolean    thingRetreating   = false;
    private float      thingSpawnTimer   = 120f; // M173: slower first spawn
    private float      thingRetreatTimer = 0f;
    private float      thingFacing       = 0f;
    private float      thingDebugTimer   = 0f;

    // M107: world-space mining crack marker
    private boolean miningCrackActive = false;
    private int miningCrackX = 0, miningCrackY = 0, miningCrackZ = 0;
    private float miningCrackProgress = 0f;
    // Target block outline
    private boolean targetOutlineActive = false;
    private int targetOutlineX = 0, targetOutlineY = 0, targetOutlineZ = 0;

    // M111: terrain texture atlas foundation (with tint fallback)
    private boolean terrainTextured = false;
    private int terrainTexId = 0;
    private ThingState thingState        = ThingState.SEEK_COVER;
    private float      thingStateTimer   = 0f;
    private final Vector3f thingPos         = new Vector3f();
    private final Vector3f thingCoverTarget = new Vector3f();
    private boolean thingDragPending = false;   // M198: set when drag teleport fires
    private float   thingDragDestX  = 0f;
    private float   thingDragDestZ  = 0f;

    private final ArrayDeque<String> watcherHistory = new ArrayDeque<>();
    private static final class WatcherTrace { final Vector3f pos = new Vector3f(); float ttl; WatcherTrace(float x,float y,float z,float t){pos.set(x,y,z); ttl=t;} }
    private final ArrayList<WatcherTrace> watcherTraces = new ArrayList<>();
    private long watcherLastNs = 0L;

    public Renderer() {
        for (int i = 0; i < lampPos.length; i++) lampPos[i] = new Vector3f();
        for (int i = 0; i < MAX_FIREFLIES; i++) fireflyPos[i] = new Vector3f();
        for (int i = 0; i < MAX_WISPS; i++) {
            wispPos[i]    = new Vector3f();
            wispTarget[i] = new Vector3f();
        }
        terrainTextured = TerrainAtlas.ensureLoaded();
        terrainTexId = TerrainAtlas.textureId;
        pipeline.setTexturedTerrain(terrainTextured);
        System.out.println("[Renderer] Terrain atlas " + (terrainTextured ? "ENABLED" : "FALLBACK_TINT"));
        setWorld("default", 1337L);
    }

    public void setWorld(String worldId, long seed) {
        this.worldId = worldId;
        this.worldSeed = seed;
        this.editsFile = Path.of("worlds", worldId + "-edits.txt");

        for (GpuMesh m : chunkMeshes.values()) m.destroy();
        chunkMeshes.clear();
        chunkData.clear();
        pipeline.clearReady();
        pipeline.setWorldSeed(seed);

        loadEditsFromDisk();
        thingActive      = false;
        thingRetreating  = false;
        thingSpawnTimer  = 120f; // M173
        thingRetreatTimer= 0f;
        thingState       = ThingState.SEEK_COVER;
        thingStateTimer  = 0f;
        thingDragPending = false;
        fogWatchers.clear();
        droppedItems.clear();   // M148: clear physics drops on world change
        pigs.clear();           // M150: clear pigs on world change
        chickens.clear();       // M192: clear chickens on world change
        screamers.clear();      // M196: clear screamers on world change
        screamerSoundPending  = false;
        hideSeekCaughtPending = false;
        screamerSpawnTimer   = 90f; // M199: reset spawn delay on world change
        nuns.clear();           // M201: clear nuns on world change
        nunHitPending  = false;
        nunStepPending = false;
        nunSpawnTimer  = 120f;
        chickenSpawnTimer = 8f;
        hallucinations.clear(); // M152
        halluSpawnTimer      = 60f; // M173
        thickFogBurst        = 0f;
        thickFogEventTimer   = 45f;
        thickFogEventReady   = false;
        bloodRainMode        = false;
        horrorProgression    = 0f;
        deadFogCooldown      = 120f;
        deadFogEventReady    = false;
        lampCacheDirty      = true;  // M151: force lamp re-scan on new world
        lampCacheChunkSize  = 0;     // M152 fix: will re-detect once chunks stream in
        torchCacheDirty = true; // M151: force torch cache rebuild on new world
        watcherDirectorTimer = 0f;
        watcherVisibleTimer = 0f;
        watcherLookAccum = 0f;
        watcherNightElapsed = 0f;
        watcherNextEventIn = 6f;
        watcherState = "IDLE";
        watcherEvent = "NONE";
        watcherHistory.clear();
        watcherTraces.clear();
        watcherCalmTimer = 0f;
        pendingPlayerHits = 0;
        playerHitImmunity = 0f;
        skyMode = SkyMode.CYCLE;
        cloudDrift = 0f;
        fireflyCount      = 0;
        fireflySpawnTimer = 2f;
        fireflyLastNs     = 0L;
        deepActive      = false;
        deepSpawnTimer  = 300f;
        deepHitPlayer   = false;
        deepHitCooldown = 0f;
        playerMoving    = false;
        wispCount         = 0;
        wispSpawnTimer    = 5f;
        rainInitialized   = false;
        windX             = 0f;
        windZ             = 0f;
        lightningWorldFlash = 0f;
        figureActive      = false;
        figureSpawnTimer  = nextFigureSpawnDelay() + 120f; // M173: extra delay on world change
        figureMorphT      = 0f;
        figureState       = FigureState.WANDER;
        smokeCount        = 0;
    }

    public void rebuildVisibleChunks() {
        for (GpuMesh m : chunkMeshes.values()) m.destroy();
        chunkMeshes.clear();
        chunkData.clear();
        pipeline.clearReady();
    }

    public String worldId() { return worldId; }
    public long worldSeed() { return worldSeed; }

    public void render(FreeCamera camera, int width, int height) {
        long t0 = System.nanoTime();

        float skyDt = (lastSkyNs == 0L) ? 0.016f : Math.min(0.1f, (t0 - lastSkyNs) / 1_000_000_000f);
        lastSkyNs = t0;
        updateSkyLighting(paused ? 0f : skyDt);
        glClearColor(clearR, clearG, clearB, 1f);
        glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);

        int pcx = Math.floorDiv((int) Math.floor(camera.position().x), Chunk.SIZE);
        int pcz = Math.floorDiv((int) Math.floor(camera.position().z), Chunk.SIZE);

        Set<ChunkPos> need = new HashSet<>();
        for (int dz = -radius; dz <= radius; dz++) {
            for (int dx = -radius; dx <= radius; dx++) {
                need.add(new ChunkPos(pcx + dx, pcz + dz));
            }
        }

        // unload out-of-range meshes
        chunkMeshes.entrySet().removeIf(e -> {
            if (!need.contains(e.getKey())) {
                e.getValue().destroy();
                chunkData.remove(e.getKey());
                return true;
            }
            return false;
        });

        // soft memory budget eviction (farthest first)
        if (chunkMeshes.size() > maxLoadedChunks) {
            ArrayList<ChunkPos> loaded = new ArrayList<>(chunkMeshes.keySet());
            loaded.sort((a, b) -> {
                int da = Math.max(Math.abs(a.x() - pcx), Math.abs(a.z() - pcz));
                int db = Math.max(Math.abs(b.x() - pcx), Math.abs(b.z() - pcz));
                return Integer.compare(db, da);
            });
            int remove = chunkMeshes.size() - maxLoadedChunks;
            for (int i = 0; i < remove && i < loaded.size(); i++) {
                ChunkPos p = loaded.get(i);
                GpuMesh m = chunkMeshes.remove(p);
                if (m != null) m.destroy();
                chunkData.remove(p);
            }
        }

        // request missing chunks (distance-prioritized)
        ArrayList<ChunkPos> missing = new ArrayList<>();
        for (ChunkPos p : need) {
            if (!chunkMeshes.containsKey(p)) missing.add(p);
        }
        missing.sort(Comparator.comparingInt(p -> Math.max(Math.abs(p.x() - pcx), Math.abs(p.z() - pcz))));

        int req = 0;
        int backlogCap = Math.max(16, requestBudget * 6);
        for (ChunkPos p : missing) {
            if (req >= requestBudget) break;
            if (pipeline.inFlightCount() + pipeline.readyCount() >= backlogCap) break;
            pipeline.request(p);
            req++;
        }
        lastRequestedChunks = req;

        // upload completed meshes with per-frame cap (GL thread-safe)
        long u0 = System.nanoTime();
        int uploaded = 0;
        while (uploaded < uploadBudget) {
            MeshedChunk m = pipeline.pollReady();
            if (m == null) break;
            if (m.seed() != worldSeed) continue;
            if (!need.contains(m.pos())) continue;

            VoxelChunk c = m.chunk();
            float[] verts = m.verts();
            if (!worldEdits.isEmpty() && hasEditsInChunk(c)) {
                applyEditsToChunk(c);
                verts = terrainTextured ? VoxelMesher.meshTextured(c) : VoxelMesher.mesh(c);
            }

            GpuMesh old = chunkMeshes.put(m.pos(), terrainTextured ? new GpuMesh(verts, true) : new GpuMesh(verts));
            if (old != null) old.destroy();
            chunkData.put(m.pos(), c);
            uploaded++;
        }
        lastUploadedChunks = uploaded;
        lastUploadMs = (System.nanoTime() - u0) / 1_000_000f;

        // frustum culling + render
        Matrix4f proj = camera.projection(width, height);
        Matrix4f view = camera.view();
        FrustumIntersection frustum = new FrustumIntersection(new Matrix4f(proj).mul(view));

        shader.bind();
        shader.setProj(proj);
        shader.setView(view);
        shader.setModelIdentity(); // identity for all world chunks

        float radiusMul = fogAutoByRenderDistance
                ? Math.max(0.60f, Math.min(2.60f, 4.0f / Math.max(2f, radius)))
                : 1.0f;
        // M152: thick fog burst spikes density temporarily; cap raised to allow the effect
        // M152: DEAD_FOG adds a massive multiplier; cap is raised to allow near-blackout
        float deadFogBoost = (weatherState == WeatherState.DEAD_FOG && !underground) ? 14f : 0f; // M176
        float fogCap = (deadFogBoost > 0f) ? 0.28f : 0.06f;
        fogApplied = Math.max(0.00015f, Math.min(fogCap, fogDensity * fogUserMultiplier * radiusMul * (1f + thickFogBurst + deadFogBoost)));

        // M51: lightning briefly cranks ambient  - " entities visible at distance through fog
        float renderAmbient = ambient + lightningWorldFlash;
        // M188: nights grow progressively darker as horror increases (max -0.022 at full horror+full night)
        renderAmbient = Math.max(0f, renderAmbient - nightFactor * horrorProgression * 0.022f);
        shader.setLight(lightDir, renderAmbient, direct, fogApplied, camera.position());
        // Fog colour tracks actual sky colour; night gets only a tiny floor so the horizon stays
        // faintly readable but blocks no longer outshine the fog (old 0.18 floor caused a
        // camera-distance halo making nearby blocks look player-lit). M152 fix.
        float nf = nightFactor * 0.07f;
        shader.setFogColor(Math.max(clearR, nf), Math.max(clearG, nf * 1.05f), Math.max(clearB, nf * 1.20f));
        int lampCount = collectNearbyLamps(camera.position());
        lampCount = injectFireflyLamps(camera.position(), lampCount); // M48: fireflies cast real light
        if (heldTorchActive && lampCount < lampPos.length) {
            lampPos[lampCount].set(heldTorchPos);
            lampPower[lampCount] = 6.0f; // boosted: held torch is primary light source in dark caves
            lampCount++;
        }
        // M156: torch flicker â€” all lights briefly drop out for psychological dread
        if (torchFlickerActive) lampCount = 0;
        // M151: only update lamp model uniforms when preset changes
        if (lanternPreset != lastLanternPreset) {
            lastLanternPreset = lanternPreset;
            switch (lanternPreset) {
                case NORMAL        -> shader.setLampModel(2.00f, 0.10f, 0.015f);
                case STRONG        -> shader.setLampModel(2.60f, 0.07f, 0.010f);
                case HORROR_BRIGHT -> shader.setLampModel(3.20f, 0.05f, 0.006f);
            }
        }
        shader.setLamps(lampPos, lampPower, lampCount);

        if (terrainTextured && terrainTexId != 0) {
            shader.setUseTexture(true);
            glActiveTexture(GL_TEXTURE0);
            glBindTexture(GL_TEXTURE_2D, terrainTexId);
        } else {
            shader.setUseTexture(false);
        }

        int visible = 0;
        for (Map.Entry<ChunkPos, GpuMesh> e : chunkMeshes.entrySet()) {
            ChunkPos p = e.getKey();
            float minX = p.x() * Chunk.SIZE;
            float minZ = p.z() * Chunk.SIZE;
            float maxX = minX + Chunk.SIZE;
            float maxZ = minZ + Chunk.SIZE;

            if (!frustum.testAab(minX, -1f, minZ, maxX, VoxelChunk.SIZE_Y + 2f, maxZ)) continue;
            e.getValue().render();
            visible++;
        }

        if (terrainTextured && terrainTexId != 0) glBindTexture(GL_TEXTURE_2D, 0);
        shader.setUseTexture(false); // fallback tint path for entity/temp meshes
        // M122: prevent lamp overexposure on voxel entities (lurker/thing/figure).
        // Lamps are applied to terrain; entities keep directional+fog lighting only.
        shader.setLamps(lampPos, lampPower, 0);

        renderTargetOutline(camera.position());
        renderMiningCrack(camera.position());
        renderPlacedTorches(camera.position());
        renderCampfires(camera.position());        // M178: animated campfire geometry
        renderPortals(camera.position());          // M229+: animated liminal portal gate glow
        if (!paused) updateDroppedItems(skyDt);
        renderDroppedItems(camera.position());
        if (!paused) updatePigs(camera.position(), skyDt);
        renderPigs(camera.position());
        if (!paused) updateChickens(camera.position(), skyDt); // M192
        renderChickens(camera.position());                     // M192
        if (!paused) updateScreamers(camera.position(), skyDt); // M196
        renderScreamers(camera.position());                     // M196
        if (!paused) updateNuns(camera.position(), skyDt);      // M201
        renderNuns(camera.position());                          // M201

        if (!paused) updateFogWatchers(camera.position(), camera.forward());
        if (!paused) updateHallucinations(camera.position(), camera.forward(), skyDt); // M152

        // M229: zone-specific entity gating
        if (liminalZoneId == 1) {
            // Meadow: only fog watchers allowed â€” clear everything else
            screamers.clear(); nuns.clear(); hallucinations.clear();
        } else if (liminalZoneId == 2) {
            // Dark room: only NUN â€” clear fog watchers and hallucinations
            fogWatchers.clear(); hallucinations.clear();
        } else if (liminalZoneId == 0) {
            // Overworld: no NUNs (they only belong in zone 2)
            nuns.clear();
        }
        // M228: builder mode â€” flush ALL hostile entities every frame
        if (noctfield.core.GameApp.BUILDER_MODE) {
            screamers.clear(); nuns.clear(); fogWatchers.clear(); hallucinations.clear();
        }

        if (!paused) updatePsychEvents(camera.position(), camera.forward(), skyDt);    // M156
        if (!paused) updateThing(camera.position(), skyDt);
        if (!paused) updateFireflies(camera.position(), skyDt);
        if (!paused) updateWisps(camera.position(), camera.forward(), skyDt);
        if (!paused) updateRainDrops(camera.position(), skyDt);
        if (!paused) updateFigure(camera.position(), skyDt);
        if (!paused) updateSmoke(skyDt);      // M59: smoke particles from Figure hit
        if (!paused) updateLurker(camera.position(), skyDt); // M86: ceiling lurker
        if (!paused) updateDeep(camera.position(), skyDt);   // M166: THE DEEP
        renderFogWatchers(camera.position());
        if (!paused) renderHallucinations(camera.position()); // M152 â€” not on title screen
        renderThingVoxel(camera.position());  // M52: blocky voxel humanoid replaces OBJ
        renderFigure(camera.position());       // M54: morph entity
        renderSmoke(camera.position());       // M59: Figure smoke burst
        // M104 test: stalactite cone overlay render disabled for isolation.
        // renderStalactites(camera.position());
        renderLurker(camera.position());      // M86: ceiling lurker
        renderDeep(camera.position());        // M166: THE DEEP
        renderFireflies(camera.position());   // M45: after entities, before sky
        renderWisps(camera.position());       // M46: will-o'-wisps after fireflies
        if (!paused) renderRainDrops(camera.position()); // M51 â€” not on title screen
        renderSkyAtmosphere(camera.position());
        renderSkyBodies(camera.position(), camera.forward());
        // Restore world lighting state after emissive-style sky draw.
        shader.setLight(lightDir, ambient, direct, fogApplied, camera.position());

        renderVoidBeacon(camera.position()); // M169: purple sky beacon when relics complete
        shader.unbind();


        lastVisibleChunks = visible;
        lastRenderMs = (System.nanoTime() - t0) / 1_000_000f;
    }

    private static long blockKey(int wx, int wy, int wz) {
        long x = (wx & 0x1FFFFFL);
        long y = (wy & 0x3FFFL);
        long z = (wz & 0x1FFFFFL);
        return (x << 43) | (y << 29) | z;
    }

    private ChunkPos chunkPosForWorld(int wx, int wz) {
        return new ChunkPos(Math.floorDiv(wx, Chunk.SIZE), Math.floorDiv(wz, Chunk.SIZE));
    }

    private boolean hasEditsInChunk(VoxelChunk c) {
        int baseX = c.pos.x() * Chunk.SIZE;
        int baseZ = c.pos.z() * Chunk.SIZE;

        for (Map.Entry<Long, Byte> e : worldEdits.entrySet()) {
            long k = e.getKey();
            int wx = (int)((k >>> 43) & 0x1FFFFF); if (wx >= 0x100000) wx -= 0x200000;
            int wy = (int)((k >>> 29) & 0x3FFF);   if (wy >= 0x2000) wy -= 0x4000;
            int wz = (int)(k & 0x1FFFFF);          if (wz >= 0x100000) wz -= 0x200000;

            if (wy < 0 || wy >= VoxelChunk.SIZE_Y) continue;
            if (wx < baseX || wx >= baseX + Chunk.SIZE || wz < baseZ || wz >= baseZ + Chunk.SIZE) continue;
            return true;
        }
        return false;
    }

    private void applyEditsToChunk(VoxelChunk c) {
        int baseX = c.pos.x() * Chunk.SIZE;
        int baseZ = c.pos.z() * Chunk.SIZE;

        for (Map.Entry<Long, Byte> e : worldEdits.entrySet()) {
            long k = e.getKey();
            int wx = (int)((k >>> 43) & 0x1FFFFF); if (wx >= 0x100000) wx -= 0x200000;
            int wy = (int)((k >>> 29) & 0x3FFF);   if (wy >= 0x2000) wy -= 0x4000;
            int wz = (int)(k & 0x1FFFFF);          if (wz >= 0x100000) wz -= 0x200000;

            if (wy < 0 || wy >= VoxelChunk.SIZE_Y) continue;
            if (wx < baseX || wx >= baseX + Chunk.SIZE || wz < baseZ || wz >= baseZ + Chunk.SIZE) continue;

            c.set(wx - baseX, wy, wz - baseZ, e.getValue());
        }
    }

    private void remeshChunk(ChunkPos cp) {
        VoxelChunk c = chunkData.get(cp);
        if (c == null) return;
        float[] verts = terrainTextured ? VoxelMesher.meshTextured(c) : VoxelMesher.mesh(c);
        GpuMesh old = chunkMeshes.put(cp, terrainTextured ? new GpuMesh(verts, true) : new GpuMesh(verts));
        if (old != null) old.destroy();
    }

    private void invalidateNeighborsIfBorder(int wx, int wz, int lx, int lz) {
        if (lx == 0) remeshChunk(chunkPosForWorld(wx - 1, wz));
        if (lx == Chunk.SIZE - 1) remeshChunk(chunkPosForWorld(wx + 1, wz));
        if (lz == 0) remeshChunk(chunkPosForWorld(wx, wz - 1));
        if (lz == Chunk.SIZE - 1) remeshChunk(chunkPosForWorld(wx, wz + 1));
    }

    public boolean setBlock(int wx, int wy, int wz, byte id) {
        if (wy < 0 || wy >= VoxelChunk.SIZE_Y) return false;
        ChunkPos cp = chunkPosForWorld(wx, wz);
        VoxelChunk c = chunkData.get(cp);
        if (c == null) {
            c = ChunkGenerator.generate(cp, worldSeed);
            applyEditsToChunk(c);
            chunkData.put(cp, c);
        }

        int lx = Math.floorMod(wx, Chunk.SIZE);
        int lz = Math.floorMod(wz, Chunk.SIZE);
        c.set(lx, wy, lz, id);

        // Persist only deviations from deterministic base terrain.
        byte base = baseTerrainBlock(wx, wy, wz);
        long key = blockKey(wx, wy, wz);
        if (id == base) worldEdits.remove(key);
        else worldEdits.put(key, id);
        // M237: clear door facing metadata when a door block is removed
        if (id == BlockId.AIR) doorFacing.remove(key);

        remeshChunk(cp);
        invalidateNeighborsIfBorder(wx, wz, lx, lz);
        lampCacheDirty = true;  // M151: block changed  - " re-scan lamps next frame
        torchCacheDirty = true; // M151: block changed  - " rebuild torch/door geometry next frame
        return true;
    }

    /** M237: store player-facing orientation for a door block (0=Z-axis, 1=X-axis). */
    public void setDoorFacing(int wx, int wy, int wz, byte f) {
        doorFacing.put(blockKey(wx, wy, wz), f);
    }

    private byte baseTerrainBlock(int wx, int wy, int wz) {
        return ChunkGenerator.baseBlockAt(wx, wy, wz, worldSeed);
    }

    public int editCount() {
        return worldEdits.size();
    }

    public void setTargetOutline(boolean active, int wx, int wy, int wz) {
        targetOutlineActive = active;
        targetOutlineX = wx; targetOutlineY = wy; targetOutlineZ = wz;
    }

    public void setMiningCrack(boolean active, int wx, int wy, int wz, float progress) {
        miningCrackActive = active;
        if (!active) return;
        miningCrackX = wx;
        miningCrackY = wy;
        miningCrackZ = wz;
        miningCrackProgress = Math.max(0f, Math.min(1f, progress));
    }

    public void saveEditsToDisk() {
        ArrayList<String> lines = new ArrayList<>(worldEdits.size() + doorFacing.size());
        for (Map.Entry<Long, Byte> e : worldEdits.entrySet()) {
            long k = e.getKey();
            int wx = (int)((k >>> 43) & 0x1FFFFF); if (wx >= 0x100000) wx -= 0x200000;
            int wy = (int)((k >>> 29) & 0x3FFF);   if (wy >= 0x2000) wy -= 0x4000;
            int wz = (int)(k & 0x1FFFFF);          if (wz >= 0x100000) wz -= 0x200000;
            lines.add(wx + " " + wy + " " + wz + " " + e.getValue());
        }
        // M237: persist door facing metadata
        for (Map.Entry<Long, Byte> e : doorFacing.entrySet()) {
            long k = e.getKey();
            int wx = (int)((k >>> 43) & 0x1FFFFF); if (wx >= 0x100000) wx -= 0x200000;
            int wy = (int)((k >>> 29) & 0x3FFF);   if (wy >= 0x2000) wy -= 0x4000;
            int wz = (int)(k & 0x1FFFFF);          if (wz >= 0x100000) wz -= 0x200000;
            lines.add("DF " + wx + " " + wy + " " + wz + " " + e.getValue());
        }
        try {
            Files.createDirectories(editsFile.getParent());
            Files.write(editsFile, lines, StandardCharsets.UTF_8);
        } catch (IOException ex) {
            System.err.println("[Renderer] Failed saving edits: " + ex.getMessage());
        }
    }

    public void loadEditsFromDisk() {
        worldEdits.clear();
        doorFacing.clear(); // M237: also clear door facing on world load
        if (!Files.exists(editsFile)) return;
        try {
            for (String line : Files.readAllLines(editsFile, StandardCharsets.UTF_8)) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                String[] p = line.split("\\s+");
                // M237: "DF x y z f" lines store door facing
                if (p.length == 5 && "DF".equals(p[0])) {
                    int wx = Integer.parseInt(p[1]);
                    int wy = Integer.parseInt(p[2]);
                    int wz = Integer.parseInt(p[3]);
                    byte f = Byte.parseByte(p[4]);
                    doorFacing.put(blockKey(wx, wy, wz), f);
                    continue;
                }
                if (p.length < 4) continue;
                int wx = Integer.parseInt(p[0]);
                int wy = Integer.parseInt(p[1]);
                int wz = Integer.parseInt(p[2]);
                byte id = (byte) Integer.parseInt(p[3]);
                worldEdits.put(blockKey(wx, wy, wz), id);
            }
        } catch (Exception ex) {
            System.err.println("[Renderer] Failed loading edits: " + ex.getMessage());
        }

        // Re-apply edits to loaded chunks and remesh.
        for (Map.Entry<ChunkPos, VoxelChunk> e : chunkData.entrySet()) {
            applyEditsToChunk(e.getValue());
            remeshChunk(e.getKey());
        }
    }

    public void adjustRadius(int delta) { radius = Math.max(2, Math.min(10, radius + delta)); }
    public void setRadius(int v) { radius = Math.max(2, Math.min(10, v)); }
    public void adjustRequestBudget(int delta) { requestBudget = Math.max(1, Math.min(24, requestBudget + delta)); }
    public void setRequestBudget(int v) { requestBudget = Math.max(1, Math.min(24, v)); }
    public void adjustUploadBudget(int delta) { uploadBudget = Math.max(1, Math.min(16, uploadBudget + delta)); }
    public void setUploadBudget(int v) { uploadBudget = Math.max(1, Math.min(16, v)); }

    public boolean hasBlockNear(Vector3f p, float radius, byte blockId) {
        float r2 = radius * radius;
        for (Map.Entry<ChunkPos, VoxelChunk> e : chunkData.entrySet()) {
            ChunkPos cp = e.getKey();
            VoxelChunk c = e.getValue();
            int baseX = cp.x() * Chunk.SIZE;
            int baseZ = cp.z() * Chunk.SIZE;

            for (int y = 0; y < VoxelChunk.SIZE_Y; y++) {
                for (int z = 0; z < Chunk.SIZE; z++) {
                    for (int x = 0; x < Chunk.SIZE; x++) {
                        if (c.get(x, y, z) != blockId) continue;
                        float dx = (baseX + x + 0.5f) - p.x;
                        float dy = (y + 0.5f) - p.y;
                        float dz = (baseZ + z + 0.5f) - p.z;
                        float d2 = dx*dx + dy*dy + dz*dz;
                        if (d2 <= r2) return true;
                    }
                }
            }
        }
        return false;
    }

    public boolean hasLanternNear(Vector3f p, float radius) {
        float r2 = radius * radius;
        for (Map.Entry<ChunkPos, VoxelChunk> e : chunkData.entrySet()) {
            ChunkPos cp = e.getKey();
            VoxelChunk c = e.getValue();
            int baseX = cp.x() * Chunk.SIZE;
            int baseZ = cp.z() * Chunk.SIZE;

            for (int y = 0; y < VoxelChunk.SIZE_Y; y++) {
                for (int z = 0; z < Chunk.SIZE; z++) {
                    for (int x = 0; x < Chunk.SIZE; x++) {
                        if (c.get(x, y, z) != BlockId.LANTERN) continue;
                        float dx = (baseX + x + 0.5f) - p.x;
                        float dy = (y + 0.5f) - p.y;
                        float dz = (baseZ + z + 0.5f) - p.z;
                        float d2 = dx*dx + dy*dy + dz*dz;
                        if (d2 <= r2) return true;
                    }
                }
            }
        }
        return false;
    }

    public byte getBlock(int wx, int wy, int wz) {
        Byte edited = worldEdits.get(blockKey(wx, wy, wz));
        if (edited != null) return edited;

        int cx = Math.floorDiv(wx, Chunk.SIZE);
        int cz = Math.floorDiv(wz, Chunk.SIZE);
        int lx = Math.floorMod(wx, Chunk.SIZE);
        int lz = Math.floorMod(wz, Chunk.SIZE);

        VoxelChunk c = chunkData.get(new ChunkPos(cx, cz));
        if (c == null) {
            // deterministic fallback for not-yet-streamed chunks
            return baseTerrainBlock(wx, wy, wz);
        }
        return c.get(lx, wy, lz);
    }

    /** M182: Returns true if this world position has an explicit player-placed edit (e.g. relic collected = AIR). */
    public boolean isBlockEdited(int wx, int wy, int wz) {
        return worldEdits.containsKey(blockKey(wx, wy, wz));
    }

    public org.joml.Vector3f findNearestRelic(org.joml.Vector3f camPos) {
        org.joml.Vector3f nearest = null;
        float nearD2 = Float.MAX_VALUE;
        for (java.util.Map.Entry<noctfield.world.ChunkPos, noctfield.world.VoxelChunk> e : chunkData.entrySet()) {
            noctfield.world.ChunkPos cp = e.getKey();
            noctfield.world.VoxelChunk vc = e.getValue();
            int bx = cp.x() * noctfield.world.Chunk.SIZE;
            int bz = cp.z() * noctfield.world.Chunk.SIZE;
            for (int y = 0; y < noctfield.world.VoxelChunk.SIZE_Y; y++)
                for (int z = 0; z < noctfield.world.Chunk.SIZE; z++)
                    for (int x = 0; x < noctfield.world.Chunk.SIZE; x++)
                        if (vc.get(x, y, z) == noctfield.world.BlockId.RELIC) {
                            float rx = bx + x + 0.5f, rz = bz + z + 0.5f;
                            float d2 = (camPos.x-rx)*(camPos.x-rx)+(camPos.z-rz)*(camPos.z-rz);
                            if (d2 < nearD2) { nearD2 = d2; nearest = new org.joml.Vector3f(rx, y + 0.5f, rz); }
                        }
        }
        return nearest;
    }

    private int collectNearbyLamps(Vector3f camPos) {
        // M234: flat mode — still inject close LANTERN lamps (within 10 blocks) so held/placed lanterns work
        if (!dynamicLighting) {
            int cnt = 0;
            for (java.util.Map.Entry<ChunkPos, VoxelChunk> fe : chunkData.entrySet()) {
                VoxelChunk fc = fe.getValue();
                int fbaseX = fe.getKey().x() * Chunk.SIZE;
                int fbaseZ = fe.getKey().z() * Chunk.SIZE;
                for (int fy = 0; fy < VoxelChunk.SIZE_Y && cnt < lampPos.length; fy++) {
                    for (int fz = 0; fz < Chunk.SIZE; fz++) {
                        for (int fx = 0; fx < Chunk.SIZE; fx++) {
                            if (fc.get(fx, fy, fz) != BlockId.LANTERN) continue;
                            float lx = fbaseX + fx + 0.5f, ly = fy + 0.5f, lz2 = fbaseZ + fz + 0.5f;
                            float ldx = lx - camPos.x, ldy = ly - camPos.y, ldz = lz2 - camPos.z;
                            if (ldx*ldx + ldy*ldy + ldz*ldz > 100f) continue; // 10-block radius
                            lampPos[cnt].set(lx, ly, lz2);
                            lampPower[cnt++] = 3.8f;
                        }
                    }
                }
            }
            return cnt;
        }
        // M152 fix: if chunks have loaded since last scan (async world gen), force a rescan so
        // lamps in newly-loaded terrain don't stay invisible until the player moves 1.5 blocks.
        if (chunkData.size() != lampCacheChunkSize) {
            lampCacheDirty = true;
        }
        // M151: skip full chunk scan if player hasn't moved and no blocks changed
        if (!lampCacheDirty) {
            float dx = camPos.x - lampCacheLastX, dz = camPos.z - lampCacheLastZ;
            if (dx * dx + dz * dz < 2.25f) return lampCacheCount; // < 1.5 block movement
        }
        lampCacheDirty      = false;
        lampCacheChunkSize  = chunkData.size(); // M152 fix: snapshot current chunk count
        lampCacheLastX = camPos.x;
        lampCacheLastZ = camPos.z;

        int count = 0;

        for (Map.Entry<ChunkPos, VoxelChunk> e : chunkData.entrySet()) {
            ChunkPos cp = e.getKey();
            VoxelChunk c = e.getValue();
            int baseX = cp.x() * Chunk.SIZE;
            int baseZ = cp.z() * Chunk.SIZE;

            for (int y = 0; y < VoxelChunk.SIZE_Y; y++) {
                for (int z = 0; z < Chunk.SIZE; z++) {
                    for (int x = 0; x < Chunk.SIZE; x++) {
                        byte btype = c.get(x, y, z);
                        if (btype != BlockId.LANTERN && btype != BlockId.RELIC && btype != BlockId.CAMPFIRE
                                && btype != BlockId.FUNGUS && btype != BlockId.CRYSTAL
                                && btype != BlockId.TORCH_STAND && btype != BlockId.TORCH_WALL
                                && btype != BlockId.LIMINAL_PORTAL) continue; // M229+: portal tiles emit light

                        float wx = baseX + x + 0.5f;
                        float wy = y + 0.5f;
                        float wz = baseZ + z + 0.5f;
                        float dx = wx - camPos.x;
                        float dy = wy - camPos.y;
                        float dz = wz - camPos.z;
                        float d2 = dx*dx + dy*dy + dz*dz;
                        if (d2 > 140f * 140f) continue; // M116: keep distant lanterns participating
                        // RELIC dim amber, LANTERN full-bright, CAMPFIRE flickering warm, FUNGUS dim blue-green pulse
                        float power;
                        if (btype == BlockId.RELIC) {
                            power = 1.6f;
                        } else if (btype == BlockId.CAMPFIRE) {
                            float flicker = (float)Math.sin(entityAnimTime * 8.7f + wx * 2.3f + wz * 1.7f) * 0.8f;
                            power = 3.0f + flicker;
                        } else if (btype == BlockId.FUNGUS) {
                            float pulse = (float)Math.sin(entityAnimTime * 1.3f + wx * 0.7f + wz * 0.9f) * 0.25f;
                            power = 0.75f + pulse;
                        } else if (btype == BlockId.CRYSTAL) {
                            float shimmer = (float)Math.sin(entityAnimTime * 3.1f + wx * 1.4f + wz * 0.8f) * 0.08f;
                            power = 0.55f + shimmer;
                        } else if (btype == BlockId.TORCH_STAND || btype == BlockId.TORCH_WALL) {
                            power = 2.6f;
                        } else if (btype == BlockId.LIMINAL_PORTAL) {
                            float pulse = (float)Math.sin(entityAnimTime * 2.4f + wx * 0.7f + wz * 0.5f) * 0.4f;
                            power = 3.5f + pulse; // M229+: portal tiles glow cyan, pulses gently
                        } else {
                            power = 3.8f; // LANTERN baseline (preset multiplier applied in shader)
                        }

                        // Reserve the last 8 slots for dynamic lights (fireflies, held torch).
                        int staticCap = lampPos.length - 8;
                        if (count < staticCap) {
                            lampPos[count].set(wx, wy, wz);
                            lampPower[count] = power;
                            count++;
                        } else {
                            // Replace the farthest existing lamp with closer candidates.
                            int farI = 0;
                            float farD2 = -1f;
                            for (int li = 0; li < lampPos.length; li++) {
                                float ldx = lampPos[li].x - camPos.x;
                                float ldy = lampPos[li].y - camPos.y;
                                float ldz = lampPos[li].z - camPos.z;
                                float ld2 = ldx*ldx + ldy*ldy + ldz*ldz;
                                if (ld2 > farD2) { farD2 = ld2; farI = li; }
                            }
                            if (d2 < farD2) {
                                lampPos[farI].set(wx, wy, wz);
                                lampPower[farI] = power;
                            }
                        }
                    }
                }
            }
        }
        lampCacheCount = count; // M151: save for cache
        return count;
    }

    // M48: inject nearby fireflies as point lights so they actually illuminate the world
    private int injectFireflyLamps(Vector3f cam, int count) {
        for (int i = 0; i < fireflyCount && count < lampPos.length; i++) {
            Vector3f p  = fireflyPos[i];
            float    dx = p.x - cam.x, dy = p.y - cam.y, dz = p.z - cam.z;
            float    d2 = dx * dx + dy * dy + dz * dz;
            if (d2 > 18f * 18f) continue; // only fireflies close enough to matter
            // Match the render flicker so visual and lighting pulse together
            float flicker = 0.65f + 0.35f * (float)Math.sin(entityAnimTime * 11f + fireflyPhase[i]);
            lampPos[count].set(p);
            lampPower[count] = 0.55f * flicker; // M234: softer pulse — warm glow not a spotlight
            count++;
        }
        return count;
    }

    public void setSanity01(float s) { sanity01 = Math.max(0f, Math.min(1f, s)); }
    // M152
    public void  setHorrorProgression(float v) { horrorProgression = Math.max(0f, Math.min(1f, v)); }
    public float horrorProgression()            { return horrorProgression; }
    public boolean bloodRainActive()            { return bloodRainMode; }
    public boolean consumeThickFogEvent()       { boolean v = thickFogEventReady; thickFogEventReady = false; return v; }
    public void setBiomeAggroMultiplier(float v) { biomeAggroMul = Math.max(0.7f, Math.min(1.4f, v)); }

    public void setBiomeVisual(int biome) {
        if (biome == ChunkGenerator.BIOME_PINE) setBiomeVisualWeights(1f, 0f, 0f);
        else if (biome == ChunkGenerator.BIOME_DEAD) setBiomeVisualWeights(0f, 1f, 0f);
        else if (biome == ChunkGenerator.BIOME_SWAMP) setBiomeVisualWeights(0f, 0f, 1f);
    }

    public void setBiomeVisualWeights(float pineW, float deadW, float swampW) {
        float sum = pineW + deadW + swampW;
        if (sum <= 0.0001f) {
            pineW = 0.34f; deadW = 0.33f; swampW = 0.33f;
            sum = 1f;
        }
        pineW /= sum; deadW /= sum; swampW /= sum;
        biomePineW = pineW; biomeDeadW = deadW; biomeSwampW = swampW;

        biomeBaseClearR = pineW * 0.030f + deadW * 0.048f + swampW * 0.024f;
        biomeBaseClearG = pineW * 0.048f + deadW * 0.040f + swampW * 0.040f;
        biomeBaseClearB = pineW * 0.060f + deadW * 0.046f + swampW * 0.036f;
        biomeBaseFog = Math.max(0.0013f, Math.min(0.0045f, pineW * 0.0017f + deadW * 0.0020f + swampW * 0.0025f));
    }

    private void updateSkyLighting(float dt) {
        if (skyMode == SkyMode.CYCLE) {
            timeOfDay01 += dt / Math.max(60f, dayLengthSeconds);
            if (timeOfDay01 > 1f) timeOfDay01 -= 1f;
            cloudDrift += dt * 0.015f;

            float day = 0.5f + 0.5f * (float) Math.sin((timeOfDay01 - 0.25f) * Math.PI * 2.0);
            day = Math.max(0f, Math.min(1f, day));
            // Dusk should only affect true sunrise/sunset windows, not deep night.
            float dusk = 1f - Math.min(1f, Math.abs(day - 0.5f) / 0.20f);
            nightFactor = 1f - day;
            nightMode = nightFactor > 0.62f;

            // Conservative ranges to avoid over-bright daytime and preserve mood.
            // M152 fix: old 0.10 floor kept night too bright, blocks outshone fog -> fake player halo
            // M153: ambient/direct final values (upstream win over stash)
            ambient = Math.max(0.03f, Math.min(0.32f, 0.04f + day * 0.28f));
            direct  = Math.max(0.05f, Math.min(0.95f, 0.06f + day * 0.89f));

            // Time-of-day sky tint: make transition unmistakable.
            float dayR = 0.45f, dayG = 0.68f, dayB = 0.95f;
            float duskR = 0.90f, duskG = 0.45f, duskB = 0.25f;
            float nightR = 0.01f, nightG = 0.015f, nightB = 0.035f;

            float skyR = nightR * nightFactor + dayR * day;
            float skyG = nightG * nightFactor + dayG * day;
            float skyB = nightB * nightFactor + dayB * day;
            skyR = skyR * (1f - dusk * 0.55f) + duskR * (dusk * 0.55f);
            skyG = skyG * (1f - dusk * 0.55f) + duskG * (dusk * 0.55f);
            skyB = skyB * (1f - dusk * 0.55f) + duskB * (dusk * 0.55f);

            // Pure time-of-day palette (no biome tint / no night clamp blending).
            clearR = skyR;
            clearG = skyG;
            clearB = skyB;

            float fogNight = Math.max(0.0015f, Math.min(0.0055f, biomeBaseFog + 0.0012f));
            float fogDay = Math.max(0.0009f, Math.min(0.0038f, biomeBaseFog * 0.92f));
            fogDensity = fogNight * nightFactor + fogDay * (1f - nightFactor);

            float sunAngle = (timeOfDay01 - 0.25f) * (float) (Math.PI * 2.0);
            float sunEl = (float) Math.sin(sunAngle) * 1.05f;
            float sunAz = sunAngle * 0.55f;

            float lx = (float) Math.cos(sunAz) * (float) Math.cos(sunEl);
            float ly = (float) Math.sin(sunEl);
            float lz = (float) Math.sin(sunAz) * (float) Math.cos(sunEl);

            // If sun is below horizon, use moon (opposite direction) as key light.
            if (ly < 0f) {
                lx = -lx;
                ly = -ly;
                lz = -lz;
            }
            skyLightDir.set(-lx, -Math.max(0.15f, ly), -lz).normalize();
            lightDir.set(skyLightDir);
        } else {
            // Static night fallback mode (safe rollback)
            nightFactor = 1f;
            nightMode = true;
            ambient = 0.04f; // M152 fix: was 0.10, made blocks glow near camera
            direct  = 0.08f;
            fogDensity = 0.0036f;

            // Force genuinely dark night sky regardless of biome base tint.
            clearR = 0.006f;
            clearG = 0.010f;
            clearB = 0.020f;

            lightDir.set(-0.2f, -1.0f, -0.05f).normalize();
            skyLightDir.set(lightDir);
        }

        // M37/M128: underground ambient + direct dimming (smooth blend)
        // ambient: drive offset to -2.0 so it FULLY kills ambient regardless of time-of-day
        // (old -0.14 only cancelled night ambient; daytime underground still had 0.38 ambient).
        // direct: kill the sun entirely underground (that's the blowout source)
        float ugAmbTarget = underground ? -2.0f : 0f;
        float ugDirectTarget = underground ? 0f : 1f;
        undergroundAmbientOffset += (ugAmbTarget - undergroundAmbientOffset) * Math.min(1f, dt * 2.5f);
        undergroundDirectMul    += (ugDirectTarget - undergroundDirectMul)    * Math.min(1f, dt * 2.5f);
        ambient = Math.max(0f, ambient + undergroundAmbientOffset); // M135: no floor  - " caves are truly dark
        direct  = direct * undergroundDirectMul;

        // M225: liminal zone â€” override to flat fluorescent palette (pale yellow-white, no shadows)
        if (liminalZoneMode) {
            ambient    = 0.72f;
            direct     = 0.05f;
            fogDensity = 0.0006f;
            clearR     = 0.90f;
            clearG     = 0.86f;
            clearB     = 0.72f;
            nightFactor = 0f;
            nightMode   = false;
        }

        // M229: zone-specific overrides
        if (liminalZoneId == 1) {
            // Zone 1 - Meadow: permanent bright noon, no fog, blue sky
            ambient     = 0.92f;
            direct      = 1.60f;
            fogDensity  = 0.0001f;
            clearR      = 0.40f;
            clearG      = 0.65f;
            clearB      = 0.95f;
            nightFactor = 0f;
            nightMode   = false;
            timeOfDay01 = 0.5f;
        } else if (liminalZoneId == 2) {
            // M233: Zone 2 - Holloway Manor: dim warm amber night — torch-lit feel, dark areas in unlighted rooms
            ambient     = 0.28f;        // dim but visible; simulates diffuse torchlight fill
            direct      = 0.18f;        // slight directional gives depth/shadow on plank faces
            fogDensity  = 0.010f;       // light indoor haze
            clearR      = 0.08f;
            clearG      = 0.04f;
            clearB      = 0.01f;        // near-black warm amber sky (distant firelight tint)
            nightFactor = 1f;
            nightMode   = true;
            timeOfDay01 = 0.0f;
        }

        // M228: builder mode â€” frozen clear noon
        if (noctfield.core.GameApp.BUILDER_MODE) {
            ambient = 0.88f; direct = 1.40f; fogDensity = 0.0002f;
            clearR = 0.40f; clearG = 0.62f; clearB = 0.92f;
            nightFactor = 0f; nightMode = false; timeOfDay01 = 0.5f;
        }

        // M39 / M152: weather state machine (CLEAR / RAIN / DEAD_FOG) â€” skip in any liminal zone or builder
        if (deadFogCooldown > 0f) deadFogCooldown -= dt;
        weatherTimer -= dt;
        if (weatherTimer <= 0f && !underground && liminalZoneId == 0 && !noctfield.core.GameApp.BUILDER_MODE) { // M176/M228/M229
            if (weatherState == WeatherState.CLEAR) {
                // M152: DEAD_FOG has priority at high horror when cooldown is done
                boolean tryDeadFog = horrorProgression > 0.60f && deadFogCooldown <= 0f
                        && fogRng.nextFloat() < (horrorProgression - 0.60f) * 1.8f;
                if (tryDeadFog) {
                    weatherState      = WeatherState.DEAD_FOG;
                    weatherTimer      = 30f + fogRng.nextFloat() * 30f; // 30-60s blackout
                    deadFogCooldown   = 180f + fogRng.nextFloat() * 180f; // 3-6 min cooldown
                    deadFogEventReady = true;
                } else {
                    float rainChance = 0.28f + nightFactor * 0.22f;
                    if (fogRng.nextFloat() < rainChance) {
                        weatherState  = WeatherState.RAIN;
                        weatherTimer  = 30f + fogRng.nextFloat() * 60f;
                        lightningTimer = 8f + fogRng.nextFloat() * 16f;
                        // M152: blood rain scales with horror; starts at 30%, peaks at 100%
                        bloodRainMode = horrorProgression > 0.30f
                                && fogRng.nextFloat() < (horrorProgression - 0.30f) * 1.4f;
                    } else {
                        weatherTimer = 25f + fogRng.nextFloat() * 50f;
                    }
                }
            } else if (weatherState == WeatherState.DEAD_FOG) {
                // DEAD_FOG ends â€” return to CLEAR
                weatherState = WeatherState.CLEAR;
                weatherTimer = 50f + fogRng.nextFloat() * 60f;
            } else {
                weatherState  = WeatherState.CLEAR;
                weatherTimer  = 40f + fogRng.nextFloat() * 60f;
                bloodRainMode = false; // M152: clear blood rain when rain stops
            }
        }
        // M152: blood rain sky tint â€” bleed red into clear colour during rain
        if (bloodRainMode && weatherState == WeatherState.RAIN) {
            float pulse = 0.06f + 0.05f * (float)Math.sin(entityAnimTime * 1.8f);
            clearR = Math.min(1f, clearR + pulse * 1.4f);
            clearG = Math.max(0f, clearG - pulse * 0.5f);
            clearB = Math.max(0f, clearB - pulse * 0.7f);
        }
        // M152: DEAD_FOG sky â€” the world drains to near-black with a sickly purple cast
        if (weatherState == WeatherState.DEAD_FOG) {
            float crush = 0.07f + 0.03f * (float)Math.sin(entityAnimTime * 0.7f); // slow dark pulse
            clearR = Math.max(0.02f, clearR * (1f - crush) + 0.025f * crush);
            clearG = Math.max(0.01f, clearG * (1f - crush) + 0.010f * crush);
            clearB = Math.max(0.04f, clearB * (1f - crush) + 0.040f * crush);
        }
        // M152: periodic thick fog events once horror reaches 30%
        if (horrorProgression > 0.3f && !underground) {
            thickFogEventTimer -= dt;
            if (thickFogEventTimer <= 0f) {
                thickFogBurst      = 3.0f + fogRng.nextFloat() * 2.0f;
                thickFogEventTimer = 60f  + fogRng.nextFloat() * 90f;
                thickFogEventReady = true;
            }
        }
        thickFogBurst = Math.max(0f, thickFogBurst - dt * 0.08f); // decays over ~12-37s

        // During rain: thicken fog, desaturate sky -- surface only (M176)
        if (weatherState == WeatherState.RAIN && !underground) {
            fogDensity = Math.min(fogDensity * 1.0f + 0.0006f * dt, fogDensity + 0.0018f);
            lightningTimer -= dt;
            if (lightningTimer <= 0f) {
                lightningFlashTimer   = 0.12f;
                lightningFlashReady   = true;
                lightningWorldFlash   = 1.8f; // M51: briefly illuminate the 3D world
                lightningTimer        = 10f + fogRng.nextFloat() * 20f;
            }
        }
        if (lightningFlashTimer > 0f) lightningFlashTimer -= dt;

        // M51: lightning world flash fades quickly; wind gusts during rain, calms in clear
        lightningWorldFlash = Math.max(0f, lightningWorldFlash - 20f * dt);
        float tWindX = weatherState == WeatherState.RAIN ? (float)Math.sin(entityAnimTime * 0.31f) * 2.4f : 0f;
        float tWindZ = weatherState == WeatherState.RAIN ? (float)Math.cos(entityAnimTime * 0.25f) * 2.4f : 0f;
        windX += (tWindX - windX) * Math.min(1f, dt * 0.7f);
        windZ += (tWindZ - windZ) * Math.min(1f, dt * 0.7f);
    }

    // ---- M36 ----------------------------------------------------------------
    public void setRelicLevel(int n) { relicLevel = Math.max(0, n); }
    public int  relicLevel()         { return relicLevel; }

    // M176: block-scanning ground query â€” works correctly in caves AND on surface
    private float entityGroundY(float ex, float ez, float nearY) {
        int gx = (int)Math.floor(ex);
        int gz = (int)Math.floor(ez);
        int scanTop = Math.min((int)Math.floor(nearY) + 4, 127);
        for (int gy = scanTop; gy >= 0; gy--) {
            byte b = getBlock(gx, gy, gz);
            if (BlockId.isSolid(b)) return gy + 1f;
        }
        return ChunkGenerator.heightAt(gx, gz, worldSeed) + 1f; // fallback
    }

        // ---- M37 ----------------------------------------------------------------
    public void setUnderground(boolean v) { underground = v; }
    public boolean isUnderground()        { return underground; }

    // ---- M39 ----------------------------------------------------------------
    public boolean isRaining()      { return weatherState == WeatherState.RAIN; }
    public boolean isDeadFogActive(){ return weatherState == WeatherState.DEAD_FOG; }
    /** Returns true (once) when a DEAD_FOG event begins â€” for GameApp to show a message. */
    public boolean consumeDeadFogEvent() { if (!deadFogEventReady) return false; deadFogEventReady = false; return true; }

    /** Returns true once after a lightning strike is triggered, then resets. */
    public boolean consumeLightningFlash() {
        if (!lightningFlashReady) return false;
        lightningFlashReady = false;
        return true;
    }

    private void leaveWatcherTrace(Vector3f w) {
        watcherTraces.add(new WatcherTrace(w.x, w.y + 0.03f, w.z, 18f));
        if (watcherTraces.size() > 48) watcherTraces.remove(0);
    }

    /** Debug: spawn THE THING directly in front of the player at the given distance. */
    public void debugSpawnThingInFront(Vector3f camPos, Vector3f camForward, float dist) {
        if (!thingMeshLoaded) loadThingMesh();
        Vector3f f = new Vector3f(camForward).normalize();
        thingPos.x = camPos.x + f.x * dist;
        thingPos.z = camPos.z + f.z * dist;
        thingPos.y = ChunkGenerator.heightAt((int)Math.floor(thingPos.x), (int)Math.floor(thingPos.z), worldSeed) + 1;
        // Face back toward player on spawn
        thingFacing      = (float)Math.atan2(camPos.x - thingPos.x, camPos.z - thingPos.z);
        thingRetreating  = false;
        thingActive      = true;
        thingSpawnTimer  = 60f; // don't auto-spawn a second one immediately
        thingDebugTimer  = 60f; // bypass night-only gate so it works in daylight
        thingRenderFrames= 0;
        System.out.printf("[Debug] THE THING spawned at (%.1f, %.1f, %.1f)%n", thingPos.x, thingPos.y, thingPos.z);
    }

    public void debugSpawnWatcherInFront(Vector3f camPos, Vector3f camForward, float dist) {
        Vector3f f = new Vector3f(camForward).normalize();
        float wx = camPos.x + f.x * dist;
        float wz = camPos.z + f.z * dist;
        int y = ChunkGenerator.heightAt((int)Math.floor(wx), (int)Math.floor(wz), worldSeed) + 1;

        fogWatchers.clear();
        fogWatchers.add(new Vector3f(wx, y, wz));
        watcherVisibleTimer = 8f;
        watcherLookAccum = 0f;
        watcherDebugForceTimer = 8f;
        watcherState = "DEBUG_PEEK";
        watcherEvent = "DEBUG";
    }

    /** Debug: force-spawn The Figure in front of the player.
     *  Starts in NOTICE state so the morph behaviour kicks off immediately. */
    /** Debug: immediately spawn the Ceiling Lurker in CRAWLING state near the player. */
    public void debugSpawnLurkerNow(Vector3f camPos) {
        float offX = (fogRng.nextFloat() - 0.5f) * 8f;
        float offZ = (fogRng.nextFloat() - 0.5f) * 8f;
        lurkerLandY = camPos.y - 1.62f + 0.5f;
        lurkerCeilY = lurkerLandY + 5f + fogRng.nextFloat() * 3f;
        lurkerPos.set(camPos.x + offX, lurkerLandY + 0.5f, camPos.z + offZ);
        lurkerState = LurkerState.CRAWLING;
        lurkerStateTimer = 3.0f;
        lurkerFacing = (float)Math.atan2(-offX, -offZ);
        lurkerActive = true;
        lurkerHitPlayer = false;
        System.out.println("[Debug] Lurker spawned in CRAWLING state at (" +
                String.format("%.1f, %.1f, %.1f", lurkerPos.x, lurkerPos.y, lurkerPos.z) + ")");
    }

    public void debugSpawnFigureInFront(Vector3f camPos, Vector3f camForward, float dist) {
        Vector3f f = new Vector3f(camForward).normalize();
        float fx = camPos.x + f.x * dist;
        float fz = camPos.z + f.z * dist;
        float fy = ChunkGenerator.heightAt((int)Math.floor(fx), (int)Math.floor(fz), worldSeed) + 0.5f;
        figurePos.set(fx, fy, fz);
        figureTarget.set(fx, fy, fz);
        figureFacing     = (float)Math.atan2(camPos.x - fx, camPos.z - fz);
        figureState      = FigureState.NOTICE;
        figureMorphT     = 0f;
        figureStateTimer = 2.0f;
        figureSpawnTimer = 60f;
        figureActive     = true;
        System.out.printf("[Debug] The Figure spawned at (%.1f, %.1f, %.1f)%n", fx, fy, fz);
    }

    public boolean figureActive()    { return figureActive; }
    public String  figureStateName() { return figureActive ? figureState.name() : "INACTIVE"; }
    public float   figureMorphT()    { return figureMorphT; }

    private void recordWatcherEvent(String e) {
        watcherEvent = e;
        watcherHistory.addLast(e);
        while (watcherHistory.size() > 3) watcherHistory.removeFirst();

        // M36: convergence â€” at relic level 3+ a second sentinel appears during certain events
        if (e.contains("CHARGE") && relicLevel >= 3 && fogWatchers.size() < 2) {
            float angle = (float)(fogRng.nextDouble() * Math.PI * 2.0);
            spawnWatcherAtAngle(lastCamPos, angle, 10f, 20f);
            watcherHistory.addLast("CONVERGENCE");
        }
    }

    private String pickWatcherEvent() {
        float t = Math.min(1f, watcherNightElapsed / 90f);
        float r = fogRng.nextFloat();

        // Biome tactics:
        // Pine: more false-safe / slower pressure.
        // Dead: balanced but prefers double peeks.
        // Swamp: more flank pressure.
        float doubleBias = 0.22f + t * 0.18f + biomeDeadW * 0.10f;
        float flankBias = 0.24f + t * 0.22f + biomeSwampW * 0.14f;
        float syncBias = 0.22f + t * 0.18f + biomeDeadW * 0.04f;
        float falseSafeBias = 0.20f + biomePineW * 0.16f;

        float sum = doubleBias + flankBias + syncBias + falseSafeBias;
        doubleBias /= sum;
        flankBias /= sum;
        syncBias /= sum;

        if (r < doubleBias) return "DOUBLE_PEEK";
        if (r < doubleBias + flankBias) return "FLANK_PEEK";
        if (r < doubleBias + flankBias + syncBias) return "SYNC_VANISH";
        return "FALSE_SAFE";
    }

    private void spawnWatcherAtAngle(Vector3f cam, float angle, float minR, float maxR) {
        float r = minR + fogRng.nextFloat() * (maxR - minR);
        float wx = cam.x + (float)Math.cos(angle) * r;
        float wz = cam.z + (float)Math.sin(angle) * r;
        int y = ChunkGenerator.heightAt((int)Math.floor(wx), (int)Math.floor(wz), worldSeed) + 1;
        fogWatchers.add(new Vector3f(wx, y, wz));
    }

    public void forceNextWatcherEventNow() {
        watcherNextEventIn = 0f;
    }

    public int consumePendingPlayerHits() {
        int h = pendingPlayerHits;
        pendingPlayerHits = 0;
        return h;
    }

    public String watcherHistoryText() {
        return watcherHistory.isEmpty() ? "[]" : watcherHistory.toString();
    }

    private void updateFogWatchers(Vector3f cam, Vector3f forward) {
        long now = System.nanoTime();
        float dt = (watcherLastNs == 0L) ? 0.016f : Math.min(0.08f, (now - watcherLastNs) / 1_000_000_000f);
        watcherLastNs = now;

        entityAnimTime += dt;
        lastCamPos.set(cam); // M36: for convergence spawn
        watcherDebugForceTimer = Math.max(0f, watcherDebugForceTimer - dt);
        if (!nightMode && watcherDebugForceTimer <= 0f) {
            fogWatchers.clear();
            watcherState = "IDLE";
            watcherEvent = "NONE";
            return;
        }

        // NIGHT_SENTINEL (passive observers) â€” M157: scale count 1â†’3 with horror
        behindYouExtraTimer = Math.max(0f, behindYouExtraTimer - dt);
        int sentinelTarget = 1 + Math.min(2, (int)(horrorProgression * 3.2f)); // 1 at low, up to 3 at high horror
        if (behindYouExtraTimer > 0f) sentinelTarget++; // M159: extra slot for behind-you spawn
        while (fogWatchers.size() < sentinelTarget) {
            float a = (float) (fogRng.nextDouble() * Math.PI * 2.0);
            float r = 22f + fogRng.nextFloat() * 13f;
            float wx = cam.x + (float) Math.cos(a) * r;
            float wz = cam.z + (float) Math.sin(a) * r;
            float wy = ChunkGenerator.heightAt((int) Math.floor(wx), (int) Math.floor(wz), worldSeed) + 1;
            fogWatchers.add(new Vector3f(wx, wy, wz));
        }
        // Trim excess if horror dropped (edge case)
        while (fogWatchers.size() > sentinelTarget) fogWatchers.remove(fogWatchers.size() - 1);

        // Check all sentinels: vanish when directly observed, reappear elsewhere
        for (int _wi = 0; _wi < fogWatchers.size(); _wi++) {
            Vector3f sentinel = fogWatchers.get(_wi);
            float sFacing = forward.dot(new Vector3f(sentinel).sub(cam).normalize());
            if (sFacing > 0.985f) {
                leaveWatcherTrace(sentinel);
                float a = (float) (fogRng.nextDouble() * Math.PI * 2.0);
                float r = 26f + fogRng.nextFloat() * 14f;
                sentinel.x = cam.x + (float) Math.cos(a) * r;
                sentinel.z = cam.z + (float) Math.sin(a) * r;
                sentinel.y = ChunkGenerator.heightAt((int) Math.floor(sentinel.x), (int) Math.floor(sentinel.z), worldSeed) + 1;
                recordWatcherEvent("NIGHT_SENTINEL_VANISH");
            }
        }

        if (!distortionEnabled) {
            distortionIntensity = 0f;
            distortionTarget = 0f;
        } else {
            distortionIntensity += (distortionTarget - distortionIntensity) * Math.min(1f, dt * 4.5f);
        }
    }

    private void emitBillboardDisc(WatcherBuilder fb, Vector3f center, Vector3f camPos, float radius, float r, float g, float b, int segments) {
        Vector3f toCam = new Vector3f(camPos).sub(center);
        if (toCam.lengthSquared() < 0.0001f) toCam.set(0, 0, -1);
        else toCam.normalize();

        Vector3f worldUp = new Vector3f(0, 1, 0);
        Vector3f right = new Vector3f(worldUp).cross(toCam);
        if (right.lengthSquared() < 0.0001f) right.set(1, 0, 0);
        else right.normalize();
        Vector3f up = new Vector3f(toCam).cross(right).normalize();

        for (int i = 0; i < segments; i++) {
            float a0 = (float) (Math.PI * 2.0 * i / segments);
            float a1 = (float) (Math.PI * 2.0 * (i + 1) / segments);

            Vector3f p0 = new Vector3f(center);
            Vector3f p1 = new Vector3f(center).add(new Vector3f(right).mul((float) Math.cos(a0) * radius)).add(new Vector3f(up).mul((float) Math.sin(a0) * radius));
            Vector3f p2 = new Vector3f(center).add(new Vector3f(right).mul((float) Math.cos(a1) * radius)).add(new Vector3f(up).mul((float) Math.sin(a1) * radius));

            fb.v(p0.x, p0.y, p0.z, toCam.x, toCam.y, toCam.z, r, g, b);
            fb.v(p1.x, p1.y, p1.z, toCam.x, toCam.y, toCam.z, r, g, b);
            fb.v(p2.x, p2.y, p2.z, toCam.x, toCam.y, toCam.z, r, g, b);
        }
    }

    private Vector3f skyBodyPos(Vector3f cam, float azimuth, float elevation, float distance) {
        float cosEl = (float) Math.cos(elevation);
        return new Vector3f(
                cam.x + (float) Math.cos(azimuth) * cosEl * distance,
                cam.y + (float) Math.sin(elevation) * distance,
                cam.z + (float) Math.sin(azimuth) * cosEl * distance
        );
    }

    private void emitCloudLobe(WatcherBuilder fb, Vector3f part, Vector3f cam, float radius, float cr, float cg, float cb) {
        Vector3f shadowDir = new Vector3f(-skyLightDir.x, 0f, -skyLightDir.z);
        if (shadowDir.lengthSquared() < 0.0001f) shadowDir.set(1f, 0f, 0f);
        else shadowDir.normalize();
        Vector3f shadowPos = new Vector3f(part).fma(radius * 0.24f, shadowDir).add(0f, -0.35f, 0f);

        float shadowStrength = Math.max(0.12f, 0.58f - nightFactor * 0.42f);
        emitBillboardDisc(fb, shadowPos, cam, radius * 1.02f, cr * shadowStrength, cg * shadowStrength, cb * (shadowStrength + 0.04f), 18);
        emitBillboardDisc(fb, part, cam, radius, cr, cg, cb, 18);
    }

    private void renderSkyAtmosphere(Vector3f cam) {
        WatcherBuilder fb = new WatcherBuilder();

        shader.setLight(lightDir, 1.0f, 0.0f, 0.0f, cam);

        float day = 1f - nightFactor;
        float dusk = 1f - Math.min(1f, Math.abs(day - 0.5f) / 0.20f);

        // Cloud bands (day + dusk + faint night clouds) with mixed archetypes: wispy/chunky/stretched
        float cloudVis = Math.max(day * 0.7f + dusk * 0.35f, nightFactor * 0.14f);
        if (cloudVis > 0.02f) {
            for (int i = 0; i < 11; i++) {
                float a = (float) (i * (Math.PI * 2.0 / 11.0) + timeOfDay01 * 0.35f + cloudDrift * 0.12f);
                float r = 146f + (i % 4) * 16f;
                float driftX = cloudDrift * (8f + (i % 3) * 2.5f);
                float driftZ = cloudDrift * (5f + (i % 2) * 2.0f);
                Vector3f c = new Vector3f(cam.x + (float) Math.cos(a) * r + driftX, cam.y + 118f + (i % 5) * 5.5f, cam.z + (float) Math.sin(a) * r + driftZ);

                float nightCloud = Math.max(0f, nightFactor * 0.18f);
                float dayCloud = Math.max(0f, 1f - nightFactor);
                float cr = (0.72f * dayCloud + 0.22f * nightCloud) * cloudVis;
                float cg = (0.75f * dayCloud + 0.26f * nightCloud) * cloudVis;
                float cb = (0.78f * dayCloud + 0.34f * nightCloud) * cloudVis;

                int type = i % 3; // 0 wispy, 1 chunky, 2 stretched
                if (type == 0) {
                    int lobes = 5 + (i % 3);
                    float spine = 7.0f + (i % 3) * 1.2f;
                    for (int l = 0; l < lobes; l++) {
                        float t = (l / (float) (lobes - 1)) * 2f - 1f;
                        float jitter = (float) Math.sin((i * 9.7f + l * 3.1f) * 0.8f);
                        Vector3f part = new Vector3f(c.x + t * spine, c.y + jitter * 0.7f, c.z + (float) Math.cos(i + l) * 1.6f);
                        float rr = 3.1f + (1f - Math.abs(t)) * 2.2f;
                        emitCloudLobe(fb, part, cam, rr, cr * 0.95f, cg * 0.98f, cb);
                    }
                } else if (type == 1) {
                    int lobes = 4 + (i % 4);
                    float base = 6.2f + (i % 4) * 1.6f;
                    for (int l = 0; l < lobes; l++) {
                        float seed = i * 17.31f + l * 11.73f;
                        float la = (float) (seed + timeOfDay01 * 0.08f);
                        float lx = (float) Math.cos(la) * (2.6f + (l % 3) * 1.4f);
                        float lz = (float) Math.sin(la * 1.23f) * (2.2f + (l % 2) * 1.9f);
                        float ly = ((l % 2) == 0 ? 0.6f : -0.3f) + (float) Math.sin(seed * 0.37f) * 0.45f;
                        Vector3f part = new Vector3f(c.x + lx, c.y + ly, c.z + lz);
                        float rr = base * (0.72f + (l % 3) * 0.18f);
                        emitCloudLobe(fb, part, cam, rr, cr, cg, cb);
                    }
                } else {
                    int lobes = 6 + (i % 3);
                    float longAxis = 10.0f + (i % 3) * 1.6f;
                    for (int l = 0; l < lobes; l++) {
                        float t = (l / (float) (lobes - 1)) * 2f - 1f;
                        float lx = t * longAxis;
                        float lz = (float) Math.sin((l + i) * 1.4f) * 1.5f;
                        float ly = (float) Math.cos((l + i) * 0.9f) * 0.55f;
                        Vector3f part = new Vector3f(c.x + lx, c.y + ly, c.z + lz);
                        float rr = 2.8f + (1f - Math.abs(t)) * 2.8f;
                        emitCloudLobe(fb, part, cam, rr, cr * 0.96f, cg * 0.98f, cb);
                    }
                }
            }
        }

        // Stars (night)
        float starVis = Math.max(0f, nightFactor * 1.2f - 0.08f);
        if (starVis > 0.02f) {
            for (int i = 0; i < 36; i++) {
                float a = (float) (i * 0.61803398875 * Math.PI * 2.0);
                float el = 0.35f + (i % 8) * 0.14f;
                Vector3f s = skyBodyPos(cam, a, el, 240f);
                float tw = 0.70f + 0.30f * (float) Math.sin((timeOfDay01 * 40f) + i * 1.7f);
                float sr = (0.72f + tw * 0.28f) * starVis;
                float sg = (0.74f + tw * 0.26f) * starVis;
                float sb = (0.82f + tw * 0.18f) * starVis;
                emitBillboardDisc(fb, s, cam, 0.55f + (i % 3) * 0.08f, sr, sg, sb, 8);
            }
        }

        if (fb.n <= 0) return;
        streamMesh.draw(fb.a, fb.n); // M151: streaming draw
    }

    private void renderSkyBodies(Vector3f cam, Vector3f camForward) {
        WatcherBuilder fb = new WatcherBuilder();

        // Draw sun/moon as emissive-style bodies so they don't go black from scene light angle.
        shader.setLight(lightDir, 1.05f, 0.0f, 0.0f, cam);

        float angle = (timeOfDay01 - 0.25f) * (float) (Math.PI * 2.0);
        float sunEl = (float) Math.sin(angle) * 1.05f;
        float sunAz = angle * 0.55f;

        // Sun in day
        float dayVis = Math.max(0f, 1f - nightFactor * 1.15f);
        if (dayVis > 0.03f && sunEl > -0.20f) {
            float horizon = Math.max(0f, 1f - Math.max(0f, sunEl) / 0.65f);
            float sr = 1.00f;
            float sg = 0.90f - horizon * 0.24f;
            float sb = 0.56f - horizon * 0.42f;

            Vector3f sunPos = skyBodyPos(cam, sunAz, sunEl, 260f);
            Vector3f toCamSun = new Vector3f(cam).sub(sunPos).normalize();
            float coreSize = 7.5f + dayVis * 2.0f;
            emitBillboardDisc(fb, sunPos, cam, coreSize * 1.45f, sr * 0.85f, Math.max(0f, sg * 0.82f), Math.max(0f, sb * 0.75f), 28);
            emitBillboardDisc(fb, new Vector3f(sunPos).fma(0.6f, toCamSun), cam, coreSize, sr, Math.max(0f, sg), Math.max(0f, sb), 32);
        }

        // Moon opposite side at night (always present above horizon at night window)
        float moonEl = Math.max(-0.05f, Math.min(0.72f, -sunEl));
        float moonAz = sunAz + (float) Math.PI;
        float moonVis = Math.max(0f, nightFactor * 1.35f - 0.02f);
        if (moonVis > 0.01f) {
            float mr = 0.80f + moonVis * 0.18f;
            float mg = 0.84f + moonVis * 0.16f;
            float mb = 0.94f + moonVis * 0.10f;

            Vector3f moonPos = skyBodyPos(cam, moonAz, moonEl, 230f);
            Vector3f toCamMoon = new Vector3f(cam).sub(moonPos).normalize();
            float moonCore = 7.5f + moonVis * 1.8f;
            emitBillboardDisc(fb, moonPos, cam, moonCore * 1.55f, mr * 0.60f, mg * 0.62f, mb * 0.66f, 26);
            emitBillboardDisc(fb, new Vector3f(moonPos).fma(0.6f, toCamMoon), cam, moonCore, mr, mg, mb, 32);
        }

        if (fb.n <= 0) return;
        streamMesh.draw(fb.a, fb.n); // M151: streaming draw
    }

    private void renderTargetOutline(Vector3f cam) {
        if (!targetOutlineActive) return;
        byte id = getBlock(targetOutlineX, targetOutlineY, targetOutlineZ);
        if (id == BlockId.AIR) return;

        float x0 = targetOutlineX, y0 = targetOutlineY, z0 = targetOutlineZ;
        float x1 = x0 + 1f, y1 = y0 + 1f, z1 = z0 + 1f;
        float e = 0.003f;  // slight gap beyond block face to avoid z-fighting
        float t = 0.030f;  // edge thickness
        // outline color: near-white for visibility in dark caves
        float r = 0.90f, g = 0.95f, b = 0.88f;

        WatcherBuilder wb = new WatcherBuilder();
        // 12 edges of the cube, each as a thin strip.
        // 4 edges parallel to X (horizontal top/bottom, front/back)
        emitCube(wb, x0-e, y0-e-t, z0-e-t, x1+e, y0-e,   z0-e,   r,g,b); // bottom-front
        emitCube(wb, x0-e, y1+e,   z0-e-t, x1+e, y1+e+t, z0-e,   r,g,b); // top-front
        emitCube(wb, x0-e, y0-e-t, z1+e,   x1+e, y0-e,   z1+e+t, r,g,b); // bottom-back
        emitCube(wb, x0-e, y1+e,   z1+e,   x1+e, y1+e+t, z1+e+t, r,g,b); // top-back
        // 4 edges parallel to Z (horizontal top/bottom, left/right)
        emitCube(wb, x0-e-t, y0-e-t, z0-e, x0-e,   y0-e,   z1+e, r,g,b); // left-bottom
        emitCube(wb, x1+e,   y0-e-t, z0-e, x1+e+t, y0-e,   z1+e, r,g,b); // right-bottom
        emitCube(wb, x0-e-t, y1+e,   z0-e, x0-e,   y1+e+t, z1+e, r,g,b); // left-top
        emitCube(wb, x1+e,   y1+e,   z0-e, x1+e+t, y1+e+t, z1+e, r,g,b); // right-top
        // 4 vertical edges (parallel to Y)
        emitCube(wb, x0-e-t, y0-e, z0-e-t, x0-e,   y1+e, z0-e,   r,g,b); // left-front
        emitCube(wb, x1+e,   y0-e, z0-e-t, x1+e+t, y1+e, z0-e,   r,g,b); // right-front
        emitCube(wb, x0-e-t, y0-e, z1+e,   x0-e,   y1+e, z1+e+t, r,g,b); // left-back
        emitCube(wb, x1+e,   y0-e, z1+e,   x1+e+t, y1+e, z1+e+t, r,g,b); // right-back

        if (wb.n <= 0) return;
        shader.setModelIdentity();
        shader.setAnimTime(0f, false);
        shader.setUseTexture(false);
        shader.setSpecular(0f);
        shader.setLight(lightDir, ambient, direct, fogApplied, cam);
        streamMesh.draw(wb.a, wb.n); // M151: streaming draw
    }

    private void renderMiningCrack(Vector3f cam) {
        if (!miningCrackActive || miningCrackProgress <= 0f) return;
        byte targetId = getBlock(miningCrackX, miningCrackY, miningCrackZ);
        if (targetId == BlockId.AIR) return;

        float p = Math.max(0f, Math.min(1f, miningCrackProgress));
        float x0 = miningCrackX;
        float y0 = miningCrackY;
        float z0 = miningCrackZ;
        float x1 = x0 + 1f;
        float y1 = y0 + 1f;
        float z1 = z0 + 1f;
        float e = 0.004f;   // offset outward from block face to avoid z-fighting
        float t = 0.018f;    // crack stroke thickness
        float c = 0.02f;     // crack color intensity

        // M110: material crack styles
        boolean woodStyle = (targetId == BlockId.WOOD || targetId == BlockId.CAMPFIRE);
        boolean dirtStyle = (targetId == BlockId.DIRT || targetId == BlockId.MUD || targetId == BlockId.GRASS);
        boolean crystalStyle = (targetId == BlockId.CRYSTAL);
        if (woodStyle) { t = 0.014f; c = 0.05f; }
        if (dirtStyle) { t = 0.022f; c = 0.03f; }
        if (crystalStyle) { t = 0.012f; c = 0.00f; }

        WatcherBuilder wb = new WatcherBuilder();

        // Draw jagged, full-span cracks (no '+' cross) on all visible-oriented faces.
        // +Z face main zig-zag (top-left -> bottom-right across full block)
        emitCube(wb, x0 + 0.06f, y1 - 0.14f, z1 + e, x0 + 0.34f, y1 - 0.10f, z1 + e + t, c, c, c);
        emitCube(wb, x0 + 0.30f, y0 + 0.60f, z1 + e, x0 + 0.60f, y0 + 0.64f, z1 + e + t, c, c, c);
        emitCube(wb, x0 + 0.56f, y0 + 0.28f, z1 + e, x0 + 0.92f, y0 + 0.32f, z1 + e + t, c, c, c);

        // -Z face mirrored zig-zag
        emitCube(wb, x0 + 0.08f, y0 + 0.28f, z0 - e - t, x0 + 0.44f, y0 + 0.32f, z0 - e, c, c, c);
        emitCube(wb, x0 + 0.40f, y0 + 0.60f, z0 - e - t, x0 + 0.72f, y0 + 0.64f, z0 - e, c, c, c);
        emitCube(wb, x0 + 0.68f, y1 - 0.14f, z0 - e - t, x0 + 0.94f, y1 - 0.10f, z0 - e, c, c, c);

        // +X face vertical-ish jagged spine across full height
        emitCube(wb, x1 + e, y1 - 0.12f, z0 + 0.10f, x1 + e + t, y1 - 0.08f, z0 + 0.42f, c, c, c);
        emitCube(wb, x1 + e, y0 + 0.54f, z0 + 0.36f, x1 + e + t, y0 + 0.58f, z0 + 0.68f, c, c, c);
        emitCube(wb, x1 + e, y0 + 0.14f, z0 + 0.62f, x1 + e + t, y0 + 0.18f, z0 + 0.94f, c, c, c);

        // -X face mirrored
        emitCube(wb, x0 - e - t, y0 + 0.14f, z0 + 0.06f, x0 - e, y0 + 0.18f, z0 + 0.38f, c, c, c);
        emitCube(wb, x0 - e - t, y0 + 0.54f, z0 + 0.32f, x0 - e, y0 + 0.58f, z0 + 0.66f, c, c, c);
        emitCube(wb, x0 - e - t, y1 - 0.12f, z0 + 0.60f, x0 - e, y1 - 0.08f, z0 + 0.94f, c, c, c);

        // +Y top face full-span diagonal so top-hit blocks also show cracks clearly
        emitCube(wb, x0 + 0.08f, y1 + e, z0 + 0.84f, x0 + 0.40f, y1 + e + t, z0 + 0.88f, c, c, c);
        emitCube(wb, x0 + 0.36f, y1 + e, z0 + 0.52f, x0 + 0.66f, y1 + e + t, z0 + 0.56f, c, c, c);
        emitCube(wb, x0 + 0.62f, y1 + e, z0 + 0.12f, x0 + 0.94f, y1 + e + t, z0 + 0.16f, c, c, c);

        if (woodStyle) {
            // Splinter lines: long mostly-parallel fibers.
            emitCube(wb, x0 + 0.20f, y0 + 0.08f, z1 + e, x0 + 0.24f, y1 - 0.08f, z1 + e + t, c, c, c);
            emitCube(wb, x0 + 0.42f, y0 + 0.06f, z1 + e, x0 + 0.46f, y1 - 0.06f, z1 + e + t, c, c, c);
            emitCube(wb, x0 + 0.72f, y0 + 0.10f, z1 + e, x0 + 0.76f, y1 - 0.10f, z1 + e + t, c, c, c);
        }
        if (dirtStyle) {
            // Chunkier fractures: blocky chunks instead of thin splinters.
            emitCube(wb, x0 + 0.18f, y0 + 0.62f, z1 + e, x0 + 0.34f, y0 + 0.72f, z1 + e + t, c, c, c);
            emitCube(wb, x0 + 0.52f, y0 + 0.42f, z1 + e, x0 + 0.74f, y0 + 0.52f, z1 + e + t, c, c, c);
        }
        if (crystalStyle) {
            // Spiderweb/glass pattern: radial spokes from center.
            float cc = 0.10f;
            emitCube(wb, x0 + 0.49f, y0 + 0.49f, z1 + e, x0 + 0.51f, y0 + 0.92f, z1 + e + t, cc, cc, cc);
            emitCube(wb, x0 + 0.49f, y0 + 0.08f, z1 + e, x0 + 0.51f, y0 + 0.51f, z1 + e + t, cc, cc, cc);
            emitCube(wb, x0 + 0.08f, y0 + 0.49f, z1 + e, x0 + 0.51f, y0 + 0.51f, z1 + e + t, cc, cc, cc);
            emitCube(wb, x0 + 0.49f, y0 + 0.49f, z1 + e, x0 + 0.92f, y0 + 0.51f, z1 + e + t, cc, cc, cc);
            emitCube(wb, x0 + 0.16f, y0 + 0.80f, z1 + e, x0 + 0.52f, y0 + 0.84f, z1 + e + t, cc, cc, cc);
            emitCube(wb, x0 + 0.48f, y0 + 0.52f, z1 + e, x0 + 0.84f, y0 + 0.56f, z1 + e + t, cc, cc, cc);
        }

        // Progressive extra branches by stage.
        if (p > 0.22f) {
            emitCube(wb, x0 + 0.18f, y0 + 0.72f, z1 + e, x0 + 0.22f, y0 + 0.88f, z1 + e + t, c, c, c);
            emitCube(wb, x1 + e, y0 + 0.70f, z0 + 0.18f, x1 + e + t, y0 + 0.86f, z0 + 0.22f, c, c, c);
        }
        if (p > 0.45f) {
            emitCube(wb, x0 + 0.46f, y0 + 0.44f, z1 + e, x0 + 0.64f, y0 + 0.48f, z1 + e + t, c, c, c);
            emitCube(wb, x0 - e - t, y0 + 0.30f, z0 + 0.46f, x0 - e, y0 + 0.34f, z0 + 0.64f, c, c, c);
        }
        if (p > 0.68f) {
            emitCube(wb, x0 + 0.04f, y0 + 0.04f, z1 + e, x0 + 0.24f, y0 + 0.08f, z1 + e + t, c, c, c);
            emitCube(wb, x0 + 0.76f, y1 + e, z0 + 0.76f, x0 + 0.96f, y1 + e + t, z0 + 0.80f, c, c, c);
        }
        if (p > 0.84f) {
            emitCube(wb, x0 + 0.02f, y1 - 0.08f, z0 - e - t, x0 + 0.22f, y1 - 0.04f, z0 - e, c, c, c);
            emitCube(wb, x1 - 0.08f, y0 + 0.02f, z0 - e - t, x1 - 0.04f, y0 + 0.24f, z0 - e, c, c, c);
        }

        if (wb.n <= 0) return;
        shader.setModelIdentity();
        shader.setAnimTime(0f, false);
        shader.setUseTexture(false);
        shader.setSpecular(0f);
        // Full-bright to stay visible in caves/darkness.
        shader.setLight(lightDir, 1.35f, 0f, 0f, cam);
        streamMesh.draw(wb.a, wb.n); // M151: streaming draw
        shader.setLight(lightDir, ambient, direct, fogApplied, cam);
    }

    // M151: torch cache last-computed camera position
    private float torchCacheLastX = Float.MAX_VALUE, torchCacheLastZ = Float.MAX_VALUE;

    private void renderPlacedTorches(Vector3f cam) {
        // M151: rebuild geometry only when blocks changed or player moved noticeably
        float tcDx = cam.x - torchCacheLastX, tcDz = cam.z - torchCacheLastZ;
        if (!torchCacheDirty && tcDx * tcDx + tcDz * tcDz < 16f) {
            // Geometry still valid — re-issue both scene-lit and emissive draws
            if (torchCacheCount > 0) {
                shader.setModelIdentity(); shader.setAnimTime(0f, false);
                shader.setUseTexture(false); shader.setSpecular(0f);
                shader.setLight(lightDir, ambient, direct, fogApplied, cam);
                streamMesh.draw(torchCacheVerts, torchCacheCount);
            }
            if (torchGlowCacheCount > 0) { // M232: emissive flame tips always bright
                shader.setLight(lightDir, 4.5f, 0.0f, 0.0f, cam);
                streamMesh.draw(torchGlowCacheVerts, torchGlowCacheCount);
                shader.setLight(lightDir, ambient, direct, fogApplied, cam);
            }
            return;
        }
        torchCacheDirty = false;
        torchCacheLastX = cam.x;
        torchCacheLastZ = cam.z;

        WatcherBuilder wb     = new WatcherBuilder(); // scene-lit: stems, doors, frames
        WatcherBuilder wbGlow = new WatcherBuilder(); // M232: emissive: flame tips (always visible)
        for (Map.Entry<ChunkPos, VoxelChunk> e : chunkData.entrySet()) {
            ChunkPos cp = e.getKey();
            VoxelChunk c = e.getValue();
            int baseX = cp.x() * Chunk.SIZE;
            int baseZ = cp.z() * Chunk.SIZE;

            for (int y = 0; y < VoxelChunk.SIZE_Y; y++) {
                for (int z = 0; z < Chunk.SIZE; z++) {
                    for (int x = 0; x < Chunk.SIZE; x++) {
                        byte b = c.get(x, y, z);
                        if (b != BlockId.TORCH_STAND && b != BlockId.TORCH_WALL
                                && b != BlockId.DOOR_CLOSED && b != BlockId.DOOR_OPEN
                                && b != BlockId.FUNGUS && b != BlockId.BLOODSTAIN
                                && b != BlockId.LANTERN
                                && b != BlockId.BONES && b != BlockId.COBWEB
                                && b != BlockId.LIMINAL_PORTAL) continue;
                        float wx = baseX + x;
                        float wy = y;
                        float wz = baseZ + z;
                        float dx = (wx + 0.5f) - cam.x;
                        float dy = (wy + 0.5f) - cam.y;
                        float dz = (wz + 0.5f) - cam.z;
                        float dd2 = dx*dx + dy*dy + dz*dz;
                        // M223: torches/doors/lanterns visible far; decorative decals capped at 24 blocks
                        // to prevent vertex-buffer explosion from dense BONES/BLOODSTAIN/COBWEB generation.
                        boolean isDecal = b == BlockId.BONES || b == BlockId.BLOODSTAIN
                                || b == BlockId.COBWEB || b == BlockId.FUNGUS;
                        if (isDecal ? dd2 > 24f * 24f : dd2 > 90f * 90f) continue;

                        if (b == BlockId.TORCH_STAND) {
                            // Standing torch: stem (scene-lit) + flame cap (emissive)
                            emitCube(wb,     wx+0.44f, wy+0.00f, wz+0.44f, wx+0.56f, wy+0.62f, wz+0.56f, 0.50f, 0.32f, 0.16f);
                            emitCube(wbGlow, wx+0.40f, wy+0.58f, wz+0.40f, wx+0.60f, wy+0.78f, wz+0.60f, 0.96f, 0.74f, 0.22f);
                            emitCube(wbGlow, wx+0.43f, wy+0.62f, wz+0.43f, wx+0.57f, wy+0.74f, wz+0.57f, 1.00f, 0.92f, 0.58f);
                        } else if (b == BlockId.TORCH_WALL) {
                            // M156: Wall torch â€” base flush against wall, leans outward
                            int ax = 0, az = 0;
                            if (getBlock((int)wx - 1, (int)wy, (int)wz) != BlockId.AIR) ax = 1;
                            else if (getBlock((int)wx + 1, (int)wy, (int)wz) != BlockId.AIR) ax = -1;
                            else if (getBlock((int)wx, (int)wy, (int)wz - 1) != BlockId.AIR) az = 1;
                            else if (getBlock((int)wx, (int)wy, (int)wz + 1) != BlockId.AIR) az = -1;
                            float cx = wx + 0.5f;
                            float cz = wz + 0.5f;
                            // lean[] = signed offsets from block center: negative pushes toward wall face
                            // For ax=1 (wall on -X): lean=-0.44 â†’ x=wx+0.06 (right at wall face)
                            float[] lean    = {-0.44f, -0.28f, -0.12f};
                            float[] heights = { 0.05f,  0.22f,  0.38f};
                            for (int s = 0; s < 3; s++) {
                                float lo = lean[s];
                                emitCube(wb, cx-0.05f+ax*lo, wy+heights[s],        cz-0.05f+az*lo,
                                             cx+0.05f+ax*lo, wy+heights[s]+0.16f,  cz+0.05f+az*lo,
                                             0.50f, 0.32f, 0.16f);
                            }
                            // M233: flame tip — emissive; suppressed in zone 2 (decorative torches only)
                            if (liminalZoneId != 2) {
                                float fl = 0.04f;
                                emitCube(wbGlow, cx-0.10f+ax*fl, wy+0.55f, cz-0.10f+az*fl,
                                                 cx+0.10f+ax*fl, wy+0.72f, cz+0.10f+az*fl,
                                                 0.96f, 0.74f, 0.22f);
                                emitCube(wbGlow, cx-0.07f+ax*fl, wy+0.59f, cz-0.07f+az*fl,
                                                 cx+0.07f+ax*fl, wy+0.69f, cz+0.07f+az*fl,
                                                 1.00f, 0.92f, 0.58f);
                            }
                        } else if (b == BlockId.DOOR_CLOSED || b == BlockId.DOOR_OPEN) {
                            // Door (DOOR_CLOSED or DOOR_OPEN):
                            // Quarter-block-thick slab of 4 planks with visible gaps.
                            boolean open = (b == BlockId.DOOR_OPEN);
                            int iwx = (int)wx, iwy = (int)wy, iwz = (int)wz;
                            // M237: use stored player-facing direction; fall back to adjacent-block only for
                            // pre-existing doors that have no stored facing (old worlds / gen doors).
                            int wallAx = 0, wallAz = 0;
                            Byte storedF = doorFacing.get(blockKey(iwx, iwy, iwz));
                            if (storedF == null) storedF = doorFacing.get(blockKey(iwx, iwy - 1, iwz)); // check lower half
                            if (storedF != null) {
                                if (storedF == 1) wallAx = 1; // X-axis door
                                else              wallAz = 1; // Z-axis door
                            } else {
                                // Legacy: detect from adjacent solid blocks
                                if      (BlockId.isSolid(getBlock(iwx+1, iwy, iwz))) wallAx =  1;
                                else if (BlockId.isSolid(getBlock(iwx-1, iwy, iwz))) wallAx = -1;
                                else if (BlockId.isSolid(getBlock(iwx, iwy, iwz+1))) wallAz =  1;
                                else if (BlockId.isSolid(getBlock(iwx, iwy, iwz-1))) wallAz = -1;
                                else wallAz = 1; // free-standing fallback
                            }

                            final float TH = 0.25f; // 1/4 block thickness
                            float dx0, dx1, dz0, dz1;
                            if (!open) {
                                // Closed: slab pressed against the wall face
                                if      (wallAx ==  1) { dx0=wx+1f-TH; dx1=wx+1f;   dz0=wz;       dz1=wz+1f;   }
                                else if (wallAx == -1) { dx0=wx;        dx1=wx+TH;   dz0=wz;       dz1=wz+1f;   }
                                else if (wallAz ==  1) { dx0=wx;        dx1=wx+1f;   dz0=wz+1f-TH; dz1=wz+1f;   }
                                else                   { dx0=wx;        dx1=wx+1f;   dz0=wz;       dz1=wz+TH;   }
                            } else {
                                // Open: pivot 90Ã‚Â° at hinge corner (door swings inward)
                                if      (wallAx ==  1) { dx0=wx+1f-TH; dx1=wx+1f;   dz0=wz;       dz1=wz+TH;   }
                                else if (wallAx == -1) { dx0=wx;        dx1=wx+TH;   dz0=wz+1f-TH; dz1=wz+1f;   }
                                else if (wallAz ==  1) { dx0=wx;        dx1=wx+TH;   dz0=wz;       dz1=wz+1f;   }
                                else                   { dx0=wx+1f-TH;  dx1=wx+1f;   dz0=wz;       dz1=wz+1f;   }
                            }
                            // Plank color (warm wood) + darker rail color
                            float wpR=0.62f, wpG=0.44f, wpB=0.24f;
                            float frR=0.20f, frG=0.12f, frB=0.05f;
                            // 3 planks with 2 separated rail bands between them (no overlap = no Z-fighting)
                            // Layout per block-height: plank|RAIL|plank|RAIL|plank
                            emitCube(wb, dx0, wy+0.00f, dz0, dx1, wy+0.28f, dz1, wpR, wpG, wpB);
                            emitCube(wb, dx0, wy+0.28f, dz0, dx1, wy+0.36f, dz1, frR, frG, frB);
                            emitCube(wb, dx0, wy+0.36f, dz0, dx1, wy+0.64f, dz1, wpR, wpG, wpB);
                            emitCube(wb, dx0, wy+0.64f, dz0, dx1, wy+0.72f, dz1, frR, frG, frB);
                            emitCube(wb, dx0, wy+0.72f, dz0, dx1, wy+1.00f, dz1, wpR, wpG, wpB);

                        } else if (b == BlockId.FUNGUS) {
                            // M221: bioluminescent cave mushroom â€” stem + layered cap + glow spots
                            float sR=0.82f, sG=0.78f, sB=0.70f;          // pale cream stem
                            float cR=0.26f, cG=0.68f, cB=0.62f;          // teal-green cap body
                            float gR=0.62f, gG=1.00f, gB=0.88f;          // bright bioluminescent spots
                            // Stem
                            emitCube(wb, wx+0.43f, wy,       wz+0.43f, wx+0.57f, wy+0.40f, wz+0.57f, sR, sG, sB);
                            // Cap underside (wide flat disc, slightly darker)
                            emitCube(wb, wx+0.20f, wy+0.36f, wz+0.20f, wx+0.80f, wy+0.46f, wz+0.80f, cR*0.65f, cG*0.65f, cB*0.65f);
                            // Cap main body
                            emitCube(wb, wx+0.22f, wy+0.44f, wz+0.22f, wx+0.78f, wy+0.58f, wz+0.78f, cR, cG, cB);
                            // Cap dome (narrower, slightly lighter on top)
                            emitCube(wb, wx+0.30f, wy+0.56f, wz+0.30f, wx+0.70f, wy+0.66f, wz+0.70f, cR*0.85f, cG*0.92f, cB*0.92f);
                            // Bioluminescent glow spots on cap surface
                            emitCube(wb, wx+0.36f, wy+0.63f, wz+0.37f, wx+0.44f, wy+0.68f, wz+0.45f, gR, gG, gB);
                            emitCube(wb, wx+0.54f, wy+0.62f, wz+0.51f, wx+0.62f, wy+0.67f, wz+0.59f, gR, gG, gB);
                            emitCube(wb, wx+0.40f, wy+0.62f, wz+0.59f, wx+0.47f, wy+0.66f, wz+0.67f, gR*0.90f, gG, gB*0.92f);

                        } else if (b == BlockId.BLOODSTAIN) {
                            // M221: blood splatter â€” thin irregular floor decal
                            float bR=0.55f, bG=0.04f, bB=0.04f;   // dark crimson pool
                            float dR=0.38f, dG=0.02f, dB=0.02f;   // dried dark edges/drips
                            // Central pool
                            emitCube(wb, wx+0.24f, wy, wz+0.24f, wx+0.76f, wy+0.028f, wz+0.76f, bR, bG, bB);
                            // Left splatter arm
                            emitCube(wb, wx+0.04f, wy, wz+0.38f, wx+0.24f, wy+0.022f, wz+0.60f, dR, dG, dB);
                            // Right splatter arm
                            emitCube(wb, wx+0.76f, wy, wz+0.36f, wx+0.96f, wy+0.022f, wz+0.62f, dR, dG, dB);
                            // Top drip
                            emitCube(wb, wx+0.38f, wy, wz+0.06f, wx+0.62f, wy+0.020f, wz+0.24f, dR, dG, dB);
                            // Bottom drip
                            emitCube(wb, wx+0.32f, wy, wz+0.76f, wx+0.68f, wy+0.020f, wz+0.94f, dR, dG, dB);
                            // Corner scatter drops
                            emitCube(wb, wx+0.10f, wy, wz+0.14f, wx+0.22f, wy+0.018f, wz+0.26f, dR*0.85f, dG, dB);
                            emitCube(wb, wx+0.76f, wy, wz+0.72f, wx+0.88f, wy+0.018f, wz+0.84f, dR*0.85f, dG, dB);

                        } else if (b == BlockId.LANTERN) {
                            // M221: wrought-iron cage lantern â€” 4 corner posts + bands + amber glow core
                            float iR=0.22f, iG=0.20f, iB=0.15f;   // dark iron
                            float gR=0.98f, gG=0.80f, gB=0.20f;   // bright amber glow (bottom)
                            float hR=0.92f, hG=0.52f, hB=0.08f;   // deeper orange (top core)
                            // Base plate
                            emitCube(wb, wx+0.28f, wy+0.04f, wz+0.28f, wx+0.72f, wy+0.09f, wz+0.72f, iR, iG, iB);
                            // Four vertical corner posts
                            emitCube(wb, wx+0.28f, wy+0.09f, wz+0.28f, wx+0.34f, wy+0.74f, wz+0.34f, iR, iG, iB);
                            emitCube(wb, wx+0.66f, wy+0.09f, wz+0.28f, wx+0.72f, wy+0.74f, wz+0.34f, iR, iG, iB);
                            emitCube(wb, wx+0.28f, wy+0.09f, wz+0.66f, wx+0.34f, wy+0.74f, wz+0.72f, iR, iG, iB);
                            emitCube(wb, wx+0.66f, wy+0.09f, wz+0.66f, wx+0.72f, wy+0.74f, wz+0.72f, iR, iG, iB);
                            // Mid horizontal band
                            emitCube(wb, wx+0.30f, wy+0.40f, wz+0.30f, wx+0.70f, wy+0.44f, wz+0.70f, iR, iG, iB);
                            // Top cap
                            emitCube(wb, wx+0.28f, wy+0.74f, wz+0.28f, wx+0.72f, wy+0.80f, wz+0.72f, iR, iG, iB);
                            emitCube(wb, wx+0.32f, wy+0.80f, wz+0.32f, wx+0.68f, wy+0.88f, wz+0.68f, iR, iG, iB);
                            // Hook stem
                            emitCube(wb, wx+0.45f, wy+0.88f, wz+0.45f, wx+0.55f, wy+0.97f, wz+0.55f, iR, iG, iB);
                            // Glowing amber interior â€” bottom (brighter)
                            emitCube(wb, wx+0.34f, wy+0.09f, wz+0.34f, wx+0.66f, wy+0.44f, wz+0.66f, gR, gG, gB);
                            // Glowing amber interior â€” top (deeper orange)
                            emitCube(wb, wx+0.34f, wy+0.44f, wz+0.34f, wx+0.66f, wy+0.74f, wz+0.66f, hR, hG, hB);

                        } else if (b == BlockId.BONES) {
                            // M222: scattered bone pile â€” skull + crossed long bones + rib fragments
                            float bR=0.88f, bG=0.85f, bB=0.76f;   // aged bone white
                            float sR=0.82f, sG=0.79f, sB=0.70f;   // slightly darker shaft
                            float dR=0.36f, dG=0.32f, dB=0.26f;   // dark socket shadows
                            // Skull (rounded lump at back-left corner)
                            emitCube(wb, wx+0.10f, wy,       wz+0.10f, wx+0.38f, wy+0.22f, wz+0.38f, bR, bG, bB);
                            emitCube(wb, wx+0.12f, wy+0.22f, wz+0.12f, wx+0.36f, wy+0.32f, wz+0.36f, bR*0.92f, bG*0.92f, bB*0.92f);
                            // Eye sockets (dark insets on skull front face)
                            emitCube(wb, wx+0.15f, wy+0.10f, wz+0.08f, wx+0.22f, wy+0.18f, wz+0.12f, dR, dG, dB);
                            emitCube(wb, wx+0.26f, wy+0.10f, wz+0.08f, wx+0.33f, wy+0.18f, wz+0.12f, dR, dG, dB);
                            // Long bone A â€” runs diagonally (NW to SE)
                            emitCube(wb, wx+0.06f, wy,       wz+0.48f, wx+0.70f, wy+0.09f, wz+0.62f, sR, sG, sB);
                            // Knobbed ends of bone A
                            emitCube(wb, wx+0.04f, wy,       wz+0.46f, wx+0.14f, wy+0.12f, wz+0.66f, bR, bG, bB);
                            emitCube(wb, wx+0.60f, wy,       wz+0.46f, wx+0.74f, wy+0.12f, wz+0.66f, bR, bG, bB);
                            // Long bone B â€” crosses over A (E-W bias)
                            emitCube(wb, wx+0.30f, wy+0.06f, wz+0.36f, wx+0.92f, wy+0.14f, wz+0.50f, sR, sG, sB);
                            // Knobbed ends of bone B
                            emitCube(wb, wx+0.28f, wy+0.04f, wz+0.34f, wx+0.42f, wy+0.16f, wz+0.52f, bR, bG, bB);
                            emitCube(wb, wx+0.80f, wy+0.04f, wz+0.34f, wx+0.94f, wy+0.16f, wz+0.52f, bR, bG, bB);
                            // Small rib/finger fragments
                            emitCube(wb, wx+0.52f, wy,       wz+0.68f, wx+0.70f, wy+0.07f, wz+0.78f, sR, sG, sB);
                            emitCube(wb, wx+0.44f, wy,       wz+0.75f, wx+0.60f, wy+0.06f, wz+0.86f, sR*0.94f, sG*0.94f, sB*0.94f);

                        } else if (b == BlockId.COBWEB) {
                            // M222: cobweb mass â€” central cluster + radiating strands + hanging wisps
                            float wR=0.76f, wG=0.76f, wB=0.82f;   // pale silvery web
                            float tR=0.68f, tG=0.68f, tB=0.75f;   // slightly darker strands
                            // Central web mass (upper portion â€” hangs from ceiling)
                            emitCube(wb, wx+0.18f, wy+0.58f, wz+0.18f, wx+0.82f, wy+0.88f, wz+0.82f, wR, wG, wB);
                            // Dense mid-layer
                            emitCube(wb, wx+0.24f, wy+0.40f, wz+0.24f, wx+0.76f, wy+0.60f, wz+0.76f, wR*0.92f, wG*0.92f, wB*0.92f);
                            // 4 radiating side lobes (the web spreading along walls)
                            emitCube(wb, wx,       wy+0.54f, wz+0.30f, wx+0.20f, wy+0.80f, wz+0.70f, tR, tG, tB); // -X lobe
                            emitCube(wb, wx+0.80f, wy+0.54f, wz+0.30f, wx+1.00f, wy+0.80f, wz+0.70f, tR, tG, tB); // +X lobe
                            emitCube(wb, wx+0.30f, wy+0.54f, wz,       wx+0.70f, wy+0.80f, wz+0.20f, tR, tG, tB); // -Z lobe
                            emitCube(wb, wx+0.30f, wy+0.54f, wz+0.80f, wx+0.70f, wy+0.80f, wz+1.00f, tR, tG, tB); // +Z lobe
                            // Hanging strand wisps (thin vertical drops from the mass)
                            emitCube(wb, wx+0.40f, wy+0.08f, wz+0.40f, wx+0.48f, wy+0.40f, wz+0.48f, tR, tG, tB);
                            emitCube(wb, wx+0.54f, wy+0.14f, wz+0.52f, wx+0.60f, wy+0.42f, wz+0.58f, tR, tG, tB);
                            emitCube(wb, wx+0.30f, wy+0.18f, wz+0.56f, wx+0.36f, wy+0.38f, wz+0.62f, tR*0.90f, tG*0.90f, tB*0.90f);
                            emitCube(wb, wx+0.62f, wy+0.10f, wz+0.32f, wx+0.68f, wy+0.36f, wz+0.38f, tR*0.90f, tG*0.90f, tB*0.90f);

                        } else if (b == BlockId.LIMINAL_PORTAL) {
                            // M225: portal floor tile â€” flat glowing slab with cyan core + pale rim
                            float pR=0.72f, pG=0.96f, pB=0.92f;   // pale cyan-teal surface
                            float cR=0.42f, cG=1.00f, cB=0.88f;   // bright core
                            // Thin slab base (slight raise off floor)
                            emitCube(wb, wx+0.02f, wy,       wz+0.02f, wx+0.98f, wy+0.04f, wz+0.98f, pR*0.70f, pG*0.70f, pB*0.70f);
                            // Glowing surface layer
                            emitCube(wb, wx+0.04f, wy+0.04f, wz+0.04f, wx+0.96f, wy+0.07f, wz+0.96f, pR, pG, pB);
                            // Bright inner core (slightly smaller, on top)
                            emitCube(wb, wx+0.14f, wy+0.07f, wz+0.14f, wx+0.86f, wy+0.09f, wz+0.86f, cR, cG, cB);
                            // Corner accent pillars (faint anchor points)
                            float aR=0.30f, aG=0.85f, aB=0.78f;
                            emitCube(wb, wx+0.02f, wy,       wz+0.02f, wx+0.12f, wy+0.18f, wz+0.12f, aR, aG, aB);
                            emitCube(wb, wx+0.88f, wy,       wz+0.02f, wx+0.98f, wy+0.18f, wz+0.12f, aR, aG, aB);
                            emitCube(wb, wx+0.02f, wy,       wz+0.88f, wx+0.12f, wy+0.18f, wz+0.98f, aR, aG, aB);
                            emitCube(wb, wx+0.88f, wy,       wz+0.88f, wx+0.98f, wy+0.18f, wz+0.98f, aR, aG, aB);
                        }
                    }
                }
            }
        }

        // M151: save scene-lit geometry to cache
        torchCacheCount = wb.n;
        if (wb.n > 0) {
            if (torchCacheVerts.length < wb.n) torchCacheVerts = new float[wb.n];
            System.arraycopy(wb.a, 0, torchCacheVerts, 0, wb.n);
        }
        // M232: save emissive flame geometry to glow cache
        torchGlowCacheCount = wbGlow.n;
        if (wbGlow.n > 0) {
            if (torchGlowCacheVerts.length < wbGlow.n) torchGlowCacheVerts = new float[wbGlow.n];
            System.arraycopy(wbGlow.a, 0, torchGlowCacheVerts, 0, wbGlow.n);
        }
        // Draw scene-lit geometry
        if (torchCacheCount > 0) {
            shader.setModelIdentity(); shader.setAnimTime(0f, false);
            shader.setUseTexture(false); shader.setSpecular(0f);
            shader.setLight(lightDir, ambient, direct, fogApplied, cam);
            streamMesh.draw(torchCacheVerts, torchCacheCount);
        }
        // Draw emissive flame tips — always bright regardless of scene darkness
        if (torchGlowCacheCount > 0) {
            shader.setLight(lightDir, 4.5f, 0.0f, 0.0f, cam);
            streamMesh.draw(torchGlowCacheVerts, torchGlowCacheCount);
            shader.setLight(lightDir, ambient, direct, fogApplied, cam);
        }
    }

    // M150: passive pig animal  - " wanders surface, flees when hit, drops RAW_PORK on death
    private static final class PigEntity {
        float x, y, z;
        float vx = 0, vz = 0;
        int   health = 4;       // 4 hits to kill
        float animTime  = 0f;
        float stateTimer = 0f;
        int   state = 0;        // 0=IDLE 1=WANDER 2=FLEE
        float facing = 0f;      // Y-axis rotation in radians
        float fleeFromX, fleeFromZ;
        float hitFlash = 0f;    // red tint duration after being struck
        boolean spectral    = false; // M172: ghost pig easter egg
        float spectralLife  = 0f;   // M172: countdown to despawn
        PigEntity(float x, float y, float z, Random rng) {
            this.x = x; this.y = y; this.z = z;
            this.facing = rng.nextFloat() * (float)(Math.PI * 2.0);
            this.stateTimer = 1.5f + rng.nextFloat() * 2f;
            if (rng.nextFloat() < 0.02f) { spectral = true; spectralLife = 8f + rng.nextFloat() * 5f; } // 2% ghost
        }
    }

    /** Emit a rotated box relative to a pig's world position. Local space: +Z = pig's back. */
    private void emitPigCube(WatcherBuilder wb, float cosA, float sinA,
                              float ox, float oy, float oz,
                              float lx, float ly, float lz,
                              float hw, float hh, float hd,
                              float cr, float cg, float cb) {
        // Rotate local XZ offset by pig facing
        float rx = cosA * lx - sinA * lz;
        float rz = sinA * lx + cosA * lz;
        emitCube(wb, ox + rx - hw, oy + ly - hh, oz + rz - hd,
                     ox + rx + hw, oy + ly + hh, oz + rz + hd, cr, cg, cb);
    }

    /** Try to damage the nearest pig in front of the player. Returns true if a pig was hit. */
    /** M172: returns true once when a spectral pig despawns (ghost pig easter egg). */
    public boolean consumeSpectralDespawn() { boolean v = spectralPigDespawned; spectralPigDespawned = false; return v; }

    public boolean tryHitPig(float px, float pz, float fwdX, float fwdZ, float range) {
        float best = Float.MAX_VALUE;
        PigEntity target = null;
        float r2 = range * range;
        for (PigEntity p : pigs) {
            float dx = p.x - px, dz = p.z - pz;
            float dist2 = dx * dx + dz * dz;
            if (dist2 > r2) continue;
            float dot = dx * fwdX + dz * fwdZ;
            if (dot < 0f) continue; // behind player
            if (dist2 < best) { best = dist2; target = p; }
        }
        if (target == null) return false;
        target.health--;
        target.hitFlash = 0.28f;
        target.state = 2; // FLEE
        target.fleeFromX = px;
        target.fleeFromZ = pz;
        target.stateTimer = 4f + fogRng.nextFloat() * 2f;
        return true;
    }

    /** Update pig AI  - " call once per frame when !paused. */
    private void updatePigs(Vector3f cam, float dt) {
        // Spawn new pigs periodically
        pigSpawnTimer -= dt;
        if (pigSpawnTimer <= 0f && pigs.size() < PIG_MAX) {
            pigSpawnTimer = 10f + fogRng.nextFloat() * 10f; // M173: less frequent
            float angle = (float)(fogRng.nextDouble() * Math.PI * 2.0);
            float dist  = 14f + fogRng.nextFloat() * 18f;
            float spx = cam.x + (float)Math.cos(angle) * dist;
            float spz = cam.z + (float)Math.sin(angle) * dist;
            int   spy = ChunkGenerator.heightAt((int)Math.floor(spx), (int)Math.floor(spz), worldSeed);
            byte  surf = getBlock((int)Math.floor(spx), spy, (int)Math.floor(spz));
            if (surf == BlockId.GRASS || surf == BlockId.DIRT) {
                pigs.add(new PigEntity(spx, spy + 1f, spz, fogRng));
            }
        }

        for (int i = pigs.size() - 1; i >= 0; i--) {
            PigEntity p = pigs.get(i);
            // Dead pig  - " spawn meat and remove
            if (p.health <= 0) {
                int dropCount = 1 + (fogRng.nextFloat() < 0.45f ? 1 : 0);
                spawnDrop((int)Math.floor(p.x), (int)Math.floor(p.y), (int)Math.floor(p.z), BlockId.RAW_PORK, dropCount);
                pigs.remove(i);
                continue;
            }
            if (p.hitFlash > 0f) p.hitFlash -= dt;
            // M172: ghost pig countdown
            if (p.spectral) {
                p.spectralLife -= dt;
                if (p.spectralLife <= 0f) { spectralPigDespawned = true; pigs.remove(i); continue; }
            }

            p.stateTimer -= dt;
            if (p.state == 2) {           // FLEE  - " run away from damage source
                float dx = p.x - p.fleeFromX, dz = p.z - p.fleeFromZ;
                float len = (float)Math.sqrt(dx * dx + dz * dz);
                if (len > 0.01f) {
                    p.vx = (dx / len) * 4.4f;
                    p.vz = (dz / len) * 4.4f;
                    p.facing = (float)Math.atan2(p.vx, -p.vz); // M170 fix: match render convention
                }
                if (p.stateTimer <= 0f) { p.state = 1; p.stateTimer = 2f + fogRng.nextFloat() * 2f; }
            } else if (p.state == 1) {    // WANDER
                p.vx = (float)Math.sin(p.facing) * 1.1f;
                p.vz = -(float)Math.cos(p.facing) * 1.1f; // M170 fix: head faces (sin,âˆ’cos)
                if (p.stateTimer <= 0f) {
                    p.state = 0; p.stateTimer = 1.5f + fogRng.nextFloat() * 3f;
                    p.vx = 0; p.vz = 0;
                }
            } else {                      // IDLE
                p.vx = 0; p.vz = 0;
                if (p.stateTimer <= 0f) {
                    p.state = 1;
                    p.facing = fogRng.nextFloat() * (float)(Math.PI * 2.0);
                    p.stateTimer = 1.5f + fogRng.nextFloat() * 2.5f;
                }
            }

            // M192: horizontal collision â€” block pigs from walking through solid blocks
            float newPX = p.x + p.vx * dt;
            float newPZ = p.z + p.vz * dt;
            int pgY = (int)Math.floor(p.y); // block row pig occupies
            boolean blkPX = BlockId.isMovementBlocker(getBlock((int)Math.floor(newPX), pgY, (int)Math.floor(p.z)));
            boolean blkPZ = BlockId.isMovementBlocker(getBlock((int)Math.floor(p.x), pgY, (int)Math.floor(newPZ)));
            // Also block if next terrain is a sheer cliff (>0.55 step-up)
            int ghPX = ChunkGenerator.heightAt((int)Math.floor(newPX), (int)Math.floor(p.z), worldSeed);
            int ghPZ = ChunkGenerator.heightAt((int)Math.floor(p.x), (int)Math.floor(newPZ), worldSeed);
            if (!blkPX && (ghPX + 1f) <= p.y + 0.55f) p.x = newPX;
            else { p.vx = 0f; if (p.state == 1) { p.facing = fogRng.nextFloat() * (float)(Math.PI * 2.0); p.stateTimer = 0f; } }
            if (!blkPZ && (ghPZ + 1f) <= p.y + 0.55f) p.z = newPZ;
            else { p.vz = 0f; if (p.state == 1) { p.facing = fogRng.nextFloat() * (float)(Math.PI * 2.0); p.stateTimer = 0f; } }

            // Snap to terrain surface
            int tx = (int)Math.floor(p.x), tz = (int)Math.floor(p.z);
            float groundY = ChunkGenerator.heightAt(tx, tz, worldSeed) + 1f;
            p.y = groundY;

            float speed = (float)Math.sqrt(p.vx * p.vx + p.vz * p.vz);
            if (speed > 0.1f) p.animTime += dt * speed * 2.8f;

            // Despawn if far from player
            float pdx = p.x - cam.x, pdz = p.z - cam.z;
            if (pdx * pdx + pdz * pdz > 70f * 70f) { pigs.remove(i); }
        }
    }

    /** Render all active pigs as voxel characters. */
    private void renderPigs(Vector3f cam) {
        if (pigs.isEmpty()) return;
        WatcherBuilder wb = new WatcherBuilder();
        for (PigEntity p : pigs) {
            float dx = p.x - cam.x, dz = p.z - cam.z;
            if (dx * dx + dz * dz > 50f * 50f) continue;

            float cosA = (float)Math.cos(p.facing);
            float sinA = (float)Math.sin(p.facing);
            float ox = p.x, oy = p.y, oz = p.z;

            // M172: ghost pig uses pale blue-white; normal pig uses pink + hit-flash
            float flash = Math.max(0f, p.hitFlash);
            float pR, pG, pB, sR, sG, sB;
            if (p.spectral) {
                float flicker = 0.85f + 0.15f * (float)Math.sin(p.animTime * 12f);
                pR = 0.80f * flicker; pG = 0.88f * flicker; pB = 0.96f * flicker;
                sR = 0.70f * flicker; sG = 0.80f * flicker; sB = 0.92f * flicker;
            } else {
                pR = 0.90f + flash * 0.10f; pG = 0.68f - flash * 0.32f; pB = 0.65f - flash * 0.30f;
                sR = 0.80f; sG = 0.55f; sB = 0.52f;
            }

            // Leg swing (opposing diagonal pairs)
            float sw = (float)Math.sin(p.animTime * 2.4f) * 0.07f;

            // Body
            emitPigCube(wb, cosA, sinA, ox, oy, oz,  0f, 0.33f,  0f,    0.44f, 0.28f, 0.25f, pR, pG, pB);
            // Head
            emitPigCube(wb, cosA, sinA, ox, oy, oz,  0f, 0.53f, -0.38f, 0.21f, 0.20f, 0.18f, pR, pG, pB);
            // Snout
            emitPigCube(wb, cosA, sinA, ox, oy, oz,  0f, 0.44f, -0.58f, 0.10f, 0.08f, 0.07f, sR, sG, sB);
            // Ears
            emitPigCube(wb, cosA, sinA, ox, oy, oz, -0.11f, 0.74f, -0.33f, 0.06f, 0.05f, 0.04f, sR, sG, sB);
            emitPigCube(wb, cosA, sinA, ox, oy, oz,  0.11f, 0.74f, -0.33f, 0.06f, 0.05f, 0.04f, sR, sG, sB);
            // Tail
            emitPigCube(wb, cosA, sinA, ox, oy, oz,  0f, 0.42f,  0.27f, 0.05f, 0.04f, 0.03f, sR, sG, sB);
            // Legs (opposing pairs swing opposite phase)
            emitPigCube(wb, cosA, sinA, ox, oy, oz, -0.24f, 0.12f + sw, -0.18f, 0.08f, 0.12f, 0.08f, pR, pG, pB);
            emitPigCube(wb, cosA, sinA, ox, oy, oz,  0.24f, 0.12f - sw, -0.18f, 0.08f, 0.12f, 0.08f, pR, pG, pB);
            emitPigCube(wb, cosA, sinA, ox, oy, oz, -0.24f, 0.12f - sw,  0.18f, 0.08f, 0.12f, 0.08f, pR, pG, pB);
            emitPigCube(wb, cosA, sinA, ox, oy, oz,  0.24f, 0.12f + sw,  0.18f, 0.08f, 0.12f, 0.08f, pR, pG, pB);
        }
        if (wb.n <= 0) return;
        shader.setLight(lightDir, Math.max(ambient, 0.35f), direct, fogApplied, cam);
        streamMesh.draw(wb.a, wb.n); // M151: streaming draw
        shader.setLight(lightDir, ambient + lightningWorldFlash, direct, fogApplied, cam);
    }

    // ---------------------------------------------------------------- CHICKENS (M192)
    private static final int CHICKEN_MAX = 5;
    private final java.util.List<ChickenEntity> chickens = new java.util.ArrayList<>();
    private float chickenSpawnTimer = 8f;

    private static final class ChickenEntity {
        float x, y, z;
        float vx = 0, vz = 0;
        int   health = 2;       // 2 hits to kill
        float animTime  = 0f;
        float stateTimer = 0f;
        int   state = 0;        // 0=IDLE 1=WANDER 2=FLEE
        float facing = 0f;
        float fleeFromX, fleeFromZ;
        float hitFlash = 0f;
        float peckTimer = 0f;   // head-bob peck animation cycle
        boolean pecking = false;
        ChickenEntity(float x, float y, float z, Random rng) {
            this.x = x; this.y = y; this.z = z;
            this.facing = rng.nextFloat() * (float)(Math.PI * 2.0);
            this.stateTimer = 1f + rng.nextFloat() * 2f;
            this.peckTimer = rng.nextFloat() * 6f;
        }
    }

    public boolean tryHitChicken(float px, float pz, float fwdX, float fwdZ, float range) {
        float best = Float.MAX_VALUE;
        ChickenEntity target = null;
        float r2 = range * range;
        for (ChickenEntity c : chickens) {
            float dx = c.x - px, dz = c.z - pz;
            float dist2 = dx * dx + dz * dz;
            if (dist2 > r2) continue;
            float dot = dx * fwdX + dz * fwdZ;
            if (dot < 0f) continue;
            if (dist2 < best) { best = dist2; target = c; }
        }
        if (target == null) return false;
        target.health--;
        target.hitFlash = 0.28f;
        target.state = 2;
        target.fleeFromX = px;
        target.fleeFromZ = pz;
        target.stateTimer = 4f + fogRng.nextFloat() * 2f;
        return true;
    }

    private void updateChickens(Vector3f cam, float dt) {
        // Spawn
        chickenSpawnTimer -= dt;
        if (chickenSpawnTimer <= 0f && chickens.size() < CHICKEN_MAX) {
            chickenSpawnTimer = 12f + fogRng.nextFloat() * 12f;
            float angle = (float)(fogRng.nextDouble() * Math.PI * 2.0);
            float dist  = 12f + fogRng.nextFloat() * 20f;
            float spx = cam.x + (float)Math.cos(angle) * dist;
            float spz = cam.z + (float)Math.sin(angle) * dist;
            int   spy = ChunkGenerator.heightAt((int)Math.floor(spx), (int)Math.floor(spz), worldSeed);
            byte  surf = getBlock((int)Math.floor(spx), spy, (int)Math.floor(spz));
            if (surf == BlockId.GRASS || surf == BlockId.DIRT) {
                chickens.add(new ChickenEntity(spx, spy + 1f, spz, fogRng));
            }
        }

        for (int i = chickens.size() - 1; i >= 0; i--) {
            ChickenEntity c = chickens.get(i);
            if (c.health <= 0) {
                int dropCount = 1 + (fogRng.nextFloat() < 0.40f ? 1 : 0);
                spawnDrop((int)Math.floor(c.x), (int)Math.floor(c.y), (int)Math.floor(c.z),
                          BlockId.RAW_CHICKEN, dropCount);
                chickens.remove(i);
                continue;
            }
            if (c.hitFlash > 0f) c.hitFlash -= dt;

            // Peck animation cycle (idle only)
            c.peckTimer -= dt;
            if (c.peckTimer <= 0f) {
                c.pecking = !c.pecking;
                c.peckTimer = c.pecking ? (0.3f + fogRng.nextFloat() * 0.3f)
                                        : (2.0f + fogRng.nextFloat() * 4.0f);
            }

            c.stateTimer -= dt;
            if (c.state == 2) { // FLEE
                float dx = c.x - c.fleeFromX, dz = c.z - c.fleeFromZ;
                float len = (float)Math.sqrt(dx * dx + dz * dz);
                if (len > 0.01f) {
                    c.vx = (dx / len) * 4.0f;
                    c.vz = (dz / len) * 4.0f;
                    c.facing = (float)Math.atan2(c.vx, -c.vz);
                }
                if (c.stateTimer <= 0f) { c.state = 1; c.stateTimer = 1.5f + fogRng.nextFloat() * 2f; }
            } else if (c.state == 1) { // WANDER
                c.vx = (float)Math.sin(c.facing) * 1.2f;
                c.vz = -(float)Math.cos(c.facing) * 1.2f;
                if (c.stateTimer <= 0f) { c.state = 0; c.stateTimer = 1f + fogRng.nextFloat() * 3f; c.vx = 0; c.vz = 0; }
            } else { // IDLE
                c.vx = 0; c.vz = 0;
                if (c.stateTimer <= 0f) {
                    c.state = 1;
                    c.facing = fogRng.nextFloat() * (float)(Math.PI * 2.0);
                    c.stateTimer = 1f + fogRng.nextFloat() * 2f;
                }
            }

            // M192: collision â€” block chickens from walking through solid blocks/cliffs
            float newCX = c.x + c.vx * dt;
            float newCZ = c.z + c.vz * dt;
            int cgY = (int)Math.floor(c.y);
            boolean blkCX = BlockId.isMovementBlocker(getBlock((int)Math.floor(newCX), cgY, (int)Math.floor(c.z)));
            boolean blkCZ = BlockId.isMovementBlocker(getBlock((int)Math.floor(c.x), cgY, (int)Math.floor(newCZ)));
            int ghCX = ChunkGenerator.heightAt((int)Math.floor(newCX), (int)Math.floor(c.z), worldSeed);
            int ghCZ = ChunkGenerator.heightAt((int)Math.floor(c.x), (int)Math.floor(newCZ), worldSeed);
            if (!blkCX && (ghCX + 1f) <= c.y + 0.55f) c.x = newCX;
            else { c.vx = 0f; c.facing = fogRng.nextFloat() * (float)(Math.PI * 2.0); c.stateTimer = 0f; }
            if (!blkCZ && (ghCZ + 1f) <= c.y + 0.55f) c.z = newCZ;
            else { c.vz = 0f; c.facing = fogRng.nextFloat() * (float)(Math.PI * 2.0); c.stateTimer = 0f; }

            // Snap to ground
            int ctx = (int)Math.floor(c.x), ctz = (int)Math.floor(c.z);
            c.y = ChunkGenerator.heightAt(ctx, ctz, worldSeed) + 1f;

            float spd = (float)Math.sqrt(c.vx * c.vx + c.vz * c.vz);
            if (spd > 0.1f) c.animTime += dt * spd * 3.2f;

            // Despawn if far
            float cdx = c.x - cam.x, cdz = c.z - cam.z;
            if (cdx * cdx + cdz * cdz > 70f * 70f) chickens.remove(i);
        }
    }

    private void renderChickens(Vector3f cam) {
        if (chickens.isEmpty()) return;
        WatcherBuilder wb = new WatcherBuilder();
        for (ChickenEntity c : chickens) {
            float dx = c.x - cam.x, dz = c.z - cam.z;
            if (dx * dx + dz * dz > 50f * 50f) continue;

            float cosA = (float)Math.cos(c.facing);
            float sinA = (float)Math.sin(c.facing);
            float ox = c.x, oy = c.y, oz = c.z;

            float flash = Math.max(0f, c.hitFlash);
            // White body with hit flash
            float bR = 0.96f + flash * 0.04f;
            float bG = 0.94f - flash * 0.48f;
            float bB = 0.90f - flash * 0.45f;
            float legR = 0.88f, legG = 0.60f, legB = 0.22f; // orange legs/beak
            float wR   = 0.72f, wG   = 0.10f, wB   = 0.10f; // red wattle

            // Leg swing
            float sw = (float)Math.sin(c.animTime * 3.0f) * 0.06f;
            // Head peck tilt
            float peckOff = c.pecking ? -0.06f : 0f;

            // Body (compact, slightly taller than wide)
            emitPigCube(wb, cosA, sinA, ox, oy, oz,  0f,  0.28f,  0f,    0.22f, 0.20f, 0.18f, bR, bG, bB);
            // Wings (flat panels on sides)
            emitPigCube(wb, cosA, sinA, ox, oy, oz, -0.25f, 0.26f,  0.02f, 0.04f, 0.16f, 0.14f, bR * 0.88f, bG * 0.88f, bB * 0.88f);
            emitPigCube(wb, cosA, sinA, ox, oy, oz,  0.25f, 0.26f,  0.02f, 0.04f, 0.16f, 0.14f, bR * 0.88f, bG * 0.88f, bB * 0.88f);
            // Tail (upright feather tuft)
            emitPigCube(wb, cosA, sinA, ox, oy, oz,  0f,  0.44f,  0.18f, 0.06f, 0.10f, 0.04f, bR * 0.82f, bG * 0.82f, bB * 0.82f);
            // Head (pecks down when pecking flag)
            emitPigCube(wb, cosA, sinA, ox, oy, oz,  0f,  0.46f + peckOff, -0.22f, 0.14f, 0.14f, 0.13f, bR, bG, bB);
            // Beak
            emitPigCube(wb, cosA, sinA, ox, oy, oz,  0f,  0.44f + peckOff, -0.36f, 0.06f, 0.04f, 0.05f, legR, legG, legB);
            // Wattle (red chin)
            emitPigCube(wb, cosA, sinA, ox, oy, oz,  0f,  0.38f + peckOff, -0.32f, 0.04f, 0.04f, 0.03f, wR, wG, wB);
            // Comb (red top)
            emitPigCube(wb, cosA, sinA, ox, oy, oz,  0f,  0.60f + peckOff, -0.20f, 0.04f, 0.05f, 0.03f, wR, wG, wB);
            // Legs
            emitPigCube(wb, cosA, sinA, ox, oy, oz, -0.10f, 0.08f + sw, 0f, 0.05f, 0.10f, 0.05f, legR, legG, legB);
            emitPigCube(wb, cosA, sinA, ox, oy, oz,  0.10f, 0.08f - sw, 0f, 0.05f, 0.10f, 0.05f, legR, legG, legB);
        }
        if (wb.n <= 0) return;
        shader.setLight(lightDir, Math.max(ambient, 0.35f), direct, fogApplied, cam);
        streamMesh.draw(wb.a, wb.n);
        shader.setLight(lightDir, ambient + lightningWorldFlash, direct, fogApplied, cam);
    }

    // ---------------------------------------------------------------- THE SCREAMER (M196)

    private static final class ScreamerEntity {
        float x, y, z;
        float facing    = 0f;
        float animTime  = 0f;
        float stateTimer = 3f;
        int   state = 0;        // 0=WANDER  1=STARE  2=SCREAM
        boolean remove  = false; // M199: flag for post-scream despawn
        boolean hideSeek  = false; // M215: true = spawned by hide & seek event
        float   aiDelay   = 0f;   // M219: seconds of frozen AI before activation
        float patrolCX = 0f, patrolCZ = 0f; // M215: patrol centre
        float patrolTargetX = 0f, patrolTargetZ = 0f; // M215: current wander target
        static final int WANDER = 0, STARE = 1, SCREAM = 2, PATROL = 3;

        ScreamerEntity(float x, float y, float z, Random rng) {
            this.x = x; this.y = y; this.z = z;
            this.facing = rng.nextFloat() * (float)(Math.PI * 2.0);
            this.stateTimer = 2f + rng.nextFloat() * 3f;
        }
    }

    // M219: Line-of-sight and wall-collision helpers
    private boolean hasLOS(float mx, float my, float mz, float px, float py, float pz) {
        float dx = px - mx, dy = py - my, dz = pz - mz;
        float len = (float)Math.sqrt(dx*dx + dy*dy + dz*dz);
        if (len < 1f) return true;
        int steps = Math.max(2, (int)(len * 3));
        float sx = dx/steps, sy = dy/steps, sz = dz/steps;
        for (int i = 1; i < steps; i++) {
            byte b = getBlock((int)Math.floor(mx + sx*i), (int)Math.floor(my + sy*i), (int)Math.floor(mz + sz*i));
            if (BlockId.isSolid(b) && !BlockId.isLiquid(b)) return false;
        }
        return true;
    }

    private boolean isMoveBlocked(float nx, float ny, float nz) {
        int bx = (int)Math.floor(nx), by = (int)Math.floor(ny), bz = (int)Math.floor(nz);
        boolean lo = BlockId.isSolid(getBlock(bx, by,   bz)) && !BlockId.isLiquid(getBlock(bx, by,   bz));
        boolean hi = BlockId.isSolid(getBlock(bx, by+1, bz)) && !BlockId.isLiquid(getBlock(bx, by+1, bz));
        return lo || hi;
    }

    /** M239: 4-corner bounding box check for entity movement — prevents clipping through wall corners. */
    private static final float ENTITY_HALF_W = 0.28f;
    private boolean isEntityBlocked(float nx, float ny, float nz) {
        int by = (int)Math.floor(ny);
        float[] xs = { nx - ENTITY_HALF_W, nx - ENTITY_HALF_W, nx + ENTITY_HALF_W, nx + ENTITY_HALF_W };
        float[] zs = { nz - ENTITY_HALF_W, nz + ENTITY_HALF_W, nz - ENTITY_HALF_W, nz + ENTITY_HALF_W };
        for (int i = 0; i < 4; i++) {
            int bx = (int)Math.floor(xs[i]);
            int bz = (int)Math.floor(zs[i]);
            if ((BlockId.isSolid(getBlock(bx, by,   bz)) && !BlockId.isLiquid(getBlock(bx, by,   bz))) ||
                (BlockId.isSolid(getBlock(bx, by+1, bz)) && !BlockId.isLiquid(getBlock(bx, by+1, bz)))) {
                return true;
            }
        }
        return false;
    }

    /** Emit a body-part cube rotated around the Screamer's Y-axis. */
    private void emitScreamerCube(WatcherBuilder wb, float cosA, float sinA,
                                   float ox, float oy, float oz,
                                   float lx, float ly, float lz,
                                   float hw, float hh, float hd,
                                   float cr, float cg, float cb) {
        float rx = cosA * lx - sinA * lz;
        float rz = sinA * lx + cosA * lz;
        emitCube(wb, ox+rx-hw, oy+ly-hh, oz+rz-hd,
                     ox+rx+hw, oy+ly+hh, oz+rz+hd, cr, cg, cb);
    }

    private void updateScreamers(Vector3f cam, float dt) {
        if (!nightMode) { screamers.clear(); screamerSpawnTimer = Math.max(screamerSpawnTimer, 60f); return; }
        // M199: only 1 screamer active at a time, gated behind a spawn timer
        screamerSpawnTimer -= dt;
        if (screamers.isEmpty() && screamerSpawnTimer <= 0f) {
            float a  = (float)(fogRng.nextDouble() * Math.PI * 2.0);
            float r  = 35f + fogRng.nextFloat() * 25f;
            float sx = cam.x + (float)Math.cos(a) * r;
            float sz = cam.z + (float)Math.sin(a) * r;
            sx = Math.max(-(ChunkGenerator.WORLD_RADIUS - 5), Math.min(ChunkGenerator.WORLD_RADIUS - 5, sx));
            sz = Math.max(-(ChunkGenerator.WORLD_RADIUS - 5), Math.min(ChunkGenerator.WORLD_RADIUS - 5, sz));
            float sy = ChunkGenerator.heightAt((int)Math.floor(sx), (int)Math.floor(sz), worldSeed) + 1f;
            screamers.add(new ScreamerEntity(sx, sy, sz, fogRng));
            screamerSpawnTimer = 120f + fogRng.nextFloat() * 60f; // 2-3 min before another can spawn
        }

        screamers.removeIf(s -> s.remove);

        for (ScreamerEntity s : screamers) {
            s.animTime += dt;
            if (s.aiDelay > 0f) { s.aiDelay -= dt; continue; } // M219: frozen during countdown
            s.stateTimer = Math.max(0f, s.stateTimer - dt);
            float dx = cam.x - s.x, dz = cam.z - s.z;

            switch (s.state) {
                case ScreamerEntity.WANDER -> {
                    // Run in facing direction, flailing around
                    float speed = 3.2f;
                    float wNX = s.x + (float)Math.cos(s.facing) * speed * dt;
                    float wNZ = s.z + (float)Math.sin(s.facing) * speed * dt;
                    if      (!isEntityBlocked(wNX, s.y, wNZ)) { s.x = wNX; s.z = wNZ; }
                    else if (!isEntityBlocked(wNX, s.y, s.z)) { s.x = wNX; s.facing = -(float)Math.PI - s.facing; }
                    else if (!isEntityBlocked(s.x, s.y, wNZ)) { s.z = wNZ; s.facing = (float)Math.PI - s.facing; }
                    else                                     { s.facing += (float)Math.PI; }
                    if (liminalZoneId == 2) {
                        s.x = Math.max(-16f, Math.min(16f, s.x));
                        s.z = Math.max(-46f, Math.min(-2f, s.z));
                        s.y = 1.0f;
                    } else {
                        s.x = Math.max(-(ChunkGenerator.WORLD_RADIUS-5), Math.min(ChunkGenerator.WORLD_RADIUS-5, s.x));
                        s.z = Math.max(-(ChunkGenerator.WORLD_RADIUS-5), Math.min(ChunkGenerator.WORLD_RADIUS-5, s.z));
                        s.y = ChunkGenerator.heightAt((int)Math.floor(s.x), (int)Math.floor(s.z), worldSeed) + 1f;
                    }
                    // Change direction periodically
                    if (s.stateTimer <= 0f) {
                        s.facing += (float)((fogRng.nextDouble() - 0.5) * Math.PI * 1.4);
                        s.stateTimer = 2f + fogRng.nextFloat() * 3f;
                    }
                    // Player within 18 blocks with line of sight -> STARE
                    if (dx*dx + dz*dz < 18f*18f && hasLOS(s.x, s.y+1f, s.z, cam.x, cam.y+1f, cam.z)) {
                        s.state = ScreamerEntity.STARE;
                        s.stateTimer = 2.0f;
                    }
                }
                case ScreamerEntity.STARE -> {
                    // Face player, stand still, 2-second stare
                    s.facing = (float)Math.atan2(dz, dx);
                    if (s.stateTimer <= 0f) {
                        s.state = ScreamerEntity.SCREAM;
                        s.stateTimer = 5.0f;
                        // Signal to GameApp to play audio
                        screamerSoundPending = true;
                        screamerSoundXYZ[0] = s.x;
                        screamerSoundXYZ[1] = s.y;
                        screamerSoundXYZ[2] = s.z;
                    }
                }
                case ScreamerEntity.SCREAM -> {
                    // Chase the player while screaming
                    s.facing = (float)Math.atan2(dz, dx);
                    float chaseSpeed = 6.5f;
                    float cNX = s.x + (float)Math.cos(s.facing) * chaseSpeed * dt;
                    float cNZ = s.z + (float)Math.sin(s.facing) * chaseSpeed * dt;
                    if      (!isEntityBlocked(cNX, s.y, cNZ)) { s.x = cNX; s.z = cNZ; }
                    else if (!isEntityBlocked(cNX, s.y, s.z)) { s.x = cNX; }
                    else if (!isEntityBlocked(s.x, s.y, cNZ)) { s.z = cNZ; }
                    if (liminalZoneId == 2) {
                        s.x = Math.max(-16f, Math.min(16f, s.x));
                        s.z = Math.max(-46f, Math.min(-2f, s.z));
                        s.y = 1.0f;
                    } else {
                        s.x = Math.max(-(ChunkGenerator.WORLD_RADIUS-5), Math.min(ChunkGenerator.WORLD_RADIUS-5, s.x));
                        s.z = Math.max(-(ChunkGenerator.WORLD_RADIUS-5), Math.min(ChunkGenerator.WORLD_RADIUS-5, s.z));
                        s.y = ChunkGenerator.heightAt((int)Math.floor(s.x), (int)Math.floor(s.z), worldSeed) + 1f;
                    }
                    if (s.stateTimer <= 0f) {
                        s.remove = true; // M199: despawn after scream; timer gates next spawn
                    }
                }
                case ScreamerEntity.PATROL -> {
                    // M215: Hide & Seek patrol Ã¢â‚¬â€ wanders within ~22 blocks of patrol centre
                    float pdx = s.patrolTargetX - s.x, pdz = s.patrolTargetZ - s.z;
                    float pdist2 = pdx*pdx + pdz*pdz;
                    if (pdist2 < 2.0f || s.stateTimer <= 0f) {
                        // Pick a new random target within patrol radius of centre
                        float angle = fogRng.nextFloat() * (float)(Math.PI * 2.0);
                        float rad   = 6f + fogRng.nextFloat() * 16f;
                        s.patrolTargetX = s.patrolCX + (float)Math.cos(angle) * rad;
                        s.patrolTargetZ = s.patrolCZ + (float)Math.sin(angle) * rad;
                        s.stateTimer = 3f + fogRng.nextFloat() * 4f;
                    }
                    s.facing = (float)Math.atan2(pdz, pdx);
                    float ps = 2.8f;
                    float pNX = s.x + (float)Math.cos(s.facing) * ps * dt;
                    float pNZ = s.z + (float)Math.sin(s.facing) * ps * dt;
                    if      (!isEntityBlocked(pNX, s.y, pNZ)) { s.x = pNX; s.z = pNZ; }
                    else if (!isEntityBlocked(pNX, s.y, s.z)) { s.x = pNX; s.stateTimer = 0f; }
                    else if (!isEntityBlocked(s.x, s.y, pNZ)) { s.z = pNZ; s.stateTimer = 0f; }
                    else                                     { s.stateTimer = 0f; }
                    if (liminalZoneId == 2) {
                        s.x = Math.max(-16f, Math.min(16f, s.x));
                        s.z = Math.max(-46f, Math.min(-2f, s.z));
                        s.y = 1.0f;
                        // Also clamp patrol targets to mansion interior
                        s.patrolTargetX = Math.max(-16f, Math.min(16f, s.patrolTargetX));
                        s.patrolTargetZ = Math.max(-46f, Math.min(-2f, s.patrolTargetZ));
                    } else {
                        s.x = Math.max(-(ChunkGenerator.WORLD_RADIUS-5), Math.min(ChunkGenerator.WORLD_RADIUS-5, s.x));
                        s.z = Math.max(-(ChunkGenerator.WORLD_RADIUS-5), Math.min(ChunkGenerator.WORLD_RADIUS-5, s.z));
                        s.y = ChunkGenerator.heightAt((int)Math.floor(s.x), (int)Math.floor(s.z), worldSeed) + 1f;
                    }
                    // If player visible within 12 blocks (LOS required), switch to STARE -> SCREAM
                    if (dx*dx + dz*dz < 12f*12f && hasLOS(s.x, s.y+1f, s.z, cam.x, cam.y+1f, cam.z)) {
                        s.state = ScreamerEntity.STARE;
                        s.stateTimer = 2.0f;
                        if (s.hideSeek) hideSeekCaughtPending = true; // M218: player found
                    }
                }
            }
        }
    }

    private void renderScreamers(Vector3f cam) {
        if (screamers.isEmpty()) return;
        WatcherBuilder wb = new WatcherBuilder();

        for (ScreamerEntity s : screamers) {
            float dist = (float)Math.sqrt((s.x-cam.x)*(s.x-cam.x) + (s.z-cam.z)*(s.z-cam.z));
            if (dist > 80f) continue;
            float ff   = entityFogFrac(dist);
            float cosA = (float)Math.cos(s.facing);
            float sinA = (float)Math.sin(s.facing);
            float ox = s.x, oy = s.y, oz = s.z;

            // Dark gray body â€” blends with darkness, slightly fog-tinted at distance
            float wR = fogMix(0.22f, clearR, ff), wG = fogMix(0.22f, clearG, ff), wB = fogMix(0.24f, clearB, ff);
            // Eyes: hollow dark normally, bright crimson during SCREAM
            float eR, eG, eB;
            if (s.state == ScreamerEntity.SCREAM) {
                float ef = ff * 0.10f;
                eR = fogMix(2.0f, clearR, ef); eG = fogMix(0.08f, clearG, ef); eB = fogMix(0.08f, clearB, ef);
            } else {
                eR = fogMix(0.06f, clearR, ff); eG = fogMix(0.06f, clearG, ff); eB = fogMix(0.08f, clearB, ff);
            }

            // Torso
            emitScreamerCube(wb, cosA, sinA, ox, oy, oz,  0f,    0.80f,  0f,    0.22f, 0.80f, 0.18f, wR, wG, wB);
            // Head
            emitScreamerCube(wb, cosA, sinA, ox, oy, oz,  0f,    1.72f,  0f,    0.20f, 0.22f, 0.20f, wR, wG, wB);
            // Eye sockets (front face)
            emitScreamerCube(wb, cosA, sinA, ox, oy, oz, -0.10f, 1.78f, -0.19f, 0.055f, 0.045f, 0.04f, eR, eG, eB);
            emitScreamerCube(wb, cosA, sinA, ox, oy, oz,  0.10f, 1.78f, -0.19f, 0.055f, 0.045f, 0.04f, eR, eG, eB);
            // Legs (slightly swinging)
            float legSw = (float)Math.sin(s.animTime * (s.state == ScreamerEntity.WANDER ? 6.5f : 1.0f)) * 0.10f;
            emitScreamerCube(wb, cosA, sinA, ox, oy, oz, -0.12f, 0.25f,  0f, 0.08f, 0.28f, 0.08f, wR, wG, wB);
            emitScreamerCube(wb, cosA, sinA, ox, oy, oz,  0.12f, 0.25f + legSw, 0f, 0.08f, 0.28f, 0.08f, wR, wG, wB);

            // Arms â€” animated per state
            float armLY, armRY, armLX, armRX, armShake;
            if (s.state == ScreamerEntity.WANDER) {
                // Wild flail: arms swing fast, opposite phase
                armLY = 1.20f + (float)Math.sin(s.animTime * 7.5f)              * 0.45f;
                armRY = 1.20f + (float)Math.sin(s.animTime * 7.5f + Math.PI) * 0.45f;
                armLX = -0.36f; armRX = 0.36f; armShake = 0f;
            } else if (s.state == ScreamerEntity.STARE) {
                // Arms slowly lower as it stares
                float t  = Math.max(0f, 1f - s.stateTimer / 2.0f);
                armLY = 1.20f - t * 0.25f; armRY = armLY;
                armLX = -0.36f; armRX = 0.36f; armShake = 0f;
            } else {
                // SCREAM: arms thrust wide and up, shaking violently
                armShake = (float)Math.sin(s.animTime * 20f) * 0.10f;
                armLY = 1.58f + armShake; armRY = 1.58f - armShake;
                armLX = -0.52f + armShake; armRX = 0.52f - armShake;
            }
            emitScreamerCube(wb, cosA, sinA, ox, oy, oz, armLX, armLY, 0f, 0.07f, 0.38f, 0.07f, wR, wG, wB);
            emitScreamerCube(wb, cosA, sinA, ox, oy, oz, armRX, armRY, 0f, 0.07f, 0.38f, 0.07f, wR, wG, wB);
        }

        if (wb.n <= 0) return;
        streamMesh.draw(wb.a, wb.n); // use normal scene lighting â€” dark gray blends naturally
    }

    /** M196: consume pending screamer sound â€” returns {x,y,z} if a scream just fired, null otherwise. */
    /** M215: Spawn a hide-and-seek screamer that patrols around the player position. */
    public void spawnHideSeekScreamer(float px, float pz) {
        screamers.removeIf(s -> s.hideSeek);
        float angle = fogRng.nextFloat() * (float)(Math.PI * 2.0);
        float dist  = 15f + fogRng.nextFloat() * 10f;
        float sx = px + (float)Math.cos(angle) * dist;
        float sz = pz + (float)Math.sin(angle) * dist;
        float sy;
        if (liminalZoneId == 2) {
            // M240: clamp to mansion interior; heightAt() returns overworld terrain (~70+) in zone 2
            sx = Math.max(-14f, Math.min(14f, sx));
            sz = Math.max(-44f, Math.min(-4f, sz));
            sy = 1.0f;
        } else {
            sx = Math.max(-(ChunkGenerator.WORLD_RADIUS-5), Math.min(ChunkGenerator.WORLD_RADIUS-5, sx));
            sz = Math.max(-(ChunkGenerator.WORLD_RADIUS-5), Math.min(ChunkGenerator.WORLD_RADIUS-5, sz));
            sy = ChunkGenerator.heightAt((int)Math.floor(sx), (int)Math.floor(sz), worldSeed) + 1f;
        }
        ScreamerEntity s = new ScreamerEntity(sx, sy, sz, fogRng);
        s.state      = ScreamerEntity.PATROL;
        s.hideSeek   = true;
        s.aiDelay    = 18.5f; // M219: freeze AI until countdown finishes
        s.patrolCX   = px;
        s.patrolCZ   = pz;
        s.patrolTargetX = sx;
        s.patrolTargetZ = sz;
        s.stateTimer = 4f;
        screamers.add(s);
        thickFogBurst = 5.0f; // M215: thick fog for hide & seek
    }

    /** M215: Despawn all hide-and-seek screamers (called when event ends). */
    public void clearHideSeekScreamer() {
        screamers.removeIf(s -> s.hideSeek);
    }

    /** M218: Returns true (once) if hide-seek screamer spotted the player. */
    public boolean consumeHideSeekCaught() {
        boolean v = hideSeekCaughtPending; hideSeekCaughtPending = false; return v;
    }

    /** M218: Trigger thick fog for hide & seek phase 1 (before screamer spawns). */
    public void spawnHideSeekFog() { thickFogBurst = 5.0f; }

    public float[] consumeScreamerSoundPos() {
        if (!screamerSoundPending) return null;
        screamerSoundPending  = false;
        hideSeekCaughtPending = false;
        return new float[]{screamerSoundXYZ[0], screamerSoundXYZ[1], screamerSoundXYZ[2]};
    }

    // ---------------------------------------------------------------- THE NUN (M201)
    // Inspired by Nun Massacre (Puppet Combo / Night of the Nun, 2018).
    // Tall figure: long black habit, white guimpe, pale face, kitchen knife.
    // AI: PATROL (slow wander) -> HUNT (relentless stalk) -> STRIKE (knife attack) -> RETREAT
    private static final class NunEntity {
        static final int PATROL  = 0;
        static final int HUNT    = 1;
        static final int STRIKE  = 2;
        static final int RETREAT = 3;
        float x, y, z;
        float facing;
        int   state       = PATROL;
        float stateTimer  = 0f;
        float animTime    = 0f;
        float stepTimer   = 0f;
        float wanderTimer = 0f;
        boolean remove   = false;
        boolean hitFired = false; // true once damage fired this strike cycle
        NunEntity(float x, float y, float z, Random rng) {
            this.x = x; this.y = y; this.z = z;
            this.facing      = rng.nextFloat() * (float)(Math.PI * 2.0);
            this.wanderTimer = 3f + rng.nextFloat() * 4f;
        }
    }

    /** Emit a rotated body-part cube for THE NUN (same convention as emitScreamerCube). */
    private void emitNunCube(WatcherBuilder wb, float cosA, float sinA,
                              float ox, float oy, float oz,
                              float lx, float ly, float lz,
                              float hw, float hh, float hd,
                              float cr, float cg, float cb) {
        float rx = cosA * lx - sinA * lz;
        float rz = sinA * lx + cosA * lz;
        emitCube(wb, ox+rx-hw, oy+ly-hh, oz+rz-hd,
                     ox+rx+hw, oy+ly+hh, oz+rz+hd, cr, cg, cb);
    }

    private void updateNuns(Vector3f cam, float dt) {
        if (!nunModelLoaded) loadNunMesh(); // M205: lazy load FBX model on first call
        // Auto-spawn gate â€” debug-spawned nuns are NOT cleared here so F11 spawns persist
        boolean inMansion = (liminalZoneId == 2);
        boolean canDecrement = (nightMode && horrorProgression >= 0.20f) || inMansion;
        if (canDecrement) {
            nunSpawnTimer -= dt;
        } else {
            nunSpawnTimer = Math.max(nunSpawnTimer, 60f);
        }
        if (canDecrement && nuns.isEmpty() && nunSpawnTimer <= 0f) {
            float sx, sz, sy;
            if (inMansion) {
                // M232: spawn NUN randomly within mansion bounds
                sx = -14f + fogRng.nextFloat() * 28f;
                sz = -38f + fogRng.nextFloat() * 36f;
                sy = 2.0f;
            } else {
                float a  = (float)(fogRng.nextDouble() * Math.PI * 2.0);
                float r  = 30f + fogRng.nextFloat() * 20f;
                sx = cam.x + (float)Math.cos(a) * r;
                sz = cam.z + (float)Math.sin(a) * r;
                sx = Math.max(-(ChunkGenerator.WORLD_RADIUS-5), Math.min(ChunkGenerator.WORLD_RADIUS-5, sx));
                sz = Math.max(-(ChunkGenerator.WORLD_RADIUS-5), Math.min(ChunkGenerator.WORLD_RADIUS-5, sz));
                sy = ChunkGenerator.heightAt((int)Math.floor(sx), (int)Math.floor(sz), worldSeed) + 1f;
            }
            nuns.add(new NunEntity(sx, sy, sz, fogRng));
            nunSpawnTimer = inMansion ? 60f + fogRng.nextFloat() * 60f  // 1-2 min in mansion
                                       : 150f + fogRng.nextFloat() * 90f; // 2.5-4 min overworld
        }
        nuns.removeIf(n -> n.remove);
        for (NunEntity n : nuns) {
            n.animTime  += dt;
            n.stepTimer  = Math.max(0f, n.stepTimer - dt);
            float dx = cam.x - n.x, dz = cam.z - n.z;
            float distSq = dx * dx + dz * dz;
            float dy = cam.y - (n.y + 1.0f); // M239: vertical gap (player eye vs NUN centre)
            switch (n.state) {
                case NunEntity.PATROL -> {
                    float speed = 1.5f;
                    float nPX = n.x + (float)Math.cos(n.facing) * speed * dt;
                    float nPZ = n.z + (float)Math.sin(n.facing) * speed * dt;
                    if      (!isEntityBlocked(nPX, n.y, nPZ)) { n.x = nPX; n.z = nPZ; }
                    else if (!isEntityBlocked(nPX, n.y, n.z)) { n.x = nPX; n.wanderTimer = 0f; }
                    else if (!isEntityBlocked(n.x, n.y, nPZ)) { n.z = nPZ; n.wanderTimer = 0f; }
                    else                                     { n.wanderTimer = 0f; }
                    // M233: zone 2 — clamp to mansion bounds, snap to floor y=1
                    if (liminalZoneId == 2) {
                        n.x = Math.max(-16f, Math.min(16f, n.x));
                        n.z = Math.max(-46f, Math.min(-2f, n.z));
                        n.y = 1.0f;
                    } else {
                        n.x = Math.max(-(ChunkGenerator.WORLD_RADIUS-5), Math.min(ChunkGenerator.WORLD_RADIUS-5, n.x));
                        n.z = Math.max(-(ChunkGenerator.WORLD_RADIUS-5), Math.min(ChunkGenerator.WORLD_RADIUS-5, n.z));
                        n.y = ChunkGenerator.heightAt((int)Math.floor(n.x), (int)Math.floor(n.z), worldSeed) + 1f;
                    }
                    n.wanderTimer -= dt;
                    if (n.wanderTimer <= 0f) {
                        n.facing     += (float)((fogRng.nextDouble() - 0.5) * Math.PI * 1.2);
                        n.wanderTimer = 3f + fogRng.nextFloat() * 4f;
                    }
                    if (n.stepTimer <= 0f) {
                        nunStepPending = true;
                        nunStepXYZ[0] = n.x; nunStepXYZ[1] = n.y; nunStepXYZ[2] = n.z;
                        n.stepTimer = 0.7f;
                    }
                    if (distSq < 20f * 20f && hasLOS(n.x, n.y+1f, n.z, cam.x, cam.y+1f, cam.z)) n.state = NunEntity.HUNT;
                }
                case NunEntity.HUNT -> {
                    n.facing = (float)Math.atan2(dz, dx);
                    float speed = 2.4f;
                    float newX = n.x + (float)Math.cos(n.facing) * speed * dt;
                    float newZ = n.z + (float)Math.sin(n.facing) * speed * dt;
                    float ndx = cam.x - newX, ndz = cam.z - newZ;
                    if (liminalZoneId == 2) {
                        // M233: zone 2 — clamp to mansion, flat floor
                        if ((ndx*ndx + ndz*ndz) >= 0.8f * 0.8f && !isEntityBlocked(newX, n.y, newZ)) {
                            n.x = Math.max(-16f, Math.min(16f, newX));
                            n.z = Math.max(-46f, Math.min(-2f, newZ));
                        } else if (!isEntityBlocked(newX, n.y, n.z)) {
                            n.x = Math.max(-16f, Math.min(16f, newX));
                        } else if (!isEntityBlocked(n.x, n.y, newZ)) {
                            n.z = Math.max(-46f, Math.min(-2f, newZ));
                        }
                        n.y = 1.0f;
                    } else {
                        if ((ndx*ndx + ndz*ndz) >= 0.8f * 0.8f && !isEntityBlocked(newX, n.y, newZ)) {
                            n.x = Math.max(-(ChunkGenerator.WORLD_RADIUS-5), Math.min(ChunkGenerator.WORLD_RADIUS-5, newX));
                            n.z = Math.max(-(ChunkGenerator.WORLD_RADIUS-5), Math.min(ChunkGenerator.WORLD_RADIUS-5, newZ));
                        } else if (!isEntityBlocked(newX, n.y, n.z)) {
                            n.x = Math.max(-(ChunkGenerator.WORLD_RADIUS-5), Math.min(ChunkGenerator.WORLD_RADIUS-5, newX));
                        } else if (!isEntityBlocked(n.x, n.y, newZ)) {
                            n.z = Math.max(-(ChunkGenerator.WORLD_RADIUS-5), Math.min(ChunkGenerator.WORLD_RADIUS-5, newZ));
                        }
                        n.y = ChunkGenerator.heightAt((int)Math.floor(n.x), (int)Math.floor(n.z), worldSeed) + 1f;
                    }
                    if (n.stepTimer <= 0f) {
                        nunStepPending = true;
                        nunStepXYZ[0] = n.x; nunStepXYZ[1] = n.y; nunStepXYZ[2] = n.z;
                        n.stepTimer = 0.50f;
                    }
                    if (distSq < 1.5f * 1.5f && Math.abs(dy) < 2.5f) {
                        n.state = NunEntity.STRIKE;
                        n.stateTimer = 0.55f;
                        n.hitFired   = false;
                    }
                    if (distSq > 32f * 32f) {
                        n.state = NunEntity.PATROL;
                        n.wanderTimer = 3f + fogRng.nextFloat() * 3f;
                    }
                }
                case NunEntity.STRIKE -> {
                    n.facing = (float)Math.atan2(dz, dx);
                    n.stateTimer -= dt;
                    if (n.stateTimer <= 0f && !n.hitFired) {
                        if (distSq < 2.2f * 2.2f && Math.abs(dy) < 2.5f) nunHitPending = true;
                        n.hitFired   = true;
                        n.state      = NunEntity.RETREAT;
                        n.stateTimer = 0.40f;
                    }
                }
                case NunEntity.RETREAT -> {
                    n.stateTimer -= dt;
                    // Back away briefly
                    float rx2 = n.x - (float)Math.cos(n.facing) * 1.8f * dt;
                    float rz2 = n.z - (float)Math.sin(n.facing) * 1.8f * dt;
                    // M233: zone 2 — clamp to mansion, flat floor
                    if (liminalZoneId == 2) {
                        n.x = Math.max(-16f, Math.min(16f, rx2));
                        n.z = Math.max(-46f, Math.min(-2f, rz2));
                        n.y = 1.0f;
                    } else {
                        n.x = Math.max(-(ChunkGenerator.WORLD_RADIUS-5), Math.min(ChunkGenerator.WORLD_RADIUS-5, rx2));
                        n.z = Math.max(-(ChunkGenerator.WORLD_RADIUS-5), Math.min(ChunkGenerator.WORLD_RADIUS-5, rz2));
                        n.y = ChunkGenerator.heightAt((int)Math.floor(n.x), (int)Math.floor(n.z), worldSeed) + 1f;
                    }
                    if (n.stateTimer <= 0f) n.state = NunEntity.HUNT;
                }
            }
        }
    }

    private void renderNuns(Vector3f cam) {
        if (nuns.isEmpty()) return;

        // M201 blocky voxel nun (reverted from M205/M206-M209 redesign)
        WatcherBuilder wb = new WatcherBuilder();
        for (NunEntity n : nuns) {
            float dist = (float)Math.sqrt((n.x-cam.x)*(n.x-cam.x) + (n.z-cam.z)*(n.z-cam.z));
            if (dist > 80f) continue;
            float ff   = entityFogFrac(dist);
            float cosA = (float)Math.cos(n.facing);
            float sinA = (float)Math.sin(n.facing);
            float ox = n.x, oy = n.y, oz = n.z;

            float hR = fogMix(0.10f, clearR, ff), hG = fogMix(0.10f, clearG, ff), hB = fogMix(0.12f, clearB, ff); // black habit
            float wR = fogMix(0.86f, clearR, ff), wG = fogMix(0.86f, clearG, ff), wB = fogMix(0.86f, clearB, ff); // white wimple
            float fR = fogMix(0.78f, clearR, ff), fG = fogMix(0.72f, clearG, ff), fB = fogMix(0.64f, clearB, ff); // pale face
            float eR = fogMix(0.02f, clearR, ff), eG = fogMix(0.02f, clearG, ff), eB = fogMix(0.02f, clearB, ff); // dark eyes
            float kbR = fogMix(0.20f, clearR, ff), kbG = fogMix(0.14f, clearG, ff), kbB = fogMix(0.10f, clearB, ff); // knife handle
            float klR = fogMix(0.78f, clearR, ff), klG = fogMix(0.78f, clearG, ff), klB = fogMix(0.80f, clearB, ff); // knife blade

            // Lower body / skirt
            emitNunCube(wb, cosA, sinA, ox, oy, oz,  0f,   0.50f,  0f,   0.22f, 0.50f, 0.16f, hR, hG, hB);
            // Upper body
            emitNunCube(wb, cosA, sinA, ox, oy, oz,  0f,   1.16f,  0f,   0.17f, 0.16f, 0.13f, hR, hG, hB);
            // White guimpe (chest panel)
            emitNunCube(wb, cosA, sinA, ox, oy, oz,  0f,   1.18f, -0.13f, 0.09f, 0.18f, 0.005f, wR, wG, wB);
            // Head (pale face)
            emitNunCube(wb, cosA, sinA, ox, oy, oz,  0f,   1.58f,  0f,   0.10f, 0.15f, 0.09f, fR, fG, fB);
            // Black veil - wider + taller than head
            emitNunCube(wb, cosA, sinA, ox, oy, oz,  0f,   1.90f,  0f,   0.145f, 0.30f, 0.125f, hR, hG, hB);
            // Eyes
            emitNunCube(wb, cosA, sinA, ox, oy, oz, -0.045f, 1.62f, -0.085f, 0.026f, 0.020f, 0.006f, eR, eG, eB);
            emitNunCube(wb, cosA, sinA, ox, oy, oz,  0.045f, 1.62f, -0.085f, 0.026f, 0.020f, 0.006f, eR, eG, eB);
            // Left arm (hanging)
            emitNunCube(wb, cosA, sinA, ox, oy, oz, -0.28f, 1.20f, 0f, 0.065f, 0.28f, 0.065f, hR, hG, hB);
            // Right arm - animated: raised + thrust forward during STRIKE windup
            float armLY, armLZ;
            if (n.state == NunEntity.STRIKE && !n.hitFired) {
                float t = Math.max(0f, 1f - n.stateTimer / 0.55f);
                armLY = 1.20f + t * 0.42f; // raise up to ~1.62
                armLZ = -t * 0.16f;         // swing forward
            } else if (n.state == NunEntity.RETREAT && n.hitFired) {
                armLY = 1.35f; armLZ = -0.05f; // slightly elevated after striking
            } else {
                armLY = 1.20f; armLZ = 0f; // neutral hang
            }
            emitNunCube(wb, cosA, sinA, ox, oy, oz, 0.28f, armLY, armLZ, 0.065f, 0.28f, 0.065f, hR, hG, hB);
            // Knife at tip of right arm
            float kLY = armLY - 0.29f;
            float kLZ = armLZ;
            emitNunCube(wb, cosA, sinA, ox, oy, oz, 0.285f, kLY,         kLZ,         0.022f, 0.07f,  0.015f, kbR, kbG, kbB); // handle
            emitNunCube(wb, cosA, sinA, ox, oy, oz, 0.285f, kLY - 0.17f, kLZ - 0.04f, 0.012f, 0.17f,  0.008f, klR, klG, klB); // blade
        }
        if (wb.n <= 0) return;
        shader.setLight(lightDir, ambient, direct, fogApplied, cam);
        streamMesh.draw(wb.a, wb.n);
    }

    /** M201 DEBUG: force-spawn THE NUN 6 blocks in front of the camera, ignoring timer/night/horror gates. */
    public void debugSpawnNun(Vector3f cam, Vector3f fwd) {
        float sx = cam.x + fwd.x * 6f;
        float sz = cam.z + fwd.z * 6f;
        float sy = ChunkGenerator.heightAt((int)Math.floor(sx), (int)Math.floor(sz), worldSeed) + 1f;
        nuns.clear(); // one at a time
        nuns.add(new NunEntity(sx, sy, sz, fogRng));
        nunSpawnTimer = 30f; // cooldown after debug spawn
        System.out.println("[Debug] NUN spawned at " + sx + ", " + sy + ", " + sz);
    }

    /** M201: consume NUN knife strike â€” returns true if the knife just landed on the player. */
    public boolean consumeNunHit() {
        if (!nunHitPending) return false;
        nunHitPending = false;
        return true;
    }

    /** M201: consume NUN footstep position â€” returns {x,y,z} or null. */
    public float[] consumeNunStepPos() {
        if (!nunStepPending) return null;
        nunStepPending = false;
        return new float[]{nunStepXYZ[0], nunStepXYZ[1], nunStepXYZ[2]};
    }

    // M148: dropped item entity  - " physics block that flies out and lands, pickup by proximity
    private static final class DroppedItemData {
        float x, y, z;
        float vx, vy, vz;
        final byte blockId;
        final int count;
        float lifetime    = 60f;  // despawn after 60s
        float pickupDelay = 0.8f; // can't collect until after this countdown
        boolean settled   = false;
        float bobPhase;
        DroppedItemData(float x, float y, float z, byte id, int count, Random rng) {
            this.x = x; this.y = y; this.z = z;
            this.blockId = id; this.count = count;
            this.vx = (rng.nextFloat() - 0.5f) * 2.5f;
            this.vy = 3.8f;
            this.vz = (rng.nextFloat() - 0.5f) * 2.5f;
            this.bobPhase = rng.nextFloat() * (float)(Math.PI * 2.0);
        }
    }

    /** Spawn a dropped block item at world block position (bx, by, bz). */
    public void spawnDrop(int bx, int by, int bz, byte blockId, int count) {
        droppedItems.add(new DroppedItemData(bx + 0.5f, by + 0.6f, bz + 0.5f, blockId, count, fogRng));
    }

    /** Collect any settled drops within radius of (px, pz). Returns list of {blockId, count} pairs. */
    public java.util.List<int[]> collectDropsNear(float px, float pz, float radius) {
        java.util.List<int[]> out = new java.util.ArrayList<>();
        float r2 = radius * radius;
        for (int i = droppedItems.size() - 1; i >= 0; i--) {
            DroppedItemData d = droppedItems.get(i);
            if (d.pickupDelay > 0f) continue;
            float dx = d.x - px, dz = d.z - pz;
            if (dx * dx + dz * dz <= r2) {
                out.add(new int[]{d.blockId & 0xFF, d.count});
                droppedItems.remove(i);
            }
        }
        return out;
    }

    /** Returns RGB [0..1] for the dropped item cube colour. */
    private static float[] blockDropColor(byte id) {
        return switch (id) {
            case BlockId.GRASS          -> new float[]{0.30f, 0.62f, 0.26f};
            case BlockId.DIRT           -> new float[]{0.44f, 0.29f, 0.18f};
            case BlockId.STONE          -> new float[]{0.55f, 0.57f, 0.60f};
            case BlockId.COAL           -> new float[]{0.22f, 0.22f, 0.26f};
            case BlockId.RAW_PORK       -> new float[]{0.88f, 0.46f, 0.40f};
            case BlockId.LANTERN        -> new float[]{0.95f, 0.78f, 0.25f};
            case BlockId.WOOD           -> new float[]{0.58f, 0.40f, 0.22f};
            case BlockId.LEAVES         -> new float[]{0.22f, 0.56f, 0.24f};
            case BlockId.MUD            -> new float[]{0.28f, 0.22f, 0.16f};
            case BlockId.RELIC          -> new float[]{0.82f, 0.66f, 0.20f};
            case BlockId.JOURNAL        -> new float[]{0.72f, 0.70f, 0.62f};
            case BlockId.CAMPFIRE       -> new float[]{0.88f, 0.44f, 0.18f};
            case BlockId.BONES          -> new float[]{0.86f, 0.84f, 0.76f};
            case BlockId.COBWEB         -> new float[]{0.86f, 0.86f, 0.92f};
            case BlockId.CRYSTAL        -> new float[]{0.42f, 0.88f, 0.92f};
            case BlockId.WATER          -> new float[]{0.20f, 0.55f, 0.90f};
            case BlockId.FUNGUS         -> new float[]{0.68f, 0.44f, 0.68f};
            case BlockId.BLOODSTAIN     -> new float[]{0.72f, 0.12f, 0.12f};
            case BlockId.VOIDSTONE      -> new float[]{0.15f, 0.12f, 0.22f};
            case BlockId.WOOD_PLANK     -> new float[]{0.82f, 0.60f, 0.32f};
            case BlockId.CRAFTING_TABLE -> new float[]{0.52f, 0.34f, 0.20f};
            case BlockId.DOOR_CLOSED, BlockId.DOOR_OPEN -> new float[]{0.62f, 0.44f, 0.24f};
            case BlockId.TOOL_TORCH, BlockId.TORCH_STAND, BlockId.TORCH_WALL -> new float[]{0.96f, 0.74f, 0.24f};
            default                     -> new float[]{0.65f, 0.65f, 0.65f};
        };
    }

    /** Update dropped item physics and lifetime. Call once per frame with skyDt. */
    private void updateDroppedItems(float dt) {
        for (int i = droppedItems.size() - 1; i >= 0; i--) {
            DroppedItemData d = droppedItems.get(i);
            d.lifetime -= dt;
            if (d.lifetime <= 0f) { droppedItems.remove(i); continue; }
            if (d.pickupDelay > 0f) d.pickupDelay -= dt;

            if (!d.settled) {
                d.vy -= 20f * dt;  // gravity
                d.x += d.vx * dt;
                float newY = d.y + d.vy * dt;
                d.z += d.vz * dt;

                // Floor collision: check if block below current position is solid
                int ix = (int)Math.floor(d.x);
                int iy = (int)Math.floor(newY);
                int iz = (int)Math.floor(d.z);
                boolean solidBelow = BlockId.isMovementBlocker(getBlock(ix, iy - 1, iz));
                float floorY = iy; // top face of the solid block below

                if (d.vy < 0 && solidBelow && newY < floorY + 0.22f) {
                    newY = floorY + 0.22f;
                    d.vy = -d.vy * 0.32f;   // bounce with energy loss
                    d.vx *= 0.55f;
                    d.vz *= 0.55f;
                    if (Math.abs(d.vy) < 0.6f) {
                        d.vy = 0f; d.vx = 0f; d.vz = 0f;
                        d.settled = true;
                    }
                }
                d.y = newY;
            } else {
                // Settled: gentle bob
                d.bobPhase += dt * 1.6f;
            }
        }
    }

    /** Render all dropped items as small glowing cubes. */
    private void renderDroppedItems(Vector3f cam) {
        if (droppedItems.isEmpty()) return;
        WatcherBuilder wb = new WatcherBuilder();
        float s = 0.18f; // half-size of item cube
        for (DroppedItemData d : droppedItems) {
            float bob = d.settled ? (float)Math.sin(d.bobPhase) * 0.055f : 0f;
            float ry = d.y + bob;
            float[] c = blockDropColor(d.blockId);
            float cr = c[0], cg = c[1], cb = c[2];
            // Emit a simple AABB cube (rotation not fully supported in emitCube  - " use flat AABB, item still readable)
            emitCube(wb, d.x - s, ry - s, d.z - s, d.x + s, ry + s, d.z + s, cr, cg, cb);
            // Bright highlight cap so item stands out in dark caves
            emitCube(wb, d.x - s * 0.5f, ry + s * 0.6f, d.z - s * 0.5f,
                        d.x + s * 0.5f, ry + s * 1.1f, d.z + s * 0.5f,
                        Math.min(1f, cr * 1.5f), Math.min(1f, cg * 1.5f), Math.min(1f, cb * 1.5f));
        }
        // Upload and draw  - " boost ambient so items are always visible even in dark caves
        if (wb.n <= 0) return;
        shader.setLight(lightDir, Math.max(ambient, 0.80f), 0f, fogApplied, cam);
        streamMesh.draw(wb.a, wb.n); // M151: streaming draw
        // Restore normal lighting
        shader.setLight(lightDir, ambient + lightningWorldFlash, direct, fogApplied, cam);
    }

    private static final class WatcherBuilder {
        float[] a = new float[4096];
        int n = 0;
        void add(float v) { if (n >= a.length) a = Arrays.copyOf(a, a.length * 2); a[n++] = v; }
        void v(float x,float y,float z,float nx,float ny,float nz,float r,float g,float b){
            add(x);add(y);add(z); add(nx);add(ny);add(nz); add(r);add(g);add(b);
        }
    }

    // M32: fog-distance blend factor (0 = fully visible, 1 = fully fogged)
    private float entityFogFrac(float dist) {
        float f = 1f - (float)Math.exp(-fogApplied * 1.5f * dist * dist);
        return Math.max(0f, Math.min(0.55f, f)); // M44: cap at 0.55 so entities never fully disappear into fog
    }

    // M32: linearly interpolate entity colour toward sky/fog colour
    private static float fogMix(float entity, float sky, float fogFrac) {
        return entity * (1f - fogFrac) + sky * fogFrac;
    }

    private void renderFogWatchers(Vector3f cam) {
        WatcherBuilder fb = new WatcherBuilder();

        // Fading trace footprints
        for (int i = watcherTraces.size() - 1; i >= 0; i--) {
            WatcherTrace tr = watcherTraces.get(i);
            tr.ttl -= 0.016f;
            if (tr.ttl <= 0f) { watcherTraces.remove(i); continue; }
            float a = Math.max(0.05f, Math.min(0.35f, tr.ttl / 18f));
            emitShadowQuad(fb, tr.pos.x, tr.pos.y, tr.pos.z, 1.10f, 0.08f * a, 0.08f * a, 0.10f * a);
        }

        // NIGHT_SENTINEL â€” floating face only, always turns to watch the player (M195)
        int si = 0;
        for (Vector3f w : fogWatchers) {
            float dist = new Vector3f(w).sub(cam).length();
            float ff   = entityFogFrac(dist);
            float bob  = (float)Math.sin(entityAnimTime * 0.55f + si * 1.8f) * 0.05f; // gentle float
            si++;

            // Forward (toward player) and right vectors in XZ plane
            float fdx = cam.x - w.x, fdz = cam.z - w.z;
            float fd  = (float)Math.sqrt(fdx * fdx + fdz * fdz);
            if (fd < 0.001f) { fdx = 1f; fdz = 0f; } else { fdx /= fd; fdz /= fd; }
            float rtX = -fdz, rtZ = fdx; // right = 90-deg CCW of forward in XZ

            // Eyes + smile pierce fog â€” barely fade with distance
            float eyeFf = ff * 0.12f;
            float eR  = fogMix(2.2f, clearR, eyeFf), eG  = fogMix(2.2f, clearG, eyeFf), eB  = fogMix(2.4f, clearB, eyeFf);
            float smR = fogMix(1.9f, clearR, eyeFf), smG = fogMix(1.7f, clearG, eyeFf), smB = fogMix(0.9f, clearB, eyeFf);
            float sa = Math.max(0f, 0.07f * (1f - ff));

            float fp = 0.08f;          // face plane offset toward player
            float ey = w.y + 1.82f + bob; // floating eye height

            // Eyes â€” two glowing blobs oriented toward player
            float er = 0.068f;
            float lx = w.x + rtX * (-0.12f) + fdx * fp, lz = w.z + rtZ * (-0.12f) + fdz * fp;
            float rx = w.x + rtX * ( 0.12f) + fdx * fp, rz = w.z + rtZ * ( 0.12f) + fdz * fp;
            emitCube(fb, lx-er, ey-er, lz-er, lx+er, ey+er, lz+er, eR, eG, eB);
            emitCube(fb, rx-er, ey-er, rz-er, rx+er, ey+er, rz+er, eR, eG, eB);

            // Smile â€” 5 dots in a curving grin (outer corners higher than center)
            float[] smLat  = { -0.18f, -0.09f,  0.00f,  0.09f,  0.18f };
            float[] smYOff = {  0.06f,  0.00f, -0.025f, 0.00f,  0.06f };
            float sr = 0.048f;
            for (int k = 0; k < 5; k++) {
                float scx = w.x + rtX * smLat[k] + fdx * fp;
                float scy = (w.y + 1.64f + bob) + smYOff[k];
                float scz = w.z + rtZ * smLat[k] + fdz * fp;
                emitCube(fb, scx-sr, scy-sr, scz-sr, scx+sr, scy+sr, scz+sr, smR, smG, smB);
            }
            emitShadowQuad(fb, w.x, w.y + 0.03f, w.z, 0.9f, sa, sa, sa * 1.2f);
        }

        if (fb.n <= 0) return;
        streamMesh.draw(fb.a, fb.n); // M151: streaming draw
    }

    // ---------------------------------------------------------------- THE THING

    private void loadThingMesh() {
        thingMeshLoaded = true; // set first so we don't retry on failure

        // --- flat-colour fallback (always loaded) ---
        float[] flatVerts = ObjMesh.load(THING_PATH, 0.12f, 0.10f, 0.10f);
        if (flatVerts.length > 0) {
            thingMesh = new GpuMesh(flatVerts);
            System.out.println("[Renderer] THE THING mesh (flat) - " + (flatVerts.length / 9) + " verts");
        } else {
            System.out.println("[Renderer] THE THING mesh FAILED - entity will not render.");
            return;
        }

        // --- M43: textured variant ---
        float[] texVerts = ObjMesh.loadTextured(THING_PATH);
        if (texVerts.length > 0) {
            thingMeshTextured = new GpuMesh(texVerts, true);
            System.out.println("[Renderer] THE THING mesh (textured) - " + (texVerts.length / 8) + " verts");
        }

        // --- M43: load JPEG texture via Java ImageIO (no extra dependencies) ---
        String texPath = "models/thing/textured_mesh.jpg";
        try {
            java.io.File texFile = java.nio.file.Paths.get(texPath).toAbsolutePath().toFile();
            BufferedImage img = ImageIO.read(texFile);
            if (img != null) {
                int w = img.getWidth(), h = img.getHeight();
                int[] pixels = new int[w * h];
                img.getRGB(0, 0, w, h, pixels, 0, w);

                // Convert ARGB ints Ã¢â€ ' tightly packed RGB bytes, flipped vertically for OpenGL
                ByteBuffer buf = MemoryUtil.memAlloc(w * h * 3);
                for (int y = h - 1; y >= 0; y--) {
                    for (int x = 0; x < w; x++) {
                        int argb = pixels[y * w + x];
                        buf.put((byte) ((argb >> 16) & 0xFF)); // R
                        buf.put((byte) ((argb >>  8) & 0xFF)); // G
                        buf.put((byte) ( argb        & 0xFF)); // B
                    }
                }
                buf.flip();

                thingTexId = glGenTextures();
                glBindTexture(GL_TEXTURE_2D, thingTexId);
                glTexImage2D(GL_TEXTURE_2D, 0, GL_RGB, w, h, 0, GL_RGB, GL_UNSIGNED_BYTE, buf);
                glGenerateMipmap(GL_TEXTURE_2D);
                glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
                glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
                glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR_MIPMAP_LINEAR);
                glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
                glBindTexture(GL_TEXTURE_2D, 0);
                MemoryUtil.memFree(buf);
                System.out.println("[Renderer] THE THING texture loaded: " + w + "x" + h);
            }
        } catch (Exception e) {
            System.out.println("[Renderer] THE THING texture failed: " + e.getMessage());
        }

        // --- M43: optional eyes mesh (models/thing/eyes.obj loaded with flat white) ---
        String eyesPath = "models/thing/eyes.obj";
        if (java.nio.file.Files.exists(java.nio.file.Paths.get(eyesPath))) {
            float[] eyeVerts = ObjMesh.load(eyesPath, 1.0f, 1.0f, 1.0f); // pure white Ã¢â€ ' triggers eyeWhite glow
            if (eyeVerts.length > 0) {
                thingEyesMesh = new GpuMesh(eyeVerts);
                System.out.println("[Renderer] THE THING eyes mesh loaded - " + (eyeVerts.length / 9) + " verts");
            }
        } else {
            System.out.println("[Renderer] No eyes.obj found at " + eyesPath + " - skip eye mesh");
        }
    }

    // ---------------------------------------------------------------- NUN model load (M205)

    private void loadNunMesh() {
        // M207: load separated body + arm OBJs (rigid body-part animation)
        nunModelLoaded = true;

        java.nio.file.Path bodyAbs = java.nio.file.Paths.get(NUN_BODY_PATH).toAbsolutePath().normalize();
        java.nio.file.Path armAbs  = java.nio.file.Paths.get(NUN_ARM_PATH).toAbsolutePath().normalize();
        java.nio.file.Path texAbs  = java.nio.file.Paths.get(NUN_TEX_PATH).toAbsolutePath().normalize();

        System.out.println("[NunMesh] body : " + bodyAbs + "  exists=" + java.nio.file.Files.exists(bodyAbs));
        System.out.println("[NunMesh] arm  : " + armAbs  + "  exists=" + java.nio.file.Files.exists(armAbs));
        System.out.println("[NunMesh] tex  : " + texAbs  + "  exists=" + java.nio.file.Files.exists(texAbs));

        if (!java.nio.file.Files.exists(bodyAbs)) {
            System.out.println("[NunMesh] body.obj not found â€” voxel fallback stays active.");
            return;
        }

        float[] bodyVerts = ObjMesh.loadTextured(bodyAbs.toString());
        if (bodyVerts.length > 0) {
            nunBodyMesh = new GpuMesh(bodyVerts, true);
            System.out.println("[NunMesh] Body mesh: " + (bodyVerts.length / 8) + " verts");
        } else {
            System.out.println("[NunMesh] Body OBJ parse failed â€” voxel fallback stays active.");
            return;
        }

        // Arms use loadTexturedRaw â€” no Y-shift, pivot is shoulder joint not floor
        if (java.nio.file.Files.exists(armAbs)) {
            float[] armVerts = ObjMesh.loadTexturedRaw(armAbs.toString());
            if (armVerts.length > 0) {
                nunArmMesh = new GpuMesh(armVerts, true);
                System.out.println("[NunMesh] Arm mesh : " + (armVerts.length / 8) + " verts");
            }
        } else {
            System.out.println("[NunMesh] arm_right.obj not found â€” arm will not render separately.");
        }

        if (java.nio.file.Files.exists(texAbs)) {
            nunModelTex = TextureLoader.load(texAbs.toString());
        } else {
            System.out.println("[NunMesh] Texture not found â€” rendering untextured.");
        }
    }

    private void updateThing(Vector3f cam, float dt) {
        if (!thingMeshLoaded) loadThingMesh();

        thingDebugTimer = Math.max(0f, thingDebugTimer - dt);
        if (!nightMode && thingDebugTimer <= 0f) {
            if (thingActive) { thingActive = false; thingSpawnTimer = Math.max(thingSpawnTimer, 20f); }
            return;
        }

        if (!thingActive) {
            if (horrorProgression < 0.15f) return; // M152: THE THING waits for 15% progression
            thingSpawnTimer -= dt;
            if (thingSpawnTimer <= 0f) {
                float angle  = (float)(fogRng.nextDouble() * Math.PI * 2.0);
                float spawnR = 65f + fogRng.nextFloat() * 20f;
                thingPos.x = cam.x + (float)Math.cos(angle) * spawnR;
                thingPos.z = cam.z + (float)Math.sin(angle) * spawnR;
                thingPos.y = ChunkGenerator.heightAt((int)Math.floor(thingPos.x), (int)Math.floor(thingPos.z), worldSeed) + 1;
                thingFacing    = (float)Math.atan2(cam.x - thingPos.x, cam.z - thingPos.z);
                thingRetreating= false;
                thingActive    = true;
                thingState     = ThingState.SEEK_COVER;
                thingStateTimer= 0f;
                thingRenderFrames = 0;
                System.out.printf("[THE THING] Emerged at dist %.0f%n", spawnR);
            }
            return;
        }

        float dx   = cam.x - thingPos.x;
        float dz   = cam.z - thingPos.z;
        float dist = (float)Math.sqrt(dx * dx + dz * dz);
        thingStateTimer += dt;

        switch (thingState) {

            case SEEK_COVER -> {
                // Pick best hidden position that advances toward the player
                thingCoverTarget.set(findThingCoverPos(cam, dist));
                thingState     = ThingState.MOVE_TO_COVER;
                thingStateTimer= 0f;
            }

            case MOVE_TO_COVER -> {
                float tdx = thingCoverTarget.x - thingPos.x;
                float tdz = thingCoverTarget.z - thingPos.z;
                float td  = (float)Math.sqrt(tdx * tdx + tdz * tdz);

                if (td < 0.9f || thingStateTimer > 7f) {
                    // Reached cover (or timed out)  - " occasionally peek, otherwise advance
                    thingState     = (fogRng.nextFloat() < 0.32f && dist > 14f)
                            ? ThingState.PEEK : ThingState.SEEK_COVER;
                    thingStateTimer= 0f;
                } else {
                    float spd = THING_APPROACH_SPD * 1.6f;
                    thingPos.x += (tdx / td) * spd * dt;
                    thingPos.z += (tdz / td) * spd * dt;
                    thingPos.y = ChunkGenerator.heightAt((int)Math.floor(thingPos.x), (int)Math.floor(thingPos.z), worldSeed) + 1;
                    thingFacing = (float)Math.atan2(tdx, tdz);
                }
                if (dist < 13f) { thingState = ThingState.CLOSE_APPROACH; thingStateTimer = 0f; }
            }

            case PEEK -> {
                // Step slowly toward player  - " visible for ~2s, then back to cover hunting
                thingFacing = (float)Math.atan2(dx, dz);
                if (thingStateTimer < 2.0f && dist > 2f) {
                    float nx = dx / dist, nz = dz / dist;
                    thingPos.x += nx * THING_APPROACH_SPD * 0.4f * dt;
                    thingPos.z += nz * THING_APPROACH_SPD * 0.4f * dt;
                    thingPos.y = ChunkGenerator.heightAt((int)Math.floor(thingPos.x), (int)Math.floor(thingPos.z), worldSeed) + 1;
                } else if (thingStateTimer >= 2.0f) {
                    thingState = ThingState.SEEK_COVER;
                    thingStateTimer = 0f;
                }
                if (dist < 13f) { thingState = ThingState.CLOSE_APPROACH; thingStateTimer = 0f; }
            }

            case CLOSE_APPROACH -> {
                // Drops all stealth â€” walks straight at player relentlessly
                if (dist < 1.6f) {
                    // M198: GRAB â€” pick a random dark destination 50-80 blocks away, then drag
                    float dragAngle  = (float)(fogRng.nextDouble() * Math.PI * 2.0);
                    float dragRadius = 50f + fogRng.nextFloat() * 30f;
                    thingDragDestX = Math.max(-(ChunkGenerator.WORLD_RADIUS - 10),
                                     Math.min( ChunkGenerator.WORLD_RADIUS - 10,
                                     cam.x + (float)Math.cos(dragAngle) * dragRadius));
                    thingDragDestZ = Math.max(-(ChunkGenerator.WORLD_RADIUS - 10),
                                     Math.min( ChunkGenerator.WORLD_RADIUS - 10,
                                     cam.z + (float)Math.sin(dragAngle) * dragRadius));
                    thingState    = ThingState.DRAG;
                    thingStateTimer = 0f;
                    recordWatcherEvent("THING_DRAG");
                    return;
                }
                float nx = dx / Math.max(0.001f, dist);
                float nz = dz / Math.max(0.001f, dist);
                thingPos.x += nx * THING_APPROACH_SPD * dt;
                thingPos.z += nz * THING_APPROACH_SPD * dt;
                thingPos.y = ChunkGenerator.heightAt((int)Math.floor(thingPos.x), (int)Math.floor(thingPos.z), worldSeed) + 1;
                thingFacing = (float)Math.atan2(dx, dz);
                if (dist > 20f) { thingState = ThingState.SEEK_COVER; thingStateTimer = 0f; }
            }

            case RETREAT -> {
                thingRetreatTimer -= dt;
                float rx = -dx / Math.max(0.001f, dist);
                float rz = -dz / Math.max(0.001f, dist);
                thingPos.x += rx * THING_RETREAT_SPD * dt;
                thingPos.z += rz * THING_RETREAT_SPD * dt;
                thingPos.y = ChunkGenerator.heightAt((int)Math.floor(thingPos.x), (int)Math.floor(thingPos.z), worldSeed) + 1;
                thingFacing = (float)Math.atan2(rx, rz);
                if (thingRetreatTimer <= 0f || dist > 80f) {
                    thingActive     = false;
                    thingRetreating = false;
                    thingSpawnTimer = Math.max(380f, 560f - 150f * horrorProgression) + fogRng.nextFloat() * 90f; // M173
                    System.out.println("[THE THING] Retreated and vanished.");
                }
            }

            case DRAG -> {
                // M198: carry the player â€” move THE THING toward drag destination at speed
                float tdx  = thingDragDestX - thingPos.x;
                float tdz  = thingDragDestZ - thingPos.z;
                float td   = (float)Math.sqrt(tdx * tdx + tdz * tdz);
                float dragSpd = 9.0f;
                if (td > 1.0f) {
                    thingPos.x += (tdx / td) * dragSpd * dt;
                    thingPos.z += (tdz / td) * dragSpd * dt;
                    thingPos.y  = ChunkGenerator.heightAt((int)Math.floor(thingPos.x),
                                                          (int)Math.floor(thingPos.z), worldSeed) + 1;
                    thingFacing = (float)Math.atan2(tdx, tdz);
                }
                // Fire the teleport signal after 0.3s (brief grab frame) then retreat
                if (thingStateTimer >= 0.3f && !thingDragPending) {
                    thingDragPending = true; // consumed by GameApp â†’ teleports player
                }
                // End drag: arrived or timed out (max 8s)
                if (td < 1.5f || thingStateTimer > 8f) {
                    pendingPlayerHits++;  // damage on drop
                    thingRetreating   = true;
                    thingRetreatTimer = 5.0f;
                    thingState        = ThingState.RETREAT;
                    thingStateTimer   = 0f;
                    System.out.println("[THE THING] Released player at drag destination.");
                }
            }
        }
    }

    /**
     * Scan 12 candidate positions near THE THING; score by occlusion from player
     * (so it prefers hiding behind trees/terrain) plus distance gain toward player.
     */
    private Vector3f findThingCoverPos(Vector3f cam, float distToPlayer) {
        float     bestScore = -Float.MAX_VALUE;
        Vector3f  result    = new Vector3f(thingPos);
        float     toPlayer  = (float)Math.atan2(cam.x - thingPos.x, cam.z - thingPos.z);

        for (int i = 0; i < 12; i++) {
            // Spread candidates in an arc biased toward the player
            float spread = ((float)i / 11f - 0.5f) * 2f * (float)Math.PI * 0.75f;
            float angle  = toPlayer + spread;
            float r      = 5f + fogRng.nextFloat() * 14f;
            float cx     = thingPos.x + (float)Math.sin(angle) * r;
            float cz     = thingPos.z + (float)Math.cos(angle) * r;
            float cy     = ChunkGenerator.heightAt((int)Math.floor(cx), (int)Math.floor(cz), worldSeed) + 1;

            // Eye-level LOS check using real block data (catches trees)
            Vector3f candEye = new Vector3f(cx, cy + 1.7f, cz);
            float occl    = thingOcclusion(candEye, cam);

            // Reward positions that advance toward the player
            float newDist  = (float)Math.sqrt((cam.x-cx)*(cam.x-cx) + (cam.z-cz)*(cam.z-cz));
            float distGain = (distToPlayer - newDist) / Math.max(1f, distToPlayer);

            float score = occl * 0.65f + distGain * 0.35f;
            if (score > bestScore) {
                bestScore = score;
                result.set(cx, cy, cz);
            }
        }
        return result;
    }

    /**
     * Returns fraction of the line 'from Ã¢â€ ' cam' blocked by solid voxels.
     * 0 = full line of sight, 1 = completely occluded.
     * Uses getBlock() so trees (WOOD/LEAVES) and terrain all count.
     */
    private float thingOcclusion(Vector3f from, Vector3f cam) {
        final int steps = 10;
        int hits = 0;
        for (int i = 1; i < steps; i++) {
            float t  = (float) i / steps;
            int   bx = (int)Math.floor(from.x + (cam.x - from.x) * t);
            int   by = (int)Math.floor(from.y + (cam.y - from.y) * t);
            int   bz = (int)Math.floor(from.z + (cam.z - from.z) * t);
            if (BlockId.isSolid(getBlock(bx, by, bz))) hits++;
        }
        return hits / (float)(steps - 1);
    }

    private int thingRenderFrames = 0; // debug counter

    // M45: firefly system  - " moving emissive lights, flee from THE THING
    private static final int MAX_FIREFLIES = 20;
    private final Vector3f[] fireflyPos   = new Vector3f[MAX_FIREFLIES];
    private final float[]    fireflyPhase = new float[MAX_FIREFLIES];
    private int   fireflyCount      = 0;
    private float fireflySpawnTimer = 2f;
    private long  fireflyLastNs     = 0L;

    // M46: Will-o'-Wisps  - " predatory AI lights that lure the player
    private static final int WISP_IDLE  = 0;
    private static final int WISP_LURE  = 1;
    private static final int WISP_EVADE = 2;
    private static final int MAX_WISPS  = 3;
    private final Vector3f[] wispPos       = new Vector3f[MAX_WISPS];
    private final Vector3f[] wispTarget    = new Vector3f[MAX_WISPS];
    private final float[]    wispPhase     = new float[MAX_WISPS];
    private final int[]      wispState     = new int[MAX_WISPS];
    private final float[]    wispStateTimer= new float[MAX_WISPS];
    private int   wispCount      = 0;
    private float wispSpawnTimer = 5f;

    private void renderThing(Vector3f cam) {
        if (!thingActive || thingMesh == null) {
            // Only print once per activation to avoid log spam
            if (thingActive && thingMesh == null && thingRenderFrames == 0) {
                System.out.println("[THE THING] active but mesh is null - load failed, entity disabled.");
                thingRenderFrames = 1; // suppress repeat
            }
            return;
        }

        float dist = new Vector3f(thingPos).sub(cam).length();
        float ff   = entityFogFrac(dist);
        if (ff >= 0.94f) return; // fully in fog, skip draw

        if (thingRenderFrames < 5) {
            System.out.printf("[THE THING] rendering - pos(%.1f,%.1f,%.1f) dist=%.1f fog=%.2f%n",
                    thingPos.x, thingPos.y, thingPos.z, dist, ff);
            thingRenderFrames++;
        }

        // Subtle breathing bob
        float bob  = (float)Math.sin(entityAnimTime * 1.55f) * 0.045f;

        // Build model matrix: world translate Ã¢â€ ' Y rotation Ã¢â€ ' uniform scale Ã¢â€ ' model-space Y offset
        Matrix4f model = new Matrix4f()
                .translate(thingPos.x, thingPos.y + bob, thingPos.z)
                .rotateY(thingFacing + THING_FACING_OFF)
                .scale(THING_SCALE)
                .translate(0f, THING_MODEL_Y_OFF, 0f);

        shader.setLight(lightDir, ambient, direct, fogApplied, cam);
        shader.setModel(model);
        shader.setAnimTime(entityAnimTime, true);

        // M43: prefer textured render path when both mesh and texture are ready
        if (thingMeshTextured != null && thingTexId != -1) {
            glActiveTexture(GL_TEXTURE0);
            glBindTexture(GL_TEXTURE_2D, thingTexId);
            shader.setUseTexture(true);
            shader.setSpecular(0.35f);          // mild skin specularity
            thingMeshTextured.render();
            glBindTexture(GL_TEXTURE_2D, 0);
        } else {
            // Flat-colour fallback (dark silhouette)
            shader.setUseTexture(false);
            shader.setSpecular(0.0f);
            thingMesh.render();
        }

        // M43: eye mesh  - " white vertex colour triggers eyeWhite glow; high specular = glossy
        if (thingEyesMesh != null) {
            shader.setUseTexture(false);
            shader.setSpecular(6.0f);
            thingEyesMesh.render();
        }

        // Restore defaults
        shader.setAnimTime(0f, false);
        shader.setUseTexture(false);
        shader.setSpecular(0.0f);
        shader.setModelIdentity();
        shader.setLight(lightDir, ambient, direct, fogApplied, cam);
    }

    // ---------------------------------------------------------------- THE THING VOXEL (M52)

    /** Build THE THING as a procedural voxel humanoid  - " blocky, consistent with world aesthetic. */
    private void renderThingVoxel(Vector3f cam) {
        if (!thingActive) return;

        float dist = new Vector3f(thingPos).sub(cam).length();
        float ff   = entityFogFrac(dist);
        if (ff >= 0.98f) return; // M96: visible through heavy fog  - " psychological horror

        float angle = thingFacing + THING_FACING_OFF + (float)Math.PI; // M52 fix: voxel faces camera
        float cosA  = (float)Math.cos(angle);
        float sinA  = (float)Math.sin(angle);
        float sc    = THING_SCALE;
        float bob   = (float)Math.sin(entityAnimTime * 5.0f) * 0.03f;

        // Y-lift walking cycle  - " independent of rotation angle, always looks correct
        float wc    = Math.abs((float)Math.sin(entityAnimTime * 2.5f));
        float lLegY = 0.38f + wc * 0.16f;
        float rLegY = 0.38f + (1f - wc) * 0.16f;
        float lArmY = 1.05f - wc * 0.08f;
        float rArmY = 1.05f - (1f - wc) * 0.08f;

        float bR = 0.78f, bG = 0.75f, bB = 0.82f; // M96: pallid bone-white  - " psychological horror, clearly visible in darkness
        float eR = 0.96f, eG = 0.05f, eB = 0.05f; // red  - " triggers eyeRed glow in shader

        WatcherBuilder wb = new WatcherBuilder();
        emitThingCube(wb, cosA, sinA,  0.00f, 1.65f,  0.00f, 0.22f, 0.22f, 0.22f, bR, bG, bB, sc, bob); // head
        emitThingCube(wb, cosA, sinA,  0.00f, 1.05f,  0.00f, 0.28f, 0.35f, 0.18f, bR, bG, bB, sc, bob); // torso
        emitThingCube(wb, cosA, sinA, -0.42f, lArmY,  0.00f, 0.12f, 0.30f, 0.12f, bR, bG, bB, sc, bob); // L arm
        emitThingCube(wb, cosA, sinA,  0.42f, rArmY,  0.00f, 0.12f, 0.30f, 0.12f, bR, bG, bB, sc, bob); // R arm
        emitThingCube(wb, cosA, sinA, -0.18f, lLegY,  0.00f, 0.13f, 0.38f, 0.13f, bR, bG, bB, sc, bob); // L leg
        emitThingCube(wb, cosA, sinA,  0.18f, rLegY,  0.00f, 0.13f, 0.38f, 0.13f, bR, bG, bB, sc, bob); // R leg
        emitThingCube(wb, cosA, sinA, -0.09f, 1.70f, -0.21f, 0.055f, 0.055f, 0.04f, eR, eG, eB, sc, bob); // L eye
        emitThingCube(wb, cosA, sinA,  0.09f, 1.70f, -0.21f, 0.055f, 0.055f, 0.04f, eR, eG, eB, sc, bob); // R eye

        if (wb.n <= 0) return;

        shader.setModelIdentity();
        shader.setAnimTime(0f, false);
        shader.setUseTexture(false);
        shader.setSpecular(0f);
        shader.setLight(lightDir, ambient, direct, fogApplied, cam);
        streamMesh.draw(wb.a, wb.n); // M151: streaming draw
    }

    /** Emit one axis-aligned voxel cube at a Y-rotated, scaled position relative to an origin.
     *  Uses JOML-matching rotation convention: rx = cos*lx + sin*lz, rz = -sin*lx + cos*lz */
    private void emitVoxelCube(WatcherBuilder wb, float cosA, float sinA,
                                float originX, float originY, float originZ,
                                float lx, float ly, float lz,
                                float hw, float hh, float hd,
                                float cr, float cg, float cb) {
        float rx = cosA * lx + sinA * lz;
        float rz = -sinA * lx + cosA * lz;
        float wx = originX + rx;
        float wy = originY + ly;
        float wz = originZ + rz;
        emitCube(wb, wx - hw, wy - hh, wz - hd, wx + hw, wy + hh, wz + hd, cr, cg, cb);
    }

    /** Convenience wrapper that scales local offsets and half-sizes by scale + adds body bob. */
    private void emitThingCube(WatcherBuilder wb, float cosA, float sinA,
                                float lx, float ly, float lz,
                                float hw, float hh, float hd,
                                float cr, float cg, float cb,
                                float scale, float bob) {
        emitVoxelCube(wb, cosA, sinA,
                thingPos.x, thingPos.y + bob, thingPos.z,
                lx * scale, ly * scale, lz * scale,
                hw * scale, hh * scale, hd * scale,
                cr, cg, cb);
    }

    // ---------------------------------------------------------------- THE FIGURE (M54)

    private static float lerpF(float a, float b, float t) { return a + (b - a) * t; }
    /** M153: figure spawn delay â€” early 8-12 min, late game 3-5 min (was 2-4 min; toned down). */
    private float nextFigureSpawnDelay() {
        float base = Math.max(400f, 720f - 320f * horrorProgression); // 720sâ†’400s (rarer)
        return base + fogRng.nextFloat() * base * 0.4f;
    }

    // M86 ---------------------------------------------------------------- Ceiling Lurker

    /** Returns the current lurker world position (for VFX on hit). */
    public Vector3f lurkerPosition() { return lurkerPos; }

    /** Returns true (and clears the flag) if the lurker hit the player this frame. */
    public boolean consumeLurkerHit() {
        boolean v = lurkerHitPlayer;
        lurkerHitPlayer = false;
        return v;
    }

    public boolean consumeDeepHit() {
        boolean v = deepHitPlayer;
        deepHitPlayer = false;
        return v;
    }

    public org.joml.Vector3f deepPosition()  { return deepPos; }
    public boolean isDeepActive()  { return deepActive; }
    public boolean isDeepHunting() { return deepActive && deepState == DeepState.HUNTING; }

    public void setPlayerMoving(boolean v)   { playerMoving   = v; }
    public void setRelicsComplete(boolean v) { relicsComplete = v; }

    private void updateLurker(Vector3f cam, float dt) {
        // Only active underground
        if (!underground) { lurkerActive = false; lurkerSpawnTimer = 20f; return; }

        lurkerStateTimer = Math.max(0f, lurkerStateTimer - dt);

        // --- Spawn logic ---
        if (!lurkerActive) {
            if (horrorProgression < 0.30f) return; // M152: Lurker waits for 30%
            lurkerSpawnTimer -= dt;
            if (lurkerSpawnTimer <= 0f) {
                // Spawn on ceiling ~6-10 units above player, offset XZ so it's not directly above
                float offX = ((float)Math.random() - 0.5f) * 14f;
                float offZ = ((float)Math.random() - 0.5f) * 14f;
                lurkerLandY = cam.y - 1.62f + 0.5f; // terrain level approx
                lurkerCeilY = lurkerLandY + 5f + (float)Math.random() * 4f;
                // M96: start at floor, CRAWLING state  - " visibly climbs to ceiling
                lurkerPos.set(cam.x + offX, lurkerLandY + 0.5f, cam.z + offZ);
                lurkerState = LurkerState.CRAWLING;
                lurkerStateTimer = 3.0f; // 3 seconds to crawl up
                lurkerFacing = (float)Math.atan2(cam.x - lurkerPos.x, cam.z - lurkerPos.z);
                lurkerActive = true;
                lurkerHitPlayer = false;
                // M157: lurker respawn â€” rarer; 8 min early, 5 min at max horror
                float lurkerBase = Math.max(420f, 640f - 180f * horrorProgression); // M173
                lurkerSpawnTimer = lurkerBase + (float)Math.random() * 180f;
            }
            return;
        }

        float dx = cam.x - lurkerPos.x;
        float dz = cam.z - lurkerPos.z;
        float xzDist = (float)Math.sqrt(dx * dx + dz * dz);

        switch (lurkerState) {
            case CRAWLING -> {
                // M96: lurker visibly climbs from floor to ceiling over 3 seconds
                float crawlT = 1f - (lurkerStateTimer / 3.0f);
                crawlT = Math.max(0f, Math.min(1f, crawlT));
                lurkerPos.y = lurkerLandY + 0.5f + (lurkerCeilY - lurkerLandY - 0.5f) * crawlT;
                lurkerFacing = (float)Math.atan2(dx, dz);
                if (lurkerStateTimer <= 0f) {
                    lurkerPos.y = lurkerCeilY;
                    lurkerState = LurkerState.HANGING;
                    lurkerStateTimer = 0f;
                }
            }
            case HANGING -> {
                // Update facing toward player
                lurkerFacing = (float)Math.atan2(dx, dz);
                // Drop when player gets close
                if (xzDist < 9f) {
                    lurkerState = LurkerState.DROPPING;
                    lurkerStateTimer = 0.4f; // drop duration
                }
                // Despawn after a long hang (player left the area)
                if (lurkerStateTimer <= 0f && xzDist > 30f) { lurkerActive = false; }
            }
            case DROPPING -> {
                // Fall from ceiling to land Y over 0.4s
                float t = Math.max(0f, 1f - lurkerStateTimer / 0.4f);
                lurkerPos.y = lurkerCeilY + (lurkerLandY - lurkerCeilY) * t;
                if (lurkerStateTimer <= 0f) {
                    lurkerPos.y = lurkerLandY;
                    lurkerState = LurkerState.HUNTING;
                    lurkerStateTimer = 8f; // hunt for up to 8s
                }
            }
            case HUNTING -> {
                // Chase player on XZ
                lurkerFacing = (float)Math.atan2(dx, dz);
                float speed = 4.5f;
                float step = speed * dt;
                if (xzDist > 0.6f) {
                    float nx = dx / xzDist, nz = dz / xzDist;
                    float newXZ = xzDist - step;
                    if (newXZ < 0.8f) { // overshoot guard
                        lurkerPos.x = cam.x - nx * 0.8f;
                        lurkerPos.z = cam.z - nz * 0.8f;
                    } else {
                        lurkerPos.x += nx * step;
                        lurkerPos.z += nz * step;
                    }
                }
                // Attack on contact
                if (xzDist < 1.2f) {
                    lurkerHitPlayer = true;
                    lurkerState = LurkerState.RETREATING;
                    lurkerStateTimer = 4f;
                }
                // Give up after timer
                if (lurkerStateTimer <= 0f) {
                    lurkerState = LurkerState.RETREATING;
                    lurkerStateTimer = 3f;
                }
            }
            case RETREATING -> {
                // Flee away from player
                if (xzDist > 0.01f) {
                    float nx = -dx / xzDist, nz = -dz / xzDist;
                    lurkerPos.x += nx * 3.5f * dt;
                    lurkerPos.z += nz * 3.5f * dt;
                }
                if (lurkerStateTimer <= 0f) lurkerActive = false;
            }
        }
    }

    // M166: THE DEEP â€” blind floor crawler that hunts by sound (player footsteps)
    private void updateDeep(Vector3f cam, float dt) {
        if (!underground) {
            deepActive = false;
            if (deepSpawnTimer < 240f) deepSpawnTimer = 240f;
            return;
        }
        deepHitCooldown = Math.max(0f, deepHitCooldown - dt);

        if (!deepActive) {
            if (horrorProgression < 0.25f) return;
            deepSpawnTimer -= dt;
            if (deepSpawnTimer > 0f) return;
            float angle = (float)(fogRng.nextDouble() * Math.PI * 2.0);
            float dist  = 18f + fogRng.nextFloat() * 14f;
            float gx    = cam.x + (float)Math.cos(angle) * dist;
            float gz    = cam.z + (float)Math.sin(angle) * dist;
            float gy    = ChunkGenerator.caveBandMinY() + 1.5f; // M176: cave floor, not surface
            deepPos.set(gx, gy, gz);
            deepFacing    = (float)Math.atan2(cam.x - gx, cam.z - gz);
            deepState     = DeepState.DORMANT;
            deepStateTimer= 0f;
            deepActive    = true;
            deepHitPlayer = false;
            deepSpawnTimer = 240f + fogRng.nextFloat() * 120f; // 4-6 min respawn
            return;
        }

        float dx = cam.x - deepPos.x;
        float dz = cam.z - deepPos.z;
        float xzDist = (float)Math.sqrt(dx * dx + dz * dz);

        switch (deepState) {
            case DORMANT -> {
                // Slow wander; snap to ground
                float dWX = deepPos.x + (float)Math.sin(deepFacing) * 0.5f * dt;
                float dWZ = deepPos.z + (float)Math.cos(deepFacing) * 0.5f * dt;
                if (!isMoveBlocked(dWX, deepPos.y, dWZ)) { deepPos.x = dWX; deepPos.z = dWZ; }
                else { deepFacing += (float)Math.PI * 0.5f; } // turn on wall hit
                deepPos.y = entityGroundY(deepPos.x, deepPos.z, deepPos.y); // M176: cave floor snap
                deepStateTimer -= dt;
                if (deepStateTimer <= 0f) {
                    deepFacing     = (float)(fogRng.nextDouble() * Math.PI * 2.0);
                    deepStateTimer = 1.5f + fogRng.nextFloat() * 2.5f;
                }
                // Triggered by player making noise (moving) within 20 blocks
                if (playerMoving && xzDist < 20f) {
                    deepState      = DeepState.HUNTING;
                    deepStateTimer = 12f;
                }
                if (xzDist > 50f) deepActive = false; // player too far, despawn
            }
            case HUNTING -> {
                deepFacing = (float)Math.atan2(dx, dz);
                float speed = 3.2f;
                float step  = speed * dt;
                if (xzDist > 0.8f) {
                    float nx   = dx / xzDist;
                    float nz   = dz / xzDist;
                    float newX = deepPos.x + nx * step;
                    float newZ = deepPos.z + nz * step;
                    // Overshoot guard
                    float nd   = (float)Math.sqrt((cam.x - newX) * (cam.x - newX) + (cam.z - newZ) * (cam.z - newZ));
                    if (nd < 0.8f) { newX = deepPos.x; newZ = deepPos.z; }
                    deepPos.x = newX;
                    deepPos.z = newZ;
                    deepPos.y = entityGroundY(deepPos.x, deepPos.z, deepPos.y); // M176
                }
                // Damage on contact
                if (xzDist < 1.1f && deepHitCooldown <= 0f) {
                    deepHitPlayer   = true;
                    deepHitCooldown = 3f;
                }
                // Give up if player goes silent or retreats
                deepStateTimer -= dt;
                if (!playerMoving || xzDist > 30f || deepStateTimer <= 0f) {
                    deepState      = DeepState.DORMANT;
                    deepStateTimer = 2f + fogRng.nextFloat() * 2f;
                }
            }
        }
    }

    private void renderDeep(Vector3f cam) {
        if (!deepActive) return;
        float dx = cam.x - deepPos.x, dz = cam.z - deepPos.z;
        if (dx * dx + dz * dz > 32f * 32f) return;

        float bx = deepPos.x, gy = deepPos.y, bz = deepPos.z;

        // Dark flat body â€” barely visible in darkness
        WatcherBuilder wb = new WatcherBuilder();
        emitCube(wb, bx - 0.50f, gy,        bz - 0.40f, bx + 0.50f, gy + 0.24f, bz + 0.40f, 0.07f, 0.04f, 0.07f);
        float hx = bx + (float)Math.sin(deepFacing) * 0.38f;
        float hz = bz + (float)Math.cos(deepFacing) * 0.38f;
        emitCube(wb, hx - 0.20f, gy + 0.04f, hz - 0.20f, hx + 0.20f, gy + 0.34f, hz + 0.20f, 0.06f, 0.03f, 0.06f);
        if (wb.n > 0) {
            shader.setLight(lightDir, Math.max(ambient, 0.12f), 0.0f, fogApplied, cam);
            streamMesh.draw(wb.a, wb.n);
            shader.setLight(lightDir, ambient, direct, fogApplied, cam);
        }

        // Emissive red eyes
        WatcherBuilder eb = new WatcherBuilder();
        float fx  = (float)Math.sin(deepFacing), fz  = (float)Math.cos(deepFacing);
        float rx  = fz,                          rz  = -fx;
        float ey  = gy + 0.26f;
        float es  = 0.044f;
        float ex1 = hx - rx * 0.10f, ez1 = hz - rz * 0.10f;
        float ex2 = hx + rx * 0.10f, ez2 = hz + rz * 0.10f;
        emitCube(eb, ex1 - es, ey - es, ez1 - es, ex1 + es, ey + es, ez1 + es, 0.95f, 0.12f, 0.0f);
        emitCube(eb, ex2 - es, ey - es, ez2 - es, ex2 + es, ey + es, ez2 + es, 0.95f, 0.12f, 0.0f);
        if (eb.n > 0) {
            shader.setLight(lightDir, 4.5f, 0.0f, 0.0f, cam);
            streamMesh.draw(eb.a, eb.n);
            shader.setLight(lightDir, ambient, direct, fogApplied, cam);
        }
    }

    // M169: tall emissive beacon at world origin, visible from far away when relics complete
    private void renderVoidBeacon(Vector3f cam) {
        if (!relicsComplete) return;
        float groundY = ChunkGenerator.heightAt(0, 0, worldSeed) + 1f;
        float pulse   = 0.55f + 0.20f * (float)Math.sin(entityAnimTime * 2.1f);
        float r = 0.55f * pulse, g = 0.15f * pulse, b = 0.90f * pulse;
        WatcherBuilder wb = new WatcherBuilder();
        emitCube(wb, -0.20f, groundY,       -0.20f,  0.20f, groundY + 70f, 0.20f, r, g, b);
        emitCube(wb, -0.55f, groundY,       -0.55f,  0.55f, groundY + 2f,  0.55f, r * 0.7f, g * 0.7f, b * 0.7f);
        if (wb.n <= 0) return;
        shader.setLight(lightDir, 5.0f, 0.0f, 0.0f, cam);
        streamMesh.draw(wb.a, wb.n);
        shader.setLight(lightDir, ambient, direct, fogApplied, cam);
    }

    /** M96: render tapered cone overlays at each detected stalactite tip in scan range */
    private void renderStalactites(Vector3f cam) {
        if (!underground) return;
        WatcherBuilder wb = new WatcherBuilder();
        int cx = (int)Math.floor(cam.x);
        int cy = (int)Math.floor(cam.y);
        int cz = (int)Math.floor(cam.z);
        int count = 0;
        // Scan overhead  - " stalactites hang down so look from cam Y up to Y+10
        for (int dy = 0; dy <= 10 && count < 80; dy++) {
            for (int dz = -12; dz <= 12 && count < 80; dz++) {
                for (int dx = -12; dx <= 12 && count < 80; dx++) {
                    int bx = cx + dx, by = cy + dy, bz = cz + dz;
                    byte b     = getBlock(bx, by,     bz);
                    byte above = getBlock(bx, by + 1, bz);
                    byte below = getBlock(bx, by - 1, bz);
                    if (b != BlockId.STONE || above != BlockId.STONE || below != BlockId.AIR) continue;
                    float dist = (float)Math.sqrt(dx*dx + dy*dy + dz*dz);
                    if (entityFogFrac(dist) >= 0.92f) continue;
                    // Emit tapered cone: 3 tiers, narrowest at tip (bottom), widest at base (ceiling)
                    float ox = bx + 0.5f, oz = bz + 0.5f;
                    float cr = 0.50f, cg = 0.50f, cb = 0.54f; // slightly lighter than stone
                    emitCube(wb, ox-0.07f, by,      oz-0.07f, ox+0.07f, by+0.28f, oz+0.07f, cr, cg, cb);
                    emitCube(wb, ox-0.16f, by+0.28f, oz-0.16f, ox+0.16f, by+0.60f, oz+0.16f, cr, cg, cb);
                    emitCube(wb, ox-0.28f, by+0.60f, oz-0.28f, ox+0.28f, by+1.0f,  oz+0.28f, cr, cg, cb);
                    count++;
                }
            }
        }
        if (wb.n <= 0) return;
        shader.setModelIdentity();
        shader.setAnimTime(0f, false);
        shader.setUseTexture(false);
        shader.setSpecular(0f);
        shader.setLight(lightDir, ambient, direct, fogApplied, cam);
        streamMesh.draw(wb.a, wb.n); // M151: streaming draw
    }

    private void renderLurker(Vector3f cam) {
        if (!lurkerActive) return;
        float dist = new Vector3f(lurkerPos).sub(cam).length();
        if (entityFogFrac(dist) >= 0.96f) return; // M96: visible through heavy fog

        float cosA = (float)Math.cos(lurkerFacing + (float)Math.PI);
        float sinA = (float)Math.sin(lurkerFacing + (float)Math.PI);

        boolean hanging  = (lurkerState == LurkerState.HANGING);
        boolean dropping = (lurkerState == LurkerState.DROPPING);
        boolean crawling = (lurkerState == LurkerState.CRAWLING);

        // M96: Lurker colours  - " pale ashen (visible in darkness for psychological horror)
        float bR = 0.55f, bG = 0.52f, bB = 0.58f;
        float lR = 0.42f, lG = 0.40f, lB = 0.44f;

        float bob = hanging ? (float)Math.sin(entityAnimTime * 1.2f) * 0.03f : 0f;
        float oy = lurkerPos.y + bob;

        // When hanging: compact, curled form clinging to ceiling; legs/arms folded up
        // When dropping/hunting: elongated upright form
        // M96: CRAWLING = upright (tT=0) so it's clearly visible ascending
        float tT;
        if (hanging)       tT = 1f;
        else if (dropping) tT = lurkerStateTimer / 0.4f; // smooth unfurl during drop
        else               tT = 0f; // CRAWLING or HUNTING: upright

        WatcherBuilder wb = new WatcherBuilder();

        // BODY  - " squashed flat when hanging, tall when hunting
        float bodyH = lerpF(0.38f, 0.15f, tT);
        float bodyW = lerpF(0.18f, 0.28f, tT);
        float bodyOY = lerpF(0.90f, 0.12f, tT);
        emitVoxelCube(wb, cosA, sinA, lurkerPos.x, oy, lurkerPos.z,
                0f, bodyOY, 0f, bodyW, bodyH, bodyW, bR, bG, bB);

        // HEAD
        float headOY = lerpF(1.30f, 0.05f, tT);
        emitVoxelCube(wb, cosA, sinA, lurkerPos.x, oy, lurkerPos.z,
                0f, headOY, lerpF(0.14f, -0.05f, tT),
                lerpF(0.14f, 0.20f, tT), lerpF(0.14f, 0.18f, tT), 0.14f,
                bR, bG, bB);

        // LEGS  - " tucked up when hanging, down when hunting
        float legOY = lerpF(0.44f, -0.02f, tT);
        emitVoxelCube(wb, cosA, sinA, lurkerPos.x, oy, lurkerPos.z,
                -0.12f, legOY, lerpF(-0.14f, 0f, tT),
                0.08f, lerpF(0.24f, 0.30f, tT), 0.08f, lR, lG, lB);
        emitVoxelCube(wb, cosA, sinA, lurkerPos.x, oy, lurkerPos.z,
                0.12f, legOY, lerpF(-0.14f, 0f, tT),
                0.08f, lerpF(0.24f, 0.30f, tT), 0.08f, lR, lG, lB);

        // CLAWS  - " long, splayed when hanging, tucked when running
        float clawSpread = lerpF(0.08f, 0.35f, tT);
        float clawOY     = lerpF(0.80f, -0.04f, tT);
        for (int c2 = -1; c2 <= 1; c2 += 2) {
            emitVoxelCube(wb, cosA, sinA, lurkerPos.x, oy, lurkerPos.z,
                    c2 * clawSpread, clawOY, lerpF(0.0f, -0.05f, tT),
                    0.04f, lerpF(0.30f, 0.10f, tT), 0.04f, lR, lG, lB);
        }

        // M96: EYES  - " glowing red when active (crawling, dropping, hunting)
        if (lurkerState == LurkerState.HUNTING || lurkerState == LurkerState.DROPPING
                || lurkerState == LurkerState.CRAWLING) {
            emitVoxelCube(wb, cosA, sinA, lurkerPos.x, oy, lurkerPos.z,
                    -0.07f, headOY + 0.02f, lerpF(0.14f, 0.18f, tT),
                    0.04f, 0.04f, 0.03f, 0.95f, 0.04f, 0.04f);
            emitVoxelCube(wb, cosA, sinA, lurkerPos.x, oy, lurkerPos.z,
                     0.07f, headOY + 0.02f, lerpF(0.14f, 0.18f, tT),
                    0.04f, 0.04f, 0.03f, 0.95f, 0.04f, 0.04f);
        }

        if (wb.n <= 0) return;
        shader.setModelIdentity();
        shader.setAnimTime(0f, false);
        shader.setUseTexture(false);
        shader.setSpecular(0f);
        shader.setLight(lightDir, ambient, direct, fogApplied, cam);
        streamMesh.draw(wb.a, wb.n); // M151: streaming draw
    }

    private void updateFigure(Vector3f cam, float dt) {
        figureSpawnTimer -= dt;
        if (!figureActive) {
            if (horrorProgression < 0.25f) return; // M152: Figure waits for 25%
            if (figureSpawnTimer <= 0f) {
                float ang = (float)(fogRng.nextDouble() * Math.PI * 2.0);
                float r   = 20f + fogRng.nextFloat() * 8f;
                float gx  = cam.x + (float)Math.cos(ang) * r;
                float gz  = cam.z + (float)Math.sin(ang) * r;
                float gy  = ChunkGenerator.heightAt((int)gx, (int)gz, worldSeed) + 0.5f;
                figurePos.set(gx, gy, gz);
                figureTarget.set(gx, gy, gz);
                figureFacing   = (float)(fogRng.nextDouble() * Math.PI * 2.0);
                figureState    = FigureState.WANDER;
                figureMorphT   = 0f;
                figureStateTimer = 3f + fogRng.nextFloat() * 4f;
                figureActive   = true;
            }
            return;
        }

        float dist = new Vector3f(figurePos).sub(cam).length();

        switch (figureState) {
            case WANDER -> {
                float tdx  = figureTarget.x - figurePos.x;
                float tdz  = figureTarget.z - figurePos.z;
                float tlen = (float)Math.sqrt(tdx * tdx + tdz * tdz);
                if (tlen > 0.5f) {
                    float spd = 1.8f * dt;
                    figurePos.x   += (tdx / tlen) * spd;
                    figurePos.z   += (tdz / tlen) * spd;
                    figureFacing   = (float)Math.atan2(tdx, tdz);
                }
                figurePos.y = ChunkGenerator.heightAt(
                        (int)Math.floor(figurePos.x), (int)Math.floor(figurePos.z), worldSeed) + 0.5f;

                figureStateTimer -= dt;
                if (figureStateTimer <= 0f || tlen < 0.5f) {
                    float a = (float)(fogRng.nextDouble() * Math.PI * 2.0);
                    float rr = 8f + fogRng.nextFloat() * 12f;
                    figureTarget.set(figurePos.x + (float)Math.cos(a) * rr,
                                     figurePos.y,
                                     figurePos.z + (float)Math.sin(a) * rr);
                    figureStateTimer = 3f + fogRng.nextFloat() * 5f;
                }
                if (dist < 18f && !underground) {
                    figureState      = FigureState.NOTICE;
                    figureStateTimer = 1.2f;
                }
            }
            case NOTICE -> {
                figureFacing    = (float)Math.atan2(cam.x - figurePos.x, cam.z - figurePos.z);
                figureMorphT    = Math.min(1f, figureMorphT + 0.12f * dt);
                figureStateTimer -= dt;
                if (dist < 10f || figureStateTimer <= 0f) {
                    figureState      = FigureState.TRANSFORM;
                    figureStateTimer = 1.5f;
                }
                if (dist > 24f) {
                    figureState      = FigureState.WANDER;
                    figureStateTimer = 3f;
                }
            }
            case TRANSFORM -> {
                figureFacing = (float)Math.atan2(cam.x - figurePos.x, cam.z - figurePos.z);
                figureMorphT = Math.min(1f, figureMorphT + 1.0f * dt);
                if (figureMorphT >= 1.0f) figureState = FigureState.MONSTER;
            }
            case MONSTER -> {
                float dx = cam.x - figurePos.x, dz = cam.z - figurePos.z;
                float xzD = (float)Math.sqrt(dx * dx + dz * dz);
                figureFacing = (float)Math.atan2(dx, dz);
                if (xzD > 0.8f) {
                    // Overshoot guard: stop at 0.8 XZ units so the figure never clips inside the camera.
                    float spd = 5.5f * dt;
                    float stepX = (dx / xzD) * spd;
                    float stepZ = (dz / xzD) * spd;
                    float newXZ = (float)Math.sqrt((dx - stepX) * (dx - stepX) + (dz - stepZ) * (dz - stepZ));
                    if (newXZ < 0.8f) {
                        float frac = (xzD - 0.8f) / Math.max(0.001f, xzD - newXZ);
                        stepX *= frac;
                        stepZ *= frac;
                    }
                    figurePos.x += stepX;
                    figurePos.z += stepZ;
                }
                figurePos.y = ChunkGenerator.heightAt(
                        (int)Math.floor(figurePos.x), (int)Math.floor(figurePos.z), worldSeed) + 0.5f;
                // Hit check on XZ distance only  - " 3D dist includes ~1.2 Y gap (cam eye vs terrain+0.5)
                // which made the hit threshold physically unreachable on flat ground.
                if (xzD < 1.6f && playerHitImmunity <= 0f) {
                    pendingPlayerHits++;
                    playerHitImmunity = 1.2f;
                    spawnFigureSmoke(figurePos);   // burst of smoke at figure's position
                    figureActive     = false;       // immediately despawn  - " no sticking to camera
                    figureSpawnTimer = nextFigureSpawnDelay(); // keep Figure rare always
                    return;
                }
                if (xzD > 28f) figureState = FigureState.RETREATING;
            }
            case RETREATING -> {
                figureFacing = (float)Math.atan2(cam.x - figurePos.x, cam.z - figurePos.z);
                figureMorphT = Math.max(0f, figureMorphT - 0.5f * dt);
                if (figureMorphT <= 0f) {
                    figureState      = FigureState.WANDER;
                    float a  = (float)(fogRng.nextDouble() * Math.PI * 2.0);
                    float rr = 8f + fogRng.nextFloat() * 10f;
                    figureTarget.set(figurePos.x + (float)Math.cos(a) * rr,
                                     figurePos.y,
                                     figurePos.z + (float)Math.sin(a) * rr);
                    figureStateTimer = 3f + fogRng.nextFloat() * 4f;
                }
            }
        }
    }

    private void renderFigure(Vector3f cam) {
        if (!figureActive) return;
        float dist = new Vector3f(figurePos).sub(cam).length();
        if (entityFogFrac(dist) >= 0.94f) return;

        float t     = figureMorphT;
        float angle = figureFacing + (float)Math.PI; // face toward camera
        float cosA  = (float)Math.cos(angle);
        float sinA  = (float)Math.sin(angle);

        float animSpd = 2.5f + t * 3.5f;
        float wc      = Math.abs((float)Math.sin(entityAnimTime * animSpd));
        float lLegY   = lerpF(0.48f, 0.36f, t) + wc       * lerpF(0.12f, 0.18f, t);
        float rLegY   = lerpF(0.48f, 0.36f, t) + (1f - wc)* lerpF(0.12f, 0.18f, t);
        float lArmY   = lerpF(1.10f, 0.80f, t) + wc        * 0.06f;
        float rArmY   = lerpF(1.10f, 0.80f, t) + (1f - wc) * 0.06f;
        float bob     = (float)Math.sin(entityAnimTime * (5f + t * 4f)) * 0.025f;
        float oy      = figurePos.y + bob;

        // Lerped body colours  friendly(warm skin/clothes) Ã¢â€ ' monster(dark shadow)
        float bR = lerpF(0.74f, 0.06f, t), bG = lerpF(0.56f, 0.04f, t), bB = lerpF(0.42f, 0.09f, t);
        float tR = lerpF(0.40f, 0.06f, t), tG = lerpF(0.48f, 0.04f, t), tB = lerpF(0.62f, 0.09f, t);
        float lR = lerpF(0.22f, 0.06f, t), lG = lerpF(0.28f, 0.04f, t), lB = lerpF(0.38f, 0.09f, t);

        WatcherBuilder wb = new WatcherBuilder();

        // HEAD  - " grows and rises as it transforms
        emitVoxelCube(wb, cosA, sinA, figurePos.x, oy, figurePos.z,
                0f, lerpF(1.55f, 1.74f, t), lerpF(0.15f, 0.12f, t),
                lerpF(0.18f, 0.29f, t), lerpF(0.18f, 0.25f, t), lerpF(0.18f, 0.25f, t),
                bR, bG, bB);
        // TORSO
        emitVoxelCube(wb, cosA, sinA, figurePos.x, oy, figurePos.z,
                0f, lerpF(1.10f, 1.05f, t), 0f,
                lerpF(0.20f, 0.24f, t), lerpF(0.28f, 0.36f, t), lerpF(0.14f, 0.18f, t),
                tR, tG, tB);
        // ARMS  - " elongate and drop as monster grows
        emitVoxelCube(wb, cosA, sinA, figurePos.x, oy, figurePos.z,
                lerpF(-0.32f, -0.40f, t), lArmY, 0f,
                0.09f, lerpF(0.22f, 0.52f, t), 0.09f, bR, bG, bB);
        emitVoxelCube(wb, cosA, sinA, figurePos.x, oy, figurePos.z,
                lerpF(0.32f, 0.40f, t), rArmY, 0f,
                0.09f, lerpF(0.22f, 0.52f, t), 0.09f, bR, bG, bB);
        // LEGS
        emitVoxelCube(wb, cosA, sinA, figurePos.x, oy, figurePos.z,
                lerpF(-0.11f, -0.14f, t), lLegY, 0f,
                lerpF(0.09f, 0.12f, t), lerpF(0.28f, 0.32f, t), lerpF(0.09f, 0.12f, t),
                lR, lG, lB);
        emitVoxelCube(wb, cosA, sinA, figurePos.x, oy, figurePos.z,
                lerpF(0.11f, 0.14f, t), rLegY, 0f,
                lerpF(0.09f, 0.12f, t), lerpF(0.28f, 0.32f, t), lerpF(0.09f, 0.12f, t),
                lR, lG, lB);
        // EYES  - " snap on at t>0.3, trigger eyeRed glow in shader
        if (t > 0.30f) {
            float ez = lerpF(0.17f, 0.24f, t);
            float ey = lerpF(1.60f, 1.78f, t);
            emitVoxelCube(wb, cosA, sinA, figurePos.x, oy, figurePos.z,
                    -0.09f, ey, ez, 0.055f, 0.055f, 0.04f, 0.96f, 0.05f, 0.05f);
            emitVoxelCube(wb, cosA, sinA, figurePos.x, oy, figurePos.z,
                     0.09f, ey, ez, 0.055f, 0.055f, 0.04f, 0.96f, 0.05f, 0.05f);
        }

        if (wb.n <= 0) return;
        shader.setModelIdentity();
        shader.setAnimTime(0f, false);
        shader.setUseTexture(false);
        shader.setSpecular(0f);
        shader.setLight(lightDir, ambient, direct, fogApplied, cam);
        streamMesh.draw(wb.a, wb.n); // M151: streaming draw
    }

    // ---------------------------------------------------------------- FIGURE SMOKE (M59)

    public void spawnFigureSmoke(Vector3f pos) {
        smokeCount = SMOKE_MAX;
        for (int i = 0; i < SMOKE_MAX; i++) {
            float a    = (float)(fogRng.nextDouble() * Math.PI * 2.0);
            float hSpd = 0.3f + fogRng.nextFloat() * 1.4f;
            smokeParts[i][0] = pos.x + (fogRng.nextFloat() - 0.5f) * 0.6f;
            smokeParts[i][1] = pos.y + fogRng.nextFloat() * 1.2f;
            smokeParts[i][2] = pos.z + (fogRng.nextFloat() - 0.5f) * 0.6f;
            smokeParts[i][3] = (float)Math.cos(a) * hSpd;          // vx
            smokeParts[i][4] = 1.5f + fogRng.nextFloat() * 2.0f;   // vy upward
            smokeParts[i][5] = (float)Math.sin(a) * hSpd;          // vz
            smokeParts[i][6] = 0.6f + fogRng.nextFloat() * 0.6f;   // ttl (0.6 - "1.2 s)
        }
    }

    private void updateSmoke(float dt) {
        int i = 0;
        while (i < smokeCount) {
            smokeParts[i][0] += smokeParts[i][3] * dt;
            smokeParts[i][1] += smokeParts[i][4] * dt;
            smokeParts[i][2] += smokeParts[i][5] * dt;
            smokeParts[i][4] = Math.max(0f, smokeParts[i][4] - 3.5f * dt); // decelerate upward
            smokeParts[i][6] -= dt;
            if (smokeParts[i][6] <= 0f) {
                smokeCount--;
                if (i < smokeCount) {
                    System.arraycopy(smokeParts[smokeCount], 0, smokeParts[i], 0, 7);
                }
                // don't increment i  - " re-check same slot (now holds swapped particle)
            } else {
                i++;
            }
        }
    }

    private void renderSmoke(Vector3f cam) {
        if (smokeCount <= 0) return;
        WatcherBuilder rb = new WatcherBuilder();
        for (int i = 0; i < smokeCount; i++) {
            float ttl   = smokeParts[i][6];
            float fade  = Math.min(1f, ttl / 0.35f);           // fade out in last 0.35 s
            float sz    = 0.06f + (1f - fade) * 0.12f;         // puffs grow as they fade
            float cr    = fogMix(0.10f * fade, clearR, 0f);
            float cg    = fogMix(0.08f * fade, clearG, 0f);
            float cb    = fogMix(0.14f * fade, clearB, 0f);    // slight purple tint
            float x = smokeParts[i][0], y = smokeParts[i][1], z = smokeParts[i][2];
            emitCube(rb, x - sz, y - sz, z - sz, x + sz, y + sz, z + sz, cr, cg, cb);
        }
        if (rb.n <= 0) return;
        shader.setLight(lightDir, 1.2f, 0f, 0f, cam); // full-bright so smoke is always visible
        streamMesh.draw(rb.a, rb.n); // M151: streaming draw
        shader.setLight(lightDir, ambient + lightningWorldFlash, direct, fogApplied, cam);
    }

    // ---------------------------------------------------------------- FIREFLIES (M45)

    private void updateFireflies(Vector3f cam, float dt) {
        // Only active at night, above ground
        if (!nightMode || underground) {
            fireflyCount      = 0;
            fireflySpawnTimer = 2f;
            fireflyLastNs     = 0L;
            return;
        }

        // Gradually fill up to MAX_FIREFLIES
        fireflySpawnTimer -= dt;
        if (fireflySpawnTimer <= 0f && fireflyCount < MAX_FIREFLIES) {
            float angle = (float)(fogRng.nextDouble() * Math.PI * 2.0);
            float r     = 8f + fogRng.nextFloat() * 12f; // 8-20 units from player
            int fi = fireflyCount++;
            int gx = (int)Math.floor(cam.x + (float)Math.cos(angle) * r);
            int gz = (int)Math.floor(cam.z + (float)Math.sin(angle) * r);
            float gy = ChunkGenerator.heightAt(gx, gz, worldSeed) + 1.2f + fogRng.nextFloat() * 2.5f;
            fireflyPos[fi].set(cam.x + (float)Math.cos(angle) * r, gy,
                               cam.z + (float)Math.sin(angle) * r);
            fireflyPhase[fi] = fogRng.nextFloat() * (float)(Math.PI * 2.0);
            fireflySpawnTimer = 0.15f + fogRng.nextFloat() * 0.3f;
        }

        // Is THE THING close? (flee trigger)
        boolean thingNear = thingActive
                && new Vector3f(thingPos).sub(cam).length() < 20f;

        for (int i = 0; i < fireflyCount; i++) {
            Vector3f p     = fireflyPos[i];
            float    phase = fireflyPhase[i];

            if (thingNear) {
                // Flee: dart away from THE THING
                float fx = p.x - thingPos.x;
                float fz = p.z - thingPos.z;
                float fd = (float)Math.sqrt(fx * fx + fz * fz);
                if (fd > 0.001f) { fx /= fd; fz /= fd; }
                p.x += fx * 9f * dt;
                p.z += fz * 9f * dt;
            } else {
                // Gentle sinusoidal wander
                p.x += (float)Math.sin(entityAnimTime * 0.7f + phase)         * 0.9f * dt;
                p.z += (float)Math.cos(entityAnimTime * 0.5f + phase + 1.2f)  * 0.9f * dt;
            }
            // M51: wind pushes fireflies during rain
            p.x += windX * dt;
            p.z += windZ * dt;
            // Gentle vertical float
            p.y += (float)Math.sin(entityAnimTime * 1.1f + phase) * 0.35f * dt;

            // Clamp height to reasonable band above ground
            int gx = (int)Math.floor(p.x);
            int gz = (int)Math.floor(p.z);
            float ground = ChunkGenerator.heightAt(gx, gz, worldSeed);
            p.y = Math.max(ground + 0.4f, Math.min(ground + 5.5f, p.y));

            // Cull if drifted too far from player
            float dx = p.x - cam.x, dz = p.z - cam.z;
            if (dx * dx + dz * dz > 38f * 38f) {
                fireflyPos[i].set(fireflyPos[fireflyCount - 1]);
                fireflyPhase[i] = fireflyPhase[fireflyCount - 1];
                fireflyCount--;
                i--;
            }
        }
    }

    private void renderFireflies(Vector3f cam) {
        if (fireflyCount <= 0) return;
        WatcherBuilder fb = new WatcherBuilder();

        for (int i = 0; i < fireflyCount; i++) {
            Vector3f p = fireflyPos[i];
            // M234: fixed warm yellow-orange body — never dark; flicker only modulates size slightly
            float s = 0.055f + 0.012f * (float)Math.sin(entityAnimTime * 9f + fireflyPhase[i]);
            emitCube(fb, p.x - s, p.y - s, p.z - s, p.x + s, p.y + s, p.z + s,
                     0.98f, 0.72f, 0.10f);
        }

        if (fb.n <= 0) return;
        shader.setLight(lightDir, 4.5f, 0.0f, 0.0f, cam); // M234: HDR emissive — always bright
        streamMesh.draw(fb.a, fb.n); // M151: streaming draw
        // Restore scene lighting
        shader.setLight(lightDir, ambient, direct, fogApplied, cam);
    }

    // ---------------------------------------------------------------- CAMPFIRE RENDER (M178)
    // Animated custom geometry: ash base + crossed logs + 3-layer flickering fire.
    // Run every frame (no cache) so fire can animate.
    private void renderCampfires(Vector3f cam) {
        WatcherBuilder fb = new WatcherBuilder();
        float t = entityAnimTime;

        for (java.util.Map.Entry<ChunkPos, VoxelChunk> e : chunkData.entrySet()) {
            ChunkPos cp = e.getKey();
            VoxelChunk vc = e.getValue();
            int baseX = cp.x() * Chunk.SIZE;
            int baseZ = cp.z() * Chunk.SIZE;
            for (int y = 0; y < VoxelChunk.SIZE_Y; y++) {
                for (int z = 0; z < Chunk.SIZE; z++) {
                    for (int x = 0; x < Chunk.SIZE; x++) {
                        if (vc.get(x, y, z) != BlockId.CAMPFIRE) continue;
                        float wx = baseX + x, wy = y, wz = baseZ + z;
                        float dd = (wx+.5f-cam.x)*(wx+.5f-cam.x) + (wy+.5f-cam.y)*(wy+.5f-cam.y) + (wz+.5f-cam.z)*(wz+.5f-cam.z);
                        if (dd > 60f * 60f) continue;

                        // --- Ash base ---
                        emitCube(fb, wx+0.16f, wy,       wz+0.16f, wx+0.84f, wy+0.03f, wz+0.84f, 0.14f, 0.11f, 0.08f);

                        // --- Log A (east-west) ---
                        emitCube(fb, wx+0.04f, wy+0.03f, wz+0.40f, wx+0.96f, wy+0.14f, wz+0.54f, 0.40f, 0.24f, 0.10f);
                        // --- Log B (north-south) ---
                        emitCube(fb, wx+0.40f, wy+0.03f, wz+0.04f, wx+0.54f, wy+0.14f, wz+0.96f, 0.38f, 0.22f, 0.09f);
                        // --- Crossing stump A (NW lean) ---
                        emitCube(fb, wx+0.28f, wy+0.14f, wz+0.28f, wx+0.44f, wy+0.32f, wz+0.44f, 0.34f, 0.20f, 0.08f);
                        // --- Crossing stump B (SE lean) ---
                        emitCube(fb, wx+0.56f, wy+0.14f, wz+0.56f, wx+0.72f, wy+0.32f, wz+0.72f, 0.34f, 0.20f, 0.08f);

                        // --- Animated fire ---
                        float seed = wx * 3.7f + wz * 2.3f;
                        float cx = wx + 0.5f, cz = wz + 0.5f;
                        float baseY = wy + 0.30f;

                        // Outer shell (dark orange, wide, slow)
                        float h1 = 0.14f + 0.08f * Math.abs((float)Math.sin(t * 3.8f + seed));
                        float ox1 = 0.022f * (float)Math.sin(t * 3.1f + seed + 0.5f);
                        float oz1 = 0.022f * (float)Math.sin(t * 3.5f + seed + 1.2f);
                        emitCube(fb, cx-0.13f+ox1, baseY,      cz-0.13f+oz1,
                                     cx+0.13f+ox1, baseY+h1,   cz+0.13f+oz1, 0.90f, 0.28f, 0.03f);

                        // Middle flame (amber, medium speed)
                        float h2 = 0.11f + 0.10f * Math.abs((float)Math.sin(t * 6.2f + seed + 1.0f));
                        float ox2 = 0.016f * (float)Math.sin(t * 6.7f + seed + 2.0f);
                        float oz2 = 0.016f * (float)Math.sin(t * 5.9f + seed + 0.8f);
                        emitCube(fb, cx-0.09f+ox2, baseY+0.04f,      cz-0.09f+oz2,
                                     cx+0.09f+ox2, baseY+h2+0.04f, cz+0.09f+oz2, 1.00f, 0.62f, 0.07f);

                        // Core flame (bright yellow, narrow, fast)
                        float h3 = 0.07f + 0.09f * Math.abs((float)Math.sin(t * 9.4f + seed + 0.3f));
                        float ox3 = 0.010f * (float)Math.sin(t * 10.1f + seed + 1.5f);
                        float oz3 = 0.010f * (float)Math.sin(t * 8.7f + seed + 0.4f);
                        emitCube(fb, cx-0.04f+ox3, baseY+0.09f,      cz-0.04f+oz3,
                                     cx+0.04f+ox3, baseY+h3+0.09f, cz+0.04f+oz3, 1.00f, 0.96f, 0.52f);
                    }
                }
            }
        }
        if (fb.n <= 0) return;
        shader.setLight(lightDir, 3.5f, 0.0f, 0.0f, cam); // HDR warmth through Reinhard
        shader.setUseTexture(false);
        shader.setSpecular(0f);
        streamMesh.draw(fb.a, fb.n);
        shader.setLight(lightDir, ambient, direct, fogApplied, cam); // restore
    }

        // ---------------------------------------------------------------- WILL-O'-WISPS (M46)

    private void updateWisps(Vector3f cam, Vector3f fwd, float dt) {
        if (!nightMode || underground) {
            wispCount      = 0;
            wispSpawnTimer = 5f;
            return;
        }

        // Spawn new wisps gradually
        wispSpawnTimer -= dt;
        if (wispSpawnTimer <= 0f && wispCount < MAX_WISPS) {
            float angle = (float)(fogRng.nextDouble() * Math.PI * 2.0);
            float r     = 14f + fogRng.nextFloat() * 10f; // 14-24 units away
            int wi = wispCount++;
            int gx = (int)Math.floor(cam.x + (float)Math.cos(angle) * r);
            int gz = (int)Math.floor(cam.z + (float)Math.sin(angle) * r);
            float gy = ChunkGenerator.heightAt(gx, gz, worldSeed) + 1.5f + fogRng.nextFloat() * 2f;
            wispPos[wi].set(cam.x + (float)Math.cos(angle) * r, gy,
                            cam.z + (float)Math.sin(angle) * r);
            wispPhase[wi]      = fogRng.nextFloat() * (float)(Math.PI * 2.0);
            wispState[wi]      = WISP_IDLE;
            wispStateTimer[wi] = 3f + fogRng.nextFloat() * 3f;
            wispTarget[wi].set(wispPos[wi]);
            wispSpawnTimer = 5f + fogRng.nextFloat() * 5f; // 5-10s between wisps
        }

        // Is THE THING nearby? Wisps lure toward it when active.
        boolean thingClose = thingActive
                && new Vector3f(thingPos).sub(cam).length() < 35f;

        for (int i = 0; i < wispCount; i++) {
            Vector3f p     = wispPos[i];
            float    phase = wispPhase[i];
            float    dx    = p.x - cam.x;
            float    dz    = p.z - cam.z;
            float    distToCam = (float)Math.sqrt(dx * dx + dz * dz);

            wispStateTimer[i] -= dt;

            switch (wispState[i]) {
                case WISP_IDLE -> {
                    // Gentle ethereal drift  - " slower than fireflies
                    p.x += (float)Math.sin(entityAnimTime * 0.4f + phase)        * 0.5f * dt;
                    p.z += (float)Math.cos(entityAnimTime * 0.3f + phase + 0.8f) * 0.5f * dt;
                    p.y += (float)Math.sin(entityAnimTime * 0.7f + phase)        * 0.25f * dt;

                    if (wispStateTimer[i] <= 0f) {
                        // Transition to LURE: pick a target ahead of the player
                        float lureLen = 14f + fogRng.nextFloat() * 8f;
                        float tx = cam.x + fwd.x * lureLen;
                        float tz = cam.z + fwd.z * lureLen;
                        // If THE THING is close, bias target toward it
                        if (thingClose) {
                            tx = tx * 0.4f + thingPos.x * 0.6f;
                            tz = tz * 0.4f + thingPos.z * 0.6f;
                        }
                        int tgx = (int)Math.floor(tx);
                        int tgz = (int)Math.floor(tz);
                        float tgy = ChunkGenerator.heightAt(tgx, tgz, worldSeed) + 1.4f + fogRng.nextFloat() * 1.5f;
                        wispTarget[i].set(tx, tgy, tz);
                        wispState[i]      = WISP_LURE;
                        wispStateTimer[i] = 8f + fogRng.nextFloat() * 6f;
                    }
                }
                case WISP_LURE -> {
                    // Drift toward lure target
                    float tdx = wispTarget[i].x - p.x;
                    float tdy = wispTarget[i].y - p.y;
                    float tdz = wispTarget[i].z - p.z;
                    float tlen = (float)Math.sqrt(tdx*tdx + tdy*tdy + tdz*tdz);
                    if (tlen > 0.5f) {
                        float speed = 2.5f * dt;
                        p.x += (tdx / tlen) * speed;
                        p.y += (tdy / tlen) * speed;
                        p.z += (tdz / tlen) * speed;
                    }
                    // Subtle ethereal bob while luring
                    p.y += (float)Math.sin(entityAnimTime * 1.3f + phase) * 0.15f * dt;

                    // Player got too close Ã¢â€ ' vanish away
                    if (distToCam < 6f) {
                        wispState[i]      = WISP_EVADE;
                        wispStateTimer[i] = 1.5f;
                    } else if (wispStateTimer[i] <= 0f) {
                        wispState[i]      = WISP_IDLE;
                        wispStateTimer[i] = 3f + fogRng.nextFloat() * 3f;
                    }
                }
                case WISP_EVADE -> {
                    // Dart away from the player
                    if (distToCam > 0.01f) {
                        float speed = 10f * dt;
                        p.x += (dx / distToCam) * speed;
                        p.z += (dz / distToCam) * speed;
                    }
                    p.y += 1.5f * dt; // rise as it flees

                    if (wispStateTimer[i] <= 0f) {
                        // Re-enter LURE immediately after evade
                        float lureLen = 16f + fogRng.nextFloat() * 6f;
                        float tx = cam.x + fwd.x * lureLen;
                        float tz = cam.z + fwd.z * lureLen;
                        if (thingClose) {
                            tx = tx * 0.4f + thingPos.x * 0.6f;
                            tz = tz * 0.4f + thingPos.z * 0.6f;
                        }
                        int tgx = (int)Math.floor(tx);
                        int tgz = (int)Math.floor(tz);
                        float tgy = ChunkGenerator.heightAt(tgx, tgz, worldSeed) + 1.4f + fogRng.nextFloat() * 1.5f;
                        wispTarget[i].set(tx, tgy, tz);
                        wispState[i]      = WISP_LURE;
                        wispStateTimer[i] = 8f + fogRng.nextFloat() * 5f;
                    }
                }
            }

            // M51: wind pushes wisps during rain
            p.x += windX * 0.6f * dt; // wisps resist wind more than fireflies
            p.z += windZ * 0.6f * dt;

            // Clamp height above ground
            int gx = (int)Math.floor(p.x);
            int gz = (int)Math.floor(p.z);
            float ground = ChunkGenerator.heightAt(gx, gz, worldSeed);
            p.y = Math.max(ground + 0.5f, Math.min(ground + 6f, p.y));

            // Cull if too far
            if (distToCam > 50f) {
                wispPos[i].set(wispPos[wispCount - 1]);
                wispTarget[i].set(wispTarget[wispCount - 1]);
                wispPhase[i]      = wispPhase[wispCount - 1];
                wispState[i]      = wispState[wispCount - 1];
                wispStateTimer[i] = wispStateTimer[wispCount - 1];
                wispCount--;
                i--;
            }
        }
    }

    private void renderWisps(Vector3f cam) {
        if (wispCount <= 0) return;
        WatcherBuilder wb = new WatcherBuilder();

        for (int i = 0; i < wispCount; i++) {
            Vector3f p = wispPos[i];
            // Slower, eerier flicker than fireflies
            float flicker = 0.7f + 0.3f * (float)Math.sin(entityAnimTime * 6f + wispPhase[i]);
            // Blue-white: classic will-o'-wisp colour
            float r = 0.55f * flicker;
            float g = 0.88f * flicker;
            float b = 1.00f * flicker;
            // Wisps are slightly larger than fireflies and pulse in size
            float s = 0.10f + 0.03f * (float)Math.sin(entityAnimTime * 4f + wispPhase[i]);
            emitCube(wb, p.x - s, p.y - s, p.z - s, p.x + s, p.y + s, p.z + s, r, g, b);
        }

        if (wb.n <= 0) return;
        // Reinhard-aware emissive: ambient=4.5 gives ~0.82 output for white, cold ethereal blue-white.
        shader.setLight(lightDir, 4.5f, 0.0f, 0.0f, cam);
        streamMesh.draw(wb.a, wb.n); // M151: streaming draw
        shader.setLight(lightDir, ambient, direct, fogApplied, cam);
    }

    // ---------------------------------------------------------------- M229+: PORTAL GATE GLOW

    /**
     * Renders the active glow effect above LIMINAL_PORTAL tiles — a tall shimmering gate.
     * Called every frame (not cached) so it animates. Uses HDR emissive lighting (ambient=6.0)
     * so the portal is self-lit and visible in both daylight and darkness.
     */
    private void renderPortals(Vector3f cam) {
        WatcherBuilder wb = new WatcherBuilder();
        float t = entityAnimTime;

        for (java.util.Map.Entry<ChunkPos, VoxelChunk> e : chunkData.entrySet()) {
            ChunkPos cp = e.getKey();
            VoxelChunk vc = e.getValue();
            int baseX = cp.x() * Chunk.SIZE;
            int baseZ = cp.z() * Chunk.SIZE;
            for (int iy = 0; iy < VoxelChunk.SIZE_Y; iy++) {
                for (int iz = 0; iz < Chunk.SIZE; iz++) {
                    for (int ix = 0; ix < Chunk.SIZE; ix++) {
                        if (vc.get(ix, iy, iz) != BlockId.LIMINAL_PORTAL) continue;
                        int iwx = baseX + ix, iwy = iy, iwz = baseZ + iz;
                        float wx = iwx, wy = iwy, wz = iwz;
                        float ddx = wx+.5f-cam.x, ddy = wy+.5f-cam.y, ddz = wz+.5f-cam.z;
                        if (ddx*ddx + ddy*ddy + ddz*ddz > 50f*50f) continue;

                        // Detect arch orientation via neighbors
                        boolean adjXneg = getBlock(iwx-1,iwy,iwz)==BlockId.LIMINAL_PORTAL;
                        boolean adjXpos = getBlock(iwx+1,iwy,iwz)==BlockId.LIMINAL_PORTAL;
                        boolean adjZneg = getBlock(iwx,iwy,iwz-1)==BlockId.LIMINAL_PORTAL;
                        boolean adjZpos = getBlock(iwx,iwy,iwz+1)==BlockId.LIMINAL_PORTAL;
                        boolean linearX = (adjXneg||adjXpos) && !adjZneg && !adjZpos;
                        boolean linearZ = (adjZneg||adjZpos) && !adjXneg && !adjXpos;

                        float pulse   = 0.85f + 0.15f * (float) Math.sin(t * 2.4f + iwx * 0.7f + iwz * 0.5f);
                        float shimmer = 0.92f + 0.08f * (float) Math.sin(t * 5.1f + iwx * 1.3f + iwz * 0.9f);
                        float cR = 0.22f * pulse, cG = 0.95f * shimmer, cB = 0.90f * pulse;
                        float fR = 0.35f * pulse, fG = 1.00f * shimmer, fB = 0.96f * pulse;

                        if (linearX) {
                            // Z-facing arch: tiles line up in X. Leftmost tile draws full panel.
                            if (adjXneg) continue;
                            int runW = 1;
                            while (getBlock(iwx + runW, iwy, iwz) == BlockId.LIMINAL_PORTAL) runW++;
                            float x0 = wx, x1 = wx + runW;
                            float z0 = wz + 0.28f, z1 = wz + 0.72f;
                            emitCube(wb, x0,         wy, z0,         x1,         wy+5f, z1,         cR, cG, cB);
                            emitCube(wb, x0+0.04f,   wy, z0+0.07f,  x1-0.04f,   wy+5f, z1-0.07f,  fR, fG, fB);
                        } else if (linearZ) {
                            // X-facing arch: tiles line up in Z. First tile (Z-) draws full panel.
                            if (adjZneg) continue;
                            int runW = 1;
                            while (getBlock(iwx, iwy, iwz + runW) == BlockId.LIMINAL_PORTAL) runW++;
                            float x0 = wx + 0.28f, x1 = wx + 0.72f;
                            float z0 = wz, z1 = wz + runW;
                            emitCube(wb, x0,         wy, z0,         x1,         wy+5f, z1,         cR, cG, cB);
                            emitCube(wb, x0+0.07f,   wy, z0+0.04f,  x1-0.07f,   wy+5f, z1-0.04f,  fR, fG, fB);
                        } else {
                            // Floor portal - flat glowing pad
                            emitCube(wb, wx+0.02f, wy, wz+0.02f, wx+0.98f, wy+0.12f, wz+0.98f, cR, cG, cB);
                            emitCube(wb, wx+0.08f, wy+0.07f, wz+0.08f, wx+0.92f, wy+0.15f, wz+0.92f, fR, fG, fB);
                        }
                    }
                }
            }
        }

        if (wb.n <= 0) return;
        // HDR emissive: ambient=6.0 → ~0.86 Reinhard output → self-lit regardless of scene lighting
        shader.setModelIdentity();
        shader.setAnimTime(0f, false);
        shader.setUseTexture(false);
        shader.setSpecular(0f);
        shader.setLight(lightDir, 6.0f, 0.0f, 0.0f, cam);
        streamMesh.draw(wb.a, wb.n);
        shader.setLight(lightDir, ambient, direct, fogApplied, cam); // restore scene lighting
    }

    // ---------------------------------------------------------------- HALLUCINATIONS (M152)

    private void updateHallucinations(Vector3f cam, Vector3f forward, float dt) {
        // M152: spawn at night or during horror weather events; gate at 15% progression
        boolean horrorWeather = weatherState == WeatherState.DEAD_FOG
                || (bloodRainMode && weatherState == WeatherState.RAIN);
        if (!nightMode && !horrorWeather) { hallucinations.clear(); halluSpawnTimer = 20f; return; }
        if (horrorProgression < 0.15f)    { hallucinations.clear(); halluSpawnTimer = 20f; return; }

        // M153: Max hallucinations â€” toned down; DEAD_FOG max 4, normal night max 3
        int maxHallu = horrorWeather
                ? Math.min(4, 1 + (int)(horrorProgression * 4f))
                : Math.max(1, Math.min(3, (int)(horrorProgression * HALLU_MAX)));
        halluSpawnTimer -= dt;
        if (halluSpawnTimer <= 0f && hallucinations.size() < maxHallu) {
            halluSpawnTimer = horrorWeather
                    ? Math.max(3f, 14f - 10f * horrorProgression) + fogRng.nextFloat() * 5f
                    : Math.max(8f, 40f - 32f * horrorProgression) + fogRng.nextFloat() * 10f;

            boolean spawnFogFigure = horrorWeather && horrorProgression > 0.45f;
            // Fog figures appear at fog edge (8-18 blocks); normal ghosts are 10-32 blocks out
            float dist = spawnFogFigure
                    ? 8f + fogRng.nextFloat() * 10f
                    : 10f + fogRng.nextFloat() * 22f;
            float angle = (float)(fogRng.nextDouble() * Math.PI * 2.0);
            float hx = cam.x + (float)Math.cos(angle) * dist;
            float hz = cam.z + (float)Math.sin(angle) * dist;
            int   hy = ChunkGenerator.heightAt((int)Math.floor(hx), (int)Math.floor(hz), worldSeed) + 1;
            HallucinationEntity h = new HallucinationEntity();
            h.x = hx; h.y = hy; h.z = hz;
            h.facing    = (float)(fogRng.nextDouble() * Math.PI * 2.0);
            h.lifetime  = spawnFogFigure ? 20f + fogRng.nextFloat() * 20f : 12f + fogRng.nextFloat() * 18f;
            h.fogFigure = spawnFogFigure;
            hallucinations.add(h);
        }

        for (int i = hallucinations.size() - 1; i >= 0; i--) {
            HallucinationEntity h = hallucinations.get(i);
            h.lifetime -= dt;
            float dx     = h.x - cam.x;
            float dz     = h.z - cam.z;
            float dist2d = (float)Math.sqrt(dx * dx + dz * dz);
            if (dist2d < 0.001f) dist2d = 0.001f;

            if (h.fogFigure) {
                // Fog figures drift toward the player â€” unsettling slow advance
                float speed = 0.55f + 0.35f * horrorProgression;
                h.vx = (-dx / dist2d) * speed;
                h.vz = (-dz / dist2d) * speed;
                h.x += h.vx * dt;
                h.z += h.vz * dt;
                h.facing = (float)Math.atan2(-dx, -dz); // face toward player
                // Fog figures vanish if fog clears or they get too close (2.5 blocks)
                if (!horrorWeather || dist2d < 2.5f || h.lifetime <= 0f) {
                    h.fading = true;
                    h.fadeOut = Math.min(1f, h.fadeOut + dt * 3.0f);
                }
            } else {
                float lookDot    = forward.x * (dx / dist2d) + forward.z * (dz / dist2d);
                boolean lookingAt = lookDot > 0.95f && dist2d < 30f;
                boolean tooClose  = dist2d < 3.5f;
                if (lookingAt) h.lookTimer += dt;
                else           h.lookTimer = Math.max(0f, h.lookTimer - dt * 0.5f);
                if (!h.fading && (h.lookTimer > 0.5f || tooClose || h.lifetime <= 0f)) h.fading = true;
                if (h.fading) h.fadeOut = Math.min(1f, h.fadeOut + dt * 2.5f);
            }
            if (h.fadeOut >= 1f || h.lifetime <= 0f) hallucinations.remove(i);
        }
    }

    /** M156: Subtle psychological horror â€” ghost sounds, peripheral flashes, torch flicker. */
    private void updatePsychEvents(Vector3f cam, Vector3f fwd, float dt) {
        if (horrorProgression < 0.15f || paused) return;

        // Tick timers
        if (torchFlickerTimer > 0f) {
            torchFlickerTimer -= dt;
            if (torchFlickerTimer <= 0f) torchFlickerActive = false;
        }
        if (flashEdgeTimer > 0f) flashEdgeTimer -= dt;
        if (ghostHalluFrames > 0) ghostHalluFrames--;

        psychTimer -= dt;
        if (psychTimer > 0f) return;

        // Schedule next event â€” scales with horror: 60-120s early, 10-25s at full horror
        float minWait = Math.max(10f, 60f - 50f * horrorProgression);
        psychTimer = minWait + fogRng.nextFloat() * minWait;

        // Rotate through 5 categories; skip louder ones at low progression
        psychPhase = (psychPhase + 1) % 5;
        switch (psychPhase) {
            case 0 -> { // Peripheral dark flash â€” shadow in the corner of your eye
                flashEdgeTimer = 0.09f + fogRng.nextFloat() * 0.05f; // visible ~2-3 frames
                flashEdgeLeft  = fogRng.nextBoolean();
            }
            case 1 -> { // Ghost footstep sound BEHIND the player
                if (psychAudioEvent == 0) psychAudioEvent = 1;
            }
            case 2 -> { // Overhead creak â€” something moving above
                if (psychAudioEvent == 0) psychAudioEvent = 2;
            }
            case 3 -> { // Torch dropout â€” all nearby torches flicker dark for 100ms
                torchFlickerActive = true;
                torchFlickerTimer  = 0.08f + fogRng.nextFloat() * 0.06f;
            }
            case 4 -> { // 1-frame ghost at extreme screen edge (just barely visible)
                if (horrorProgression > 0.30f) {
                    ghostHalluFrames = 2;
                    float angle = (float)(Math.PI + (fogRng.nextFloat() - 0.5f) * 0.6f); // roughly behind
                    float dist  = 14f + fogRng.nextFloat() * 8f;
                    ghostHalluX = cam.x + (float)Math.cos(angle + Math.atan2(fwd.z, fwd.x)) * dist;
                    ghostHalluZ = cam.z + (float)Math.sin(angle + Math.atan2(fwd.z, fwd.x)) * dist;
                }
            }
        }
    }

    private void renderHallucinations(Vector3f cam) {
        if (hallucinations.isEmpty()) return;
        WatcherBuilder wb = new WatcherBuilder();
        for (HallucinationEntity h : hallucinations) {
            float dx   = h.x - cam.x;
            float dz   = h.z - cam.z;
            float dist = (float)Math.sqrt(dx * dx + dz * dz);
            float ff   = entityFogFrac(dist);
            // Fog figures are slightly more visible in fog; normal ghosts fully masked by fog
            float minFf = h.fogFigure ? 0.25f : 0.40f;
            float ghostFf = Math.min(0.99f, Math.max(ff, minFf + h.fadeOut * (1f - minFf)));
            if (ghostFf >= 0.99f) continue;
            float sway = (float)Math.sin(entityAnimTime * 0.9f + h.x * 0.4f) * (h.fogFigure ? 0.01f : 0.03f);
            float ox = h.x, oy = h.y, oz = h.z;
            if (h.fogFigure) {
                // Tall, stretched, limb-less fog entity â€” like a shadow person
                float bR = fogMix(0.04f, clearR, ghostFf), bG = fogMix(0.04f, clearG, ghostFf), bB = fogMix(0.06f, clearB, ghostFf);
                float eR = fogMix(0.65f, clearR, ghostFf), eG = fogMix(0.65f, clearG, ghostFf), eB = fogMix(1.0f, clearB, ghostFf);
                float cosF = (float)Math.cos(h.facing), sinF = (float)Math.sin(h.facing);
                // Body â€” very tall and narrow
                emitVoxelCube(wb, cosF, sinF, ox, oy, oz, 0f, 1.20f, 0f, 0.14f, 1.20f, 0.14f, bR, bG, bB);
                // Head â€” elongated oval
                emitVoxelCube(wb, cosF, sinF, ox, oy, oz, 0f, 2.60f, 0f, 0.18f, 0.28f, 0.16f, bR, bG, bB);
                // Hollow eye sockets â€” faint blue glow that breaks through fog
                emitVoxelCube(wb, cosF, sinF, ox, oy, oz, -0.07f, 2.62f, -0.14f, 0.04f, 0.04f, 0.03f, eR, eG, eB);
                emitVoxelCube(wb, cosF, sinF, ox, oy, oz,  0.07f, 2.62f, -0.14f, 0.04f, 0.04f, 0.03f, eR, eG, eB);
            } else {
                // Normal ghost â€” standard humanoid
                float bR = fogMix(0.10f, clearR, ghostFf), bG = fogMix(0.12f, clearG, ghostFf), bB = fogMix(0.17f, clearB, ghostFf);
                float eR = fogMix(0.90f, clearR, ghostFf), eG = fogMix(0.90f, clearG, ghostFf), eB = fogMix(1.00f, clearB, ghostFf);
                emitCube(wb, ox+sway-0.21f, oy,       oz-0.21f, ox+sway+0.21f, oy+1.68f, oz+0.21f, bR, bG, bB);
                emitCube(wb, ox+sway-0.12f, oy+1.73f, oz+0.17f, ox+sway-0.06f, oy+1.80f, oz+0.23f, eR, eG, eB);
                emitCube(wb, ox+sway+0.06f, oy+1.73f, oz+0.17f, ox+sway+0.12f, oy+1.80f, oz+0.23f, eR, eG, eB);
            }
        }
        if (wb.n <= 0) return;
        shader.setModelIdentity();
        shader.setAnimTime(0f, false);
        shader.setUseTexture(false);
        shader.setSpecular(0f);
        shader.setLight(lightDir, ambient, direct, fogApplied, cam);
        streamMesh.draw(wb.a, wb.n);
    }

    // ---------------------------------------------------------------- RAIN PARTICLES (M51)

    private void updateRainDrops(Vector3f cam, float dt) {
        if (weatherState != WeatherState.RAIN) {
            rainInitialized = false;
            return;
        }
        if (!rainInitialized) {
            // Seed all drops in a cylinder around the player
            for (int i = 0; i < RAIN_DROPS; i++) {
                rainDrops[i][0] = cam.x + (fogRng.nextFloat() - 0.5f) * 24f;
                rainDrops[i][1] = cam.y + fogRng.nextFloat() * 16f;
                rainDrops[i][2] = cam.z + (fogRng.nextFloat() - 0.5f) * 24f;
            }
            rainInitialized = true;
        }
        float fallSpeed = 16f;
        for (int i = 0; i < RAIN_DROPS; i++) {
            rainDrops[i][0] += windX * 0.4f * dt;
            rainDrops[i][1] -= fallSpeed * dt;
            rainDrops[i][2] += windZ * 0.4f * dt;
            // Reset drop above camera when it falls too far below
            float relY = rainDrops[i][1] - cam.y;
            float relX = rainDrops[i][0] - cam.x;
            float relZ = rainDrops[i][2] - cam.z;
            if (relY < -12f || Math.abs(relX) > 14f || Math.abs(relZ) > 14f) {
                rainDrops[i][0] = cam.x + (fogRng.nextFloat() - 0.5f) * 24f;
                rainDrops[i][1] = cam.y + 8f + fogRng.nextFloat() * 8f;
                rainDrops[i][2] = cam.z + (fogRng.nextFloat() - 0.5f) * 24f;
            }
        }
    }

    private void renderRainDrops(Vector3f cam) {
        if (weatherState != WeatherState.RAIN || !rainInitialized) return;
        WatcherBuilder rb = new WatcherBuilder();
        // M152: blood rain is heavier â€” more drops, fatter crimson streaks, slight spray width
        float sx, sz, sy, cr, cg, cb;
        int dropCount;
        if (bloodRainMode) {
            float p = 0.80f + 0.20f * (float)Math.sin(entityAnimTime * 2.8f);
            cr = 0.65f * p; cg = 0.03f; cb = 0.03f;
            sx = 0.018f; sz = 0.018f; sy = 0.55f;
            dropCount = RAIN_DROPS; // full pool for heavy blood rain
        } else {
            cr = 0.62f; cg = 0.72f; cb = 0.90f;
            sx = 0.012f; sz = 0.012f; sy = 0.40f;
            dropCount = 150; // normal rain uses 150 out of the pool
        }
        for (int i = 0; i < dropCount; i++) {
            float x = rainDrops[i][0], y = rainDrops[i][1], z = rainDrops[i][2];
            emitCube(rb, x - sx, y - sy, z - sz, x + sx, y + sy, z + sz, cr, cg, cb);
        }
        if (rb.n <= 0) return;
        shader.setLight(lightDir, 1.15f, 0f, 0f, cam);
        streamMesh.draw(rb.a, rb.n); // M151: streaming draw
        shader.setLight(lightDir, ambient + lightningWorldFlash, direct, fogApplied, cam);
    }

    private void emitShadowQuad(WatcherBuilder fb, float cx, float y, float cz, float r, float cr, float cg, float cb) {
        fb.v(cx-r,y,cz-r, 0,1,0, cr,cg,cb);
        fb.v(cx+r,y,cz-r, 0,1,0, cr,cg,cb);
        fb.v(cx+r,y,cz+r, 0,1,0, cr,cg,cb);
        fb.v(cx-r,y,cz-r, 0,1,0, cr,cg,cb);
        fb.v(cx+r,y,cz+r, 0,1,0, cr,cg,cb);
        fb.v(cx-r,y,cz+r, 0,1,0, cr,cg,cb);
    }

    private void emitCube(WatcherBuilder fb, float minX,float minY,float minZ,float maxX,float maxY,float maxZ, float cr,float cg,float cb) {
        final float[][] P = {
                {1,0,0, 1,1,0, 1,1,1, 1,0,0, 1,1,1, 1,0,1},
                {0,0,1, 0,1,1, 0,1,0, 0,0,1, 0,1,0, 0,0,0},
                {0,1,1, 1,1,1, 1,1,0, 0,1,1, 1,1,0, 0,1,0},
                {0,0,0, 1,0,0, 1,0,1, 0,0,0, 1,0,1, 0,0,1},
                {0,0,1, 1,0,1, 1,1,1, 0,0,1, 1,1,1, 0,1,1},
                {1,0,0, 0,0,0, 0,1,0, 1,0,0, 0,1,0, 1,1,0}
        };
        final float[][] N = {{1,0,0},{-1,0,0},{0,1,0},{0,-1,0},{0,0,1},{0,0,-1}};

        for (int f=0; f<6; f++) {
            for (int i=0;i<6;i++) {
                int pi=i*3;
                float px = P[f][pi], py=P[f][pi+1], pz=P[f][pi+2];
                float x = minX + (maxX-minX)*px;
                float y = minY + (maxY-minY)*py;
                float z = minZ + (maxZ-minZ)*pz;
                fb.v(x,y,z, N[f][0],N[f][1],N[f][2], cr,cg,cb);
            }
        }
    }

    public void toggleLowMemoryMode() {
        setLowMemoryMode(!lowMemoryMode);
    }

    public void setLowMemoryMode(boolean v) {
        lowMemoryMode = v;
        if (lowMemoryMode) {
            radius = Math.min(radius, 3);
            requestBudget = Math.min(requestBudget, 2);
            uploadBudget = Math.min(uploadBudget, 2);
            maxLoadedChunks = Math.min(maxLoadedChunks, 120);
        }
    }

    public void adjustMaxLoadedChunks(int d) { maxLoadedChunks = Math.max(60, Math.min(600, maxLoadedChunks + d)); }
    public void setMaxLoadedChunks(int v) { maxLoadedChunks = Math.max(60, Math.min(600, v)); }

    public void adjustAmbient(float d) { ambient = Math.max(0.05f, Math.min(1.2f, ambient + d)); }
    public void adjustDirect(float d) { direct = Math.max(0.0f, Math.min(1.5f, direct + d)); }
    public void adjustFog(float d) { fogDensity = Math.max(0.0002f, Math.min(0.01f, fogDensity + d)); }
    public void adjustFogMultiplier(float d) { fogUserMultiplier = Math.max(0.25f, Math.min(3.0f, fogUserMultiplier + d)); }
    public void setFogUserMultiplier(float v) { fogUserMultiplier = Math.max(0.25f, Math.min(3.0f, v)); }
    public void toggleFogAutoByRenderDistance() { fogAutoByRenderDistance = !fogAutoByRenderDistance; }
    public void setFogAutoByRenderDistance(boolean v) { fogAutoByRenderDistance = v; }
    public void toggleNightMode() {
        // Safety switch: toggles between cycle and locked-night.
        skyMode = (skyMode == SkyMode.CYCLE) ? SkyMode.STATIC_NIGHT : SkyMode.CYCLE;
    }

    public void toggleSkyCycleMode() {
        toggleNightMode();
    }
    public void setSkyModeCycle(boolean cycle) { skyMode = cycle ? SkyMode.CYCLE : SkyMode.STATIC_NIGHT; }
    public void resetSkyDefaults() {
        skyMode = SkyMode.CYCLE;
        timeOfDay01 = 0.45f;
        dayLengthSeconds = 120f;
        cloudDrift = 0f;
    }

    public int loadedChunkCount() { return chunkMeshes.size(); }
    public int visibleChunkCount() { return lastVisibleChunks; }
    public int queueSize() { return pipeline.readyCount(); }
    public int inFlight() { return pipeline.inFlightCount(); }
    public int radius() { return radius; }
    public int requestBudget() { return requestBudget; }
    public int uploadBudget() { return uploadBudget; }
    public int lastRequestedChunks() { return lastRequestedChunks; }
    public int lastUploadedChunks() { return lastUploadedChunks; }
    public float lastUploadMs() { return lastUploadMs; }
    public float lastRenderMs() { return lastRenderMs; }
    public float ambient() { return ambient; }
    public float direct() { return direct; }
    public float fogDensity() { return fogDensity; }
    public float fogApplied() { return fogApplied; }
    public boolean fogAutoByRenderDistance() { return fogAutoByRenderDistance; }
    public float fogUserMultiplier() { return fogUserMultiplier; }
    public boolean nightMode() { return nightMode; }
    public int maxLoadedChunks() { return maxLoadedChunks; }
    public boolean lowMemoryMode() { return lowMemoryMode; }
    public int lastMesherFloats() { return VoxelMesher.lastFloatCount(); }
    public int mesherFallbackCount() { return VoxelMesher.fallbackCount(); }
    public int watcherCount() { return fogWatchers.size(); }
    public String watcherState() { return watcherState; }

    /** M159: Instantly places a NIGHT_SENTINEL directly behind the player for the "behind you" jumpscare.
     *  The sentinel is inserted at the front of the list so trim-from-back won't remove it first. */
    public void spawnSentinelBehindPlayer(Vector3f camPos, Vector3f forward) {
        float bx = camPos.x - forward.x * 2.5f;
        float bz = camPos.z - forward.z * 2.5f;
        float by = ChunkGenerator.heightAt((int) Math.floor(bx), (int) Math.floor(bz), worldSeed) + 1f;
        fogWatchers.add(0, new Vector3f(bx, by, bz));
        behindYouExtraTimer = 3.0f; // this slot survives 3 seconds before being trimmed
    }
    public String watcherEvent() { return watcherEvent; }
    public float watcherNextEventIn() { return watcherNextEventIn; }
    public float watcherCalmTimer() { return watcherCalmTimer; }
    /** M198: returns drag destination {x,z} when THE THING just grabbed the player; null otherwise. */
    public float[] consumeThingDrag() {
        if (!thingDragPending) return null;
        thingDragPending = false;
        return new float[]{ thingDragDestX, thingDragDestZ };
    }
    /** M198: true while THE THING is in DRAG state (player follows it). */
    public boolean isThingDragging() { return thingState == ThingState.DRAG; }
    /** M198 fix: returns THE THING's current XZ position for smooth per-frame carry. */
    public float[] getThingDragPos() { return new float[]{ thingPos.x, thingPos.z }; }
    public boolean  isLurkerActive()  { return lurkerActive; }             // M98
    public String skyModeName() { return skyMode.name(); }
    public void setHeldTorch(Vector3f pos, boolean active) {
        heldTorchActive = active;
        if (active && pos != null) heldTorchPos.set(pos);
    }
    public void cycleLanternPreset() {
        LanternPreset[] vals = LanternPreset.values();
        lanternPreset = vals[(lanternPreset.ordinal() + 1) % vals.length];
    }
    public String lanternPresetName() { return lanternPreset.name(); }
    public void setPaused(boolean v) {
        if (v && !paused) { // M156: clear weather when entering menus
            weatherState = WeatherState.CLEAR; weatherTimer = 30f; bloodRainMode = false;
        }
        paused = v;
    }
    public float timeOfDay01() { return timeOfDay01; }
    public float nightFactor() { return nightFactor; }
    public float clearR() { return clearR; }
    public float clearG() { return clearG; }
    public float clearB() { return clearB; }
    public boolean isPlayerBeingStalked() {
        // THE THING within 35 blocks and not retreating counts as "being stalked"
        return thingActive && thingState != ThingState.RETREAT
                && new Vector3f(thingPos).sub(lastCamPos).length() < 35f;
    }

    public void setLiminalZoneMode(boolean v) { liminalZoneMode = v; } // M225
    public boolean isLiminalZoneMode() { return liminalZoneMode; }
    public void setLiminalZoneId(int id) {
        this.liminalZoneId = id;
        this.liminalZoneMode = (id != 0); // keep legacy field in sync
        // M234: entering zone 2 — clear foreign entities and spawn NUN immediately
        if (id == 2) {
            nuns.clear();
            float sx = -14f + fogRng.nextFloat() * 28f;  // -14..14 (mansion interior x)
            float sz = -38f + fogRng.nextFloat() * 28f;  // -38..-10 (interior z, away from foyer entrance)
            nuns.add(new NunEntity(sx, 1.0f, sz, fogRng));
            nunSpawnTimer = 90f; // cooldown for a potential second spawn if she somehow disappears
        }
    }
    public int getLiminalZoneId() { return liminalZoneId; }
    /** M225: flush all loaded chunks so they regenerate with new liminalMode setting. */
    public void clearChunksForZoneSwitch() {
        for (GpuMesh m : chunkMeshes.values()) m.destroy();
        chunkMeshes.clear();
        chunkData.clear();
        pipeline.clearReady();
        torchCacheDirty = true;
    }
    public void toggleDistortion() { distortionEnabled = !distortionEnabled; }
    public void nudgeDistortion(float d) { distortionTarget = Math.max(0f, Math.min(0.8f, distortionTarget + d)); }
    public void triggerDistortionPulse() { distortionTarget = Math.max(distortionTarget, 0.35f); distortionPulseTimer = 0.8f; }
    public float distortionIntensity() { return distortionIntensity; }
    public float distortionPulseTimer() { return distortionPulseTimer; }
    public boolean distortionEnabled() { return distortionEnabled; }

    public void destroy() {
        for (GpuMesh m : chunkMeshes.values()) m.destroy();
        chunkMeshes.clear();
        chunkData.clear();
        pipeline.clearReady();
        pipeline.shutdown();
        if (thingMesh         != null) { thingMesh.destroy();         thingMesh         = null; }
        if (thingMeshTextured != null) { thingMeshTextured.destroy(); thingMeshTextured = null; }
        if (thingEyesMesh     != null) { thingEyesMesh.destroy();     thingEyesMesh     = null; }
        if (thingTexId        != -1)   { glDeleteTextures(thingTexId); thingTexId        = -1;  }
        if (nunBodyMesh     != null) { nunBodyMesh.destroy();     nunBodyMesh     = null; }
        if (nunArmMesh      != null) { nunArmMesh.destroy();      nunArmMesh      = null; }
        if (nunModelTex     != -1)   { glDeleteTextures(nunModelTex); nunModelTex  = -1; }
        if (terrainTexId      != 0)    { glDeleteTextures(terrainTexId); terrainTexId     = 0; }
        streamMesh.destroy(); // M151: release persistent streaming VBO
        shader.destroy();
    }
}








