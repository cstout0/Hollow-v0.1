package noctfield.core;

import noctfield.debug.DebugHud;
import noctfield.render.Renderer;
import noctfield.world.BlockId;
import noctfield.world.FreeCamera;
import noctfield.world.Raycast;
import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.glfw.GLFWVidMode;
import org.lwjgl.opengl.GL;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.system.MemoryUtil.NULL;

public class GameApp {
    /** M228: set true before run() via enableBuilderMode() — launched by builder.bat */
    public static boolean BUILDER_MODE = false;
    public void enableBuilderMode() { BUILDER_MODE = true; }

    private long window;
    private InputState input;
    private FreeCamera camera = new FreeCamera(); // M226: initialized early so title screen render is safe
    private Renderer renderer;
    private DebugHud debugHud;

    private int width = 1600;
    private int height = 900;
    // M236: fullscreen state
    private boolean fullscreen  = false;
    private int windowedW       = 1600, windowedH = 900;
    private int windowedX       = 100,  windowedY = 100;

    private float interactCooldown = 0f;

    // M105: first-person hand + hold-to-break mining state
    private int   miningTargetX = Integer.MIN_VALUE;
    private int   miningTargetY = Integer.MIN_VALUE;
    private int   miningTargetZ = Integer.MIN_VALUE;
    private float miningProgress = 0f;      // 0..1 current block break progress
    private float miningTickTimer = 0f;     // cadence for punch feedback while holding
    private float handSwing = 0f;           // 0..1 swing impulse
    private float handBobTime = 0f;         // idle/walk bob phase

    // M112: separate equipped tool slot + durability
    private byte equippedTool = BlockId.AIR;
    private byte equippedArmor = BlockId.AIR; // M168
    private int toolDurability = 0;
    private int toolDurabilityMax = 0;
    private float equipAnim = 1f;

    // M113: lightweight screen-space hit particles matched to block type.
    private static final int HIT_PART_MAX = 24;
    private final float[][] hitParts = new float[HIT_PART_MAX][7]; // x,y,vx,vy,r,g,ttl
    private int hitPartCount = 0;
    // M153: 9-slot hotbar (was 8)
    private static final byte[] HOTBAR = {
        BlockId.GRASS, BlockId.DIRT, BlockId.STONE, BlockId.LANTERN,
        BlockId.WOOD, BlockId.CAMPFIRE, BlockId.BONES, BlockId.COBWEB, BlockId.CRYSTAL
    };
    private byte selectedPlaceBlock = BlockId.STONE;
    private int  selectedHotbarSlot = 2; // index into hotbar slots 0-8; slot 2 = STONE by default

    private final List<WorldProfile> worlds = new ArrayList<>();
    private int worldIndex = 0;
    private boolean titleScreenOpen = true;  // M62: shown at launch + from EXIT TO TITLE
    private int     titleMenuIndex  = 0;

    private boolean worldMenuOpen = false;
    private int worldMenuIndex = 0;
    private boolean optionsMenuOpen = false;
    private int optionsMenuIndex = 0;
    private boolean pauseMenuOpen = false;
    private int pauseMenuIndex = 0;
    private boolean controlsMenuOpen = false;
    private boolean inventoryOpen = false;
    // Death screen
    private boolean deathScreenActive = false;
    private boolean endingScreenActive = false;
    private float   endingScreenTimer  = 0f;
    private static final float ENDING_SCREEN_DURATION = 10f;
    private float   endingPlaytime  = 0f;
    private String  endingWorldName = "";
    private int     endingRelics    = 0;

    // M153: escape objective
    private static final int   RELIC_GOAL        = 3;
    private boolean escapeActive  = false;
    private float   escapeTimer   = 0f;
    private static final float ESCAPE_HOLD_SECS   = 4.5f;
    private boolean relicGoalMsgShown = false;
    private float   deathTimer        = 0f;   // counts down from DEATH_HOLD_SECS
    private static final float DEATH_HOLD_SECS = 2.8f;
    private boolean thingDragActive   = false; // M198 fix: smooth carry instead of instant teleport
    // F3 info overlay
    private boolean infoOverlayVisible = false;
    private boolean craftingTableOpen = false;
    private boolean renamingWorld = false;
    private final StringBuilder renameBuffer = new StringBuilder();

    private int health = 100;
    private float sanity = 100f; // hidden mechanic
    private float hostileTick = 0f;
    private float lastGroundY = -999f;
    private final Random fxRng = new Random();
    private float fovPulse = 0f;
    private float hitTrauma = 0f; // M61: camera shake - 0=calm, 1=max, decays after a hit

    // M63: one-click graphics presets
    private enum GraphicsPreset { MINIMAL, LOW, MEDIUM, HIGH }
    private GraphicsPreset graphicsPreset = GraphicsPreset.MEDIUM;

    private int currentBiome = noctfield.world.ChunkGenerator.BIOME_PINE;
    private float currentMoveMul = 1f;
    private float currentSanityMul = 1f;
    private float biomeBlendP = 1f;
    private float biomeBlendD = 0f;
    private float biomeBlendS = 0f;
    private String biomeAudioLabel = "PINE_HUM";
    private float wardTimer = 0f;

    // M31/M33/M34/M35 fields
    private int   relicsFound           = 0;
    private int   undergroundRelicsFound  = 0; // M179: caps underground contributions at 1
    private String relicMessage         = "";
    private float  relicMessageTimer    = 0f;
    private String lastWatcherEventSeen = "NONE";
    private boolean prevOnGround        = false;
    private noctfield.audio.AudioSystem audio;

    // M144: selected item name flash
    private String hotbarSwitchName  = "";
    private float  hotbarSwitchTimer = 0f;
    private float  introTimer        = 0f;  // M170: new-world objective intro sequence
    private float  boundaryMsgTimer  = 0f;  // M184: world boundary hit message
    private float  relicFlashTimer   = 0f;  // M188: dramatic flash on relic pickup
    private boolean recipeBookOpen   = false; // M188: R key recipe reference
    private int    prevHotbarSlot    = -1;

    // M145: biome atmosphere events
    private float   biomeEventTimer  = 55f; // seconds until next event
    private boolean biomeEventActive = false;
    private float   biomeEventFade   = 0f;

    // M147: auto-save
    private float autoSaveTimer   = 300f; // every 5 minutes
    private float savedFlashTimer = 0f;

    // M225: liminal zone
    private boolean inLiminalZone = false; // M225 legacy: true when in any liminal zone
    private int     currentZone   = 0;    // M229: 0=overworld 1=meadow 2=darkroom
    // M232: zone transition loading screen
    private boolean zoneLoading        = false;
    private float   zoneLoadTimer      = 0f;
    private static final float ZONE_LOAD_DURATION = 2.2f;
    private float   overworldReturnX   = 0f;
    private float   overworldReturnZ   = 0f;
    private float   liminalPortalCooldown = 0f; // prevent instant re-trigger after entry/exit

    // M227: structure save/load tool
    private boolean structCaptureMode  = false;
    private int     structCaptureStep  = 0;      // 0=await A, 1=await B, 2=both set
    private int[]   structA            = null;   // world block coords [x,y,z]
    private int[]   structB            = null;
    private boolean structPasteMode    = false;
    private int     structPasteIndex   = 0;
    private final java.util.List<String> savedStructNames = new java.util.ArrayList<>();
    private boolean namingStruct       = false;
    private final StringBuilder structNameBuffer = new StringBuilder();

    // M152: horror progression + escalation milestones
    private float worldPlayTime      = 0f;
    private float idleTimer          = 0f;  // M172: seconds still underground
    private float fungusTouchTimer    = 0f;  // M173: passive heal from standing on fungus
    private boolean idleScared       = false; // M172: idle scare fired this underground visit
    private float thickFogMsgTimer   = 0f;
    private String horrorEventMsg    = "";
    private int   lastEscalateMilestone = 0; // 0=none, 1=25%, 2=50%, 3=75%, 4=100%
    // M215/M218: hide & seek event (fires once per playthrough)
    private boolean hideSeekFired        = false;  // once per world
    private boolean hideSeekActive       = false;  // event running
    private boolean hideSeekSpawned      = false;  // screamer has been released
    private boolean hideSeekCaught       = false;  // player was spotted
    private float   hideSeekSpawnTimer   = 0f;    // phase 1: delay before screamer spawns (~18s)
    private float   hideSeekPatrolTimer  = 0f;    // phase 2: patrol duration (30s)
    private float   hideSeekTriggerTimer = -1f;   // armed when sanity <= 60%
    // M211: sanity-threshold taunts
    private boolean taunt85Fired = false;
    private boolean taunt50Fired = false;
    private boolean taunt30Fired = false;

    // M159: jumpscare system
    private float   jsGlobalCooldown    = 120f; // no jumpscares in first 2 minutes
    private float   faceJsCooldown      = 300f; // THE FACE: at least 5 min from start
    private float   falseChargeCooldown = 120f; // FALSE CHARGE: 2 min startup delay
    private float   behindYouCooldown   = 180f; // BEHIND YOU: 3 min startup delay
    private float   silenceCooldown     = 200f; // DEAD SILENCE: 3+ min startup delay
    private int     faceFramesLeft      = 0;    // countdown: 4=white flash, 3-1=face render
    private float   falseChargeFlash    = 0f;   // red screen flash timer
    private float   behindYouTimer      = 0f;   // "IT'S BEHIND YOU" text countdown
    private final java.util.Random jsRng = new java.util.Random(System.nanoTime());

    // M37 underground atmosphere
    private boolean underground = false;
    private float   caveFogMultiplier = 1.0f; // M83: smooth cave fog blend
    private int     caveZone = -1;            // M85: current cave zone (-1 = surface)
    private float   darknessMultiplier = 1.0f; // M92: extra fog when no lantern underground
    private boolean godMode = false;           // debug: invincibility toggle
    private boolean audioDebugOverlay = false; // M100: ALT+A shows audio source states

    // M99: surface world events
    private enum SurfaceEvent { NONE, FOG_BANK, WIND_STORM, EMBER_SHOWER }
    private SurfaceEvent surfaceEvent        = SurfaceEvent.NONE;
    private float        surfaceEventTimer   = 0f;   // countdown to event end
    private float        surfaceEventCooldown = 90f; // wait before first event
    private float        emberSpawnTimer     = 0f;   // controls ember burst rate
    private String       surfaceEventMsg     = "";
    private float        surfaceEventMsgTimer = 0f;

    // M93: stalactite drop state
    private long  stalactiteKey      = 0L;   // encoded position of pending stalactite
    private float stalactiteTimer    = -1f;  // countdown to impact (-1 = none pending)
    private float stalactiteCooldown = 0f;   // M96: global cooldown between any stalactite events
    private final java.util.Set<Long> usedStalactites = new java.util.HashSet<>();

    // M38 sprint stamina
    private float   stamina          = 100f;
    private boolean staminaExhausted = false;

    // M40 journal fragments
    private String journalMessage      = "";
    private float  journalMessageTimer = 0f;

    private static final String[] JOURNAL_LINES = {
        "IT KNOWS YOU ARE HERE",
        "DO NOT LOOK AT IT DIRECTLY",
        "THE SECOND NIGHT IS THE LAST",
        "I CAN STILL HEAR IT BREATHING",
        "THE RELICS CALL TO THEM",
        "LEAVE WHILE YOU STILL CAN",
        "IT HAS ALWAYS BEEN WATCHING",
        "WE THOUGHT THE LANTERNS WOULD HELP",
        "SOMETHING FOLLOWED ME FROM THE CAVES",
        "FIND THEM ALL AND MAYBE IT ENDS",
        "DAY THREE. IT FOUND THE CAMP AGAIN.",
        "THE THING BELOW DOES NOT SLEEP",
        "I SEALED THE ENTRANCE. IT CAME THROUGH ANYWAY.",
        "DO NOT MAKE A SOUND UNDERGROUND",
        "THE CRYSTALS GLOW BRIGHTER WHEN IT IS NEAR",
        "I COUNTED SEVEN RELICS. THEN THERE WERE SIX.",
        "IT CANNOT SEE. BUT IT HEARS EVERYTHING.",
        "THE GATE OPENS ONLY WHEN THEY ARE ALL RETURNED",
        "THEY NEST BENEATH THE CRYSTAL FIELDS",
        "MY LANTERN WENT OUT. I HEARD IT CRAWLING.",
        "STAND STILL. DO NOT BREATHE.",
        "THE VOID PREDATES THE WORLD",
        "THERE IS SOMETHING IN THE CAVE THAT IS NOT THE LURKER",
        "I WROTE THIS KNOWING YOU WILL FIND IT TOO LATE",
        "THE BONES HERE ARE NOT ANIMAL",
        "BUILT INTO THIS WORLD. YOU WERE NEVER SUPPOSED TO ESCAPE.",
        // M189: additional lore entries
        "THE COMPASS DOES NOT POINT NORTH. IT POINTS TO WHAT WANTS YOU BACK.",
        "EVERY SOUND YOU MAKE UNDERGROUND IS A DINNER BELL.",
        "WE BUILT A DOOR. THE THING DOES NOT USE DOORS.",
        "THE RELIC FELT WARM. LIKE IT WAS RECENTLY HELD.",
        "NIGHT FOUR. THE FOG BROUGHT SHAPES WITH IT.",
        "I RAN. IT FOLLOWED WITHOUT MOVING.",
        "COOKED THE LAST OF THE MEAT. SMOKE GAVE AWAY THE CAMP.",
        "THE CAMPFIRE WENT OUT ON ITS OWN. TWICE.",
        "IF YOU ARE READING THIS WE FAILED. DO NOT REPEAT OUR MISTAKE.",
        "THREE OF US CAME DOWN. ONE SET OF FOOTPRINTS LEADING OUT.",
        "THE GATE AT THE ORIGIN IS NOT AN EXIT. I DO NOT KNOW WHAT IT IS.",
        "SOMETHING MIMICS THE SOUND OF FOOTSTEPS JUST BEHIND YOU.",
    };

    // M189: relic-specific lore — returned when journal found near a relic position
    private static final String[] RELIC_JOURNAL_LINES = {
        "THIS RELIC WAS BURIED HERE BEFORE THE FOREST GREW. THE TREES SHAPED THEMSELVES AROUND IT.",
        "THE DEAD LANDS FORMED AFTER THE RELIC ARRIVED. EVERYTHING IT TOUCHES STOPS LIVING.",
        "THE RELIC SANK TO THE CAVES OVER CENTURIES. THE THINGS DOWN HERE HAVE BEEN WAITING FOR SOMEONE TO CARRY IT OUT.",
    };

    // M145: biome atmosphere whispers
    private static final String[] DEAD_WHISPERS = {
        "IT HAS FOUND YOU",  "DO NOT RUN",  "WE ARE ALL STILL HERE",
        "THE EARTH IS ROTTING",  "YOUR HEARTBEAT ECHOES",  "SOMETHING SHIFTS BENEATH",
    };
    private static final String[] SWAMP_WHISPERS = {
        "THE WATER REMEMBERS",  "STAY STILL",  "THEY NEST BELOW",
        "THE FOG IS ALIVE",  "IT WATCHES FROM THE MUD",
    };

    private final List<TempStructure> tempStructures = new ArrayList<>();
    private static final float NO_BUILD_CENTER_RADIUS = 12f;

    private enum CraftKind { ITEM, WARD }

    // Shaped crafting — each slot requires exactly 1 item; position matters like Minecraft.
    private static final class Recipe {
        final String name;
        final CraftKind kind;
        final byte outItem;
        final int outCount;
        final float wardSeconds;
        final int unlockRelics;
        final boolean needsTable;
        // shape[row][col], row 0 = visual top-left; AIR = must be empty.
        final byte[][] shape;
        Recipe(String name, CraftKind kind, byte out, int outCnt, float ward, int relics, boolean table, byte[]... rows) {
            this.name = name; this.kind = kind; this.outItem = out; this.outCount = outCnt;
            this.wardSeconds = ward; this.unlockRelics = relics; this.needsTable = table;
            this.shape = rows;
        }
    }

    // Shorthand constants for recipe pattern arrays (not exposed outside RECIPES init).
    private static final byte _A = BlockId.AIR,     _W  = BlockId.WOOD,    _S = BlockId.STONE,
                               _L = BlockId.LEAVES,  _M  = BlockId.MUD,     _B = BlockId.BONES,
                               _C = BlockId.CRYSTAL, _CB = BlockId.COBWEB,  _LN= BlockId.LANTERN,
                               _WP= BlockId.WOOD_PLANK, _CO= BlockId.COAL;
    private static final byte _CM = BlockId.COMPASS, _BN = BlockId.ARMOR_BONE,
                               _AS = BlockId.ARMOR_STONE, _AC = BlockId.ARMOR_CRYSTAL,
                               _RP = BlockId.RAW_PORK,
                               _RC = BlockId.RAW_CHICKEN; // M192

    // M236: strip path-separator chars so world/struct names can't escape the worlds/ directory
    private static String sanitizeName(String raw) {
        if (raw == null) return "world";
        // Keep only alphanumeric, spaces, hyphens, underscores
        String s = raw.replaceAll("[^A-Za-z0-9 \\-_]", "").trim();
        if (s.isEmpty()) s = "world";
        return s.length() > 32 ? s.substring(0, 32) : s;
    }

    // Helper: one recipe row.
    private static byte[] rr(byte... cols) { return cols; }

    private static final Recipe[] RECIPES = new Recipe[] {
        // ── 2×2 inventory recipes (no table needed) ────────────────────────────
        // OAK LOG → 4 WOOD PLANK: single slot, no pattern needed (1×1)
        new Recipe("WOOD PLANK x4", CraftKind.ITEM, BlockId.WOOD_PLANK, 4, 0f, 0, false,
            rr(_W)),
        // CRAFT TABLE: 2×2 planks
        new Recipe("WORKBENCH", CraftKind.ITEM, BlockId.CRAFTING_TABLE, 1, 0f, 0, false,
            rr(_WP, _WP),
            rr(_WP, _WP)),
        // TORCH x4: plank over leaves (1 col × 2 rows)
        new Recipe("TORCH x4",    CraftKind.ITEM, BlockId.TOOL_TORCH,      4, 0f, 0, false,
            rr(_WP),
            rr(_L)),
        // M149: COAL TORCH x8: coal over plank — coal is a better fuel, doubles yield
        new Recipe("TORCH x8",    CraftKind.ITEM, BlockId.TOOL_TORCH,      8, 0f, 0, false,
            rr(_CO),
            rr(_WP)),
        // LANTERN: plank left, leaves right
        new Recipe("LANTERN",     CraftKind.ITEM, BlockId.LANTERN,          1, 0f, 0, false,
            rr(_WP, _L)),
        // WOOD AXE: L-shape planks (2×2: top-left, top-right, bottom-left filled)
        new Recipe("WOOD AXE",    CraftKind.ITEM, BlockId.TOOL_WOOD_AXE,    1, 0f, 0, false,
            rr(_WP, _WP),
            rr(_WP, _A)),
        // WARD CHARGE: mud + leaves diagonal (2×2)
        new Recipe("WARD CHARGE", CraftKind.WARD, (byte)0,                  0, 45f, 0, false,
            rr(_M, _L),
            rr(_L, _M)),
        new Recipe("COMPASS", CraftKind.ITEM, BlockId.COMPASS, 1, 0f, 0, false,
            rr(_C),
            rr(_WP)),
        new Recipe("BONE ARMOR", CraftKind.ITEM, BlockId.ARMOR_BONE, 1, 0f, 0, false,
            rr(_B, _B, _B)),
        // M184: COOKED PORK — raw pork over coal (no table), heals 50 HP
        new Recipe("COOKED PORK", CraftKind.ITEM, BlockId.COOKED_PORK, 1, 0f, 0, false,
            rr(_RP),
            rr(_CO)),
        // M192: COOKED CHICKEN — raw chicken over coal (no table), heals 40 HP
        new Recipe("COOKED CHICKEN", CraftKind.ITEM, BlockId.COOKED_CHICKEN, 1, 0f, 0, false,
            rr(_RC),
            rr(_CO)),
        // ── 3×3 table recipes (needsTable = true) ─────────────────────────────
        // WOOD PICK: plank head across top, plank handle
        new Recipe("WOOD PICK",   CraftKind.ITEM, BlockId.TOOL_WOOD_PICK,   1, 0f, 0, true,
            rr(_WP, _WP, _WP),
            rr(_A,  _WP, _A),
            rr(_A,  _WP, _A)),
        // STONE PICK: stone head, plank handle (no relic gate)
        new Recipe("STONE PICK",  CraftKind.ITEM, BlockId.TOOL_STONE_PICK,  1, 0f, 0, true,
            rr(_S,  _S,  _S),
            rr(_A,  _WP, _A),
            rr(_A,  _WP, _A)),
        // CRYSTAL PICK: crystal head, stone handle (no relic gate)
        new Recipe("CRYSTAL PICK",CraftKind.ITEM, BlockId.TOOL_CRYSTAL_PICK,1, 0f, 0, true,
            rr(_C,  _C,  _C),
            rr(_A,  _S,  _A),
            rr(_A,  _S,  _A)),
        // CAMPFIRE: cross of planks around stone center
        new Recipe("CAMPFIRE",    CraftKind.ITEM, BlockId.CAMPFIRE,          1, 0f, 1, true,
            rr(_A,  _WP, _A),
            rr(_WP, _S,  _WP),
            rr(_A,  _WP, _A)),
        // BONE CHARM (ward): bones at corners, cobweb center
        new Recipe("BONE CHARM",  CraftKind.WARD, (byte)0,                  0, 30f, 2, true,
            rr(_B,  _A,  _B),
            rr(_A,  _CB, _A),
            rr(_B,  _A,  _B)),
        // RELIC WARD (ward): lanterns at corners, mud center
        new Recipe("RELIC WARD",  CraftKind.WARD, (byte)0,                  0, 90f, 3, true,
            rr(_LN, _A,  _LN),
            rr(_A,  _M,  _A),
            rr(_LN, _A,  _LN)),
        // DOOR: 2 columns × 3 rows of wood planks (needs crafting table)
        new Recipe("DOOR",        CraftKind.ITEM, BlockId.DOOR_CLOSED,       1, 0f, 0, true,
            rr(_WP, _WP),
            rr(_WP, _WP),
            rr(_WP, _WP)),
        new Recipe("STONE ARMOR",   CraftKind.ITEM, BlockId.ARMOR_STONE,   1, 0f, 0, true,
            rr(_S, _S, _S)),
        new Recipe("CRYSTAL ARMOR", CraftKind.ITEM, BlockId.ARMOR_CRYSTAL, 1, 0f, 0, true,
            rr(_C, _C, _C)),
    };

    public void run() {
        init();
        loop();
        shutdown();
    }

    private void loadWorldProfiles() {
        worlds.clear();
        Path p = Path.of("worlds.txt");
        if (Files.exists(p)) {
            try {
                for (String line : Files.readAllLines(p, StandardCharsets.UTF_8)) {
                    line = line.trim();
                    if (line.isEmpty() || line.startsWith("#")) continue;
                    String[] s = line.split("\\s+");
                    if (s.length < 2) continue;
                    worlds.add(new WorldProfile(s[0], Long.parseLong(s[1])));
                }
            } catch (Exception ex) {
                System.err.println("Failed reading worlds.txt: " + ex.getMessage());
            }
        }
        // M226: no default worlds — first launch starts with an empty list
    }

    // M65: slot-based backpack (drag/drop + split stacks)
    private static final int INV_SLOTS = 27; // M171: 9 hotbar + 2 backpack rows
    private static final int INV_COLS = 9;
    private static final int STACK_MAX = 99;
    private final byte[] slotItem = new byte[INV_SLOTS];
    private final int[] slotCount = new int[INV_SLOTS];
    private int invCursor = 0;
    private byte heldItem = BlockId.AIR;
    private int heldCount = 0;
    private boolean leftDragDistribute = false;
    private final boolean[] dragVisited = new boolean[INV_SLOTS];
    private final byte[] craftItem = new byte[9]; // 2x2 (inventory) or 3x3 (table UI)
    private final int[] craftCount = new int[9];
    private byte craftOutItem = BlockId.AIR;
    private int craftOutCount = 0;
    private float craftOutWardSeconds = 0f;

    private int inv(byte id) {
        int s = 0;
        for (int i = 0; i < INV_SLOTS; i++) if (slotItem[i] == id) s += slotCount[i];
        return s;
    }

    private void addInv(byte id, int n) {
        if (id == BlockId.AIR || n == 0) return;
        if (n > 0) {
            int rem = n;
            // fill existing stacks first
            for (int i = 0; i < INV_SLOTS && rem > 0; i++) {
                if (slotItem[i] == id && slotCount[i] < STACK_MAX) {
                    int can = Math.min(rem, STACK_MAX - slotCount[i]);
                    slotCount[i] += can;
                    rem -= can;
                }
            }
            // use empty slots
            for (int i = 0; i < INV_SLOTS && rem > 0; i++) {
                if (slotCount[i] <= 0) {
                    slotItem[i] = id;
                    int put = Math.min(rem, STACK_MAX);
                    slotCount[i] = put;
                    rem -= put;
                }
            }
            return;
        }

        int rem = -n;
        for (int i = INV_SLOTS - 1; i >= 0 && rem > 0; i--) {
            if (slotItem[i] == id && slotCount[i] > 0) {
                int take = Math.min(rem, slotCount[i]);
                slotCount[i] -= take;
                rem -= take;
                if (slotCount[i] <= 0) { slotCount[i] = 0; slotItem[i] = BlockId.AIR; }
            }
        }
    }

    private Path playerStatePath() {
        return Path.of("worlds", renderer.worldId() + "-player.txt");
    }

    private Path optionsPath() {
        return Path.of("worlds", "options.txt");
    }


    private void saveOptions() {
        try {
            Files.createDirectories(Path.of("worlds"));
            ArrayList<String> lines = new ArrayList<>();
            lines.add("graphics_preset " + graphicsPreset.name());
            lines.add("sky_cycle " + ("CYCLE".equals(renderer.skyModeName()) ? 1 : 0));
            lines.add("fog_auto " + (renderer.fogAutoByRenderDistance() ? 1 : 0));
            lines.add("fog_mult " + renderer.fogUserMultiplier());
            lines.add("radius " + renderer.radius());
            lines.add("max_chunks " + renderer.maxLoadedChunks());
            lines.add("master_volume " + audio.getMasterVolume()); // M193
            lines.add("music_volume " + audio.getMusicVolume()); // M212
            lines.add("fov " + (int)camera.getBaseFov()); // M213
            lines.add("dynamic_lighting " + (renderer.isDynamicLighting() ? 1 : 0)); // M231
            Files.write(optionsPath(), lines, StandardCharsets.UTF_8);
        } catch (Exception ignored) {}
    }

    private void loadOptions() {
        Path p = optionsPath();
        if (!Files.exists(p)) return;
        try {
            for (String line : Files.readAllLines(p, StandardCharsets.UTF_8)) {
                String[] s = line.trim().split("\\s+");
                if (s.length < 2) continue;
                switch (s[0]) {
                    case "graphics_preset" -> {
                        try {
                            graphicsPreset = GraphicsPreset.valueOf(s[1].toUpperCase());
                            applyGraphicsPreset(graphicsPreset);
                        } catch (Exception ignored) {}
                    }
                    case "sky_cycle" -> renderer.setSkyModeCycle(!"0".equals(s[1]));
                    case "fog_auto" -> renderer.setFogAutoByRenderDistance(!"0".equals(s[1]));
                    case "fog_mult" -> renderer.setFogUserMultiplier(Float.parseFloat(s[1]));
                    case "radius" -> renderer.setRadius(Integer.parseInt(s[1]));
                    case "max_chunks" -> renderer.setMaxLoadedChunks(Integer.parseInt(s[1]));
                    case "master_volume" -> audio.setMasterVolume(Float.parseFloat(s[1])); // M193
                    case "music_volume"  -> audio.setMusicVolume(Float.parseFloat(s[1]));  // M212
                    case "fov"              -> camera.setBaseFov(Float.parseFloat(s[1]));     // M213
                    case "dynamic_lighting" -> renderer.setDynamicLighting(!"0".equals(s[1])); // M231
                }
            }
        } catch (Exception ignored) {}
    }

    private void applyGraphicsPreset(GraphicsPreset preset) {
        graphicsPreset = preset;
        switch (preset) {
            case MINIMAL -> {
                renderer.setLowMemoryMode(true);
                renderer.setFogAutoByRenderDistance(true);
                renderer.setFogUserMultiplier(1.40f);
                renderer.setRadius(3);
                renderer.setRequestBudget(2);
                renderer.setUploadBudget(2);
                renderer.setMaxLoadedChunks(100);
            }
            case LOW -> {
                renderer.setLowMemoryMode(false);
                renderer.setFogAutoByRenderDistance(true);
                renderer.setFogUserMultiplier(1.15f);
                renderer.setRadius(4);
                renderer.setRequestBudget(5);
                renderer.setUploadBudget(3);
                renderer.setMaxLoadedChunks(160);
            }
            case MEDIUM -> {
                renderer.setLowMemoryMode(false);
                renderer.setFogAutoByRenderDistance(true);
                renderer.setFogUserMultiplier(1.00f);
                renderer.setRadius(6);
                renderer.setRequestBudget(10);
                renderer.setUploadBudget(6);
                renderer.setMaxLoadedChunks(260);
            }
            case HIGH -> {
                renderer.setLowMemoryMode(false);
                renderer.setFogAutoByRenderDistance(false);
                renderer.setFogUserMultiplier(0.80f);
                renderer.setRadius(8);
                renderer.setRequestBudget(16);
                renderer.setUploadBudget(10);
                renderer.setMaxLoadedChunks(420);
            }
        }
    }

    private void cycleGraphicsPreset(int step) {
        GraphicsPreset[] vals = GraphicsPreset.values();
        int i = (graphicsPreset.ordinal() + step + vals.length) % vals.length;
        applyGraphicsPreset(vals[i]);
    }

    private void savePlayerState() {
        try {
            Files.createDirectories(Path.of("worlds"));
            ArrayList<String> lines = new ArrayList<>();
            lines.add("health " + health);
            lines.add("sanity " + (int)sanity);
            lines.add("inv_grass " + inv(BlockId.GRASS));
            lines.add("inv_dirt " + inv(BlockId.DIRT));
            lines.add("inv_stone " + inv(BlockId.STONE));
            lines.add("inv_lantern " + inv(BlockId.LANTERN));
            lines.add("inv_wood " + inv(BlockId.WOOD));
            lines.add("inv_leaves " + inv(BlockId.LEAVES));
            lines.add("inv_mud " + inv(BlockId.MUD));
            lines.add("inv_campfire " + inv(BlockId.CAMPFIRE));  // M53
            lines.add("inv_bones " + inv(BlockId.BONES));        // M53
            lines.add("inv_cobweb " + inv(BlockId.COBWEB));      // M53
            lines.add("inv_craft_table " + inv(BlockId.CRAFTING_TABLE));
            lines.add("inv_wood_plank "  + inv(BlockId.WOOD_PLANK));
            lines.add("inv_wood_axe "    + inv(BlockId.TOOL_WOOD_AXE));
            lines.add("inv_door "        + inv(BlockId.DOOR_CLOSED));
            lines.add("inv_tool_wood " + inv(BlockId.TOOL_WOOD_PICK));
            lines.add("inv_tool_stone " + inv(BlockId.TOOL_STONE_PICK));
            lines.add("inv_tool_crystal " + inv(BlockId.TOOL_CRYSTAL_PICK));
            lines.add("inv_tool_torch " + inv(BlockId.TOOL_TORCH));
            lines.add("inv_compass "       + inv(BlockId.COMPASS));        // M177
            lines.add("inv_coal "          + inv(BlockId.COAL));           // M177
            lines.add("inv_raw_pork "      + inv(BlockId.RAW_PORK));       // M177
            lines.add("inv_cooked_pork "   + inv(BlockId.COOKED_PORK));   // M184
            lines.add("inv_raw_chicken "   + inv(BlockId.RAW_CHICKEN));   // M192
            lines.add("inv_cooked_chicken "+ inv(BlockId.COOKED_CHICKEN)); // M192
            lines.add("inv_cave_mushroom " + inv(BlockId.CAVE_MUSHROOM));  // M177
            lines.add("inv_armor_bone "    + inv(BlockId.ARMOR_BONE));     // M177
            lines.add("inv_armor_stone "   + inv(BlockId.ARMOR_STONE));    // M177
            lines.add("inv_armor_crystal " + inv(BlockId.ARMOR_CRYSTAL));  // M177
            lines.add("eq_tool " + equippedTool + " " + toolDurability + " " + toolDurabilityMax);
            lines.add("eq_armor " + equippedArmor);
            lines.add("relics_found " + relicsFound);
            lines.add("underground_relics_found " + undergroundRelicsFound); // M179
            // Player position & look - saved with full float precision
            if (camera != null) {
                org.joml.Vector3f p = camera.position();
                lines.add(String.format("pos_x %.4f", p.x));
                lines.add(String.format("pos_y %.4f", p.y));
                lines.add(String.format("pos_z %.4f", p.z));
                lines.add(String.format("yaw %.6f",   camera.getYaw()));
                lines.add(String.format("pitch %.6f", camera.getPitch()));
            }
            Files.write(playerStatePath(), lines, StandardCharsets.UTF_8);
        } catch (Exception ignored) {}
    }

    private void loadPlayerState() {
        health = 100;
        sanity = 100f;
        for (int i = 0; i < INV_SLOTS; i++) { slotItem[i] = BlockId.AIR; slotCount[i] = 0; }
        heldItem = BlockId.AIR;
        heldCount = 0;
        equippedTool = BlockId.AIR;
        equippedArmor = BlockId.AIR;
        toolDurability = 0;
        toolDurabilityMax = 0;
        Path p = playerStatePath();
        if (!Files.exists(p)) {
            // Fresh world — compass only (M180)
            addInv(BlockId.COMPASS, 1);
            return;
        }

        float loadedX = Float.NaN, loadedY = Float.NaN, loadedZ = Float.NaN;
        float loadedYaw = Float.NaN, loadedPitch = Float.NaN;

        try {
            for (String line : Files.readAllLines(p, StandardCharsets.UTF_8)) {
                String[] s = line.trim().split("\\s+");
                if (s.length < 2) continue;
                switch (s[0]) {
                    case "health"       -> health      = Math.max(1, Math.min(100, Integer.parseInt(s[1])));
                    case "sanity"       -> sanity      = Math.max(0f, Math.min(100f, Integer.parseInt(s[1])));
                    case "inv_grass"    -> addInv(BlockId.GRASS,   Math.max(0, Integer.parseInt(s[1])));
                    case "inv_dirt"     -> addInv(BlockId.DIRT,    Math.max(0, Integer.parseInt(s[1])));
                    case "inv_stone"    -> addInv(BlockId.STONE,   Math.max(0, Integer.parseInt(s[1])));
                    case "inv_lantern"  -> addInv(BlockId.LANTERN, Math.max(0, Integer.parseInt(s[1])));
                    case "inv_wood"     -> addInv(BlockId.WOOD,    Math.max(0, Integer.parseInt(s[1])));
                    case "inv_leaves"   -> addInv(BlockId.LEAVES,  Math.max(0, Integer.parseInt(s[1])));
                    case "inv_mud"      -> addInv(BlockId.MUD,     Math.max(0, Integer.parseInt(s[1])));
                    case "inv_campfire" -> addInv(BlockId.CAMPFIRE,Math.max(0, Integer.parseInt(s[1]))); // M53
                    case "inv_bones"    -> addInv(BlockId.BONES,   Math.max(0, Integer.parseInt(s[1]))); // M53
                    case "inv_cobweb"   -> addInv(BlockId.COBWEB,  Math.max(0, Integer.parseInt(s[1]))); // M53
                    case "inv_craft_table" -> addInv(BlockId.CRAFTING_TABLE, Math.max(0, Integer.parseInt(s[1])));
                    case "inv_wood_plank"  -> addInv(BlockId.WOOD_PLANK,     Math.max(0, Integer.parseInt(s[1])));
                    case "inv_wood_axe"    -> addInv(BlockId.TOOL_WOOD_AXE,  Math.max(0, Integer.parseInt(s[1])));
                    case "inv_door"        -> addInv(BlockId.DOOR_CLOSED,    Math.max(0, Integer.parseInt(s[1])));
                    case "inv_tool_wood" -> addInv(BlockId.TOOL_WOOD_PICK, Math.max(0, Integer.parseInt(s[1])));
                    case "inv_tool_stone" -> addInv(BlockId.TOOL_STONE_PICK, Math.max(0, Integer.parseInt(s[1])));
                    case "inv_tool_crystal" -> addInv(BlockId.TOOL_CRYSTAL_PICK, Math.max(0, Integer.parseInt(s[1])));
                    case "inv_tool_torch"    -> addInv(BlockId.TOOL_TORCH,   Math.max(0, Integer.parseInt(s[1])));
                    case "inv_compass"        -> addInv(BlockId.COMPASS,      Math.max(0, Integer.parseInt(s[1])));
                    case "inv_coal"           -> addInv(BlockId.COAL,         Math.max(0, Integer.parseInt(s[1])));
                    case "inv_raw_pork"       -> addInv(BlockId.RAW_PORK,      Math.max(0, Integer.parseInt(s[1])));
                    case "inv_cooked_pork"    -> addInv(BlockId.COOKED_PORK,   Math.max(0, Integer.parseInt(s[1]))); // M184
                    case "inv_raw_chicken"    -> addInv(BlockId.RAW_CHICKEN,   Math.max(0, Integer.parseInt(s[1]))); // M192
                    case "inv_cooked_chicken" -> addInv(BlockId.COOKED_CHICKEN,Math.max(0, Integer.parseInt(s[1]))); // M192
                    case "inv_cave_mushroom"  -> addInv(BlockId.CAVE_MUSHROOM, Math.max(0, Integer.parseInt(s[1])));
                    case "inv_armor_bone"     -> addInv(BlockId.ARMOR_BONE,    Math.max(0, Integer.parseInt(s[1])));
                    case "inv_armor_stone"    -> addInv(BlockId.ARMOR_STONE,   Math.max(0, Integer.parseInt(s[1])));
                    case "inv_armor_crystal"  -> addInv(BlockId.ARMOR_CRYSTAL, Math.max(0, Integer.parseInt(s[1]))); // M177
                    case "eq_tool" -> {
                        if (s.length >= 4) {
                            equippedTool = (byte)Integer.parseInt(s[1]);
                            toolDurability = Math.max(0, Integer.parseInt(s[2]));
                            toolDurabilityMax = Math.max(toolDurability, Integer.parseInt(s[3]));
                        }
                    }
                    case "eq_armor" -> equippedArmor = (byte) Integer.parseInt(s[1]);
                    case "relics_found"           -> relicsFound           = Math.max(0, Integer.parseInt(s[1]));
                    case "underground_relics_found" -> undergroundRelicsFound = Math.max(0, Integer.parseInt(s[1])); // M179
                    case "pos_x"        -> loadedX     = Float.parseFloat(s[1]);
                    case "pos_y"        -> loadedY     = Float.parseFloat(s[1]);
                    case "pos_z"        -> loadedZ     = Float.parseFloat(s[1]);
                    case "yaw"          -> loadedYaw   = Float.parseFloat(s[1]);
                    case "pitch"        -> loadedPitch = Float.parseFloat(s[1]);
                }
            }
        } catch (Exception ignored) {}

        if (inv(BlockId.COMPASS) <= 0) addInv(BlockId.COMPASS, 1); // M186: ensure every loaded save has a compass (old saves predate compass-only start)
        if (!BlockId.isTool(equippedTool)) { equippedTool = BlockId.AIR; toolDurability = 0; toolDurabilityMax = 0; }
        // M157: torch is not a persistent tool — return any stuck saved torch to inventory
        if (equippedTool == BlockId.TOOL_TORCH) { addInv(BlockId.TOOL_TORCH, 1); equippedTool = BlockId.AIR; toolDurability = 0; toolDurabilityMax = 0; }
        if (equippedTool != BlockId.AIR && toolDurabilityMax <= 0) {
            toolDurabilityMax = toolMaxDurability(equippedTool);
            toolDurability = Math.max(1, toolDurabilityMax);
        }
        renderer.setRelicLevel(relicsFound); // M36: restore escalation after world load
        stamina = 100f;
        staminaExhausted = false;

        // Restore exact position if all three coordinates were saved
        if (!Float.isNaN(loadedX) && !Float.isNaN(loadedY) && !Float.isNaN(loadedZ)) {
            camera.setPosition(loadedX, loadedY, loadedZ);
            if (!Float.isNaN(loadedYaw) && !Float.isNaN(loadedPitch)) {
                camera.setLook(loadedYaw, loadedPitch);
            }
            System.out.printf("[Player] Restored position (%.1f, %.1f, %.1f)%n", loadedX, loadedY, loadedZ);
        }
    }

    private void applyWorld(int idx) {
        if (worlds.isEmpty()) return;
        savePlayerState();
        worldIndex = (idx % worlds.size() + worlds.size()) % worlds.size();
        WorldProfile w = worlds.get(worldIndex);
        renderer.setWorld(w.id(), w.seed());
        camera = new FreeCamera();
        hostileTick = 0f;
        lastGroundY = -999f;
        tempStructures.clear();
        lastWatcherEventSeen = "NONE";
        worldPlayTime         = 0f;  // M152: reset horror progression on world change
        idleTimer             = 0f;
        idleScared            = false;
        thickFogMsgTimer      = 0f;
        lastEscalateMilestone = 0;
        taunt85Fired = false; taunt50Fired = false; taunt30Fired = false; // M211
        // M225/M229: exit liminal zone if active
        inLiminalZone = false;
        currentZone = 0;
        noctfield.world.ChunkGenerator.liminalMode = false;
        noctfield.world.ChunkGenerator.liminalZoneId = 0;
        renderer.setLiminalZoneMode(false);
        renderer.setLiminalZoneId(0);
        hideSeekFired = false; hideSeekActive = false; hideSeekSpawned = false;
        hideSeekCaught = false; hideSeekSpawnTimer = 0f; hideSeekPatrolTimer = 0f;
        hideSeekTriggerTimer = -1f; // M215/M218
        thingDragActive = false;
        escapeActive          = false;
        escapeTimer           = 0f;
        endingScreenActive    = false;
        endingScreenTimer     = 0f;
        relicGoalMsgShown     = false;
        undergroundRelicsFound = 0; // M179
        horrorEventMsg     = "";
        loadPlayerState();
        introTimer = (relicsFound == 0) ? 24f : 0f; // M180: 24s cutscene intro
        // M228: builder mode — noclip, no intro, all block types in hotbar
        if (BUILDER_MODE) {
            camera.setNoclip(true);
            introTimer = 0f;
            byte[] bh = { BlockId.STONE, BlockId.DIRT, BlockId.GRASS, BlockId.WOOD,
                          BlockId.WOOD_PLANK, BlockId.VOIDSTONE, BlockId.LANTERN,
                          BlockId.TORCH_STAND, BlockId.CRYSTAL };
            for (int i = 0; i < bh.length && i < slotItem.length; i++) {
                slotItem[i] = bh[i]; slotCount[i] = 99;
            }
        }
        System.out.println("Switched world -> " + w.id() + " (seed " + w.seed() + ")");
    }

    private void openWorldMenu() {
        worldMenuOpen = true;
        worldMenuIndex = worldIndex;
        glfwSetInputMode(window, GLFW_CURSOR, GLFW_CURSOR_NORMAL);
        if (!worlds.isEmpty()) {
            WorldProfile w = worlds.get(worldMenuIndex);
            System.out.println("[WorldMenu] OPEN -> " + w.id() + " (seed " + w.seed() + ")");
        } else {
            System.out.println("[WorldMenu] OPEN (no profiles)");
        }
    }

    private void closeWorldMenu(boolean applySelection) {
        renamingWorld = false;
        if (applySelection && !worlds.isEmpty()) {
            WorldProfile w = worlds.get(worldMenuIndex);
            System.out.println("[WorldMenu] APPLY -> " + w.id() + " (seed " + w.seed() + ")");
            applyWorld(worldMenuIndex);
        } else {
            System.out.println("[WorldMenu] CLOSE");
        }
        worldMenuOpen = false;
        // If we came from the title screen, dismiss it now so the game loop
        // exits the titleScreenOpen branch and actually runs the game.
        if (titleScreenOpen) closeTitleScreen();
        glfwSetInputMode(window, GLFW_CURSOR, GLFW_CURSOR_DISABLED);
        input.recenterMouse();
    }

    private void openOptionsMenu() {
        optionsMenuOpen = true;
        optionsMenuIndex = 0;
        glfwSetInputMode(window, GLFW_CURSOR, GLFW_CURSOR_NORMAL);
        System.out.println("[Options] OPEN");
    }

    private void closeOptionsMenu() {
        optionsMenuOpen = false;
        glfwSetInputMode(window, GLFW_CURSOR, GLFW_CURSOR_DISABLED);
        input.recenterMouse();
        System.out.println("[Options] CLOSE");
    }

    // M236: toggle fullscreen / windowed
    private void toggleFullscreen() {
        fullscreen = !fullscreen;
        long monitor = glfwGetPrimaryMonitor();
        if (fullscreen) {
            int[] wx = new int[1], wy = new int[1];
            glfwGetWindowPos(window, wx, wy);
            windowedX = wx[0]; windowedY = wy[0];
            windowedW = width;  windowedH = height;
            GLFWVidMode vm = glfwGetVideoMode(monitor);
            glfwSetWindowMonitor(window, monitor, 0, 0, vm.width(), vm.height(), vm.refreshRate());
        } else {
            glfwSetWindowMonitor(window, NULL, windowedX, windowedY, windowedW, windowedH, GLFW_DONT_CARE);
        }
    }

    private void enterZone(int zoneId) {
        if (zoneId == currentZone) return;
        if (currentZone == 0) { // leaving overworld — save position
            overworldReturnX = camera.position().x;
            overworldReturnZ = camera.position().z;
        }
        int prevZone   = currentZone;
        currentZone    = zoneId;
        inLiminalZone  = (zoneId != 0);
        noctfield.world.ChunkGenerator.liminalZoneId = zoneId;
        noctfield.world.ChunkGenerator.liminalMode   = (zoneId != 0); // legacy compat
        renderer.setLiminalZoneId(zoneId);
        renderer.clearChunksForZoneSwitch();
        liminalPortalCooldown = 2.5f;
        zoneLoading   = true;
        zoneLoadTimer = 0f;
        lastGroundY   = -999f; // M234: reset fall-damage tracker — prevents carry-over from prev zone

        // Position player at zone entry point
        if (zoneId == 1) {
            camera.setPosition(55f, 6f, 55f);   // meadow spawn — clear of return portal
        } else if (zoneId == 2) {
            camera.setPosition(0f, 2f, -6f);    // mansion foyer spawn
        } else {
            // Return to overworld
            camera.setPosition(overworldReturnX, 80f, overworldReturnZ);
        }
        System.out.println("[Zone] " + prevZone + " -> " + zoneId);
    }

    private void enterLiminalZone() { enterZone(1); } // legacy compat
    private void exitLiminalZone()  { enterZone(0); } // legacy compat

    private void openTitleScreen() {
        titleScreenOpen = true;
        titleMenuIndex  = 0;
        pauseMenuOpen   = false;
        worldMenuOpen   = false;
        renderer.setPaused(true);
    }

    private void closeTitleScreen() {
        titleScreenOpen = false;
        renderer.setPaused(false);
    }

    private void openPauseMenu() {
        pauseMenuOpen = true;
        pauseMenuIndex = 0;
        glfwSetInputMode(window, GLFW_CURSOR, GLFW_CURSOR_NORMAL);
    }

    private void closePauseMenu() {
        pauseMenuOpen = false;
        glfwSetInputMode(window, GLFW_CURSOR, GLFW_CURSOR_DISABLED);
        input.recenterMouse();
    }

    private void openControlsMenu() {
        controlsMenuOpen = true;
    }

    private void closeControlsMenu() {
        controlsMenuOpen = false;
    }

    private void openInventory() {
        inventoryOpen = true;
        craftingTableOpen = false;
        leftDragDistribute = false;
        for (int i = 0; i < INV_SLOTS; i++) dragVisited[i] = false;
        for (int i = 0; i < craftItem.length; i++) { craftItem[i] = BlockId.AIR; craftCount[i] = 0; }
        craftOutItem = BlockId.AIR; craftOutCount = 0; craftOutWardSeconds = 0f;
        glfwSetInputMode(window, GLFW_CURSOR, GLFW_CURSOR_NORMAL);
    }

    private void openCraftingTableUI() {
        inventoryOpen = true;
        craftingTableOpen = true;
        leftDragDistribute = false;
        for (int i = 0; i < INV_SLOTS; i++) dragVisited[i] = false;
        for (int i = 0; i < craftItem.length; i++) { craftItem[i] = BlockId.AIR; craftCount[i] = 0; }
        craftOutItem = BlockId.AIR; craftOutCount = 0; craftOutWardSeconds = 0f;
        glfwSetInputMode(window, GLFW_CURSOR, GLFW_CURSOR_NORMAL);
    }

    private void closeInventory() {
        inventoryOpen = false;
        craftingTableOpen = false;
        leftDragDistribute = false;
        for (int i = 0; i < INV_SLOTS; i++) dragVisited[i] = false;
        // return crafting grid + hand back to inventory on close
        for (int i = 0; i < craftItem.length; i++) {
            if (craftCount[i] > 0) addInv(craftItem[i], craftCount[i]);
            craftItem[i] = BlockId.AIR; craftCount[i] = 0;
        }
        if (heldCount > 0) addInv(heldItem, heldCount);
        heldItem = BlockId.AIR; heldCount = 0;
        craftOutItem = BlockId.AIR; craftOutCount = 0; craftOutWardSeconds = 0f;
        glfwSetInputMode(window, GLFW_CURSOR, GLFW_CURSOR_DISABLED);
        input.recenterMouse();
    }

    /** Normalize the craft grid into a 2D array and find the matching shaped recipe. */
    private Recipe findMatchingRecipe() {
        int gridCols = craftingTableOpen ? 3 : 2;
        int gridRows = craftingTableOpen ? 3 : 2;

        // Build 2D grid: row 0 = visual top, col 0 = visual left
        byte[][] grid = new byte[gridRows][gridCols];
        for (int i = 0; i < gridCols * gridRows; i++) {
            int row = i / gridCols, col = i % gridCols;
            grid[row][col] = (craftCount[i] > 0) ? craftItem[i] : BlockId.AIR;
        }

        // Crop to bounding box of filled cells
        int minR = gridRows, maxR = -1, minC = gridCols, maxC = -1;
        for (int r = 0; r < gridRows; r++) {
            for (int c = 0; c < gridCols; c++) {
                if (grid[r][c] != BlockId.AIR) {
                    minR = Math.min(minR, r); maxR = Math.max(maxR, r);
                    minC = Math.min(minC, c); maxC = Math.max(maxC, c);
                }
            }
        }
        if (maxR < 0) return null; // all empty

        int pRows = maxR - minR + 1;
        int pCols = maxC - minC + 1;

        for (Recipe r : RECIPES) {
            if (relicsFound < r.unlockRelics) continue;
            if (r.needsTable && !craftingTableOpen) continue;
            int rRows = r.shape.length;
            int rCols = r.shape[0].length;
            if (pRows != rRows || pCols != rCols) continue;
            boolean ok = true;
            outer:
            for (int rr = 0; rr < rRows; rr++) {
                for (int rc = 0; rc < rCols; rc++) {
                    if (grid[minR + rr][minC + rc] != r.shape[rr][rc]) { ok = false; break outer; }
                }
            }
            if (ok) return r;
        }
        return null;
    }

    // Shared layout constants for inventory + crafting UI
    private static final int UI_SZ   = 40;  // slot size
    private static final int UI_GAP  = 4;   // slot gap
    private static final int UI_MARG = 8;   // panel inner margin
    private static final int UI_HDR  = 18;  // header strip height
    private static final int UI_HBPAD = 6;  // hotbar bottom padding

    /** Bottom inventory panel: width, height, and X anchor. */
    private int invPanelW() { return 9 * UI_SZ + 8 * UI_GAP + 2 * UI_MARG; }
    private int invPanelH() { return UI_HBPAD + UI_SZ + UI_GAP + 2*(UI_SZ+UI_GAP) - UI_GAP + UI_MARG + UI_HDR; } // M171
    private int invPanelX() { return (width - invPanelW()) / 2; }
    private int invPanelY() { return Math.max(22, (height - invPanelH()) / 2 - 20); }

    /** Crafting panel: dimensions and position. */
    private int craftCols() { return craftingTableOpen ? 3 : 2; }
    private int craftRows() { return craftingTableOpen ? 3 : 2; }
    private int craftGridW() { return craftCols()*UI_SZ + (craftCols()-1)*UI_GAP; }
    private int craftGridH() { return craftRows()*UI_SZ + (craftRows()-1)*UI_GAP; }
    private int craftOutSz() { return craftingTableOpen ? 44 : 38; }
    private int craftPanelW() { return UI_MARG + craftGridW() + 14 + 20 + 10 + craftOutSz() + UI_MARG; }
    private int craftPanelH() { return UI_HDR + UI_MARG + craftGridH() + UI_MARG; }
    private int craftPanelX() { return invPanelX() + invPanelW() + 18; }
    private int craftPanelY() { return invPanelY() + Math.max(0, (invPanelH() - craftPanelH()) / 2); }
    /** Y of the top row of the craft grid (highest Y = topmost in Y-up coords). */
    private int craftCgY() { return craftPanelY() + craftPanelH() - UI_HDR - UI_MARG - UI_SZ; }

    private int slotAtMouse() {
        int slotBaseX = invPanelX() + UI_MARG;
        int panelY    = invPanelY();
        int hbY       = panelY + UI_HBPAD;
        int invBaseY  = hbY + UI_SZ + UI_GAP;

        float mx = (float) input.mouseX();
        float my = (float) (height - input.mouseY());

        // Hotbar: slots 0..8
        for (int i = 0; i < 9; i++) {
            int rx = slotBaseX + i * (UI_SZ + UI_GAP);
            if (mx >= rx && mx < rx + UI_SZ && my >= hbY && my < hbY + UI_SZ) return i;
        }
        // Backpack: slots 9..26, 9 cols x 2 rows
        for (int i = 0; i < 18; i++) {
            int col = i % 9, row = i / 9;
            int rx = slotBaseX + col * (UI_SZ + UI_GAP);
            int ry = invBaseY  + row * (UI_SZ + UI_GAP);
            if (mx >= rx && mx < rx + UI_SZ && my >= ry && my < ry + UI_SZ) return 9 + i;
        }
        return -1;
    }

    private int craftGridSlotAtMouse() {
        int cgX = craftPanelX() + UI_MARG;
        int cgY = craftCgY();
        int cols = craftCols(), rows = craftRows();
        float mx = (float) input.mouseX();
        float my = (float) (height - input.mouseY());
        for (int i = 0; i < cols * rows; i++) {
            int cx = i % cols, cr = i / cols;
            int rx = cgX + cx * (UI_SZ + UI_GAP);
            int ry = cgY - cr * (UI_SZ + UI_GAP);
            if (mx >= rx && mx < rx + UI_SZ && my >= ry && my < ry + UI_SZ) return i;
        }
        return -1;
    }

    private boolean craftOutputAtMouse() {
        int cgX     = craftPanelX() + UI_MARG;
        int cgY     = craftCgY();
        int cols    = craftCols(), rows = craftRows();
        int gridW   = craftGridW();
        int outSz   = craftOutSz();
        int outX    = cgX + gridW + 34;  // grid(10)→arrow(24) = +34, matches render
        int gridBotY = cgY - (rows-1) * (UI_SZ + UI_GAP);
        int gridTopY = cgY + UI_SZ;
        int outY    = (gridBotY + gridTopY) / 2 - outSz / 2;
        float mx = (float) input.mouseX();
        float my = (float) (height - input.mouseY());
        return mx >= outX && mx < outX + outSz && my >= outY && my < outY + outSz;
    }

    private void computeCraftOutput() {
        craftOutItem = BlockId.AIR;
        craftOutCount = 0;
        craftOutWardSeconds = 0f;
        Recipe r = findMatchingRecipe();
        if (r == null) return;
        if (r.kind == CraftKind.ITEM) { craftOutItem = r.outItem; craftOutCount = r.outCount; }
        else { craftOutWardSeconds = r.wardSeconds; }
    }

    private boolean consumeCraftGridForOutput() {
        computeCraftOutput();
        if (craftOutItem == BlockId.AIR && craftOutWardSeconds <= 0f) return false;
        // Consume exactly 1 from each filled craft slot.
        int craftSlots = craftingTableOpen ? 9 : 4;
        for (int i = 0; i < craftSlots; i++) {
            if (craftCount[i] > 0) {
                craftCount[i]--;
                if (craftCount[i] <= 0) { craftCount[i] = 0; craftItem[i] = BlockId.AIR; }
            }
        }
        if (craftOutItem != BlockId.AIR) addInv(craftOutItem, craftOutCount);
        else wardTimer = Math.min(180f, wardTimer + craftOutWardSeconds);
        computeCraftOutput();
        return true;
    }

    private boolean inNoBuildZone(int x, int z) {
        float dx = x;
        float dz = z;
        return dx * dx + dz * dz <= NO_BUILD_CENTER_RADIUS * NO_BUILD_CENTER_RADIUS;
    }

    private float tempLifetimeSeconds() {
        return switch (currentBiome) {
            case noctfield.world.ChunkGenerator.BIOME_SWAMP -> 48f;
            case noctfield.world.ChunkGenerator.BIOME_DEAD -> 72f;
            default -> 95f;
        };
    }

    private float placeCooldownSeconds() {
        return switch (currentBiome) {
            case noctfield.world.ChunkGenerator.BIOME_SWAMP -> 0.16f;
            case noctfield.world.ChunkGenerator.BIOME_DEAD -> 0.12f;
            default -> 0.09f;
        };
    }

    /** M172: Returns a journal message for a block at (x,z), with special messages for secret areas. */
    private String secretJournalMessage(int x, int z) {
        // Near world origin (Void Gate area)
        if (Math.abs(x) <= 4 && Math.abs(z) <= 4) {
            return "THIS IS WHERE IT ENDS. OR BEGINS. I CANNOT TELL ANYMORE.";
        }
        // Near 666 chamber
        if (Math.abs(x - 666) <= 4 && Math.abs(z - 666) <= 4) {
            return "YOU WERE NOT SUPPOSED TO FIND THIS. MOST NEVER DO.";
        }
        // M189: near a relic position — return relic-specific lore
        long ws = renderer.worldSeed();
        if (ws != 0) {
            int[][] relicPos = noctfield.world.ChunkGenerator.getRelicPositions(ws);
            for (int i = 0; i < 3 && i < relicPos.length; i++) {
                if (relicPos[i] != null && Math.abs(x - relicPos[i][0]) <= 8 && Math.abs(z - relicPos[i][2]) <= 8) {
                    return RELIC_JOURNAL_LINES[i % RELIC_JOURNAL_LINES.length];
                }
            }
        }
        // Normal deterministic selection
        int seed = x * 374761393 ^ z * 668265263;
        return JOURNAL_LINES[Math.abs(seed) % JOURNAL_LINES.length];
    }
    private void resetMiningProgress() {
        miningTargetX = Integer.MIN_VALUE;
        miningTargetY = Integer.MIN_VALUE;
        miningTargetZ = Integer.MIN_VALUE;
        miningProgress = 0f;
        miningTickTimer = 0f;
        if (renderer != null) renderer.setMiningCrack(false, 0, 0, 0, 0f);
    }

    private float breakDurationSeconds(byte blockId) {
        return switch (blockId) {
            case BlockId.LEAVES, BlockId.COBWEB, BlockId.BLOODSTAIN -> 0.22f;
            case BlockId.GRASS, BlockId.DIRT, BlockId.MUD, BlockId.FUNGUS -> 0.32f;
            case BlockId.WOOD, BlockId.BONES, BlockId.CAMPFIRE -> 0.48f;
            case BlockId.LANTERN, BlockId.CRYSTAL -> 0.55f;
            case BlockId.STONE -> 0.85f;
            case BlockId.COAL  -> 0.75f; // M149: slightly faster than stone, still pick-preferred
            default -> 0.40f;
        };
    }

    private enum ToolTier { HAND, WOOD, STONE, CRYSTAL }

    private int toolMaxDurability(byte tool) {
        return switch (tool) {
            case BlockId.TOOL_WOOD_PICK -> 90;
            case BlockId.TOOL_STONE_PICK -> 180;
            case BlockId.TOOL_CRYSTAL_PICK -> 360;
            case BlockId.TOOL_WOOD_AXE -> 90;
            default -> 0;
        };
    }

    private ToolTier toolTierOf(byte tool) {
        return switch (tool) {
            case BlockId.TOOL_WOOD_PICK, BlockId.TOOL_WOOD_AXE -> ToolTier.WOOD;
            case BlockId.TOOL_STONE_PICK -> ToolTier.STONE;
            case BlockId.TOOL_CRYSTAL_PICK -> ToolTier.CRYSTAL;
            default -> ToolTier.HAND;
        };
    }

    private boolean isAxeTool(byte tool) {
        return tool == BlockId.TOOL_WOOD_AXE;
    }
    private boolean isPickTool(byte tool) {
        return tool == BlockId.TOOL_WOOD_PICK || tool == BlockId.TOOL_STONE_PICK || tool == BlockId.TOOL_CRYSTAL_PICK;
    }

    private ToolTier currentToolTier() {
        return toolTierOf(equippedTool);
    }

    private int applyArmor(int rawDmg) {
        float r = BlockId.armorDamageReduce(equippedArmor);
        return Math.max(1, Math.round(rawDmg * (1f - r)));
    }

    private float miningSpeedMultiplier(ToolTier tool, byte target) {
        boolean axe  = isAxeTool(equippedTool);
        boolean pick = isPickTool(equippedTool);

        // Stone / Coal / Crystal / Voidstone: picks excel; axe and bare hand are wrong tool (very slow).
        if (target == BlockId.STONE || target == BlockId.COAL || target == BlockId.CRYSTAL || target == BlockId.VOIDSTONE) {
            if (axe)  return 0.40f; // wrong tool
            return switch (tool) {
                case HAND    -> 0.33f; // 3x slower than a wooden pick
                case WOOD    -> 1.60f; // M158: boosted — wood pick feels useful on stone (~0.53s)
                case STONE   -> 2.20f;
                case CRYSTAL -> 3.00f;
            };
        }
        // Wood / Leaves / Campfire / Bones: axe is the right tool.
        if (target == BlockId.WOOD || target == BlockId.LEAVES || target == BlockId.CAMPFIRE || target == BlockId.BONES) {
            if (axe)  return 2.80f; // axe excels here
            if (pick) return switch (tool) {
                case WOOD    -> 1.10f;
                case STONE   -> 1.05f;
                case CRYSTAL -> 1.10f;
                default      -> 1.0f;
            };
            return 1.0f; // bare hand baseline
        }
        // Everything else
        return switch (tool) {
            case HAND    -> 1.0f;
            case WOOD    -> 1.05f;
            case STONE   -> 1.10f;
            case CRYSTAL -> 1.18f;
        };
    }

    private boolean canHarvest(byte block, ToolTier tier) {
        // Stone is breakable by hand but 3x slower — no hard gate.
        if (block == BlockId.CRYSTAL) return tier == ToolTier.STONE || tier == ToolTier.CRYSTAL;
        if (block == BlockId.VOIDSTONE) return false;
        return true;
    }

    private void equipTool(byte tool, int durability, int maxDurability) {
        equippedTool = tool;
        toolDurability = durability;
        toolDurabilityMax = maxDurability;
        equipAnim = 0f;
    }

    private void tryAutoEquipBestTool() {
        byte best = BlockId.AIR;
        int bestTier = -1;
        int bestSlot = -1;
        for (int i = 0; i < INV_SLOTS; i++) {
            if (slotCount[i] <= 0) continue;
            byte id = slotItem[i];
            int tier = switch (id) {
                case BlockId.TOOL_WOOD_AXE    -> 1;
                case BlockId.TOOL_WOOD_PICK   -> 2;
                case BlockId.TOOL_STONE_PICK  -> 3;
                case BlockId.TOOL_CRYSTAL_PICK -> 4;
                default -> 0;
            };
            if (tier > bestTier) { bestTier = tier; best = id; bestSlot = i; }
        }
        if (bestTier <= 0) return;
        slotCount[bestSlot]--;
        if (slotCount[bestSlot] <= 0) { slotCount[bestSlot] = 0; slotItem[bestSlot] = BlockId.AIR; }
        equipTool(best, toolMaxDurability(best), toolMaxDurability(best));
    }

    private void init() {
        GLFWErrorCallback.createPrint(System.err).set();
        if (!glfwInit()) throw new IllegalStateException("Failed to init GLFW");

        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3);
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
        glfwWindowHint(GLFW_OPENGL_FORWARD_COMPAT, GLFW_TRUE);

        window = glfwCreateWindow(width, height, "Hollow", NULL, NULL);
        if (window == NULL) throw new IllegalStateException("Failed to create window");

        glfwMakeContextCurrent(window);
        glfwSwapInterval(1);
        GL.createCapabilities();

        input = new InputState(window);
        renderer = new Renderer();
        renderer.setPaused(true); // M226: start paused — no world loaded yet
        debugHud = new DebugHud();

        loadWorldProfiles();
        // M226: Always start at title screen — world loaded on user selection
        loadOptions();

        glEnable(GL_DEPTH_TEST);
        glClearColor(0.03f, 0.04f, 0.06f, 1f);

        glfwSetFramebufferSizeCallback(window, (w, fbw, fbh) -> {
            width = Math.max(1, fbw);
            height = Math.max(1, fbh);
            glViewport(0, 0, width, height);
        });

        glfwSetInputMode(window, GLFW_CURSOR, GLFW_CURSOR_DISABLED);

        System.out.println("Controls: WASD move, mouse look, Space jump, Shift sprint, F5 noclip toggle");
        System.out.println("Interactions: LMB break, RMB place/open/use, 1-9 hotbar (tools auto-equip), I inventory, F2 save");
        System.out.println("Worlds: F12/TAB world menu (W/S + Enter/E), F4 quick-next profile");
        System.out.println("Debug: N toggle night | F6 sanity 0 | F7 sanity 33 | F8 sanity 66 | F9 sanity 100");
        System.out.println("Overlay: F3 info | Pause: ESC");
        System.out.println("Caves: Z/X threshold -, + | C/V minY -, + | B/M maxY -, + (auto rebuild)");
        System.out.println("Memory: L low-memory toggle | [ / ] max loaded chunks -, +");

        // M33: procedural audio
        audio = new noctfield.audio.AudioSystem();
        audio.init();
    }

    private void loop() {
        double last = glfwGetTime();

        while (!glfwWindowShouldClose(window)) {
            double now = glfwGetTime();
            float dt = (float) Math.min(0.05, now - last);
            last = now;

            glfwPollEvents();
            input.beginFrame();

            handBobTime += dt * (input.isMouseDown(GLFW_MOUSE_BUTTON_LEFT) ? 7.5f : 4.0f);
            handSwing = Math.max(0f, handSwing - dt * 5.5f);
            equipAnim = Math.min(1f, equipAnim + dt * 4.0f);
            updateHitParticles(dt);

            // M62: Title screen - handles its own input/render then skips the game loop
            if (titleScreenOpen) {
                if (worldMenuOpen) {
                    // World select is layered on top of the title screen
                    if (input.wasPressed(GLFW_KEY_UP)   || input.wasPressed(GLFW_KEY_W)) {
                        worldMenuIndex = (worldMenuIndex - 1 + Math.max(1, worlds.size())) % Math.max(1, worlds.size());
                    }
                    if (input.wasPressed(GLFW_KEY_DOWN) || input.wasPressed(GLFW_KEY_S)) {
                        worldMenuIndex = (worldMenuIndex + 1) % Math.max(1, worlds.size());
                    }
                    if (renamingWorld) {
                        renameBuffer.append(input.consumeTyped());
                        if (input.wasPressed(GLFW_KEY_BACKSPACE) && renameBuffer.length() > 0)
                            renameBuffer.deleteCharAt(renameBuffer.length() - 1);
                        if (input.wasPressed(GLFW_KEY_ENTER) && renameBuffer.length() > 0) {
                            String newName = renameBuffer.toString().trim().replaceAll("[^a-zA-Z0-9_\\-]", "");
                            if (!newName.isEmpty()) {
                                worlds.set(worldMenuIndex, new WorldProfile(newName, worlds.get(worldMenuIndex).seed()));
                                saveWorldProfiles();
                            }
                            renamingWorld = false;
                        }
                        if (input.wasPressed(GLFW_KEY_ESCAPE)) renamingWorld = false;
                    } else {
                        // closeWorldMenu(true) handles: applyWorld + clear flags + closeTitleScreen + cursor lock
                        if ((input.wasPressed(GLFW_KEY_ENTER) || input.wasPressed(GLFW_KEY_E)) && !worlds.isEmpty())
                            closeWorldMenu(true);
                        if (input.wasPressed(GLFW_KEY_N)) createNewWorld();
                        if (input.wasPressed(GLFW_KEY_D) && !worlds.isEmpty()) deleteSelectedWorld(); // M226: allow deleting last world
                        if (input.wasPressed(GLFW_KEY_R) && !worlds.isEmpty()) {
                            renamingWorld = true;
                            renameBuffer.setLength(0);
                            renameBuffer.append(worlds.get(worldMenuIndex).id());
                        }
                        if (input.wasPressed(GLFW_KEY_ESCAPE)) worldMenuOpen = false; // back to title
                    }

                    renderer.render(camera, width, height);
                    renderWorldMenuOverlay();
                } else {
                    // Title screen proper — 2 items: PLAY and QUIT
                    if (input.wasPressed(GLFW_KEY_UP)   || input.wasPressed(GLFW_KEY_W))
                        titleMenuIndex = (titleMenuIndex + 1) % 2;
                    if (input.wasPressed(GLFW_KEY_DOWN)  || input.wasPressed(GLFW_KEY_S))
                        titleMenuIndex = (titleMenuIndex + 1) % 2;
                    if (input.wasPressed(GLFW_KEY_ENTER) || input.wasPressed(GLFW_KEY_E) || input.wasPressed(GLFW_KEY_SPACE)) {
                        switch (titleMenuIndex) {
                            case 0 -> { worldMenuOpen = true; worldMenuIndex = 0;
                                        glfwSetInputMode(window, GLFW_CURSOR, GLFW_CURSOR_NORMAL); }
                            case 1 -> glfwSetWindowShouldClose(window, true);
                        }
                    }
                    if (input.wasPressed(GLFW_KEY_ESCAPE)) glfwSetWindowShouldClose(window, true);

                    renderer.render(camera, width, height);
                    renderTitleScreen((float) glfwGetTime());
                }
                glfwSwapBuffers(window);
                input.endFrame();
                continue;
            }

            if (input.wasPressed(GLFW_KEY_F12)) {
                if (worldMenuOpen) closeWorldMenu(false);
                else if (!optionsMenuOpen && !pauseMenuOpen) openWorldMenu();
            }

            // Debug: F5 = test voice channel (plays taunt_there immediately)
            if (input.wasPressed(GLFW_KEY_F5))  { audio.playTaunt(1); System.out.println("[Debug] F5: playTaunt(taunt_there)"); }

            // Debug: F6-F9 = set sanity (always active during gameplay, any menu state)
            if (input.wasPressed(GLFW_KEY_F6))  { sanity = 0f;   System.out.println("[Debug] Sanity -> 0"); }
            if (input.wasPressed(GLFW_KEY_F7))  { sanity = 33f;  System.out.println("[Debug] Sanity -> 33"); }
            if (input.wasPressed(GLFW_KEY_F8))  { sanity = 66f;  System.out.println("[Debug] Sanity -> 66"); }
            if (input.wasPressed(GLFW_KEY_F9))  { sanity = 100f; System.out.println("[Debug] Sanity -> 100"); }
            // M236: F11 = fullscreen toggle; Ctrl+F11 = debug spawn NUN
            if (input.wasPressed(GLFW_KEY_F11)) {
                if (input.isDown(GLFW_KEY_LEFT_CONTROL) || input.isDown(GLFW_KEY_RIGHT_CONTROL))
                    renderer.debugSpawnNun(camera.position(), camera.forward());
                else
                    toggleFullscreen();
            }

            // M227: H key (paste mode) handled here; G key handled after raycast
            if (input.wasPressed(GLFW_KEY_H)) handleStructureHKey();
            if (input.wasPressed(GLFW_KEY_F10) && structCaptureStep == 2 && !namingStruct) {
                namingStruct = true;
                structNameBuffer.setLength(0);
                structNameBuffer.append("struct-" + (savedStructNames.size() + 1));
            }
            if (namingStruct) {
                structNameBuffer.append(input.consumeTyped());
                if (input.wasPressed(GLFW_KEY_BACKSPACE) && structNameBuffer.length() > 0)
                    structNameBuffer.deleteCharAt(structNameBuffer.length() - 1);
                if (input.wasPressed(GLFW_KEY_ENTER) && structNameBuffer.length() > 0) {
                    String sname = structNameBuffer.toString().trim().replaceAll("[^a-zA-Z0-9_\\-]", "");
                    if (!sname.isEmpty()) saveStruct(sname);
                    namingStruct = false; structCaptureMode = false; structCaptureStep = 0;
                    structA = null; structB = null;
                }
                if (input.wasPressed(GLFW_KEY_ESCAPE)) { namingStruct = false; }
            }

            if (input.wasPressed(GLFW_KEY_ESCAPE)) {
                if (namingStruct) {
                    namingStruct = false;
                } else if (structCaptureMode) {
                    structCaptureMode = false; structCaptureStep = 0; structA = null; structB = null;
                } else if (structPasteMode) {
                    structPasteMode = false;
                } else if (worldMenuOpen) {
                    closeWorldMenu(false);
                } else if (optionsMenuOpen) {
                    closeOptionsMenu();
                } else if (controlsMenuOpen) {
                    closeControlsMenu();
                } else if (recipeBookOpen) {
                    recipeBookOpen = false;
                    glfwSetInputMode(window, GLFW_CURSOR, GLFW_CURSOR_DISABLED);
                } else if (inventoryOpen) {
                    closeInventory();
                } else if (pauseMenuOpen) {
                    closePauseMenu();
                } else {
                    openPauseMenu();
                }
            }

            if (input.wasPressed(GLFW_KEY_I) && !worldMenuOpen && !optionsMenuOpen && !pauseMenuOpen) {
                if (inventoryOpen) closeInventory(); else openInventory();
            }
            // M188: R key = recipe book (toggle; closes inventory if open)
            if (input.wasPressed(GLFW_KEY_R) && !worldMenuOpen && !optionsMenuOpen && !pauseMenuOpen) {
                recipeBookOpen = !recipeBookOpen;
                if (recipeBookOpen && inventoryOpen) closeInventory();
                if (recipeBookOpen) glfwSetInputMode(window, GLFW_CURSOR, GLFW_CURSOR_NORMAL);
                else               glfwSetInputMode(window, GLFW_CURSOR, GLFW_CURSOR_DISABLED);
            }

            renderer.setPaused(worldMenuOpen || optionsMenuOpen || pauseMenuOpen || controlsMenuOpen || deathScreenActive);

            if (worldMenuOpen) {
                if ((input.wasPressed(GLFW_KEY_UP) || input.wasPressed(GLFW_KEY_W)) && !worlds.isEmpty()) {
                    worldMenuIndex = (worldMenuIndex - 1 + worlds.size()) % worlds.size();
                    WorldProfile w = worlds.get(worldMenuIndex);
                    System.out.println("[WorldMenu] SELECT -> " + w.id() + " (seed " + w.seed() + ")");
                }
                if ((input.wasPressed(GLFW_KEY_DOWN) || input.wasPressed(GLFW_KEY_S)) && !worlds.isEmpty()) {
                    worldMenuIndex = (worldMenuIndex + 1) % worlds.size();
                    WorldProfile w = worlds.get(worldMenuIndex);
                    System.out.println("[WorldMenu] SELECT -> " + w.id() + " (seed " + w.seed() + ")");
                }
                if (renamingWorld) {
                    renameBuffer.append(input.consumeTyped());
                    if (input.wasPressed(GLFW_KEY_BACKSPACE) && renameBuffer.length() > 0)
                        renameBuffer.deleteCharAt(renameBuffer.length() - 1);
                    if (input.wasPressed(GLFW_KEY_ENTER) && renameBuffer.length() > 0) {
                        String newName = renameBuffer.toString().trim().replaceAll("[^a-zA-Z0-9_\\-]", "");
                        if (!newName.isEmpty()) {
                            worlds.set(worldMenuIndex, new WorldProfile(newName, worlds.get(worldMenuIndex).seed()));
                            saveWorldProfiles();
                        }
                        renamingWorld = false;
                    }
                    if (input.wasPressed(GLFW_KEY_ESCAPE)) renamingWorld = false;
                } else {
                    if (input.wasPressed(GLFW_KEY_ENTER) || input.wasPressed(GLFW_KEY_E)) closeWorldMenu(true);
                    if (input.wasPressed(GLFW_KEY_N)) createNewWorld();
                    if (input.wasPressed(GLFW_KEY_D) && !worlds.isEmpty()) deleteSelectedWorld(); // M226
                    if (input.wasPressed(GLFW_KEY_R) && !worlds.isEmpty()) {
                        renamingWorld = true;
                        renameBuffer.setLength(0);
                        renameBuffer.append(worlds.get(worldMenuIndex).id());
                    }
                }
            } else if (controlsMenuOpen) {
                if (input.wasPressed(GLFW_KEY_ENTER) || input.wasPressed(GLFW_KEY_E))
                    closeControlsMenu();
            } else if (pauseMenuOpen) {
                if (input.wasPressed(GLFW_KEY_UP) || input.wasPressed(GLFW_KEY_W)) pauseMenuIndex = (pauseMenuIndex + 5) % 6;
                if (input.wasPressed(GLFW_KEY_DOWN) || input.wasPressed(GLFW_KEY_S)) pauseMenuIndex = (pauseMenuIndex + 1) % 6;
                if (input.wasPressed(GLFW_KEY_ENTER) || input.wasPressed(GLFW_KEY_E)) {
                    switch (pauseMenuIndex) {
                        case 0 -> closePauseMenu();
                        case 1 -> { pauseMenuOpen = false; openControlsMenu(); }
                        case 2 -> { pauseMenuOpen = false; openOptionsMenu(); }
                        case 3 -> { pauseMenuOpen = false; openWorldMenu(); } // M187: NEW WORLD / SWITCH WORLD
                        case 4 -> openTitleScreen(); // EXIT TO TITLE
                        case 5 -> glfwSetWindowShouldClose(window, true);
                    }
                }
            } else if (optionsMenuOpen) {
                if (input.wasPressed(GLFW_KEY_UP) || input.wasPressed(GLFW_KEY_W)) optionsMenuIndex = (optionsMenuIndex + 11) % 12; // M231: 12 items
                if (input.wasPressed(GLFW_KEY_DOWN) || input.wasPressed(GLFW_KEY_S)) optionsMenuIndex = (optionsMenuIndex + 1) % 12;

                int step = 0;
                if (input.wasPressed(GLFW_KEY_LEFT) || input.wasPressed(GLFW_KEY_A)) step = -1;
                if (input.wasPressed(GLFW_KEY_RIGHT) || input.wasPressed(GLFW_KEY_D)) step = +1;
                if (input.wasPressed(GLFW_KEY_ENTER) || input.wasPressed(GLFW_KEY_E)) step = +1;

                if (step != 0) {
                    switch (optionsMenuIndex) {
                        case 0 -> cycleGraphicsPreset(step);
                        case 1 -> renderer.toggleSkyCycleMode();
                        case 2 -> renderer.toggleFogAutoByRenderDistance();
                        case 3 -> renderer.adjustFogMultiplier(-step * 0.10f); // M148: right=farther (less dense)
                        case 4 -> renderer.adjustRadius(step);
                        case 5 -> renderer.adjustMaxLoadedChunks(step * 20);
                        case 6 -> audio.adjustMasterVolume(step * 0.05f); // M193: ±5% per press
                        case 7 -> audio.adjustMusicVolume(step * 0.05f);   // M212
                        case 8 -> camera.setBaseFov(camera.getBaseFov() + step * 5f); // M213
                        case 9  -> renderer.resetSkyDefaults();
                        case 10 -> renderer.setDynamicLighting(!renderer.isDynamicLighting()); // M231
                        case 11 -> {
                            closeOptionsMenu();
                            openPauseMenu();
                        }
                    }
                    saveOptions();
                }
            } else if (inventoryOpen) {
                // Backpack cursor (arrows)
                if (input.wasPressed(GLFW_KEY_UP)) invCursor = (invCursor - INV_COLS + INV_SLOTS) % INV_SLOTS;
                if (input.wasPressed(GLFW_KEY_DOWN)) invCursor = (invCursor + INV_COLS) % INV_SLOTS;
                if (input.wasPressed(GLFW_KEY_LEFT)) invCursor = (invCursor - 1 + INV_SLOTS) % INV_SLOTS;
                if (input.wasPressed(GLFW_KEY_RIGHT)) invCursor = (invCursor + 1) % INV_SLOTS;

                // Recipes panel removed (M70): crafting is 2x2-grid only.

                // Mouse hover targets
                int mouseSlot = slotAtMouse();
                int mouseCraft = craftGridSlotAtMouse();
                if (mouseSlot >= 0) invCursor = mouseSlot;

                // Left click in inventory slots (or SPACE on selected slot)
                boolean leftMouse = input.wasMousePressed(GLFW_MOUSE_BUTTON_LEFT);
                boolean clickedCraftGrid = leftMouse && mouseCraft >= 0;
                boolean clickedCraftOut = leftMouse && craftOutputAtMouse();
                // M126: don't route craft-grid clicks through inventory slot logic.
                boolean leftAction = (leftMouse && !clickedCraftGrid && !clickedCraftOut) || input.wasPressed(GLFW_KEY_SPACE);
                if (leftAction) {
                    int s = (mouseSlot >= 0) ? mouseSlot : invCursor;
                    int pickupSlot = -1; // track which slot we just cleared via pickup
                    if (s >= 0) {
                        boolean shift = input.isDown(GLFW_KEY_LEFT_SHIFT) || input.isDown(GLFW_KEY_RIGHT_SHIFT);
                        if (shift && heldCount == 0 && slotCount[s] > 0) {
                            // Shift-click quick move: hotbar(0-8) <-> backpack(9-26)
                            int start = (s < 9) ? 9 : 0;
                            int end = (s < 9) ? INV_SLOTS : 9;
                            byte id = slotItem[s];
                            int rem = slotCount[s];
                            for (int i = start; i < end && rem > 0; i++) if (slotItem[i] == id && slotCount[i] < STACK_MAX) {
                                int can = Math.min(rem, STACK_MAX - slotCount[i]); slotCount[i] += can; rem -= can;
                            }
                            for (int i = start; i < end && rem > 0; i++) if (slotCount[i] <= 0) {
                                int put = Math.min(rem, STACK_MAX); slotItem[i] = id; slotCount[i] = put; rem -= put;
                            }
                            slotCount[s] = rem;
                            if (slotCount[s] <= 0) { slotCount[s] = 0; slotItem[s] = BlockId.AIR; }
                        } else if (heldCount == 0) {
                            if (slotCount[s] > 0) {
                                heldItem = slotItem[s];
                                heldCount = slotCount[s];
                                slotItem[s] = BlockId.AIR;
                                slotCount[s] = 0;
                                pickupSlot = s; // remember so we can mark dragVisited AFTER the reset below
                            }
                        } else {
                            if (slotCount[s] == 0) {
                                slotItem[s] = heldItem;
                                slotCount[s] = heldCount;
                                heldItem = BlockId.AIR;
                                heldCount = 0;
                            } else if (slotItem[s] == heldItem) {
                                int can = Math.min(STACK_MAX - slotCount[s], heldCount);
                                slotCount[s] += can;
                                heldCount -= can;
                                if (heldCount <= 0) { heldItem = BlockId.AIR; heldCount = 0; }
                            } else {
                                byte ti = slotItem[s]; int tc = slotCount[s];
                                slotItem[s] = heldItem; slotCount[s] = heldCount;
                                heldItem = ti; heldCount = tc;
                            }
                        }
                    }
                    leftDragDistribute = input.isMouseDown(GLFW_MOUSE_BUTTON_LEFT) && heldCount > 0;
                    for (int i = 0; i < INV_SLOTS; i++) dragVisited[i] = false;
                    // Mark pickup source AFTER reset so drag-distribute can't re-deposit into it
                    if (pickupSlot >= 0) dragVisited[pickupSlot] = true;
                }

                // M153: Left-click crafting grid — place FULL stack; right-click places 1.
                if (leftMouse && mouseCraft >= 0) {
                    int s = mouseCraft;
                    if (heldCount == 0) {
                        // Pick up everything from slot
                        if (craftCount[s] > 0) {
                            heldItem = craftItem[s]; heldCount = craftCount[s];
                            craftItem[s] = BlockId.AIR; craftCount[s] = 0;
                        }
                    } else {
                        if (craftCount[s] == 0) {
                            // Place the full held stack into the empty craft slot
                            craftItem[s] = heldItem; craftCount[s] = heldCount;
                            heldItem = BlockId.AIR; heldCount = 0;
                        } else if (craftItem[s] == heldItem) {
                            // Same item — merge full stack into craft slot
                            craftCount[s] += heldCount;
                            heldItem = BlockId.AIR; heldCount = 0;
                        } else {
                            // Different item — swap: full stack in, old stack to hand
                            byte ti = craftItem[s]; int tc = craftCount[s];
                            craftItem[s] = heldItem; craftCount[s] = heldCount;
                            heldItem = ti; heldCount = tc;
                        }
                    }
                    computeCraftOutput();
                }

                // While holding LMB and stack in hand, distribute one item per unvisited hovered slot
                if (leftDragDistribute && input.isMouseDown(GLFW_MOUSE_BUTTON_LEFT) && heldCount > 0 && mouseSlot >= 0 && !dragVisited[mouseSlot]) {
                    if (slotCount[mouseSlot] == 0 || slotItem[mouseSlot] == heldItem) {
                        if (slotCount[mouseSlot] == 0) slotItem[mouseSlot] = heldItem;
                        if (slotCount[mouseSlot] < STACK_MAX) {
                            slotCount[mouseSlot] += 1;
                            heldCount -= 1;
                            dragVisited[mouseSlot] = true;
                            if (heldCount <= 0) { heldItem = BlockId.AIR; heldCount = 0; leftDragDistribute = false; }
                        }
                    }
                }
                if (!input.isMouseDown(GLFW_MOUSE_BUTTON_LEFT)) leftDragDistribute = false;

                // Right click: Minecraft-style place one from hand (or take half if hand empty)
                if (input.wasMousePressed(GLFW_MOUSE_BUTTON_RIGHT)) {
                    if (mouseCraft >= 0) {
                        int s = mouseCraft;
                        if (heldCount > 0 && (craftCount[s] == 0 || craftItem[s] == heldItem)) {
                            // M153: right-click places 1 into empty slot OR adds 1 to matching stack
                            if (craftCount[s] == 0) craftItem[s] = heldItem;
                            craftCount[s]++;
                            heldCount--;
                            if (heldCount <= 0) { heldItem = BlockId.AIR; heldCount = 0; }
                        } else if (heldCount == 0 && craftCount[s] > 0) {
                            // Pick up everything from filled slot
                            heldItem = craftItem[s]; heldCount = craftCount[s];
                            craftItem[s] = BlockId.AIR; craftCount[s] = 0;
                        }
                        computeCraftOutput();
                    } else {
                        int s = invCursor;
                        if (heldCount > 0) {
                            if (slotCount[s] == 0 || slotItem[s] == heldItem) {
                                if (slotCount[s] == 0) slotItem[s] = heldItem;
                                if (slotCount[s] < STACK_MAX) {
                                    slotCount[s] += 1;
                                    heldCount -= 1;
                                    if (heldCount <= 0) { heldItem = BlockId.AIR; heldCount = 0; }
                                }
                            }
                        } else if (slotCount[s] > 1) {
                            int half = slotCount[s] / 2;
                            heldItem = slotItem[s];
                            heldCount = half;
                            slotCount[s] -= half;
                        }
                    }
                }

                // Craft output click (2x2 crafting)
                if (craftOutputAtMouse() && input.wasMousePressed(GLFW_MOUSE_BUTTON_LEFT)) {
                    consumeCraftGridForOutput();
                }

                // Recipes panel removed (M70): craft from 2x2 output slot only.
            } else {
                // Debug: N = toggle night mode
                if (input.wasPressed(GLFW_KEY_N)) renderer.toggleNightMode();

                // M153: Keys 1-9 select hotbar slots 0-8
                if (input.wasPressed(GLFW_KEY_1)) selectedHotbarSlot = 0;
                if (input.wasPressed(GLFW_KEY_2)) selectedHotbarSlot = 1;
                if (input.wasPressed(GLFW_KEY_3)) selectedHotbarSlot = 2;
                if (input.wasPressed(GLFW_KEY_4)) selectedHotbarSlot = 3;
                if (input.wasPressed(GLFW_KEY_5)) selectedHotbarSlot = 4;
                if (input.wasPressed(GLFW_KEY_6)) selectedHotbarSlot = 5;
                if (input.wasPressed(GLFW_KEY_7)) selectedHotbarSlot = 6;
                if (input.wasPressed(GLFW_KEY_8)) selectedHotbarSlot = 7;
                if (input.wasPressed(GLFW_KEY_9)) selectedHotbarSlot = 8;
                // Scroll wheel cycles through hotbar slots
                int scroll = input.consumeScroll();
                if (scroll != 0) {
                    selectedHotbarSlot = ((selectedHotbarSlot + scroll) % HOTBAR.length + HOTBAR.length) % HOTBAR.length;
                }
                // Derive selectedPlaceBlock from the active slot
                selectedPlaceBlock = (slotCount[selectedHotbarSlot] > 0) ? slotItem[selectedHotbarSlot] : BlockId.AIR;
                // M144: flash item name when slot changes
                if (selectedHotbarSlot != prevHotbarSlot) {
                    prevHotbarSlot    = selectedHotbarSlot;
                    hotbarSwitchName  = selectedPlaceBlock == BlockId.AIR ? "" : BlockId.nameOf(selectedPlaceBlock);
                    hotbarSwitchTimer = hotbarSwitchName.isEmpty() ? 0f : 2.0f;
                    // M171: auto-equip tool when selected in hotbar
                    if (BlockId.isTool(selectedPlaceBlock) && slotCount[selectedHotbarSlot] > 0) {
                        if (equippedTool != BlockId.AIR) addInv(equippedTool, 1);
                        equippedTool      = slotItem[selectedHotbarSlot];
                        toolDurability    = toolMaxDurability(equippedTool);
                        toolDurabilityMax = toolMaxDurability(equippedTool);
                        equipAnim         = 0f;
                        slotCount[selectedHotbarSlot]--;
                        if (slotCount[selectedHotbarSlot] <= 0) { slotCount[selectedHotbarSlot] = 0; slotItem[selectedHotbarSlot] = BlockId.AIR; }
                    }
                }

                // M171: G key removed - tools auto-equip on hotbar selection
                if (input.wasPressed(GLFW_KEY_F2)) renderer.saveEditsToDisk();
                if (input.wasPressed(GLFW_KEY_F3)) infoOverlayVisible = !infoOverlayVisible;
                if (input.wasPressed(GLFW_KEY_F4)) applyWorld(worldIndex + 1);

                if (interactCooldown > 0f) interactCooldown = Math.max(0f, interactCooldown - dt);
            }

            if (glfwGetInputMode(window, GLFW_CURSOR) == GLFW_CURSOR_DISABLED) {
                int wx = (int) Math.floor(camera.position().x);
                int wz = (int) Math.floor(camera.position().z);
                long seed = renderer.worldSeed();
                float[] bw = noctfield.world.ChunkGenerator.biomeWeightsAt(wx, wz, seed);
                currentBiome = noctfield.world.ChunkGenerator.biomeAtWorld(wx, wz, seed);
                currentMoveMul = noctfield.world.ChunkGenerator.movementMultiplierAt(wx, wz, seed);
                currentSanityMul = noctfield.world.ChunkGenerator.sanityDrainMultiplierAt(wx, wz, seed);
                float aggroMul = noctfield.world.ChunkGenerator.watcherAggroMultiplierAt(wx, wz, seed);

                float blendRate = Math.min(1f, dt * 2.6f);
                biomeBlendP += (bw[noctfield.world.ChunkGenerator.BIOME_PINE] - biomeBlendP) * blendRate;
                biomeBlendD += (bw[noctfield.world.ChunkGenerator.BIOME_DEAD] - biomeBlendD) * blendRate;
                biomeBlendS += (bw[noctfield.world.ChunkGenerator.BIOME_SWAMP] - biomeBlendS) * blendRate;

                if (biomeBlendS > 0.45f) biomeAudioLabel = "SWAMP_DRONE";
                else if (biomeBlendD > 0.45f) biomeAudioLabel = "DEAD_WIND";
                else biomeAudioLabel = "PINE_HUM";

                wardTimer = Math.max(0f, wardTimer - dt);

                // M145: biome atmosphere events (surface only)
                if (!underground) {
                    biomeEventTimer -= dt;
                    if (biomeEventTimer <= 0f) {
                        float gap = 45f + fxRng.nextFloat() * 45f; // 45-90s between events
                        biomeEventTimer = gap;
                        if (currentBiome == noctfield.world.ChunkGenerator.BIOME_DEAD) {
                            renderer.nudgeDistortion(0.06f + fxRng.nextFloat() * 0.08f);
                            journalMessage = DEAD_WHISPERS[fxRng.nextInt(DEAD_WHISPERS.length)];
                            journalMessageTimer = 3.0f;
                        } else if (currentBiome == noctfield.world.ChunkGenerator.BIOME_SWAMP) {
                            caveFogMultiplier = Math.min(caveFogMultiplier + 0.35f, 2.8f);
                            renderer.setFogUserMultiplier(caveFogMultiplier * darknessMultiplier);
                            journalMessage = SWAMP_WHISPERS[fxRng.nextInt(SWAMP_WHISPERS.length)];
                            journalMessageTimer = 2.5f;
                        }
                    }
                    // Low-sanity visual flicker
                    if (sanity < 30f && fxRng.nextFloat() < dt * 0.4f) {
                        renderer.nudgeDistortion(0.03f + fxRng.nextFloat() * 0.05f);
                    }
                }

                // M147: auto-save every 5 minutes
                autoSaveTimer -= dt;
                if (autoSaveTimer <= 0f) {
                    autoSaveTimer = 300f;
                    savePlayerState();
                    savedFlashTimer = 2.5f;
                }
                savedFlashTimer = Math.max(0f, savedFlashTimer - dt);
                hotbarSwitchTimer = Math.max(0f, hotbarSwitchTimer - dt);
                introTimer        = Math.max(0f, introTimer        - dt); // M170
                if (introTimer > 0f && input.wasPressed(GLFW_KEY_SPACE)) introTimer = 0f; // M180 skip
                boundaryMsgTimer  = Math.max(0f, boundaryMsgTimer  - dt); // M184
                relicFlashTimer   = Math.max(0f, relicFlashTimer   - dt); // M188
                // M184: world boundary pushback
                {   float bx = camera.position().x, bz = camera.position().z;
                    int wr = noctfield.world.ChunkGenerator.WORLD_RADIUS;
                    if (Math.abs(bx) > wr || Math.abs(bz) > wr) {
                        float cx2 = Math.max(-wr, Math.min(wr, bx));
                        float cz2 = Math.max(-wr, Math.min(wr, bz));
                        camera.setPosition(cx2, camera.position().y, cz2);
                        boundaryMsgTimer = 4.0f;
                    }
                }

                // M152: horror progression (frozen in builder mode)
                if (!BUILDER_MODE) worldPlayTime += dt;
                float horror = BUILDER_MODE ? 0f : Math.min(1f, worldPlayTime / 600f);
                renderer.setHorrorProgression(horror);
                thickFogMsgTimer = Math.max(0f, thickFogMsgTimer - dt);

                // Escalation milestone messages — shown once per threshold
                if (thickFogMsgTimer <= 0f) {
                    int milestone = (horror >= 0.98f) ? 4 : (horror >= 0.75f) ? 3
                                  : (horror >= 0.50f) ? 2 : (horror >= 0.25f) ? 1 : 0;
                    if (milestone > lastEscalateMilestone) {
                        lastEscalateMilestone = milestone;
                        horrorEventMsg = switch (milestone) {
                            case 1 -> "SOMETHING IS WATCHING YOU";
                            case 2 -> "THE DARKNESS GROWS HUNGRY";
                            case 3 -> "THEY ARE EVERYWHERE NOW";
                            case 4 -> "THERE IS NO ESCAPE";
                            default -> "";
                        };
                        thickFogMsgTimer = 5.0f;
                        audio.playEscalation(milestone); // M211
                    }
                }
                if (renderer.consumeThickFogEvent() && thickFogMsgTimer <= 0f) {
                    horrorEventMsg = "THE FOG THICKENS";
                    thickFogMsgTimer = 4.0f;
                }
                if (renderer.consumeDeadFogEvent() && thickFogMsgTimer <= 0f) {
                    horrorEventMsg = "THE WORLD GOES DARK";
                    thickFogMsgTimer = 6.0f;
                }
                if (renderer.bloodRainActive() && thickFogMsgTimer <= 0f) {
                    horrorEventMsg = "IT BLEEDS FROM THE SKY";
                    thickFogMsgTimer = 5.0f;
                }
                if (thickFogMsgTimer <= 0f) horrorEventMsg = "";

                // M215/M218: Hide & Seek â€” once per world, armed at sanity<=60%
                if (!hideSeekFired && !hideSeekActive) {
                    if (hideSeekTriggerTimer < 0f && sanity <= 60f)
                        hideSeekTriggerTimer = 30f + (float)(Math.random() * 60f);
                    if (hideSeekTriggerTimer >= 0f) {
                        hideSeekTriggerTimer -= dt;
                        if (hideSeekTriggerTimer <= 0f) {
                            hideSeekFired      = true;
                            hideSeekActive     = true;
                            hideSeekSpawned    = false;
                            hideSeekCaught     = false;
                            hideSeekSpawnTimer = 18.5f; // wait for countdown wav to finish
                            renderer.spawnHideSeekFog(); // fog only - screamer held back
                            audio.playHideSeek();
                            System.out.println("[HideSeek] Countdown started");
                        }
                    }
                }
                if (hideSeekActive) {
                    if (renderer.consumeHideSeekCaught()) hideSeekCaught = true;
                    if (!hideSeekSpawned) {
                        hideSeekSpawnTimer -= dt;
                        if (hideSeekSpawnTimer <= 0f) {
                            hideSeekSpawned = true;
                            hideSeekPatrolTimer = 30f;
                            renderer.spawnHideSeekScreamer(camera.position().x, camera.position().z);
                            System.out.println("[HideSeek] Screamer released");
                        }
                    } else {
                        hideSeekPatrolTimer -= dt;
                        if (hideSeekPatrolTimer <= 0f || hideSeekCaught) {
                            hideSeekActive = false;
                            renderer.clearHideSeekScreamer();
                            if (!hideSeekCaught) audio.playHideSeekOver(); // only if player survived
                            System.out.println("[HideSeek] Event ended, caught=" + hideSeekCaught);
                        }
                    }
                }

                camera.setMoveSpeedMultiplier(currentMoveMul);
                // M166: LCTRL = walk/sneak mode — suppresses footsteps, reduces speed to 55%
                boolean walkMode = !inventoryOpen && (input.isDown(GLFW_KEY_LEFT_CONTROL));
                if (walkMode) camera.setMoveSpeedMultiplier(currentMoveMul * 0.55f);
                // M92/M96: darkness gives mild aggro boost — cap at 1.4x so it doesn't become impossible
                float darkAggroBoost = 1.0f + (darknessMultiplier - 1.0f) * 0.22f;
                renderer.setBiomeAggroMultiplier(aggroMul * darkAggroBoost);
                renderer.setBiomeVisualWeights(biomeBlendP, biomeBlendD, biomeBlendS);

                camera.update(input, renderer::getBlock, dt);
                fovPulse  = Math.max(0f, fovPulse  - dt * 1.8f);
                hitTrauma = Math.max(0f, hitTrauma - dt * 3.5f); // ~0.3s shake duration

                // M33: audio update
                boolean moving = camera.onGround() && (
                        input.isDown(GLFW_KEY_W) || input.isDown(GLFW_KEY_S) ||
                        input.isDown(GLFW_KEY_A) || input.isDown(GLFW_KEY_D));
                // M172: idle scare — stand still underground for 45s
                if (underground && !moving && !walkMode) {
                    idleTimer += dt;
                    if (idleTimer >= 45f && !idleScared) {
                        idleScared = true;
                        journalMessage = "TURN AROUND";
                        journalMessageTimer = 3.5f;
                        if (audio != null) audio.triggerEventSting();
                    }
                } else {
                    if (!underground) { idleTimer = 0f; idleScared = false; }
                    else idleTimer = 0f;
                }
                // M173: fungus passive heal — 1 HP per 4s while standing on FUNGUS
                if (camera.onGround()) {
                    int fx = (int)Math.floor(camera.position().x);
                    int fy = (int)Math.floor(camera.position().y) - 1;
                    int fz = (int)Math.floor(camera.position().z);
                    if (renderer.getBlock(fx, fy, fz) == noctfield.world.BlockId.FUNGUS) {
                        fungusTouchTimer += dt;
                        if (fungusTouchTimer >= 4f) { fungusTouchTimer = 0f; health = Math.min(100, health + 1); }
                    } else { fungusTouchTimer = 0f; }
                }
                // M225: liminal portal detection — walk INTO a LIMINAL_PORTAL tile to switch zones
                // Foot Y = camera.y - 1.62 (EYE height). Check block at foot level and one below.
                liminalPortalCooldown = Math.max(0f, liminalPortalCooldown - dt);
                if (liminalPortalCooldown <= 0f) {
                    int lpx = (int)Math.floor(camera.position().x);
                    int lpz = (int)Math.floor(camera.position().z);
                    int footY = (int)Math.floor(camera.position().y - 1.62f);
                    boolean onPortal = renderer.getBlock(lpx, footY,   lpz) == noctfield.world.BlockId.LIMINAL_PORTAL
                                    || renderer.getBlock(lpx, footY-1, lpz) == noctfield.world.BlockId.LIMINAL_PORTAL;
                    if (onPortal) {
                        if (currentZone == 0) {
                            enterZone(1);            // overworld -> meadow
                        } else if (currentZone == 1) {
                            float px2 = camera.position().x, pz2 = camera.position().z;
                            // Building entrance arch is at wz=73 (BZ-BHALF), wx=79-81
                            if (Math.abs(px2 - 80f) < 5f && Math.abs(pz2 - 73f) < 4f) {
                                enterZone(2);        // building arch portal -> mansion
                            } else {
                                enterZone(0);        // return portal (near spawn 50,50) -> overworld
                            }
                        }
                        // M234: zone 2 has no exit — portal is arrival marker only; player is sealed in
                    }
                }

                // M172: spectral pig despawn whisper
                if (renderer.consumeSpectralDespawn() && audio != null) {
                    audio.triggerPsychGhostStep(camera.position().x, camera.position().y, camera.position().z,
                            camera.forward().x, camera.forward().z);
                }
                if (audio != null && audio.isInitialized()) {
                    audio.update(dt, currentBiome, sanity / 100f,
                            renderer.isPlayerBeingStalked(), camera.onGround(), moving && !walkMode);
                    renderer.setPlayerMoving(moving && !walkMode);
                    renderer.setRelicsComplete(relicsFound >= RELIC_GOAL); // M169: beacon
                    // M98: 3D positional audio — update listener + enemy positions
                    org.joml.Vector3f cp = camera.position();
                    org.joml.Vector3f cf = camera.forward();
                    audio.setListenerTransform(cp.x, cp.y, cp.z, cf.x, cf.y, cf.z);
                    org.joml.Vector3f lp = renderer.lurkerPosition();
                    audio.updateEnemyPositions(0f, 0f, 0f, false,
                                               lp.x, lp.y, lp.z, renderer.isLurkerActive());
                    if (renderer.isDeepActive()) {
                        org.joml.Vector3f dp = renderer.deepPosition();
                        audio.setDeepState(dp.x, dp.y, dp.z, true, renderer.isDeepHunting());
                    } else {
                        audio.setDeepState(0f, 0f, 0f, false, false);
                    }
                                    audio.setHorrorLevel(Math.min(1f, worldPlayTime / 600f));
                    audio.setBloodRain(renderer.bloodRainActive());
                    audio.setDeadFog(renderer.isDeadFogActive());
                    audio.setNightMode(renderer.nightMode());
                    // M196: THE SCREAMER — play scream.wav at entity position when triggered
                    float[] screamPos = renderer.consumeScreamerSoundPos();
                    if (screamPos != null) audio.triggerScreamerScream(screamPos[0], screamPos[1], screamPos[2]);
                    // M201: THE NUN — footstep and knife strike
                    float[] nunStep = renderer.consumeNunStepPos();
                    if (nunStep != null) audio.triggerNunStep(nunStep[0], nunStep[1], nunStep[2]);
                    if (renderer.consumeNunHit()) {
                        if (!godMode) health = Math.max(0, health - applyArmor(35));
                        hitTrauma = Math.min(1.0f, hitTrauma + 1.0f);
                        fovPulse  = Math.max(fovPulse, 1.0f);
                        if (audio != null) {
                            org.joml.Vector3f nunCamPos = camera.position();
                            audio.triggerNunStrike(nunCamPos.x, nunCamPos.y, nunCamPos.z);
                            audio.triggerHitImpact();
                        }
                    }
                    // M198: THE THING drag — smoothly carry player alongside THE THING (no instant teleport)
                    if (renderer.consumeThingDrag() != null && !thingDragActive) {
                        thingDragActive  = true;
                        horrorEventMsg   = "YOU HAVE BEEN TAKEN";
                        thickFogMsgTimer = 5.0f;
                        if (audio != null) audio.triggerJumpscareScream();
                    }
                    if (thingDragActive) {
                        if (renderer.isThingDragging()) {
                            float[] tp   = renderer.getThingDragPos();
                            float dragY  = noctfield.world.ChunkGenerator.heightAt(
                                    (int)tp[0], (int)tp[1], renderer.worldSeed()) + 1.7f;
                            camera.setPosition(tp[0], dragY, tp[1]);
                            lastGroundY  = -999f; // prevent fall damage at new location
                            // Immediately sync audio listener so positional audio doesn't glitch
                            org.joml.Vector3f cf3 = camera.forward();
                            if (audio != null) audio.setListenerTransform(tp[0], dragY, tp[1], cf3.x, cf3.y, cf3.z);
                        } else {
                            thingDragActive = false;
                        }
                    }
                    // M159: jumpscare update
                    updateJumpscares(dt);
                    // M156: psych audio events — ghost footstep behind player, overhead creak
                    int psychEvt = renderer.consumePsychAudioEvent();
                    if (psychEvt == 1) {
                        org.joml.Vector3f cp2 = camera.position();
                        org.joml.Vector3f cf2 = camera.forward();
                        audio.triggerPsychGhostStep(cp2.x, cp2.y, cp2.z, cf2.x, cf2.z);
                    } else if (psychEvt == 2) {
                        org.joml.Vector3f cp2 = camera.position();
                        audio.triggerPsychCreak(cp2.x, cp2.y + 3.5f, cp2.z);
                    }
                }
                // Fire event sting only on notable horror events — NOT combat state transitions
                String curEvent = renderer.watcherEvent();
                if (!curEvent.equals(lastWatcherEventSeen) && !"NONE".equals(curEvent)) {
                    lastWatcherEventSeen = curEvent;
                    // M98 fix: only sting for atmospheric events
                    boolean notableEvent = curEvent.equals("NIGHT_SENTINEL_VANISH")
                            || curEvent.equals("THING_HIT")
                            || curEvent.equals("DEBUG");
                    if (notableEvent && audio != null) audio.triggerEventSting();
                }
                prevOnGround = camera.onGround();

                // M37: underground atmosphere — only applies in overworld (zone 0)
                int surfH = noctfield.world.ChunkGenerator.heightAt(
                        (int)Math.floor(camera.position().x),
                        (int)Math.floor(camera.position().z),
                        renderer.worldSeed());
                boolean nowUnderground = currentZone == 0 && camera.position().y < surfH - 1.5f;
                if (nowUnderground != underground) {
                    underground = nowUnderground;
                    if (audio != null) audio.setUnderground(underground);
                    renderer.setUnderground(underground);
                }
                // M83: smooth cave fog - closes in tight underground
                float fogTarget = underground ? 4.5f : 1.0f;
                caveFogMultiplier += (fogTarget - caveFogMultiplier) * Math.min(1f, dt * 1.8f);

                // M92: darkness - underground without a light source makes fog much thicker
                if (underground) {
                    // Lantern OR torch in active hand counts as a light source
                    boolean hasLight = (selectedPlaceBlock == BlockId.LANTERN
                            || selectedPlaceBlock == BlockId.TOOL_TORCH
                            || equippedTool == BlockId.TOOL_TORCH);
                    // Also check nearby placed lanterns (4-block radius)
                    if (!hasLight) {
                        int cx = (int)Math.floor(camera.position().x);
                        int cy = (int)Math.floor(camera.position().y);
                        int cz = (int)Math.floor(camera.position().z);
                        outer:
                        for (int dy = -2; dy <= 2; dy++) {
                            for (int dz = -4; dz <= 4; dz++) {
                                for (int dx = -4; dx <= 4; dx++) {
                                    if (renderer.getBlock(cx+dx, cy+dy, cz+dz) == BlockId.LANTERN) {
                                        hasLight = true;
                                        break outer;
                                    }
                                }
                            }
                        }
                    }
                    float darkTarget = hasLight ? 1.0f : 2.8f;
                    darknessMultiplier += (darkTarget - darknessMultiplier) * Math.min(1f, dt * 1.2f);
                } else {
                    darknessMultiplier = 1.0f;
                }
                renderer.setFogUserMultiplier(caveFogMultiplier * darknessMultiplier);

                // M85: cave zone detection + effects
                if (underground) {
                    caveZone = noctfield.world.ChunkGenerator.caveZoneAt(
                        (int)Math.floor(camera.position().x),
                        (int)Math.floor(camera.position().z),
                        renderer.worldSeed());
                    switch (caveZone) {
                        case noctfield.world.ChunkGenerator.CAVE_ZONE_FLOODED ->  {
                            // Flooded: slow movement, darker cave
                            currentMoveMul *= 0.70f;
                            camera.setMoveSpeedMultiplier(currentMoveMul);
                        }
                        case noctfield.world.ChunkGenerator.CAVE_ZONE_DEAD -> {
                            // Dead caves: sanity drains 2x faster
                            currentSanityMul *= 2.0f;
                        }
                        // CRYSTAL zone: no penalty - fungus more dense (handled in worldgen)
                        default -> {}
                    }
                } else {
                    caveZone = -1;
                }

                // M91: water wading — extra speed penalty when camera is in a water block
                if (camera.isInWater()) {
                    currentMoveMul *= 0.55f;
                    camera.setMoveSpeedMultiplier(currentMoveMul);
                }

                // M104 test: stalactite drop system disabled for audio/render artifact isolation.
                // (No scan, no creak/crash trigger, no drop damage.)
                stalactiteTimer    = -1f;
                stalactiteCooldown = 0f;

                // M38: sprint stamina
                boolean sprinting = input.isDown(GLFW_KEY_LEFT_SHIFT) && moving && camera.onGround();
                float biomeDrainMul = 1f + biomeBlendD * 0.25f + biomeBlendS * 0.40f;
                if (sprinting && !staminaExhausted) {
                    stamina = Math.max(0f, stamina - 22f * dt * biomeDrainMul);
                    if (stamina <= 0f) {
                        staminaExhausted = true;
                        camera.setSprintEnabled(false);
                        if (renderer.isPlayerBeingStalked()) renderer.forceNextWatcherEventNow();
                        if (audio != null) audio.triggerBreath();
                    }
                } else {
                    float regenMul = underground ? 0.55f : 1f;
                    stamina = Math.min(100f, stamina + 12f * dt * regenMul);
                    if (staminaExhausted && stamina >= 25f) {
                        staminaExhausted = false;
                        camera.setSprintEnabled(true);
                    }
                }

                // M39: weather - pass rain state and handle lightning flash
                if (audio != null) audio.setRaining(renderer.isRaining());
                if (renderer.consumeLightningFlash()) {
                    fillRect(0, 0, width, height, 0.95f, 0.97f, 1.00f, 0.55f);
                    if (audio != null) audio.triggerThunder();
                }

                // Fall damage + hostile pressure prototype
                if (camera.onGround()) {
                    float y = camera.position().y;
                    if (lastGroundY > -900f) {
                        float drop = lastGroundY - y;
                        if (drop > 4.2f) {
                            int dmg = (int)((drop - 4.2f) * 8f);
                            if (!godMode) health = Math.max(0, health - dmg);
                            // M235: fall impact — same hit-sound + screen shake as combat damage
                            float severity = Math.min(1.0f, dmg / 16f);
                            hitTrauma = Math.min(1.0f, hitTrauma + 0.80f * severity);
                            fovPulse  = Math.max(fovPulse,  0.70f * severity);
                            if (audio != null) audio.triggerHitImpact();
                        }
                    }
                    lastGroundY = y;
                } else {
                    float y = camera.position().y;
                    if (y > lastGroundY) lastGroundY = y;
                }

                // Night no longer passively damages player. Keep tick for future AI hooks.
                hostileTick += dt;
                if (hostileTick >= 1.0f) {
                    hostileTick = 0f;
                }

                int hits = renderer.consumePendingPlayerHits();
                if (hits > 0) {
                    if (!godMode) health = Math.max(0, health - applyArmor(hits * 16));
                    // M61: camera shake - stack trauma per hit, kick FOV
                    hitTrauma = Math.min(1.0f, hitTrauma + 0.80f * hits);
                    fovPulse  = Math.max(fovPulse, 0.70f * hits);
                    if (audio != null) audio.triggerHitImpact(); // M98: dedicated hit thud
                }

                // M86: ceiling lurker hit
                if (renderer.consumeLurkerHit()) {
                    if (!godMode) health = Math.max(0, health - applyArmor(20));  // lurker hits harder - 20 damage
                    hitTrauma = Math.min(1.0f, hitTrauma + 1.0f);
                    fovPulse  = Math.max(fovPulse, 1.0f);
                    if (audio != null) audio.triggerHitImpact(); // M98
                    renderer.spawnFigureSmoke(renderer.lurkerPosition()); // reuse smoke VFX on hit
                }
                // M166: THE DEEP hit
                if (renderer.consumeDeepHit()) {
                    if (!godMode) health = Math.max(0, health - applyArmor(25));
                    hitTrauma = Math.min(1.0f, hitTrauma + 1.0f);
                    fovPulse  = Math.max(fovPulse, 1.0f);
                    if (audio != null) audio.triggerHitImpact();
                    journalMessage = "IT FOUND YOU";
                    journalMessageTimer = 3.0f;
                }

                // M99: surface world events — only tick when above ground
                if (!underground) {
                    surfaceEventCooldown = Math.max(0f, surfaceEventCooldown - dt);
                    surfaceEventMsgTimer = Math.max(0f, surfaceEventMsgTimer - dt);

                    if (surfaceEvent == SurfaceEvent.NONE && surfaceEventCooldown <= 0f) {
                        // Pick a random event
                        int pick = fxRng.nextInt(3);
                        surfaceEvent = switch (pick) {
                            case 0 -> SurfaceEvent.FOG_BANK;
                            case 1 -> SurfaceEvent.WIND_STORM;
                            default -> SurfaceEvent.EMBER_SHOWER;
                        };
                        surfaceEventTimer = 45f + fxRng.nextFloat() * 45f; // 45-90s
                        surfaceEventCooldown = 120f + fxRng.nextFloat() * 120f; // 2-4 min until next
                        emberSpawnTimer = 0f;
                        String[] msgs = switch (surfaceEvent) {
                            case FOG_BANK     -> new String[]{"A thick fog rolls in..."};
                            case WIND_STORM   -> new String[]{"A violent wind tears through the forest..."};
                            case EMBER_SHOWER -> new String[]{"Embers fall from above..."};
                            default           -> new String[]{""};
                        };
                        surfaceEventMsg = msgs[0];
                        surfaceEventMsgTimer = 5f;
                        if (audio != null) audio.triggerEventSting();
                    }

                    if (surfaceEvent != SurfaceEvent.NONE) {
                        surfaceEventTimer -= dt;

                        switch (surfaceEvent) {
                            case FOG_BANK -> {
                                // Blend fog toward 8× during event, fade out as timer expires
                                float fogProgress = Math.min(1f, surfaceEventTimer > 5f
                                        ? (surfaceEventTimer - 5f) / 5f : surfaceEventTimer / 5f);
                                float evFogTarget = 1.0f + fogProgress * 7.0f; // up to 8×
                                caveFogMultiplier += (evFogTarget - caveFogMultiplier) * Math.min(1f, dt * 0.8f);
                                renderer.setFogUserMultiplier(caveFogMultiplier * darknessMultiplier);
                            }
                            case WIND_STORM -> {
                                // Movement penalty + jitter
                                currentMoveMul *= 0.68f;
                                camera.setMoveSpeedMultiplier(currentMoveMul);
                                float jitter = 0.015f * (float)Math.sin(surfaceEventTimer * 19.3f);
                                camera.setJitter(jitter, jitter * 0.7f);
                            }
                            case EMBER_SHOWER -> {
                                // Spawn glowing smoke bursts above and around player as embers
                                emberSpawnTimer -= dt;
                                if (emberSpawnTimer <= 0f) {
                                    emberSpawnTimer = 0.3f + fxRng.nextFloat() * 0.4f;
                                    float ex = camera.position().x + (fxRng.nextFloat() - 0.5f) * 20f;
                                    float ez = camera.position().z + (fxRng.nextFloat() - 0.5f) * 20f;
                                    float ey = camera.position().y + 4f + fxRng.nextFloat() * 6f;
                                    renderer.spawnFigureSmoke(new org.joml.Vector3f(ex, ey, ez));
                                }
                            }
                        }

                        if (surfaceEventTimer <= 0f) {
                            // Clear wind jitter when storm ends
                            if (surfaceEvent == SurfaceEvent.WIND_STORM) camera.setJitter(0f, 0f);
                            surfaceEvent = SurfaceEvent.NONE;
                        }
                    }
                } else if (surfaceEvent != SurfaceEvent.NONE) {
                    // Went underground — cancel active surface event cleanly
                    if (surfaceEvent == SurfaceEvent.WIND_STORM) camera.setJitter(0f, 0f);
                    surfaceEvent = SurfaceEvent.NONE;
                }

                // Hidden sanity loop:
                // - increases in light/safe zones
                // - decreases while actively stalked
                boolean safe = renderer.hasLanternNear(camera.position(), 10f);
                boolean stalked = renderer.isPlayerBeingStalked();

                float wardMul = wardTimer > 0f ? 0.65f : 1f;
                if (safe) sanity = Math.min(100f, sanity + dt * (1.10f / currentSanityMul));
                if (stalked) sanity = Math.max(0f, sanity - dt * (0.28f * currentSanityMul * wardMul)); // M218: even slower
                else if (!safe && renderer.nightMode()) sanity = Math.max(0f, sanity - dt * (0.02f * currentSanityMul * wardMul)); // M218

                renderer.setSanity01(sanity / 100f);
                // M211: sanity-threshold taunts
                if (!taunt85Fired && sanity <= 85f) { taunt85Fired = true; audio.playTaunt(1); System.out.println("[Voice] taunt_there triggered (sanity=" + (int)sanity + ")"); }
                if (!taunt50Fired && sanity <= 50f) { taunt50Fired = true; audio.playTaunt(2); System.out.println("[Voice] taunt_seeme triggered (sanity=" + (int)sanity + ")"); }
                if (!taunt30Fired && sanity <= 30f) { taunt30Fired = true; audio.playTaunt(0); System.out.println("[Voice] taunt_watching triggered (sanity=" + (int)sanity + ")"); }
                // M211: sanity-threshold taunts (fire once each, descending thresholds)
                if (!taunt85Fired && sanity <= 85f) { taunt85Fired = true; audio.playTaunt(1); System.out.println("[Voice] taunt_there triggered (sanity=" + (int)sanity + ")"); }
                if (!taunt50Fired && sanity <= 50f) { taunt50Fired = true; audio.playTaunt(2); System.out.println("[Voice] taunt_seeme triggered (sanity=" + (int)sanity + ")"); }
                if (!taunt30Fired && sanity <= 30f) { taunt30Fired = true; audio.playTaunt(0); System.out.println("[Voice] taunt_watching triggered (sanity=" + (int)sanity + ")"); }

                for (int i = tempStructures.size() - 1; i >= 0; i--) {
                    TempStructure s = tempStructures.get(i);
                    s.ttl -= dt;
                    if (s.ttl <= 0f && renderer.getBlock(s.x, s.y, s.z) == s.block) {
                        renderer.setBlock(s.x, s.y, s.z, BlockId.AIR);
                        tempStructures.remove(i);
                    }
                }

                if (health <= 0 && !deathScreenActive) {
                    deathScreenActive = true;
                    deathTimer = DEATH_HOLD_SECS;
                    savePlayerState(); // save zero-health so it doesn't duplicate on load
                }
                if (deathScreenActive) {
                    deathTimer -= dt;
                    if (deathTimer <= 0f) {
                        deathScreenActive = false;
                        camera = new FreeCamera();
                        health = 100;
                        sanity = 100f;
                        hostileTick = 0f;
                        lastGroundY = -999f;
                        thingDragActive = false;
                        enterZone(0); // always respawn in overworld, never stuck in liminal zone
                    }
                }

                // M153: escape objective — reach the Void Gate at origin after RELIC_GOAL relics
                if (!escapeActive && !deathScreenActive && relicsFound >= RELIC_GOAL) {
                    // Show one-time "gate is open" message
                    if (!relicGoalMsgShown) {
                        relicGoalMsgShown = true;
                        horrorEventMsg    = "THE VOID GATE AWAKENS — RETURN TO THE ORIGIN";
                        thickFogMsgTimer  = 7.0f;
                    }
                    // Player standing in the Void Gate ring (within 4 blocks of origin XZ)
                    org.joml.Vector3f pos = camera.position();
                    float distOrigin = (float)Math.sqrt(pos.x * pos.x + pos.z * pos.z);
                    if (distOrigin < 4.0f) {
                        escapeActive = true;
                        escapeTimer  = ESCAPE_HOLD_SECS;
                    }
                }
                if (escapeActive) {
                    escapeTimer -= dt;
                    if (escapeTimer <= 0f) {
                        savePlayerState();
                        escapeActive       = false;
                        endingScreenActive = true;
                        endingScreenTimer  = 0f;
                        endingPlaytime     = worldPlayTime;
                        endingWorldName    = renderer.worldId();
                        endingRelics       = relicsFound;
                    }
                }
                if (endingScreenActive) {
                    endingScreenTimer += dt;
                    if (endingScreenTimer >= ENDING_SCREEN_DURATION
                            || input.wasPressed(GLFW_KEY_ENTER) || input.wasPressed(GLFW_KEY_SPACE)) {
                        endingScreenActive = false;
                        openTitleScreen(); // M217: return to title screen after ending
                    }
                }
            }

            float dIntensity = renderer.distortionIntensity();
            // M61: trauma² gives a punchy initial slam that smoothly decays to calm
            float shakeAmt = hitTrauma * hitTrauma;
            float fovKick = (float)Math.sin(glfwGetTime() * 9.0) * (dIntensity * 0.35f)
                          + fovPulse * 2.8f
                          + shakeAmt * 6.0f;  // FOV warp on hit (up to +6 deg)
            camera.setFovOffsetDeg(fovKick);

            float jitter = Math.max(0f, dIntensity - 0.22f) * 0.015f
                         + shakeAmt * 0.055f; // positional shake on hit (up to ~5.5cm)
            float jx = (fxRng.nextFloat() - 0.5f) * jitter;
            float jy = (fxRng.nextFloat() - 0.5f) * jitter;
            camera.setJitter(jx, jy);

            glViewport(0, 0, width, height);

            Raycast.Hit hit = Raycast.cast(renderer::getBlock, camera.position(), camera.forward(), 4.0f);
            boolean cursorLocked = glfwGetInputMode(window, GLFW_CURSOR) == GLFW_CURSOR_DISABLED;

            // M150: pig attack — single click, independent of block raycast
            if (cursorLocked && interactCooldown <= 0f && input.wasMousePressed(GLFW_MOUSE_BUTTON_LEFT)) {
                org.joml.Vector3f fwd = camera.forward();
                boolean pigHit = renderer.tryHitPig(
                        camera.position().x, camera.position().z, fwd.x, fwd.z, 3.2f);
                if (pigHit) {
                    handSwing = 1.0f;
                    spawnHitParticles(BlockId.RAW_PORK); // pink splatter
                    interactCooldown = 0.35f;
                }
                // M192: chicken attack
                if (!pigHit && renderer.tryHitChicken(
                        camera.position().x, camera.position().z, fwd.x, fwd.z, 3.2f)) {
                    handSwing = 1.0f;
                    spawnHitParticles(BlockId.RAW_CHICKEN); // feather white splatter
                    interactCooldown = 0.35f;
                }
            }

            // M150: food eating — right-click with food in hand (works with or without a block target)
            if (cursorLocked && interactCooldown <= 0f && input.wasMousePressed(GLFW_MOUSE_BUTTON_RIGHT)
                    && BlockId.isFood(selectedPlaceBlock) && slotCount[selectedHotbarSlot] > 0) {
                if (health < 100) {
                    health = Math.min(100, health + BlockId.foodHealAmount(selectedPlaceBlock));
                    journalMessage = "HEALTH RESTORED +" + BlockId.foodHealAmount(selectedPlaceBlock);
                    journalMessageTimer = 1.8f;
                } else {
                    journalMessage = "HEALTH IS FULL";
                    journalMessageTimer = 1.2f;
                }
                slotCount[selectedHotbarSlot]--;
                if (slotCount[selectedHotbarSlot] <= 0) {
                    slotCount[selectedHotbarSlot] = 0;
                    slotItem[selectedHotbarSlot] = BlockId.AIR;
                }
                interactCooldown = 0.40f;
            }

            // M168: right-click armor in hotbar to equip
            if (!inventoryOpen && cursorLocked && input.wasMousePressed(GLFW_MOUSE_BUTTON_RIGHT) && BlockId.isArmor(selectedPlaceBlock) && slotCount[selectedHotbarSlot] > 0) {
                byte prev = equippedArmor;
                equippedArmor = selectedPlaceBlock;
                // swap back previous armor to slot
                slotItem[selectedHotbarSlot]  = (prev == BlockId.AIR) ? BlockId.AIR : prev;
                slotCount[selectedHotbarSlot] = (prev == BlockId.AIR) ? 0 : 1;
                if (prev == BlockId.AIR) { slotItem[selectedHotbarSlot] = BlockId.AIR; slotCount[selectedHotbarSlot] = 0; }
                journalMessage = "EQUIPPED " + BlockId.nameOf(equippedArmor);
                journalMessageTimer = 2f;
            }

            // M227: G key (mark corner) and RMB stamp — BUILDER MODE only
            if (BUILDER_MODE && cursorLocked && input.wasPressed(GLFW_KEY_G)) handleStructureGKey(hit);
            if (BUILDER_MODE && cursorLocked && structPasteMode && hit != null
                    && input.wasMousePressed(GLFW_MOUSE_BUTTON_RIGHT) && !savedStructNames.isEmpty()) {
                stampStruct(hit.x(), hit.y(), hit.z(), savedStructNames.get(structPasteIndex));
                journalMessage = "STAMPED: " + savedStructNames.get(structPasteIndex);
                journalMessageTimer = 2f;
            }

            if (cursorLocked && hit != null && interactCooldown <= 0f) {
                // M105: hold LMB to break over time (Minecraft-like); click-only for special interactions.
                if (input.isMouseDown(GLFW_MOUSE_BUTTON_LEFT)) {
                    byte broken = renderer.getBlock(hit.x(), hit.y(), hit.z());
                    if (broken == BlockId.RELIC || broken == BlockId.JOURNAL || broken == BlockId.VOIDSTONE) {
                        // Special interactions remain click-based.
                        if (input.wasMousePressed(GLFW_MOUSE_BUTTON_LEFT)) {
                            handSwing = 1.0f;
                            if (broken == BlockId.RELIC) {
                                renderer.setBlock(hit.x(), hit.y(), hit.z(), BlockId.AIR);
                                // M179: underground relics only contribute 1 toward portal
                                boolean isUnderground = hit.y() <= noctfield.world.ChunkGenerator.caveBandMaxY();
                                if (isUnderground && undergroundRelicsFound >= 1) {
                                    // Extra underground relic - removed but doesn't count
                                    relicMessage = "THE DEPTHS YIELD NO MORE SECRETS";
                                    relicMessageTimer = 4f;
                                    sanity = Math.min(100f, sanity + 3f);
                                } else {
                                    if (isUnderground) undergroundRelicsFound++;
                                    relicsFound++;
                                    renderer.setRelicLevel(relicsFound);
                                    relicMessage = "RELIC " + relicsFound + " / " + RELIC_GOAL + " ABSORBED";
                                    relicMessageTimer = 5f;
                                    relicFlashTimer   = 2.0f; // M188: dramatic pickup ceremony
                                    // M189: place a lore journal 3 blocks east of the relic (if that cell is clear)
                                    int jrnX = hit.x() + 3, jrnY = hit.y(), jrnZ = hit.z();
                                    if (renderer.getBlock(jrnX, jrnY, jrnZ) == BlockId.AIR) {
                                        renderer.setBlock(jrnX, jrnY, jrnZ, BlockId.JOURNAL);
                                    } else if (renderer.getBlock(jrnX, jrnY, jrnZ + 3) == BlockId.AIR) {
                                        renderer.setBlock(jrnX, jrnY, jrnZ + 3, BlockId.JOURNAL);
                                    }
                                    sanity = Math.min(100f, sanity + 8f);
                                }
                                interactCooldown = 0.5f;
                                System.out.println("[Relic] Collected - total: " + relicsFound + " underground: " + undergroundRelicsFound);
                            } else if (broken == BlockId.JOURNAL) {
                                journalMessage = secretJournalMessage(hit.x(), hit.z());
                                journalMessageTimer = 7f;
                                interactCooldown = 0.5f;
                            } else {
                                journalMessage = "THE VOID DOES NOT YIELD";
                                journalMessageTimer = 3f;
                                interactCooldown = 0.5f;
                            }
                        }
                        resetMiningProgress();
                    } else if (broken != BlockId.AIR) {
                        if (hit.x() != miningTargetX || hit.y() != miningTargetY || hit.z() != miningTargetZ) {
                            miningTargetX = hit.x();
                            miningTargetY = hit.y();
                            miningTargetZ = hit.z();
                            miningProgress = 0f;
                            miningTickTimer = 0f;
                        }

                        ToolTier tier = currentToolTier();
                        float breakTime = Math.max(0.08f, breakDurationSeconds(broken));
                        float speedMul = miningSpeedMultiplier(tier, broken);
                        miningProgress = Math.min(1f, miningProgress + (dt * speedMul) / breakTime);
                        miningTickTimer -= dt;
                        if (miningTickTimer <= 0f) {
                            miningTickTimer = 0.16f;
                            handSwing = 1.0f;
                        }

                        if (miningProgress >= 1f) {
                            if (!canHarvest(broken, tier)) {
                                journalMessage = "TOOL TOO WEAK";
                                journalMessageTimer = 1.2f;
                                interactCooldown = 0.15f;
                                resetMiningProgress();
                            } else {
                                renderer.setBlock(hit.x(), hit.y(), hit.z(), BlockId.AIR);
                                // Doors are 2-tall — break the paired block too (only 1 item returned)
                                if (broken == BlockId.DOOR_CLOSED || broken == BlockId.DOOR_OPEN) {
                                    int hx = hit.x(), hy = hit.y(), hz = hit.z();
                                    byte above = renderer.getBlock(hx, hy+1, hz);
                                    byte below = renderer.getBlock(hx, hy-1, hz);
                                    if (above == BlockId.DOOR_CLOSED || above == BlockId.DOOR_OPEN)
                                        renderer.setBlock(hx, hy+1, hz, BlockId.AIR);
                                    else if (below == BlockId.DOOR_CLOSED || below == BlockId.DOOR_OPEN)
                                        renderer.setBlock(hx, hy-1, hz, BlockId.AIR);
                                    // M148: spawn physical drop instead of direct inventory
                                    renderer.spawnDrop(hit.x(), hit.y(), hit.z(), BlockId.DOOR_CLOSED, 1);
                                } else {
                                    // M148: TORCH_WALL/TORCH_STAND both drop a placeable TOOL_TORCH
                                    byte loot = (broken == BlockId.TORCH_WALL || broken == BlockId.TORCH_STAND)
                                                ? BlockId.TOOL_TORCH
                                                : (broken == BlockId.FUNGUS ? BlockId.CAVE_MUSHROOM : broken); // M173
                                    renderer.spawnDrop(hit.x(), hit.y(), hit.z(), loot, 1);
                                }
                                spawnHitParticles(broken);
                                renderer.forceNextWatcherEventNow();
                                sanity = Math.max(0f, sanity - (currentBiome == noctfield.world.ChunkGenerator.BIOME_DEAD ? 1.8f : 1.1f));
                                interactCooldown = 0.06f;
                                handSwing = 1.0f;

                                // M112: tool durability loss on successful harvest.
                                if (equippedTool != BlockId.AIR) {
                                    toolDurability = Math.max(0, toolDurability - 1);
                                    if (toolDurability <= 0) {
                                        equippedTool = BlockId.AIR;
                                        toolDurability = 0;
                                        toolDurabilityMax = 0;
                                        journalMessage = "TOOL BROKE";
                                        journalMessageTimer = 1.2f;
                                    }
                                }
                                resetMiningProgress();
                            }
                        }
                    } else {
                        resetMiningProgress();
                    }
                } else {
                    // Let progress decay if player stops punching for a moment.
                    if (miningProgress > 0f) miningProgress = Math.max(0f, miningProgress - dt * 1.8f);
                    if (miningProgress <= 0f) resetMiningProgress();
                }

                if (!structPasteMode && input.wasMousePressed(GLFW_MOUSE_BUTTON_RIGHT)) { // M227: suppress normal RMB in paste mode
                    byte target = renderer.getBlock(hit.x(), hit.y(), hit.z());
                    if (target == BlockId.CRAFTING_TABLE) {
                        openCraftingTableUI();
                    } else if (target == BlockId.DOOR_CLOSED) {
                        renderer.setBlock(hit.x(), hit.y(), hit.z(), BlockId.DOOR_OPEN);
                        // Toggle paired half (above or below)
                        if (renderer.getBlock(hit.x(), hit.y()+1, hit.z()) == BlockId.DOOR_CLOSED)
                            renderer.setBlock(hit.x(), hit.y()+1, hit.z(), BlockId.DOOR_OPEN);
                        else if (renderer.getBlock(hit.x(), hit.y()-1, hit.z()) == BlockId.DOOR_CLOSED)
                            renderer.setBlock(hit.x(), hit.y()-1, hit.z(), BlockId.DOOR_OPEN);
                        interactCooldown = 0.10f;
                    } else if (target == BlockId.DOOR_OPEN) {
                        renderer.setBlock(hit.x(), hit.y(), hit.z(), BlockId.DOOR_CLOSED);
                        // Toggle paired half (above or below)
                        if (renderer.getBlock(hit.x(), hit.y()+1, hit.z()) == BlockId.DOOR_OPEN)
                            renderer.setBlock(hit.x(), hit.y()+1, hit.z(), BlockId.DOOR_CLOSED);
                        else if (renderer.getBlock(hit.x(), hit.y()-1, hit.z()) == BlockId.DOOR_OPEN)
                            renderer.setBlock(hit.x(), hit.y()-1, hit.z(), BlockId.DOOR_CLOSED);
                        interactCooldown = 0.10f;
                    }
                    resetMiningProgress();
                    handSwing = 1.0f;
                    int px = hit.placeX();
                    int py = hit.placeY();
                    int pz = hit.placeZ();
                    // M144: right-click JOURNAL → read it without destroying
                    if (target == BlockId.JOURNAL && interactCooldown <= 0f) {
                        journalMessage = secretJournalMessage(hit.x(), hit.z());
                        journalMessageTimer = 7f;
                        interactCooldown = 0.30f;
                    }

                    boolean targetIsInteractable = target == BlockId.CRAFTING_TABLE
                            || target == BlockId.DOOR_CLOSED || target == BlockId.DOOR_OPEN
                            || target == BlockId.JOURNAL;
                    if (!targetIsInteractable && !camera.intersectsBlock(px, py, pz) && selectedPlaceBlock != BlockId.AIR && slotCount[selectedHotbarSlot] > 0) {
                        if (inNoBuildZone(px, pz)) {
                            System.out.println("[Build] Blocked: no-build zone around extraction/start area");
                        } else {
                            byte placeId = selectedPlaceBlock;

                            // M126+: TOOL_TORCH places world torch variants with placement rules.
                            if (selectedPlaceBlock == BlockId.TOOL_TORCH) {
                                int dx = px - hit.x();
                                int dy = py - hit.y();
                                int dz = pz - hit.z();
                                byte hitBlock = renderer.getBlock(hit.x(), hit.y(), hit.z());
                                // Torches cannot be placed on leaves/cobweb — they'd look like they're floating
                                boolean badSurface = (hitBlock == BlockId.LEAVES || hitBlock == BlockId.COBWEB);
                                if (dy == 1 && !badSurface) {
                                    placeId = BlockId.TORCH_STAND; // placed on top face
                                } else if (!badSurface) {
                                    // place on side face only (wall torch)
                                    if (Math.abs(dx) + Math.abs(dz) == 1) placeId = BlockId.TORCH_WALL;
                                    else placeId = BlockId.AIR;
                                }
                            }

                            if (placeId != BlockId.AIR && !BlockId.isTool(placeId)
                                    && placeId != BlockId.COMPASS) { // M181: compass is passive, not placeable
                                // Doors are 2 blocks tall — need the block above to be free too.
                                boolean canPlace = placeId != BlockId.DOOR_CLOSED
                                        || renderer.getBlock(px, py + 1, pz) == BlockId.AIR;

                                if (canPlace) {
                                    if (placeId == BlockId.DOOR_CLOSED) {
                                        renderer.setBlock(px, py,     pz, BlockId.DOOR_CLOSED);
                                        renderer.setBlock(px, py + 1, pz, BlockId.DOOR_CLOSED);
                                        // M237: record which axis door faces based on camera forward
                                        org.joml.Vector3f fwd = camera.forward();
                                        byte df = (Math.abs(fwd.x) > Math.abs(fwd.z)) ? (byte)1 : (byte)0;
                                        renderer.setDoorFacing(px, py,     pz, df);
                                        renderer.setDoorFacing(px, py + 1, pz, df);
                                    } else {
                                        renderer.setBlock(px, py, pz, placeId);
                                    }
                                    slotCount[selectedHotbarSlot]--;
                                    if (slotCount[selectedHotbarSlot] <= 0) { slotCount[selectedHotbarSlot] = 0; slotItem[selectedHotbarSlot] = BlockId.AIR; }
                                }

                                // M237: blocks are permanent — removed decay mechanic (tempStructures)

                                // Building makes noise/signature too.
                                renderer.forceNextWatcherEventNow();
                                interactCooldown = placeCooldownSeconds();
                            }
                        }
                    }
                }
            } else {
                if (!cursorLocked || hit == null || interactCooldown > 0f) resetMiningProgress();
            }

            if (relicMessageTimer   > 0f) relicMessageTimer   = Math.max(0f, relicMessageTimer   - dt);
            if (journalMessageTimer > 0f) journalMessageTimer = Math.max(0f, journalMessageTimer - dt);
            if (zoneLoading) {
                zoneLoadTimer += dt;
                if (zoneLoadTimer >= ZONE_LOAD_DURATION) zoneLoading = false;
            }

            if (miningProgress > 0f && miningTargetX != Integer.MIN_VALUE) {
                renderer.setMiningCrack(true, miningTargetX, miningTargetY, miningTargetZ, miningProgress);
            } else {
                renderer.setMiningCrack(false, 0, 0, 0, 0f);
            }

            // M157 fix: torch emits light when selected in hotbar OR in tool slot.
            // M152 removed hotbar-torch lighting due to HOTBAR default having torch at slot 0.
            // HOTBAR default no longer contains torch, so hotbar selection is safe to re-enable.
            boolean heldTorch = (equippedTool == BlockId.TOOL_TORCH)
                    || (selectedPlaceBlock == BlockId.TOOL_TORCH && slotCount[selectedHotbarSlot] > 0);
            org.joml.Vector3f torchPos = new org.joml.Vector3f(camera.position()).add(0.22f, -0.16f, 0.18f);
            renderer.setHeldTorch(torchPos, heldTorch);

            // M148: pick up any dropped items within 1.5 blocks of the player
            if (glfwGetInputMode(window, GLFW_CURSOR) == GLFW_CURSOR_DISABLED) {
                float cpx = camera.position().x, cpz = camera.position().z;
                for (int[] drop : renderer.collectDropsNear(cpx, cpz, 1.5f)) {
                    byte dropId = (byte) drop[0];
                    addInv(dropId, drop[1]);
                    hotbarSwitchName  = BlockId.nameOf(dropId);
                    hotbarSwitchTimer = 2.0f;
                }
            }

            renderer.render(camera, width, height);
            renderDistortionOverlay(renderer.distortionIntensity());
            renderSanityEffects();   // M34: vignette + flicker
            renderHud();             // M31: crosshair + HP bar + pips + relic
            renderInventoryOverlay();
            if (zoneLoading) renderZoneLoadingOverlay();
            if (worldMenuOpen) renderWorldMenuOverlay();
            if (optionsMenuOpen) renderOptionsMenuOverlay();
            if (inventoryOpen) renderInventoryCraftOverlay();
            if (pauseMenuOpen) renderPauseMenuOverlay();
            if (controlsMenuOpen) renderControlsMenuOverlay();
            if (recipeBookOpen) renderRecipeBook(); // M188
            if (deathScreenActive) renderDeathScreen();
            if (escapeActive)      renderEscapeScreen();
            if (endingScreenActive) renderEndingScreen();

            // M156: Subtle psychological horror rendering
            if (renderer != null && !worldMenuOpen) {
                float edgeA = renderer.flashEdgeAlpha();
                if (edgeA > 0f) {
                    // Dark peripheral flash — shadow flickers at screen edge
                    int edgeW = width / 6;
                    if (renderer.flashEdgeLeft())
                        fillRect(0, 0, edgeW, height, 0.01f, 0.01f, 0.02f, edgeA * 0.80f);
                    else
                        fillRect(width - edgeW, 0, edgeW, height, 0.01f, 0.01f, 0.02f, edgeA * 0.80f);
                }
            }
            // M159: jumpscare overlays — render last so they cover everything
            renderJumpscareOverlays();
            if (infoOverlayVisible) renderInfoOverlay();
            // M151: reuse hit from frame start — camera/world unchanged within same frame
            if (hit != null) renderer.setTargetOutline(true,  hit.x(), hit.y(), hit.z());
            else             renderer.setTargetOutline(false, 0, 0, 0);

            String biomeTxt = "BIOME " + noctfield.world.ChunkGenerator.biomeName(currentBiome) +
                    String.format(" mvx%.2f sanx%.2f aud:%s lamp:%s ward:%.0fs temp:%d", currentMoveMul, currentSanityMul, biomeAudioLabel, renderer.lanternPresetName(), wardTimer, tempStructures.size());
            String overlay = biomeTxt;
            if (worldMenuOpen && !worlds.isEmpty()) {
                WorldProfile sel = worlds.get(worldMenuIndex);
                overlay = biomeTxt + " | WORLD MENU [UP/DOWN + ENTER] -> " + sel.id() + " (seed " + sel.seed() + ")";
            }
            if (optionsMenuOpen) {
                overlay = biomeTxt + " | OPTIONS MENU [UP/DOWN + LEFT/RIGHT]";
            }
            if (pauseMenuOpen) {
                overlay = biomeTxt + " | PAUSE MENU [UP/DOWN + ENTER]";
            }
            if (inventoryOpen) {
                overlay = biomeTxt + " | INVENTORY [MC style drag/drop + 2x2 craft]";
            }
            debugHud.render(window, camera, renderer, hit, selectedPlaceBlock, inv(selectedPlaceBlock), health, overlay, dt);

            // M100: audio debug overlay (ALT+A toggle)
            if (audioDebugOverlay && audio != null) {
                String[] lines = audio.debugStateLines();
                int ax = width - 185;
                int ay = 8;
                // show mute hint / status below the panel
                String muteLabel = audio.isAllSourcesMuted() ? "[ALL MUTED]"
                        : audio.isCaveAmbientMuted() ? "[AMB MUTED]"
                        : "ALT+Z=AMB  ALT+S=ALL";
                drawText(ax, ay + lines.length * 11 + 10, muteLabel, 1,
                        audio.isAllSourcesMuted() ? 1.0f : audio.isCaveAmbientMuted() ? 1.0f : 0.6f,
                        audio.isAllSourcesMuted() ? 0.2f : audio.isCaveAmbientMuted() ? 0.4f : 0.6f,
                        0.2f, 0.85f);
                fillRect(ax - 4, ay - 4, 182, lines.length * 11 + 8, 0f, 0f, 0f, 0.72f);
                for (int li = 0; li < lines.length; li++) {
                    if (lines[li] == null) continue;
                    // Highlight PLAYING sources in green, others in grey
                    boolean playing = lines[li].contains("PLAY") && !lines[li].contains("STOP");
                    drawText(ax, ay + li * 11, lines[li], 1,
                            playing ? 0.40f : 0.55f,
                            playing ? 0.95f : 0.65f,
                            playing ? 0.40f : 0.55f, 0.95f);
                }
            }

            glfwSwapBuffers(window);
            input.endFrame();
        }
    }

    private void renderDistortionOverlay(float intensity) {
        // M24.1: temporarily disabled (white-line sanity effect removed).
        // We'll reintroduce a redesigned post-process later.
    }

    private void fillRect(int x, int y, int w, int h, float r, float g, float b, float a) {
        if (w <= 0 || h <= 0) return;
        glEnable(GL_SCISSOR_TEST);
        glScissor(x, y, w, h);
        glClearColor(r, g, b, a);
        glClear(GL_COLOR_BUFFER_BIT);
        glDisable(GL_SCISSOR_TEST);
    }

    private void drawText(int x, int y, String text, int scale, float r, float g, float b, float a) {
        int cx = x;
        String t = text.toUpperCase();
        for (int i = 0; i < t.length(); i++) {
            char ch = t.charAt(i);
            int[] rows = glyphRows(ch);
            for (int ry = 0; ry < 7; ry++) {
                int bits = rows[ry];
                for (int rx = 0; rx < 5; rx++) {
                    if (((bits >> (4 - rx)) & 1) != 0) {
                        fillRect(cx + rx * scale, y + (6 - ry) * scale, scale, scale, r, g, b, a);
                    }
                }
            }
            cx += 6 * scale;
        }
    }

    private int[] glyphRows(char c) {
        return switch (c) {
            case 'A' -> new int[]{0b01110,0b10001,0b10001,0b11111,0b10001,0b10001,0b10001};
            case 'B' -> new int[]{0b11110,0b10001,0b10001,0b11110,0b10001,0b10001,0b11110};
            case 'C' -> new int[]{0b01110,0b10001,0b10000,0b10000,0b10000,0b10001,0b01110};
            case 'D' -> new int[]{0b11110,0b10001,0b10001,0b10001,0b10001,0b10001,0b11110};
            case 'E' -> new int[]{0b11111,0b10000,0b10000,0b11110,0b10000,0b10000,0b11111};
            case 'F' -> new int[]{0b11111,0b10000,0b10000,0b11110,0b10000,0b10000,0b10000};
            case 'G' -> new int[]{0b01110,0b10001,0b10000,0b10111,0b10001,0b10001,0b01110};
            case 'H' -> new int[]{0b10001,0b10001,0b10001,0b11111,0b10001,0b10001,0b10001};
            case 'I' -> new int[]{0b11111,0b00100,0b00100,0b00100,0b00100,0b00100,0b11111};
            case 'J' -> new int[]{0b00111,0b00010,0b00010,0b00010,0b10010,0b10010,0b01100};
            case 'K' -> new int[]{0b10001,0b10010,0b10100,0b11000,0b10100,0b10010,0b10001};
            case 'L' -> new int[]{0b10000,0b10000,0b10000,0b10000,0b10000,0b10000,0b11111};
            case 'M' -> new int[]{0b10001,0b11011,0b10101,0b10101,0b10001,0b10001,0b10001};
            case 'N' -> new int[]{0b10001,0b11001,0b10101,0b10011,0b10001,0b10001,0b10001};
            case 'O' -> new int[]{0b01110,0b10001,0b10001,0b10001,0b10001,0b10001,0b01110};
            case 'P' -> new int[]{0b11110,0b10001,0b10001,0b11110,0b10000,0b10000,0b10000};
            case 'Q' -> new int[]{0b01110,0b10001,0b10001,0b10001,0b10101,0b10010,0b01101};
            case 'R' -> new int[]{0b11110,0b10001,0b10001,0b11110,0b10100,0b10010,0b10001};
            case 'S' -> new int[]{0b01111,0b10000,0b10000,0b01110,0b00001,0b00001,0b11110};
            case 'T' -> new int[]{0b11111,0b00100,0b00100,0b00100,0b00100,0b00100,0b00100};
            case 'U' -> new int[]{0b10001,0b10001,0b10001,0b10001,0b10001,0b10001,0b01110};
            case 'V' -> new int[]{0b10001,0b10001,0b10001,0b10001,0b10001,0b01010,0b00100};
            case 'W' -> new int[]{0b10001,0b10001,0b10001,0b10101,0b10101,0b10101,0b01010};
            case 'X' -> new int[]{0b10001,0b10001,0b01010,0b00100,0b01010,0b10001,0b10001};
            case 'Y' -> new int[]{0b10001,0b10001,0b01010,0b00100,0b00100,0b00100,0b00100};
            case 'Z' -> new int[]{0b11111,0b00001,0b00010,0b00100,0b01000,0b10000,0b11111};
            case '0' -> new int[]{0b01110,0b10001,0b10011,0b10101,0b11001,0b10001,0b01110};
            case '1' -> new int[]{0b00100,0b01100,0b00100,0b00100,0b00100,0b00100,0b01110};
            case '2' -> new int[]{0b01110,0b10001,0b00001,0b00010,0b00100,0b01000,0b11111};
            case '3' -> new int[]{0b11110,0b00001,0b00001,0b00110,0b00001,0b00001,0b11110};
            case '4' -> new int[]{0b00010,0b00110,0b01010,0b10010,0b11111,0b00010,0b00010};
            case '5' -> new int[]{0b11111,0b10000,0b10000,0b11110,0b00001,0b00001,0b11110};
            case '6' -> new int[]{0b01110,0b10000,0b10000,0b11110,0b10001,0b10001,0b01110};
            case '7' -> new int[]{0b11111,0b00001,0b00010,0b00100,0b01000,0b01000,0b01000};
            case '8' -> new int[]{0b01110,0b10001,0b10001,0b01110,0b10001,0b10001,0b01110};
            case '9' -> new int[]{0b01110,0b10001,0b10001,0b01111,0b00001,0b00001,0b01110};
            case '-' -> new int[]{0,0,0,0b11111,0,0,0};
            case '_' -> new int[]{0,0,0,0,0,0,0b11111};
            case ':' -> new int[]{0,0b00100,0,0,0b00100,0,0};
            case '(' -> new int[]{0b00010,0b00100,0b01000,0b01000,0b01000,0b00100,0b00010};
            case ')' -> new int[]{0b01000,0b00100,0b00010,0b00010,0b00010,0b00100,0b01000};
            case '>' -> new int[]{0b10000,0b01000,0b00100,0b00010,0b00100,0b01000,0b10000};
            case '+' -> new int[]{0,0b00100,0b00100,0b11111,0b00100,0b00100,0};
            case '.' -> new int[]{0,0,0,0,0,0b00100,0b00100};
            case '/' -> new int[]{0b00001,0b00010,0b00100,0b00100,0b01000,0b10000,0b10000};
            case '!' -> new int[]{0b00100,0b00100,0b00100,0b00100,0b00100,0b00000,0b00100};
            case ' ' -> new int[]{0,0,0,0,0,0,0};
            default -> new int[]{0b11111,0b10001,0b00110,0b00110,0b00110,0b10001,0b11111};
        };
    }

    private void renderInventoryOverlay() {
        // Compact status panel — M191: scale=2 for legibility
        int panelW = 300;
        int panelH = 88;
        int x = 14;
        int y = 14;
        fillRect(x, y, panelW, panelH, 0.05f, 0.07f, 0.10f, 0.88f);
        fillRect(x, y + panelH - 2, panelW, 2, 0.24f, 0.46f, 0.32f, 0.70f); // top accent bar
        drawText(x + 10, y + 60, "HP " + health + (godMode ? "  [GOD]" : ""), 2,
                godMode ? 1.0f : 0.90f, godMode ? 0.85f : 0.98f, godMode ? 0.10f : 0.92f, 1f);
        drawText(x + 10, y + 36, "SEL " + BlockId.nameOf(selectedPlaceBlock) + " X" + inv(selectedPlaceBlock), 2, 0.82f, 0.93f, 0.86f, 1f);
        String toolTxt = (equippedTool == BlockId.AIR)
                ? "TOOL NONE (PRESS G)"
                : ("TOOL " + BlockId.nameOf(equippedTool) + " " + toolDurability + "/" + toolDurabilityMax);
        drawText(x + 10, y + 12, toolTxt, 2, 0.82f, 0.88f, 0.95f, 0.95f);

        // HUD hotbar - MC-style square slots matching the inventory UI
        int hsz  = 40;   // slot size
        int hgap = 4;    // gap between slots
        int total = hsz * HOTBAR.length + hgap * (HOTBAR.length - 1);
        int sx = (width - total) / 2;
        int sy = 14;

        // Panel backing behind all slots
        fillRect(sx - 4, sy - 4, total + 8, hsz + 8, 0.20f, 0.20f, 0.20f, 0.82f);

        for (int i = 0; i < HOTBAR.length; i++) {
            int rx = sx + i * (hsz + hgap);
            int ry = sy;
            // Read directly from slot arrays; highlight by slot index
            boolean sel = (i == selectedHotbarSlot);

            // Slot fill + bevel
            fillRect(rx, ry, hsz, hsz, 0.55f, 0.55f, 0.55f, 1f);
            fillRect(rx,           ry + hsz - 1, hsz, 1, 0.18f, 0.18f, 0.18f, 1f);
            fillRect(rx,           ry,            1,  hsz, 0.18f, 0.18f, 0.18f, 1f);
            fillRect(rx,           ry,            hsz, 1, 0.95f, 0.95f, 0.95f, 1f);
            fillRect(rx + hsz - 1, ry,            1,  hsz, 0.95f, 0.95f, 0.95f, 1f);
            if (sel) fillRect(rx + 1, ry + 1, hsz - 2, hsz - 2, 0.70f, 0.70f, 0.70f, 1f);

            // Block icon + count from slot
            if (slotCount[i] > 0) {
                drawBlockIcon(rx + 10, ry + 10, 20, slotItem[i]);
                if (slotCount[i] > 1) {
                    String cs = "" + slotCount[i];
                    drawText(rx + hsz - 1 - cs.length() * 6, ry + 3, cs, 1, 0.98f, 0.98f, 0.98f, 1f);
                    drawText(rx + hsz - 2 - cs.length() * 6, ry + 2, cs, 1, 0.12f, 0.12f, 0.12f, 1f);
                }
            }

            // Selection underline
            if (sel) fillRect(rx, ry + hsz, hsz, 3, 0.88f, 0.95f, 0.88f, 1f);
        }
    }

    private void renderWorldMenuOverlay() {
        // Full-screen dark background — matches title screen aesthetic
        fillRect(0, 0, width, height, 0.01f, 0.01f, 0.02f, 1f);

        // Hollow watermark top-right
        String watermark = "HOLLOW";
        int ww = watermark.length() * 6;
        drawText(width - ww - 16, height - 22, watermark, 1, 0.22f, 0.34f, 0.26f, 1f);

        // Title
        String titleStr = "SELECT WORLD";
        int titleScale = 3;
        int titleW = titleStr.length() * 6 * titleScale;
        drawText((width - titleW) / 2, height - 52, titleStr, titleScale, 0.72f, 0.96f, 0.76f, 1f);
        // Thin separator line under title
        fillRect((width - 480) / 2, height - 58, 480, 1, 0.22f, 0.38f, 0.26f, 1f);

        // World cards
        int cardW = Math.min(520, width - 80);
        int cardH = 56;
        int cardGap = 8;
        int n = worlds.size();
        int totalH = n * (cardH + cardGap) - cardGap;
        int cardsTopY = height / 2 + totalH / 2;
        int cardX = (width - cardW) / 2;

        for (int i = 0; i < n; i++) {
            boolean sel = (i == worldMenuIndex);
            WorldProfile w = worlds.get(i);
            int cy = cardsTopY - i * (cardH + cardGap) - cardH;

            // Card background
            if (sel) {
                fillRect(cardX,     cy,     cardW, cardH, 0.07f, 0.13f, 0.09f, 1f);
                fillRect(cardX,     cy,     4,     cardH, 0.42f, 0.92f, 0.50f, 1f); // green accent bar
            } else {
                fillRect(cardX,     cy,     cardW, cardH, 0.04f, 0.05f, 0.05f, 1f);
                fillRect(cardX,     cy,     4,     cardH, 0.18f, 0.28f, 0.20f, 1f); // dim green bar
            }
            // Card border
            fillRect(cardX, cy,          cardW, 1, 0.10f, 0.18f, 0.12f, 1f);
            fillRect(cardX, cy + cardH - 1, cardW, 1, 0.10f, 0.18f, 0.12f, 1f);

            // Slot number — placed near the very top of the card so it never overlaps the name
            String slotLabel = "SLOT " + (i + 1);
            drawText(cardX + 12, cy + cardH - 9, slotLabel, 1,
                    sel ? 0.42f : 0.25f, sel ? 0.72f : 0.36f, sel ? 0.46f : 0.28f, 1f);

            // World name — large, centered vertically in the lower portion of the card
            int nameScale = 2;
            drawText(cardX + 12, cy + cardH / 2 - 10, w.id().toUpperCase(), nameScale,
                    sel ? 0.90f : 0.60f, sel ? 1.00f : 0.74f, sel ? 0.88f : 0.62f, 1f);

            // Seed — small, dim, bottom-left
            String seedStr = "seed  " + w.seed();
            drawText(cardX + 12, cy + 6, seedStr, 1,
                    0.28f, 0.42f, 0.30f, 1f);

            // Arrow cursor + R tooltip for selected card
            if (sel) {
                drawText(cardX + cardW - 20, cy + cardH / 2 - 10, ">", 2, 0.52f, 0.96f, 0.58f, 1f);
                // R = rename tooltip on selected card
                String renTip = "[R] RENAME";
                drawText(cardX + cardW - renTip.length() * 6 - 12, cy + cardH - 9, renTip, 1,
                        0.30f, 0.52f, 0.34f, 1f);
            }
        }

        // Empty state
        if (n == 0) {
            String empty = "NO SAVES   PRESS  N  TO CREATE ONE";
            drawText((width - empty.length() * 6) / 2, height / 2, empty, 1, 0.40f, 0.60f, 0.44f, 1f);
        }

        // Rename input box (shown on top when active)
        if (renamingWorld) {
            int bW = Math.min(460, width - 80);
            int bH = 48;
            int bX = (width - bW) / 2;
            int bY = height / 2 - bH / 2;
            fillRect(0, 0, width, height, 0.0f, 0.0f, 0.0f, 0.55f);
            fillRect(bX, bY, bW, bH, 0.05f, 0.09f, 0.06f, 1f);
            fillRect(bX, bY, bW, 1, 0.38f, 0.72f, 0.44f, 1f);
            fillRect(bX, bY + bH - 1, bW, 1, 0.38f, 0.72f, 0.44f, 1f);
            fillRect(bX, bY, 1, bH, 0.38f, 0.72f, 0.44f, 1f);
            fillRect(bX + bW - 1, bY, 1, bH, 0.38f, 0.72f, 0.44f, 1f);
            drawText(bX + 10, bY + bH - 14, "RENAME SAVE", 1, 0.30f, 0.60f, 0.36f, 1f);
            String buf = renameBuffer.toString() + "_";
            drawText(bX + 10, bY + bH / 2 - 6, buf, 2, 0.88f, 1.00f, 0.88f, 1f);
            drawText(bX + 10, bY + 6, "ENTER=confirm   ESC=cancel   (letters, numbers, - _)", 1, 0.28f, 0.48f, 0.32f, 1f);
        }

        // Key hint bar at bottom
        fillRect(0, 0, width, 28, 0.02f, 0.03f, 0.02f, 1f);
        fillRect(0, 28, width, 1, 0.12f, 0.20f, 0.14f, 1f);
        String hint = renamingWorld
            ? "TYPE NEW NAME    ENTER=CONFIRM    ESC=CANCEL"
            : "W / S  NAVIGATE    ENTER  LOAD    N  NEW    D  DELETE    R  RENAME    ESC  CLOSE";
        drawText((width - hint.length() * 6) / 2, 8, hint, 1, 0.35f, 0.55f, 0.38f, 1f);
    }

    private void createNewWorld() {
        if (worlds.size() >= 3) return; // M174: max 3 save files
        // M167: find highest existing save-N number to avoid reusing deleted slot IDs
        int maxNum = 0;
        for (WorldProfile wp : worlds) {
            String id = wp.id();
            if (id.startsWith("save-")) {
                try { maxNum = Math.max(maxNum, Integer.parseInt(id.substring(5))); }
                catch (NumberFormatException ignored) {}
            }
        }
        int nextNum = maxNum + 1;
        String name = "save-" + nextNum;
        long seed = new java.util.Random().nextLong();
        worlds.add(new WorldProfile(name, seed));
        worldMenuIndex = worlds.size() - 1;
        saveWorldProfiles();
        System.out.println("[WorldMenu] Created -> " + name + " (seed " + seed + ")");
    }

    private void deleteSelectedWorld() {
        if (worlds.isEmpty()) return;
        WorldProfile removed = worlds.remove(worldMenuIndex);
        if (worldMenuIndex >= worlds.size()) worldMenuIndex = Math.max(0, worlds.size() - 1);
        saveWorldProfiles();
        // M147: delete player save + terrain edits so new world with same name starts clean
        try {
            java.nio.file.Path savePath  = java.nio.file.Path.of("worlds", removed.id() + "-player.txt");
            java.nio.file.Path editsPath = java.nio.file.Path.of("worlds", removed.id() + "-edits.txt");
            java.nio.file.Files.deleteIfExists(savePath);
            java.nio.file.Files.deleteIfExists(editsPath);
        } catch (Exception ignored) {}
        System.out.println("[WorldMenu] Deleted -> " + removed.id());
        // M226: if last world deleted, return to title screen
        if (worlds.isEmpty()) openTitleScreen();
    }

    private void saveWorldProfiles() {
        try {
            java.util.List<String> lines = new java.util.ArrayList<>();
            for (WorldProfile w : worlds) lines.add(w.id() + " " + w.seed());
            java.nio.file.Files.write(java.nio.file.Path.of("worlds.txt"), lines, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            System.err.println("[WorldMenu] Failed to save worlds.txt: " + e.getMessage());
        }
    }


    private void renderTitleScreen(float t) {
        // Top panel: title + tagline
        int topH = height / 3;
        fillRect(0, height - topH, width, topH, 0.01f, 0.01f, 0.02f, 1f);

        // HOLLOW - pulsing pale-green tint, slight red flicker
        float pulse = 0.82f + 0.18f * (float)Math.abs(Math.sin(t * 1.8f));
        float flick = ((int)(t * 17) % 23 == 0) ? 0.55f : 1.0f; // brief random-feel flicker
        float tr = pulse * flick * 0.88f;
        float tg = pulse * flick * 0.98f;
        float tb = pulse * flick * 0.84f;

        int titleScale = 5;
        String title = "HOLLOW";
        int titleW = title.length() * 6 * titleScale;
        drawText((width - titleW) / 2, height - topH + topH / 2 + 4, title, titleScale, tr, tg, tb, 1f);

        // Tagline below title
        String tag = "DO NOT LOOK AT IT DIRECTLY";
        int tagW = tag.length() * 6;
        float tagPulse = 0.50f + 0.15f * (float)Math.sin(t * 2.4f + 1.2f);
        drawText((width - tagW) / 2, height - topH + 14, tag, 1, tagPulse * 0.7f, tagPulse * 0.85f, tagPulse * 0.7f, 1f);

        // Bottom panel: menu
        int botH = height * 2 / 7;
        fillRect(0, 0, width, botH, 0.01f, 0.01f, 0.02f, 1f);

        String[] items = { "PLAY", "QUIT" };
        int itemScale = 2;
        int itemH = 28;
        int totalMenuH = items.length * itemH;
        int menuStartY = botH / 2 + totalMenuH / 2 - itemH;

        for (int i = 0; i < items.length; i++) {
            boolean sel = (i == titleMenuIndex);
            int itemW = items[i].length() * 6 * itemScale;
            int iy = menuStartY - i * itemH;

            if (sel) {
                // Selection highlight bar
                fillRect((width - itemW) / 2 - 14, iy - 2, itemW + 28, itemH - 4, 0.10f, 0.22f, 0.12f, 1f);
                // Cursor arrow
                drawText((width - itemW) / 2 - 12, iy + 4, ">", itemScale, 0.60f, 1.0f, 0.62f, 1f);
            }
            float ir = sel ? 0.95f : 0.62f;
            float ig = sel ? 1.00f : 0.75f;
            float ib = sel ? 0.88f : 0.62f;
            drawText((width - itemW) / 2, iy + 4, items[i], itemScale, ir, ig, ib, 1f);
        }

        // Nav hint
        String hint = "W/S  SELECT    ENTER/SPACE  CONFIRM    ESC  QUIT    F11  FULLSCREEN";
        int hintW = hint.length() * 6;
        drawText((width - hintW) / 2, 10, hint, 1, 0.38f, 0.52f, 0.40f, 1f);

        // M236: version stamp — bottom-right
        String ver = "v0.1 EARLY ACCESS";
        drawText(width - ver.length() * 6 - 10, 26, ver, 1, 0.28f, 0.38f, 0.30f, 0.70f);
    }

    private void renderInventoryCraftOverlay() {
        // World shows through — NO full-screen dim overlay.
        // Two separate panels: bottom inventory + right crafting.

        final int SZ   = UI_SZ;
        final int GAP  = UI_GAP;
        final int MARG = UI_MARG;
        final int HDR  = UI_HDR;

        // ─── CENTER INVENTORY PANEL ───────────────────────────────────────
        int ipW = invPanelW(), ipH = invPanelH(), ipX = invPanelX();
        int ipY = invPanelY();

        fillRect(ipX, ipY, ipW, ipH, 0.78f, 0.78f, 0.78f, 1f);
        fillRect(ipX, ipY + ipH - 2, ipW, 2, 0.95f, 0.95f, 0.95f, 1f);
        fillRect(ipX, ipY, 2, ipH, 0.95f, 0.95f, 0.95f, 1f);
        fillRect(ipX, ipY, ipW, 2, 0.30f, 0.30f, 0.30f, 1f);
        fillRect(ipX + ipW - 2, ipY, 2, ipH, 0.30f, 0.30f, 0.30f, 1f);
        // Header strip at the top of the panel
        fillRect(ipX, ipY + ipH - HDR, ipW, HDR, 0.65f, 0.65f, 0.65f, 1f);
        drawText(ipX + MARG, ipY + ipH - HDR + 4, "Inventory", 1, 0.18f, 0.18f, 0.18f, 1f);

        int slotBaseX = ipX + MARG;
        int hbY       = ipY + UI_HBPAD;          // hotbar: bottom row
        int invBaseY  = hbY + SZ + GAP;           // inv rows start above hotbar

        // Separator line between hotbar and inventory rows
        fillRect(ipX + 4, hbY + SZ + 1, ipW - 8, 2, 0.58f, 0.58f, 0.58f, 1f);

        int hoverSlot = slotAtMouse();

        // Hotbar slots (0..8)
        for (int i = 0; i < 9; i++) {
            int rx = slotBaseX + i * (SZ + GAP);
            int ry = hbY;
            boolean active = (i == selectedHotbarSlot);
            boolean hov = (i == hoverSlot);
            if (active) fillRect(rx - 1, ry - 1, SZ + 2, SZ + 2, 0.35f, 0.35f, 0.35f, 1f);
            fillRect(rx, ry, SZ, SZ, 0.55f, 0.55f, 0.55f, 1f);
            fillRect(rx, ry + SZ - 1, SZ, 1, 0.18f, 0.18f, 0.18f, 1f);
            fillRect(rx, ry, 1, SZ, 0.18f, 0.18f, 0.18f, 1f);
            fillRect(rx, ry, SZ, 1, 0.95f, 0.95f, 0.95f, 1f);
            fillRect(rx + SZ - 1, ry, 1, SZ, 0.95f, 0.95f, 0.95f, 1f);
            if (active || hov) fillRect(rx + 1, ry + 1, SZ - 2, SZ - 2, 0.66f, 0.66f, 0.66f, 1f);
            if (slotCount[i] > 0) {
                drawBlockIcon(rx + (SZ - 20) / 2, ry + (SZ - 20) / 2, 20, slotItem[i]);
                if (slotCount[i] > 1) {
                    String cs = "" + slotCount[i];
                    drawText(rx + SZ - 3 - cs.length() * 6, ry + 3, cs, 1, 0.98f, 0.98f, 0.98f, 1f);
                    drawText(rx + SZ - 2 - cs.length() * 6, ry + 2, cs, 1, 0.12f, 0.12f, 0.12f, 1f);
                }
            }
            if (active) fillRect(rx, ry + SZ, SZ, 3, 0.88f, 0.95f, 0.88f, 1f);
        }

        // Inventory slots (9..26) - 2 rows x 9 cols, row 0 at bottom (invBaseY), row 1 at top
        for (int i = 0; i < 18; i++) {
            int slot = 9 + i;
            int col  = i % 9, row = i / 9;
            int rx   = slotBaseX + col * (SZ + GAP);
            int ry   = invBaseY  + row * (SZ + GAP);
            boolean sel = (slot == invCursor);
            boolean hov = (slot == hoverSlot);
            fillRect(rx, ry, SZ, SZ, 0.55f, 0.55f, 0.55f, 1f);
            fillRect(rx, ry + SZ - 1, SZ, 1, 0.18f, 0.18f, 0.18f, 1f);
            fillRect(rx, ry, 1, SZ, 0.18f, 0.18f, 0.18f, 1f);
            fillRect(rx, ry, SZ, 1, 0.95f, 0.95f, 0.95f, 1f);
            fillRect(rx + SZ - 1, ry, 1, SZ, 0.95f, 0.95f, 0.95f, 1f);
            if (sel || hov) fillRect(rx + 1, ry + 1, SZ - 2, SZ - 2, 0.64f, 0.64f, 0.64f, 1f);
            if (slotCount[slot] > 0) {
                drawBlockIcon(rx + (SZ - 20) / 2, ry + (SZ - 20) / 2, 20, slotItem[slot]);
                String cnt = "" + slotCount[slot];
                drawText(rx + SZ - 3 - cnt.length() * 6, ry + 3, cnt, 1, 0.98f, 0.98f, 0.98f, 1f);
                drawText(rx + SZ - 2 - cnt.length() * 6, ry + 2, cnt, 1, 0.12f, 0.12f, 0.12f, 1f);
            }
        }

        // ─── RIGHT CRAFTING PANEL ──────────────────────────────────────────
        int cols = craftCols(), rows = craftRows(), csz = SZ;
        int cpW = craftPanelW(), cpH = craftPanelH();
        int cpX = craftPanelX(), cpY = craftPanelY();

        fillRect(cpX, cpY, cpW, cpH, 0.78f, 0.78f, 0.78f, 1f);
        fillRect(cpX, cpY + cpH - 2, cpW, 2, 0.95f, 0.95f, 0.95f, 1f);
        fillRect(cpX, cpY, 2, cpH, 0.95f, 0.95f, 0.95f, 1f);
        fillRect(cpX, cpY, cpW, 2, 0.30f, 0.30f, 0.30f, 1f);
        fillRect(cpX + cpW - 2, cpY, 2, cpH, 0.30f, 0.30f, 0.30f, 1f);
        fillRect(cpX, cpY + cpH - HDR, cpW, HDR, 0.65f, 0.65f, 0.65f, 1f);
        String craftLabel = craftingTableOpen ? "Workbench" : "Crafting";
        drawText(cpX + MARG, cpY + cpH - HDR + 4, craftLabel, 1, 0.18f, 0.18f, 0.18f, 1f);

        // Craft grid: cgX/cgY = position of slot [0,0] (top-left cell)
        int cgX = cpX + MARG;
        int cgY = craftCgY();  // Y of row-0 (top row, highest Y value in Y-up)
        int hoverCraft = craftGridSlotAtMouse();
        for (int i = 0; i < cols * rows; i++) {
            int cx = i % cols, cr = i / cols;  // cr=0 is top row
            int rx = cgX + cx * (csz + GAP);
            int ry = cgY - cr * (csz + GAP);   // row 0 at top, rows go downward
            boolean hov = (i == hoverCraft);
            fillRect(rx, ry, csz, csz, 0.55f, 0.55f, 0.55f, 1f);
            fillRect(rx, ry + csz - 1, csz, 1, 0.18f, 0.18f, 0.18f, 1f);
            fillRect(rx, ry, 1, csz, 0.18f, 0.18f, 0.18f, 1f);
            fillRect(rx, ry, csz, 1, 0.95f, 0.95f, 0.95f, 1f);
            fillRect(rx + csz - 1, ry, 1, csz, 0.95f, 0.95f, 0.95f, 1f);
            if (hov) fillRect(rx + 1, ry + 1, csz - 2, csz - 2, 0.64f, 0.64f, 0.64f, 1f);
            if (craftCount[i] > 0) {
                drawBlockIcon(rx + (csz - 20) / 2, ry + (csz - 20) / 2, 20, craftItem[i]);
                String cnt = "" + craftCount[i];
                drawText(rx + csz - 3 - cnt.length() * 6, ry + 3, cnt, 1, 0.98f, 0.98f, 0.98f, 1f);
                drawText(rx + csz - 2 - cnt.length() * 6, ry + 2, cnt, 1, 0.12f, 0.12f, 0.12f, 1f);
            }
        }

        // Arrow → centered on grid
        computeCraftOutput();
        int gridBotY    = cgY - (rows - 1) * (csz + GAP);
        int gridCenterY = (gridBotY + cgY + csz) / 2;
        int arrowX = cgX + craftGridW() + 10;
        fillRect(arrowX,      gridCenterY - 2, 16, 4, 0.30f, 0.30f, 0.30f, 1f);
        fillRect(arrowX + 16, gridCenterY - 5, 4, 10, 0.30f, 0.30f, 0.30f, 1f);

        // Output slot
        int outSz = craftOutSz();
        int outX  = arrowX + 24;
        int outY  = gridCenterY - outSz / 2;
        fillRect(outX, outY, outSz, outSz, 0.55f, 0.55f, 0.55f, 1f);
        fillRect(outX, outY + outSz - 1, outSz, 1, 0.18f, 0.18f, 0.18f, 1f);
        fillRect(outX, outY, 1, outSz, 0.18f, 0.18f, 0.18f, 1f);
        fillRect(outX, outY, outSz, 1, 0.95f, 0.95f, 0.95f, 1f);
        fillRect(outX + outSz - 1, outY, 1, outSz, 0.95f, 0.95f, 0.95f, 1f);
        if (craftOutItem != BlockId.AIR) {
            int osz = outSz * 2 / 3;
            drawBlockIcon(outX + (outSz - osz) / 2, outY + (outSz - osz) / 2, osz, craftOutItem);
            if (craftOutCount > 1) drawText(outX + outSz - 10, outY + 2, "" + craftOutCount, 1, 0.95f, 1.0f, 0.95f, 1f);
        } else if (craftOutWardSeconds > 0f) {
            drawText(outX + 4, outY + outSz / 2 - 4, "WARD", 1, 0.92f, 0.96f, 0.88f, 1f);
        }

        // ─── HOVER TOOLTIP ─────────────────────────────────────────────────
        if (hoverSlot >= 0 && slotCount[hoverSlot] > 0) {
            String tip = BlockId.nameOf(slotItem[hoverSlot]);
            int tx = (int) input.mouseX() + 12;
            int ty = (int) (height - input.mouseY()) + 12;
            int tw = tip.length() * 6 + 10;
            fillRect(tx - 1, ty - 1, tw + 2, 18, 0.22f, 0.10f, 0.36f, 1f);
            fillRect(tx, ty, tw, 16, 0.06f, 0.02f, 0.10f, 1f);
            drawText(tx + 5, ty + 4, tip, 1, 0.95f, 0.95f, 0.98f, 1f);
        }

        // ─── HINT BAR (floats just above inventory panel) ──────────────────
        String help = "I/ESC CLOSE  LMB PICK/DROP/DRAG  RMB PLACE-1/HALF  SHIFT+LMB MOVE";
        int hx = ipX + 6, hy = ipY + ipH + 4;
        fillRect(hx - 2, hy - 2, help.length() * 6 + 10, 14, 0.06f, 0.06f, 0.06f, 0.88f);
        drawText(hx, hy, help, 1, 0.82f, 0.82f, 0.82f, 1f);

        // ─── HELD ITEM AT CURSOR ───────────────────────────────────────────
        if (heldCount > 0) {
            int mx = (int) input.mouseX() + 8;
            int my = (int) (height - input.mouseY()) - 8;
            drawBlockIcon(mx, my, 20, heldItem);
            String cnt = "" + heldCount;
            drawText(mx + 20 - cnt.length() * 6, my + 2, cnt, 1, 0.98f, 1.0f, 0.96f, 1f);
        }
    }

    private float[] blockTint(byte id) {
        return switch (id) {
            case BlockId.GRASS -> new float[]{0.30f, 0.62f, 0.26f};
            case BlockId.DIRT -> new float[]{0.44f, 0.29f, 0.18f};
            case BlockId.STONE -> new float[]{0.55f, 0.57f, 0.60f};
            case BlockId.COAL     -> new float[]{0.22f, 0.22f, 0.26f};
            case BlockId.RAW_PORK      -> new float[]{0.88f, 0.46f, 0.40f};
            case BlockId.RAW_CHICKEN   -> new float[]{0.94f, 0.90f, 0.82f}; // M192: white feather burst
            case BlockId.CAVE_MUSHROOM -> new float[]{0.48f, 0.72f, 0.42f};
            case BlockId.LANTERN -> new float[]{0.95f, 0.78f, 0.25f};
            case BlockId.WOOD -> new float[]{0.58f, 0.40f, 0.22f};
            case BlockId.LEAVES -> new float[]{0.22f, 0.56f, 0.24f};
            case BlockId.MUD -> new float[]{0.28f, 0.22f, 0.16f};
            case BlockId.RELIC -> new float[]{0.82f, 0.66f, 0.20f};
            case BlockId.JOURNAL -> new float[]{0.72f, 0.70f, 0.62f};
            case BlockId.CAMPFIRE -> new float[]{0.88f, 0.44f, 0.18f};
            case BlockId.BONES -> new float[]{0.86f, 0.84f, 0.76f};
            case BlockId.COBWEB -> new float[]{0.86f, 0.86f, 0.92f};
            case BlockId.TOOL_WOOD_PICK -> new float[]{0.62f, 0.45f, 0.28f};
            case BlockId.TOOL_STONE_PICK -> new float[]{0.58f, 0.60f, 0.64f};
            case BlockId.TOOL_CRYSTAL_PICK -> new float[]{0.42f, 0.88f, 0.92f};
            case BlockId.TOOL_TORCH -> new float[]{0.96f, 0.74f, 0.24f};
            case BlockId.CRAFTING_TABLE -> new float[]{0.52f, 0.34f, 0.20f};
            case BlockId.TORCH_STAND, BlockId.TORCH_WALL -> new float[]{0.96f, 0.74f, 0.24f};
            case BlockId.WOOD_PLANK    -> new float[]{0.82f, 0.60f, 0.32f};
            case BlockId.TOOL_WOOD_AXE -> new float[]{0.72f, 0.50f, 0.28f};
            case BlockId.DOOR_CLOSED, BlockId.DOOR_OPEN -> new float[]{0.62f, 0.44f, 0.24f};
            default -> new float[]{0.65f, 0.65f, 0.65f};
        };
    }

    private void drawBlockIcon(int x, int y, int s, byte id) {
        float[] c = blockTint(id);
        if (BlockId.isTool(id)) {
            if (id == BlockId.TOOL_TORCH) { drawTorchIcon(x, y, s); return; }
            if (id == BlockId.TOOL_WOOD_AXE) {
                // Axe glyph: diagonal handle + wide blade on upper-right
                int shaftW = Math.max(2, s / 7);
                int shaftH = Math.max(8, s - 6);
                fillRect(x + s / 2 - shaftW / 2, y + 4, shaftW, shaftH, 0.45f, 0.30f, 0.18f, 1f);
                int bW = Math.max(8, s * 2 / 3), bH = Math.max(5, s * 5 / 12);
                fillRect(x + s - bW - 1, y + s - bH - 4, bW, bH, c[0], c[1], c[2], 1f);
                fillRect(x + s - bW - 1, y + s - bH - 2, bW, 2, c[0] * 0.55f, c[1] * 0.55f, c[2] * 0.55f, 1f);
                return;
            }
            // Pickaxe glyph
            int shaftW = Math.max(2, s / 7);
            int shaftH = Math.max(8, s - 8);
            int sx = x + s / 2 - shaftW / 2;
            int sy = y + 3;
            fillRect(sx, sy, shaftW, shaftH, 0.45f, 0.30f, 0.18f, 1f);
            int headW = Math.max(8, s - 6);
            int headH = Math.max(3, s / 5);
            fillRect(x + 3, y + s - headH - 4, headW, headH, c[0], c[1], c[2], 1f);
            fillRect(x + 3, y + s - headH - 2, headW, 2, c[0] * 0.55f, c[1] * 0.55f, c[2] * 0.55f, 1f);
            return;
        }
        // Door icon: door silhouette with planks + knob
        if (id == BlockId.DOOR_CLOSED || id == BlockId.DOOR_OPEN) {
            int dw = Math.max(4, s * 2 / 3);
            int dx2 = x + (s - dw) / 2;
            fillRect(dx2, y, dw, s, c[0] * 0.55f, c[1] * 0.55f, c[2] * 0.55f, 1f);        // door bg
            fillRect(dx2, y, dw, s, c[0], c[1], c[2], 1f);
            int gh = Math.max(1, s / 8);  // plank gap height
            for (int gi = 1; gi < 4; gi++) {
                int gy = y + gi * s / 4;
                fillRect(dx2, gy, dw, gh, c[0] * 0.55f, c[1] * 0.55f, c[2] * 0.55f, 1f);
            }
            // Rail lines
            fillRect(dx2, y + s / 3, dw, Math.max(1, gh / 2), c[0] * 0.45f, c[1] * 0.45f, c[2] * 0.45f, 1f);
            fillRect(dx2, y + 2 * s / 3, dw, Math.max(1, gh / 2), c[0] * 0.45f, c[1] * 0.45f, c[2] * 0.45f, 1f);
            // Knob
            fillRect(dx2 + dw - 3, y + s / 2 - 1, 2, 2, 0.88f, 0.72f, 0.30f, 1f);
            return;
        }
        // simple MC-like shaded block swatch
        fillRect(x, y, s, s, c[0] * 0.65f, c[1] * 0.65f, c[2] * 0.65f, 1f);               // shadow/base
        fillRect(x + 2, y + s - 6, s - 4, 4, Math.min(1f, c[0] * 1.15f), Math.min(1f, c[1] * 1.15f), Math.min(1f, c[2] * 1.15f), 1f); // top edge
        fillRect(x + 2, y + 2, s - 4, s - 8, c[0], c[1], c[2], 1f);                        // face
        fillRect(x + s - 3, y + 2, 2, s - 2, c[0] * 0.55f, c[1] * 0.55f, c[2] * 0.55f, 1f); // right edge
    }

    private void drawTorchIcon(int x, int y, int s) {
        // Minecraft-inspired torch silhouette: thin shaft + bright flame cap.
        int shaftW = Math.max(2, s / 8);
        int shaftH = Math.max(10, s - 10);
        int sx = x + s / 2 - shaftW / 2;
        int sy = y + 2;
        fillRect(sx, sy, shaftW, shaftH, 0.48f, 0.30f, 0.16f, 1f);
        fillRect(sx - 2, sy + shaftH - 1, shaftW + 4, 2, 0.28f, 0.16f, 0.08f, 1f);
        int fx = x + s / 2 - 3;
        int fy = y + s - 7;
        fillRect(fx, fy, 6, 4, 0.98f, 0.82f, 0.24f, 1f);
        fillRect(fx + 1, fy + 1, 4, 3, 1.00f, 0.94f, 0.62f, 1f);
    }

    private void drawBlockIcon3D(int x, int y, int s, byte id) {
        float[] c = blockTint(id);
        int depth = Math.max(5, s / 5);

        // Front face
        fillRect(x, y, s, s, c[0] * 0.62f, c[1] * 0.62f, c[2] * 0.62f, 1f);
        fillRect(x + 2, y + 2, s - 4, s - 4, c[0], c[1], c[2], 1f);

        // Top face (fake perspective cap)
        fillRect(x + depth, y + s - depth, s - depth, depth,
                Math.min(1f, c[0] * 1.20f), Math.min(1f, c[1] * 1.20f), Math.min(1f, c[2] * 1.20f), 1f);

        // Right face
        fillRect(x + s - depth, y, depth, s,
                c[0] * 0.48f, c[1] * 0.48f, c[2] * 0.48f, 1f);

        // Edge highlights
        fillRect(x, y + s - 1, s, 1, 0.95f, 0.95f, 0.95f, 0.75f);
        fillRect(x + s - 1, y, 1, s, 0.18f, 0.18f, 0.18f, 0.80f);
    }

    private void spawnHitParticles(byte blockId) {
        float[] c = blockTint(blockId);
        int add = 8;
        for (int i = 0; i < add && hitPartCount < HIT_PART_MAX; i++) {
            int idx = hitPartCount++;
            hitParts[idx][0] = width * 0.5f + (fxRng.nextFloat() - 0.5f) * 20f;
            hitParts[idx][1] = height * 0.5f + (fxRng.nextFloat() - 0.5f) * 20f;
            hitParts[idx][2] = (fxRng.nextFloat() - 0.5f) * 90f;
            hitParts[idx][3] = 20f + fxRng.nextFloat() * 90f;
            hitParts[idx][4] = c[0];
            hitParts[idx][5] = c[1];
            hitParts[idx][6] = 0.18f + fxRng.nextFloat() * 0.22f;
        }
    }

    private void updateHitParticles(float dt) {
        int i = 0;
        while (i < hitPartCount) {
            hitParts[i][0] += hitParts[i][2] * dt;
            hitParts[i][1] += hitParts[i][3] * dt;
            hitParts[i][3] -= 260f * dt;
            hitParts[i][6] -= dt;
            if (hitParts[i][6] <= 0f) {
                hitPartCount--;
                if (i < hitPartCount) System.arraycopy(hitParts[hitPartCount], 0, hitParts[i], 0, 7);
            } else i++;
        }
    }

    private void renderHitParticles() {
        for (int i = 0; i < hitPartCount; i++) {
            float ttl = hitParts[i][6];
            float a = Math.min(1f, ttl * 4f);
            int px = (int)hitParts[i][0];
            int py = (int)hitParts[i][1];
            fillRect(px, py, 3, 3, hitParts[i][4], hitParts[i][5], 0.10f, a);
        }
    }

    private void renderHeldBlockHand() {
        if (glfwGetInputMode(window, GLFW_CURSOR) != GLFW_CURSOR_DISABLED) return;

        byte handItem = (equippedTool != BlockId.AIR) ? equippedTool : selectedPlaceBlock;
        if (handItem == BlockId.AIR) return;

        float bob = (float)Math.sin(handBobTime * 0.9f) * 6.0f;
        float swing01 = (float)Math.sin(Math.min(1f, handSwing) * Math.PI);
        float swingX = swing01 * 28.0f;
        float swingY = swing01 * 11.0f;
        float equipY = (1f - equipAnim) * 28f;

        int size = BlockId.isTool(handItem) ? 54 : 50;
        int x = width - 126 + (int)(swingX * 0.65f);
        int y = 36 + (int)(bob - swingY * 0.35f + equipY);

        // hand model layers (wrist + palm silhouette)
        fillRect(x - 26, y - 6, 78, 24, 0.22f, 0.19f, 0.16f, 0.62f);
        fillRect(x - 18, y - 2, 68, 62, 0.30f, 0.26f, 0.22f, 0.76f);
        fillRect(x - 8, y + 4, 56, 52, 0.36f, 0.31f, 0.26f, 0.84f);

        // held item transform polish: tools sit angled and travel farther on swing.
        int itemX = x;
        int itemY = y;
        if (BlockId.isTool(handItem)) {
            itemX += (int)(swingX * 0.35f) + 4;
            itemY += (int)(swingY * 0.55f) + 2;
            if (handItem == BlockId.TOOL_TORCH) {
                // Minecraft-like torch proportions inspired by block model stem (2x10 on 16 grid).
                int h = size;
                int stemW = Math.max(3, h / 8);
                int stemH = Math.max(18, (int)(h * (10f / 16f)));
                int sx = itemX + h / 2 - stemW / 2;
                int sy = itemY + 6;
                fillRect(sx, sy, stemW, stemH, 0.52f, 0.34f, 0.18f, 1f);
                fillRect(sx - 1, sy + stemH - 2, stemW + 2, 2, 0.30f, 0.18f, 0.10f, 1f);
                int fx = sx - 3;
                int fy = sy + stemH - 2;
                fillRect(fx, fy, stemW + 6, 6, 0.96f, 0.72f, 0.20f, 1f);
                fillRect(fx + 1, fy + 2, stemW + 4, 4, 1.00f, 0.92f, 0.62f, 1f);
            } else {
                drawBlockIcon(itemX, itemY, size, handItem);
            }
        } else {
            drawBlockIcon3D(itemX, itemY, size, handItem);
        }

        String tt = switch (currentToolTier()) {
            case HAND -> "HAND";
            case WOOD -> "WOOD";
            case STONE -> "STONE";
            case CRYSTAL -> "CRYSTL";
        };
        drawText(x - 14, y - 12, tt, 1, 0.88f, 0.92f, 0.88f, 0.78f);
    }

    // M188 / M236: recipe reference panel — opened with R key
    private void renderRecipeBook() {
        int panelW = Math.min(760, width - 40);
        int panelH = height - 50;
        int headerH = 32;
        int px = (width - panelW) / 2, py = 25;

        // Background
        fillRect(0, 0, width, height, 0.02f, 0.02f, 0.03f, 0.60f);
        fillRect(px, py, panelW, panelH, 0.05f, 0.07f, 0.09f, 0.97f);

        // Header bar
        fillRect(px, py + panelH - headerH, panelW, headerH, 0.10f, 0.16f, 0.20f, 1f);
        drawText(px + 12, py + panelH - headerH + 9, "RECIPE BOOK", 2, 0.85f, 0.96f, 0.88f, 1f);
        drawText(px + panelW - 88, py + panelH - headerH + 10, "R / ESC CLOSE", 1, 0.45f, 0.58f, 0.48f, 0.85f);

        // Separate into two lists: inventory vs table
        java.util.List<Recipe> inv   = new java.util.ArrayList<>();
        java.util.List<Recipe> table = new java.util.ArrayList<>();
        for (Recipe r : RECIPES) (r.needsTable ? table : inv).add(r);

        int colW   = panelW / 2 - 8;
        int leftX  = px + 10;
        int rightX = px + panelW / 2 + 4;
        int entryH = 30; // each entry: name line + ingredient line
        int contentTop = py + panelH - headerH - 8;

        // Column headers
        int catY = contentTop - 16;
        fillRect(leftX,  catY - 2, colW, 14, 0.08f, 0.14f, 0.10f, 0.90f);
        fillRect(rightX, catY - 2, colW, 14, 0.14f, 0.10f, 0.08f, 0.90f);
        drawText(leftX  + 4, catY, "INVENTORY  (2x2 no table)", 1, 0.55f, 0.82f, 0.58f, 1f);
        drawText(rightX + 4, catY, "CRAFTING TABLE  (3x3)", 1, 0.90f, 0.68f, 0.38f, 1f);
        fillRect(leftX,  catY - 4,  colW, 1, 0.28f, 0.44f, 0.32f, 0.60f);
        fillRect(rightX, catY - 4,  colW, 1, 0.44f, 0.28f, 0.20f, 0.60f);

        // Draw a list: each entry is name on top, ingredients on bottom
        for (int pass = 0; pass < 2; pass++) {
            java.util.List<Recipe> list = (pass == 0) ? inv : table;
            int cx = (pass == 0) ? leftX : rightX;
            boolean isTable = (pass == 1);
            int startY = catY - 6;

            for (int i = 0; i < list.size(); i++) {
                Recipe r = list.get(i);
                int ry = startY - (i + 1) * entryH;
                if (ry < py + 14) break; // clipped

                // Name + output
                String outStr;
                if (r.kind == CraftKind.WARD) {
                    outStr = r.name + "  [WARD " + (int)r.wardSeconds + "s]";
                } else {
                    outStr = r.name + (r.outCount > 1 ? "  x" + r.outCount : "");
                }
                float nr = isTable ? 0.96f : 0.86f;
                float ng = isTable ? 0.74f : 0.94f;
                float nb = isTable ? 0.50f : 0.80f;
                drawText(cx + 2, ry + 16, outStr, 1, nr, ng, nb, 0.98f);

                // Ingredients
                java.util.LinkedHashMap<Byte, Integer> counts = new java.util.LinkedHashMap<>();
                for (byte[] sr : r.shape) for (byte b : sr)
                    if (b != BlockId.AIR) counts.merge(b, 1, Integer::sum);
                StringBuilder sb = new StringBuilder();
                for (var e : counts.entrySet()) {
                    if (sb.length() > 0) sb.append(" + ");
                    if (e.getValue() > 1) sb.append(e.getValue()).append("x ");
                    String bn = BlockId.nameOf(e.getKey());
                    sb.append(bn.length() > 10 ? bn.substring(0, 10) : bn);
                }
                drawText(cx + 4, ry + 4, sb.toString(), 1, 0.46f, 0.52f, 0.48f, 0.80f);

                // Thin divider
                fillRect(cx, ry, colW - 4, 1, 0.15f, 0.22f, 0.18f, 0.50f);
            }
        }
        // Center divider
        fillRect(px + panelW / 2 - 1, py + 14, 2, panelH - headerH - 20, 0.18f, 0.26f, 0.22f, 0.55f);
    }

    private void renderPauseMenuOverlay() {
        int panelW = Math.max(360, width / 3);
        int rowH = 32;
        int headerH = 36;
        int rows = 6; // M187: added NEW WORLD entry
        int panelH = headerH + 16 + rows * rowH + 20;

        int x = (width - panelW) / 2;
        int y = (height - panelH) / 2;

        fillRect(0, 0, width, height, 0.02f, 0.02f, 0.03f, 0.50f);
        fillRect(x, y, panelW, panelH, 0.06f, 0.08f, 0.10f, 0.94f);
        fillRect(x, y + panelH - headerH, panelW, headerH, 0.12f, 0.18f, 0.22f, 0.98f);
        drawText(x + 14, y + panelH - headerH + 8, "PAUSED", 2, 0.90f, 0.98f, 0.94f, 1f);

        String[] lines = new String[]{
                "RETURN TO GAME",
                "CONTROLS",
                "OPTIONS",
                "NEW WORLD / SWITCH WORLD",
                "EXIT TO TITLE",
                "QUIT"
        };

        for (int i = 0; i < rows; i++) {
            int ry = y + panelH - headerH - 12 - (i + 1) * rowH;
            boolean sel = (i == pauseMenuIndex);
            if (sel) fillRect(x + 10, ry, panelW - 20, rowH - 4, 0.24f, 0.46f, 0.32f, 0.98f);
            else fillRect(x + 10, ry, panelW - 20, rowH - 4, 0.13f, 0.16f, 0.19f, 0.88f);
            drawText(x + 18, ry + 10, lines[i], 1, sel ? 0.98f : 0.84f, sel ? 1.0f : 0.92f, sel ? 0.95f : 0.88f, 1f);
        }

        drawText(x + 14, y + 8, "UP/DOWN SELECT  ENTER CONFIRM  ESC RESUME", 1, 0.72f, 0.86f, 0.78f, 1f);
    }

    private void renderControlsMenuOverlay() {
        int panelW = Math.max(460, width / 2);
        int rowH   = 22;
        int headerH = 36;
        String[][] entries = {
            {"WASD",              "Move"},
            {"MOUSE",             "Look"},
            {"SPACE",             "Jump"},
            {"LEFT SHIFT",        "Sprint"},
            {"LEFT CLICK (hold)", "Mine / Break block"},
            {"RIGHT CLICK",       "Place block / Open door / Use table"},
            {"E / I",             "Open inventory"},
            {"1-4 / 8-0",         "Select hotbar slot"},
            {"F3",                "Toggle info overlay  (XYZ / biome)"},
            {"F4",                "Cycle to next world"},
            {"F12 / TAB",         "World select screen"},
            {"F5",                "Toggle noclip  (debug)"},
            {"F6-F9",             "Set sanity 0/33/66/100  (debug)"},
            {"F11",               "Spawn THE NUN in front of you  (debug)"},
            {"ESC",               "Pause / close menus"},
        };
        int rows  = entries.length;
        int panelH = headerH + 14 + rows * rowH + 24;
        int x = (width  - panelW) / 2;
        int y = (height - panelH) / 2;

        fillRect(0, 0, width, height, 0.02f, 0.02f, 0.03f, 0.55f);
        fillRect(x, y, panelW, panelH, 0.06f, 0.08f, 0.10f, 0.96f);
        fillRect(x, y + panelH - headerH, panelW, headerH, 0.12f, 0.18f, 0.22f, 0.98f);
        drawText(x + 14, y + panelH - headerH + 8, "CONTROLS", 2, 0.90f, 0.98f, 0.94f, 1f);

        int col1W = panelW / 2 - 10;
        for (int i = 0; i < rows; i++) {
            int ry = y + panelH - headerH - 10 - (i + 1) * rowH;
            boolean alt = (i % 2 == 1);
            fillRect(x + 8, ry, panelW - 16, rowH - 2,
                     alt ? 0.10f : 0.13f, alt ? 0.12f : 0.16f, alt ? 0.14f : 0.19f, 0.85f);
            drawText(x + 16,          ry + 6, entries[i][0], 1, 0.76f, 0.92f, 0.82f, 1f);
            drawText(x + col1W + 12,  ry + 6, entries[i][1], 1, 0.88f, 0.96f, 0.90f, 1f);
        }
        drawText(x + 14, y + 8, "ESC / ENTER  CLOSE", 1, 0.60f, 0.76f, 0.66f, 1f);
    }

    private void renderEscapeScreen() {
        // M153: white fade-in on escape; bright calm contrast to the death screen's black
        float elapsed = ESCAPE_HOLD_SECS - escapeTimer;
        float alpha = Math.min(1f, elapsed / 2.0f); // 2s fade in
        fillRect(0, 0, width, height, 0.95f, 0.95f, 1.0f, alpha * 0.97f);
        if (alpha > 0.5f) {
            float ta = (alpha - 0.5f) / 0.5f;
            String msg = "YOU ESCAPED THE DARK";
            int tw = msg.length() * 14;
            drawText((width - tw) / 2, height / 2 - 22, msg, 2, 0.08f * ta, 0.12f * ta, 0.20f * ta, ta);
            String sub = "the world behind you is silent";
            int sw = sub.length() * 7;
            drawText((width - sw) / 2, height / 2 + 10, sub, 1, 0.30f * ta, 0.35f * ta, 0.45f * ta, ta);
        }
    }

    private void renderDeathScreen() {
        float hold   = DEATH_HOLD_SECS;
        float elapsed = hold - deathTimer;
        // fade in over first 0.6s, hold, fade out over last 0.5s
        float alpha;
        if (elapsed < 0.6f)          alpha = elapsed / 0.6f;
        else if (deathTimer > 0.5f)  alpha = 1.0f;
        else                         alpha = deathTimer / 0.5f;
        alpha = Math.max(0f, Math.min(1f, alpha));

        fillRect(0, 0, width, height, 0.00f, 0.00f, 0.00f, alpha * 0.96f);

        if (alpha > 0.5f) {
            float ta = (alpha - 0.5f) / 0.5f;
            // horror title
            String msg = "YOU ARE GONE";
            int tw = msg.length() * 14;
            drawText((width - tw) / 2, height / 2 - 22, msg, 2, 0.85f * ta, 0.10f * ta, 0.10f * ta, ta);
            // subtitle
            String sub = "darkness takes you";
            int sw = sub.length() * 7;
            drawText((width - sw) / 2, height / 2 + 10, sub, 1, 0.55f * ta, 0.55f * ta, 0.55f * ta, ta);
        }
    }

    private void renderEndingScreen() {
        float t = Math.min(1f, endingScreenTimer / 2.0f);
        float alpha = t * 0.96f;
        fillRect(0, 0, width, height, 0.01f, 0.01f, 0.02f, alpha);
        if (t < 0.4f) return;
        float ta = Math.min(1f, (t - 0.4f) / 0.6f) * 0.96f;
        String title = "YOU ESCAPED";
        drawText((width - title.length() * 14) / 2, height / 2 - 90, title, 2,
                0.70f * ta, 0.95f * ta, 0.74f * ta, ta);
        String[] lore = { "THE VOID GATE HAS CLOSED BEHIND YOU.",
                          "THE WORLD FORGETS YOUR NAME.",
                          "SOMETHING ELSE WILL FIND THE RELICS." };
        for (int i = 0; i < lore.length; i++)
            drawText((width - lore[i].length() * 6) / 2, height / 2 - 40 + i * 18, lore[i], 1,
                    0.55f * ta, 0.72f * ta, 0.58f * ta, ta * 0.85f);
        int mins = (int)(endingPlaytime / 60f), secs = (int)(endingPlaytime % 60f);
        int sx = width / 2 - 120;
        drawText(sx, height / 2 + 20, String.format("TIME SURVIVED   %d:%02d", mins, secs), 1, 0.65f*ta, 0.65f*ta, 0.65f*ta, ta);
        drawText(sx, height / 2 + 36, String.format("RELICS FOUND    %d / %d", endingRelics, RELIC_GOAL), 1, 0.65f*ta, 0.65f*ta, 0.65f*ta, ta);
        drawText(sx, height / 2 + 52, "WORLD           " + endingWorldName.toUpperCase(), 1, 0.65f*ta, 0.65f*ta, 0.65f*ta, ta);
        if (endingScreenTimer > 4f) {
            float blink = (float)Math.sin(endingScreenTimer * 3.5f) * 0.3f + 0.7f;
            String cont = "PRESS ENTER TO CONTINUE";
            drawText((width - cont.length() * 6) / 2, height / 2 + 90, cont, 1,
                    0.40f*blink*ta, 0.65f*blink*ta, 0.44f*blink*ta, ta);
        }
    }

    private void renderInfoOverlay() {
        if (camera == null) return;
        org.joml.Vector3f p = camera.position();
        String worldName  = renderer.worldId();
        String biomeLabel = noctfield.world.ChunkGenerator.biomeName(currentBiome);
        boolean underground = renderer.isUnderground();
        String locStr  = String.format("XYZ  %.1f / %.1f / %.1f", p.x, p.y, p.z);
        String infoStr = "WORLD  " + worldName + "   BIOME  " + biomeLabel
                + (underground ? "   [UNDERGROUND]" : "");

        int lh = 14; // line height
        int pw = Math.max(locStr.length(), infoStr.length()) * 7 + 20;
        int ph = lh * 2 + 14;
        int px = 6, py = 6;
        fillRect(px, py, pw, ph, 0.04f, 0.04f, 0.06f, 0.78f);
        drawText(px + 8, py + 5,      infoStr, 1, 0.72f, 0.90f, 0.78f, 1f);
        drawText(px + 8, py + 5 + lh, locStr,  1, 0.60f, 0.78f, 0.66f, 1f);
    }
    private void renderOptionsMenuOverlay() {
        int panelW = Math.max(420, width / 3);
        int rowH = 28;
        int headerH = 34;
        int rows = 12; // M231: added Dynamic Lighting row
        int panelH = headerH + 12 + rows * rowH + 18;

        int x = (width - panelW) / 2;
        int y = (height - panelH) / 2;

        fillRect(0, 0, width, height, 0.02f, 0.02f, 0.03f, 0.45f);
        fillRect(x, y, panelW, panelH, 0.06f, 0.08f, 0.10f, 0.92f);
        fillRect(x, y + panelH - headerH, panelW, headerH, 0.12f, 0.18f, 0.22f, 0.95f);
        drawText(x + 14, y + panelH - headerH + 8, "OPTIONS", 2, 0.88f, 0.96f, 0.92f, 1f);

        String[] lines = new String[]{
                "GRAPHICS PRESET: " + graphicsPreset.name(),
                "SKY MODE: " + renderer.skyModeName(),
                "FOG AUTO BY DIST: " + (renderer.fogAutoByRenderDistance() ? "ON" : "OFF"),
                String.format("FOG DISTANCE: %d%%", Math.round(100f / renderer.fogUserMultiplier())),
                "RENDER RADIUS: " + renderer.radius(),
                "MAX CHUNKS: " + renderer.maxLoadedChunks(),
                String.format("MASTER VOLUME: %d%%", Math.round(audio.getMasterVolume() * 100f)),
                String.format("MUSIC VOLUME:  %d%%", Math.round(audio.getMusicVolume()  * 100f)),
                String.format("FIELD OF VIEW: %d", (int)camera.getBaseFov()), // M213
                "RESET SKY DEFAULTS",
                "DYNAMIC LIGHTING: " + (renderer.isDynamicLighting() ? "ON" : "OFF"),
                "BACK"
        };

        for (int i = 0; i < rows; i++) {
            int ry = y + panelH - headerH - 10 - (i + 1) * rowH;
            boolean sel = (i == optionsMenuIndex);
            if (sel) fillRect(x + 10, ry, panelW - 20, rowH - 4, 0.20f, 0.42f, 0.28f, 0.95f);
            else fillRect(x + 10, ry, panelW - 20, rowH - 4, 0.13f, 0.16f, 0.19f, 0.85f);
            drawText(x + 18, ry + 8, lines[i], 1, sel ? 0.95f : 0.82f, sel ? 1.0f : 0.90f, sel ? 0.92f : 0.86f, 1f);
        }

        drawText(x + 14, y + 8, "UP/DOWN SELECT  LEFT/RIGHT CHANGE  ESC CLOSE", 1, 0.70f, 0.84f, 0.76f, 1f);
    }

    // ---- M232: Zone loading overlay ----------------------------------------

    private void renderZoneLoadingOverlay() {
        fillRect(0, 0, width, height, 0f, 0f, 0f, 0.94f);
        int bw = Math.max(220, width / 3);
        int bh = 16;
        int bx = (width - bw) / 2;
        int by = (height - bh) / 2;
        float p = Math.max(0f, Math.min(1f, zoneLoadTimer / ZONE_LOAD_DURATION));
        fillRect(bx - 2, by - 2, bw + 4, bh + 4, 0f, 0f, 0f, 0.85f);
        fillRect(bx, by, bw, bh, 0.08f, 0.08f, 0.08f, 0.95f);
        int fw = (int)(bw * p);
        if (fw > 0) fillRect(bx, by, fw, bh, 0.78f, 0.88f, 0.92f, 0.98f);
        drawText(bx + bw/2 - 42, by + 24, "LOADING...", 2, 0.88f, 0.92f, 0.95f, 1f);
    }

    // ---- M31: On-Screen HUD ------------------------------------------------

    private void renderHud() {
        // Crosshair - only while cursor is locked (playing)
        if (glfwGetInputMode(window, GLFW_CURSOR) == GLFW_CURSOR_DISABLED) {
            int cx = width / 2;
            int cy = height / 2;
            // Horizontal arm
            fillRect(cx - 9, cy - 1, 18, 2, 0.80f, 0.88f, 0.80f, 0.55f);
            // Vertical arm
            fillRect(cx - 1, cy - 9, 2, 18, 0.80f, 0.88f, 0.80f, 0.55f);
            // Centre dot (slightly brighter)
            fillRect(cx - 1, cy - 1, 2, 2, 0.95f, 1.00f, 0.95f, 0.75f);
        }

        // M107: progress bar while punching (crack visual is now rendered on world block).
        if (miningProgress > 0f && miningTargetX != Integer.MIN_VALUE) {
            float p = Math.max(0f, Math.min(1f, miningProgress));
            int bx = width / 2 - 28;
            int by = height / 2 - 31;
            int bw = 56;
            int bh = 4;
            fillRect(bx - 1, by - 1, bw + 2, bh + 2, 0f, 0f, 0f, 0.65f);
            fillRect(bx, by, bw, bh, 0.12f, 0.08f, 0.08f, 0.85f);
            if ((int)(bw * p) > 0) {
                float gr = 0.45f + p * 0.40f;
                float gg = 0.35f + p * 0.55f;
                fillRect(bx, by, (int)(bw * p), bh, gr, gg, 0.10f, 0.95f);
            }
        }

        // M38: Stamina bar - only shown when not full or actively draining
        if (stamina < 99f) {
            int sBarW = 130, sBarH = 5, sBarX = 16, sBarY = 66;
            float sFrac = Math.max(0f, Math.min(1f, stamina / 100f));
            float sr = staminaExhausted ? 0.65f : 0.55f + sFrac * 0.35f;
            float sg = staminaExhausted ? 0.20f : sFrac * 0.75f;
            fillRect(sBarX - 1, sBarY - 1, sBarW + 2, sBarH + 2, 0f, 0f, 0f, 0.65f);
            fillRect(sBarX, sBarY, sBarW, sBarH, 0.10f, 0.07f, 0.07f, 0.88f);
            if ((int)(sBarW * sFrac) > 0)
                fillRect(sBarX, sBarY, (int)(sBarW * sFrac), sBarH, sr, sg, 0.04f, 0.92f);
        }

        // HP bar - bottom-left, colour shifts green → red
        int barW = 130, barH = 9, barX = 16, barY = 54;
        float hpFrac = Math.max(0f, Math.min(1f, health / 100f));
        float hr = hpFrac > 0.5f ? 0.70f - (1f - hpFrac) * 0.45f : 0.80f;
        float hg = hpFrac > 0.5f ? 0.80f : hpFrac * 2f * 0.80f;
        fillRect(barX - 1, barY - 1, barW + 2, barH + 2, 0f, 0f, 0f, 0.70f);
        fillRect(barX, barY, barW, barH, 0.12f, 0.08f, 0.08f, 0.90f);
        if ((int)(barW * hpFrac) > 0)
            fillRect(barX, barY, (int)(barW * hpFrac), barH, hr, hg, 0.06f, 0.95f);
        drawText(barX + barW + 8, barY, "HP", 1, 0.70f, 0.82f, 0.70f, 0.85f);

        if (equippedArmor != BlockId.AIR) {
            String armorLabel = switch (equippedArmor) {
                case BlockId.ARMOR_BONE    -> "BONE";
                case BlockId.ARMOR_STONE   -> "STONE";
                case BlockId.ARMOR_CRYSTAL -> "CRYSTAL";
                default -> "";
            };
            float ar = (equippedArmor == BlockId.ARMOR_CRYSTAL) ? 0.45f : 0.72f;
            float ag = (equippedArmor == BlockId.ARMOR_CRYSTAL) ? 0.85f : 0.60f;
            float ab = (equippedArmor == BlockId.ARMOR_CRYSTAL) ? 0.92f : 0.42f;
            drawText(12, 34, "[ARMOR] " + armorLabel, 1, ar, ag, ab, 0.90f);
        }

        // Stability pips - appear below 75% sanity, drain as sanity falls
        float san = sanity / 100f;
        if (san < 0.75f) {
            int pipCount  = 5;
            int filledPips = Math.max(0, (int)(san / 0.75f * pipCount));
            for (int i = 0; i < pipCount; i++) {
                boolean filled = i < filledPips;
                float dim = filled ? Math.max(0.12f, san * 1.3f) : 0.05f;
                float pulse = filled
                        ? (0.75f + 0.25f * (float)Math.sin(glfwGetTime() * 2.1f + i * 0.85f))
                        : 1f;
                fillRect(16 + i * 11, 44, 8, 5,
                        dim * 0.28f * pulse, dim * 0.28f * pulse, dim * pulse, 0.88f);
            }
        }

        // M175: Relic progress - always visible; pulses when goal is reached
        {
            boolean done = relicsFound >= RELIC_GOAL;
            float pulse = done ? 0.70f + 0.30f * (float)Math.sin(glfwGetTime() * 3.2f) : 1.0f;
            float rr = done ? 0.20f * pulse : 0.82f;
            float rg = done ? 0.90f * pulse : 0.60f;
            float rb = done ? 0.25f * pulse : 0.20f;
            String relicStr = "RELICS  " + relicsFound + " / " + RELIC_GOAL
                    + (done ? "  \u25C6 RETURN TO ORIGIN" : "");
            drawText(16, 66, relicStr, 1, rr, rg, rb, 0.90f);
            // M191: compass craft hint removed — recipe book (R) covers this
        }

        // Relic message (centred mid-screen, fades out)
        if (relicMessageTimer > 0f) {
            float alpha = Math.min(1f, relicMessageTimer) * 0.95f;
            int msgLen = relicMessage.length() * 6; // approx px width at scale 1
            drawText(width / 2 - msgLen / 2, height / 2 + 40, relicMessage, 1,
                    0.95f, 0.70f, 0.22f, alpha);
        }

        // M188: relic pickup ceremony — flash + big text
        if (relicFlashTimer > 0f) {
            float t = relicFlashTimer / 2.0f; // 1 → 0 over 2s
            // Phase 1 (t > 0.8): bright amber flash
            if (t > 0.8f) {
                float flashA = (t - 0.8f) / 0.2f;
                fillRect(0, 0, width, height, 0.95f, 0.78f, 0.18f, flashA * 0.75f);
            }
            // Phase 2 (0.2 < t < 0.85): dark overlay + big centered relic message
            if (t > 0.20f && t < 0.85f) {
                float a = Math.min(1f, Math.min((0.85f - t) / 0.12f, (t - 0.20f) / 0.12f));
                fillRect(0, 0, width, height, 0f, 0f, 0f, a * 0.55f);
                String msg1 = "RELIC " + relicsFound + " / " + RELIC_GOAL;
                String msg2 = "ABSORBED";
                drawText(width / 2 - msg1.length() * 9, height / 2 + 12, msg1, 3, 0.95f, 0.75f, 0.20f, a * 0.96f);
                drawText(width / 2 - msg2.length() * 6, height / 2 - 10, msg2, 2, 0.88f, 0.65f, 0.15f, a * 0.75f);
            }
        }

        // M180: cutscene-style intro — dark overlay + big font, 24s, SPACE to skip
        // Timer counts DOWN 24 -> 0
        // Phase A 24->16  "HOLLOW"              scale=6  white     sub: "THE DARKNESS WAITS"
        // Phase B 15->9   "FIND 3 RELICS"       scale=4  near-white sub: "SCATTERED ACROSS THE WORLD"
        // Phase C  8->3   "CRAFT A COMPASS"     scale=4  amber     sub: "CRYSTAL + WOOD PLANK  3x3 TABLE"
        // Phase D  2->0   "YOU ARE NOT ALONE"   scale=3  dark-red  (no sub)
        if (introTimer > 0f) {
            float[]   iHi = { 24f, 15f,  8f, 2f };
            float[]   iLo = { 16f,  9f,  3f, 0f };
            String[]  main = { "HOLLOW", "FIND 3 RELICS", "CRAFT A COMPASS", "YOU ARE NOT ALONE" };
            String[]  sub  = { "THE DARKNESS WAITS", "SCATTERED ACROSS THE WORLD",
                               "PRESS R FOR RECIPES", "" };
            int[]     msc  = { 6, 4, 4, 3 };
            float[][] col  = {
                {0.88f, 0.88f, 0.86f}, // near-white
                {0.93f, 0.93f, 0.90f}, // white
                {0.95f, 0.68f, 0.12f}, // amber
                {0.72f, 0.10f, 0.09f}  // dark red
            };
            for (int ii = 0; ii < 4; ii++) {
                if (introTimer < iLo[ii] || introTimer > iHi[ii]) continue;
                float range = iHi[ii] - iLo[ii];
                float t = (introTimer - iLo[ii]) / range;
                float f = 0.18f;
                float a = (t > 1f - f) ? (1f - t) / f : (t < f ? t / f : 1f);
                // Dark cinematic overlay — covers entire screen
                fillRect(0, 0, width, height, 0f, 0f, 0f, a * 0.91f);
                // Main text — large, centered
                int ms = msc[ii];
                int mw = main[ii].length() * 6 * ms;
                int mx = width / 2 - mw / 2;
                int subH = sub[ii].isEmpty() ? 0 : (8 * 2 + 14);
                int my = height / 2 - (8 * ms) / 2 - subH / 2;
                drawText(mx, my, main[ii], ms, col[ii][0], col[ii][1], col[ii][2], a * 0.97f);
                // Subtext — smaller, below main
                if (!sub[ii].isEmpty()) {
                    int sw = sub[ii].length() * 6 * 2;
                    int sx = width / 2 - sw / 2;
                    int sy = my + 8 * ms + 14;
                    drawText(sx, sy, sub[ii], 2, 0.52f, 0.52f, 0.50f, a * 0.68f);
                }
            }
            // Skip hint — fades in after 0.5s, fades at end
            float skipA = Math.min(1f, Math.min(introTimer * 2f, (24f - introTimer) * 0.5f)) * 0.42f;
            if (skipA > 0.04f) {
                String skipMsg = "PRESS SPACE TO SKIP";
                drawText(width / 2 - skipMsg.length() * 3, height - 22, skipMsg, 1,
                        0.48f, 0.48f, 0.46f, skipA);
            }
        }

        // M187: boundary vignette — dark-red edges appear within 50 blocks of ±WORLD_RADIUS
        if (camera != null && !worldMenuOpen && !deathScreenActive) {
            int wr = noctfield.world.ChunkGenerator.WORLD_RADIUS;
            float nearBound = Math.min(
                wr - Math.abs(camera.position().x),
                wr - Math.abs(camera.position().z));
            final float WARN = 50f;
            if (nearBound < WARN) {
                float wf = Math.max(0f, 1f - nearBound / WARN);
                float va = wf * 0.52f;
                int vW = (int)(70 * wf) + 20, vH = (int)(40 * wf) + 10;
                fillRect(0,          0,          vW,             height, 0.06f, 0f, 0f, va);
                fillRect(width - vW, 0,          vW,             height, 0.06f, 0f, 0f, va);
                fillRect(vW,         0,          width - 2 * vW, vH,     0.06f, 0f, 0f, va);
                fillRect(vW,         height - vH,width - 2 * vW, vH,     0.06f, 0f, 0f, va);
            }
        }

        // M184: World boundary message
        if (boundaryMsgTimer > 0f) {
            float ba = Math.min(1f, boundaryMsgTimer) * 0.88f;
            fillRect(0, 0, width, height, 0.06f, 0f, 0f, ba * 0.20f);
            String bm1 = "THE VOID DEVOURS ALL";
            String bm2 = "YOU CANNOT GO FURTHER";
            drawText(width / 2 - bm1.length() * 6, height / 2 + 22, bm1, 2, 0.82f, 0.10f, 0.08f, ba);
            drawText(width / 2 - bm2.length() * 3, height / 2 + 8,  bm2, 1, 0.60f, 0.08f, 0.06f, ba * 0.78f);
        }

        // M40: Journal message (appears below relic message, white text)
        if (journalMessageTimer > 0f) {
            float alpha = Math.min(1f, journalMessageTimer) * 0.92f;
            int msgLen = journalMessage.length() * 6;
            drawText(width / 2 - msgLen / 2, height / 2 + 60, journalMessage, 1,
                    0.90f, 0.92f, 0.88f, alpha);
        }

        // M99: Surface event message — only show if no horror/boundary message is active
        if (surfaceEventMsgTimer > 0f && !surfaceEventMsg.isEmpty()
                && thickFogMsgTimer <= 0f && boundaryMsgTimer <= 0f) {
            float alpha = Math.min(1f, surfaceEventMsgTimer) * 0.85f;
            float[] col = switch (surfaceEvent) {
                case FOG_BANK     -> new float[]{0.75f, 0.80f, 0.85f}; // pale blue-grey
                case WIND_STORM   -> new float[]{0.78f, 0.82f, 0.60f}; // pale yellow-green
                case EMBER_SHOWER -> new float[]{0.95f, 0.55f, 0.15f}; // ember orange
                default           -> new float[]{0.85f, 0.85f, 0.85f};
            };
            int msgLen = surfaceEventMsg.length() * 6;
            drawText(width / 2 - msgLen / 2, height / 2 + 80, surfaceEventMsg, 1,
                    col[0], col[1], col[2], alpha);
        }

        // M174: Relic proximity indicator — find nearest of the 3 world relics
        {
        int[] nearestRp = null; float nearestDist = Float.MAX_VALUE;
        for (int[] rp : noctfield.world.ChunkGenerator.getRelicPositions(renderer.worldSeed())) {
            float ddx = rp[0] - camera.position().x, ddz = rp[2] - camera.position().z;
            float d = (float)Math.sqrt(ddx*ddx + ddz*ddz);
            if (d < nearestDist) { nearestDist = d; nearestRp = rp; }
        }
        if (nearestRp == null) { } else { // M179: null when all relics collected
        float rdx = nearestRp[0] - camera.position().x;
        float rdz = nearestRp[2] - camera.position().z;
        float relicDist = nearestDist;
        byte  relicBlock = renderer.getBlock(nearestRp[0], nearestRp[1], nearestRp[2]);
        if (relicBlock == BlockId.RELIC && relicDist < 45f) {
            float proximity = 1f - relicDist / 45f;
            float pulse = 0.50f + 0.50f * (float)Math.sin(glfwGetTime() * (2.2f + proximity * 3f));
            float alpha = proximity * 0.80f * pulse;
            drawText(width / 2 - 3, height / 2 - 52, "V", 1,
                    0.95f, 0.60f, 0.08f, alpha);
        }
        }} // end relic proximity M179

        // M144: item name flash above hotbar when slot changes
        if (hotbarSwitchTimer > 0f && !hotbarSwitchName.isEmpty()) {
            float alpha = Math.min(1f, hotbarSwitchTimer) * 0.92f;
            int tw = hotbarSwitchName.length() * 7;
            drawText(width / 2 - tw / 2, height - 70, hotbarSwitchName, 1,
                    0.90f, 0.96f, 0.88f, alpha);
        }

        // M147: SAVED flash (top-right corner, fades)
        if (savedFlashTimer > 0f) {
            float alpha = Math.min(1f, savedFlashTimer) * 0.85f;
            drawText(width - 68, 8, "SAVED", 1, 0.50f, 0.90f, 0.60f, alpha);
        }

        // M152: blood rain / thick fog horror event — centred, crimson, slow fade
        if (thickFogMsgTimer > 0f && !horrorEventMsg.isEmpty()) {
            float alpha = Math.min(1f, thickFogMsgTimer * 0.8f);
            int msgW = horrorEventMsg.length() * 7;
            drawText((width - msgW) / 2, height / 2 - 60, horrorEventMsg, 1, 0.85f, 0.08f, 0.08f, alpha);
        }

        // M168: compass overlay when compass is active hotbar item
        if (!worldMenuOpen && !deathScreenActive && camera != null) {
            byte selItem = (selectedHotbarSlot >= 0 && selectedHotbarSlot < slotItem.length) ? slotItem[selectedHotbarSlot] : BlockId.AIR;
            if (selItem == BlockId.COMPASS && slotCount[selectedHotbarSlot] > 0) renderCompassHUD();
        }

        // M169: screen-edge void gate waypoint — shows when relics complete & compass NOT held
        if (!worldMenuOpen && !deathScreenActive && !endingScreenActive && camera != null
                && relicsFound >= RELIC_GOAL && !escapeActive) {
            byte selItem = (selectedHotbarSlot >= 0 && selectedHotbarSlot < slotItem.length) ? slotItem[selectedHotbarSlot] : BlockId.AIR;
            boolean compassHeld = selItem == BlockId.COMPASS && slotCount[selectedHotbarSlot] > 0;
            if (!compassHeld) renderVoidWaypoint();
        }

        // M227: structure tool HUD
        if (namingStruct) {
            int bW = Math.min(420, width - 80);
            int bH = 44;
            int bX = (width - bW) / 2;
            int bY = height / 2 - bH / 2;
            fillRect(0, 0, width, height, 0f, 0f, 0f, 0.50f);
            fillRect(bX, bY, bW, bH, 0.04f, 0.08f, 0.12f, 1f);
            fillRect(bX, bY, bW, 1, 0.30f, 0.60f, 0.90f, 1f);
            fillRect(bX, bY + bH - 1, bW, 1, 0.30f, 0.60f, 0.90f, 1f);
            drawText(bX + 8, bY + bH - 12, "SAVE STRUCTURE AS:", 1, 0.40f, 0.70f, 1.00f, 1f);
            String buf = structNameBuffer.toString() + "_";
            drawText(bX + 8, bY + bH / 2 - 6, buf, 2, 0.90f, 0.95f, 1.00f, 1f);
            drawText(bX + 8, bY + 4, "ENTER=save   ESC=cancel", 1, 0.35f, 0.55f, 0.75f, 1f);
        } else if (structCaptureMode) {
            String line;
            if (structCaptureStep == 0) {
                line = "[G]=SET CORNER A  |  ESC=CANCEL";
            } else if (structCaptureStep == 1) {
                line = "CORNER A:(" + structA[0] + "," + structA[1] + "," + structA[2] + ")  |  [G]=SET CORNER B  |  ESC=CANCEL";
            } else {
                int dx = Math.abs(structB[0]-structA[0])+1, dy = Math.abs(structB[1]-structA[1])+1, dz = Math.abs(structB[2]-structA[2])+1;
                line = "A:(" + structA[0] + "," + structA[1] + "," + structA[2] + ") B:(" + structB[0] + "," + structB[1] + "," + structB[2] + ")  SIZE:" + dx + "x" + dy + "x" + dz + "  [F10]=SAVE  [G]=RESET  ESC=CANCEL";
            }
            int tw = line.length() * 6 + 16;
            fillRect((width - tw) / 2 - 4, height - 80, tw + 8, 16, 0.02f, 0.06f, 0.12f, 0.85f);
            drawText((width - tw) / 2, height - 77, line, 1, 0.50f, 0.80f, 1.00f, 1f);
        } else if (structPasteMode) {
            String sname = savedStructNames.isEmpty() ? "NO STRUCTS SAVED" : savedStructNames.get(structPasteIndex);
            String line = "[PASTE] " + sname + "  |  [H]=NEXT  [RMB]=STAMP  ESC=CANCEL";
            int tw = line.length() * 6 + 16;
            fillRect((width - tw) / 2 - 4, height - 80, tw + 8, 16, 0.02f, 0.10f, 0.06f, 0.85f);
            drawText((width - tw) / 2, height - 77, line, 1, 0.40f, 1.00f, 0.55f, 1f);
        }

        renderHeldBlockHand();
        renderHitParticles();
    }

    private void renderCompassHUD() {
        // M204: rewritten compass — 2 surface relics + underground group
        int[][] rpos = noctfield.world.ChunkGenerator.getRelicPositions(renderer.worldSeed());
        boolean hasAllRelics = relicsFound >= RELIC_GOAL;

        // --- Collected state (3 logical steps) ---
        boolean colS0 = rpos.length >= 1 && renderer.isBlockEdited(rpos[0][0], rpos[0][1], rpos[0][2]);
        boolean colS1 = rpos.length >= 2 && renderer.isBlockEdited(rpos[1][0], rpos[1][1], rpos[1][2]);
        boolean colUG = undergroundRelicsFound > 0; // any underground relic counts

        // Find nearest uncollected underground relic for needle target
        int[] ugTarget = (rpos.length >= 3) ? rpos[2] : null;
        if (!colUG && rpos.length >= 3) {
            float bestD = Float.MAX_VALUE;
            for (int i = 2; i < rpos.length; i++) {
                float ddx = rpos[i][0] - camera.position().x, ddz = rpos[i][2] - camera.position().z;
                float dd = ddx * ddx + ddz * ddz;
                if (dd < bestD) { bestD = dd; ugTarget = rpos[i]; }
            }
        }

        // --- Active target ---
        int activeStep = -1;
        org.joml.Vector3f target = null;
        if (hasAllRelics) {
            target = new org.joml.Vector3f(0f, 0f, 0f);
        } else if (!colS0 && rpos.length >= 1) {
            activeStep = 0;
            target = new org.joml.Vector3f(rpos[0][0] + .5f, rpos[0][1] + .5f, rpos[0][2] + .5f);
        } else if (!colS1 && rpos.length >= 2) {
            activeStep = 1;
            target = new org.joml.Vector3f(rpos[1][0] + .5f, rpos[1][1] + .5f, rpos[1][2] + .5f);
        } else if (!colUG && ugTarget != null) {
            activeStep = 2;
            target = new org.joml.Vector3f(ugTarget[0] + .5f, ugTarget[1] + .5f, ugTarget[2] + .5f);
        }

        // Needle colour per step
        float nr, ng, nb;
        if      (hasAllRelics)   { nr = 0.85f; ng = 0.30f; nb = 0.85f; }
        else if (activeStep == 0){ nr = 0.42f; ng = 0.82f; nb = 0.38f; }
        else if (activeStep == 1){ nr = 0.88f; ng = 0.58f; nb = 0.18f; }
        else if (activeStep == 2){ nr = 0.55f; ng = 0.42f; nb = 0.88f; }
        else                     { nr = 0.45f; ng = 0.45f; nb = 0.45f; }

        // Layout — bottom-left, tall enough for 3-row checklist
        int cx = 72, cy = height - 82, cr = 38;
        int panelW = (cr + 10) * 2 + 100;
        int panelH = (cr + 10) * 2 + 32;
        fillRect(cx - cr - 10, cy - cr - 26, panelW, panelH, 0.02f, 0.02f, 0.04f, 0.84f);
        // Top accent bar
        fillRect(cx - cr - 10, cy - cr - 26 + panelH - 3, panelW, 3, nr * 0.5f, ng * 0.5f, nb * 0.5f, 0.70f);

        // --- Compass ring: 48 dots (fixed, never rotates) ---
        for (int a = 0; a < 48; a++) {
            float ang = a * (float)(Math.PI * 2.0) / 48f;
            int px = cx + (int)(Math.sin(ang) * cr), py = cy + (int)(Math.cos(ang) * cr);
            fillRect(px - 1, py - 1, 2, 2, 0.22f, 0.38f, 0.28f, 1f);
        }
        // Cardinal tick marks (inset, brighter)
        for (int a = 0; a < 4; a++) {
            float ang = a * (float)(Math.PI / 2.0);
            int px = cx + (int)(Math.sin(ang) * (cr - 4)), py = cy + (int)(Math.cos(ang) * (cr - 4));
            fillRect(px - 1, py - 1, 3, 3, 0.50f, 0.75f, 0.55f, 1f);
        }
        // N / S / E / W labels — fixed, always at their cardinal positions
        drawText(cx - 3,      cy + cr + 5,  "N", 1, 0.88f, 0.94f, 0.88f, 1f); // N = top (+Z = north, up on ring)
        drawText(cx - 3,      cy - cr - 13, "S", 1, 0.55f, 0.62f, 0.55f, 0.85f);
        drawText(cx + cr + 5, cy - 4,       "E", 1, 0.55f, 0.62f, 0.55f, 0.85f);
        drawText(cx - cr - 11,cy - 4,       "W", 1, 0.55f, 0.62f, 0.55f, 0.85f);

        // Player facing indicator — small yellow notch on the ring edge
        float yaw = camera.getYaw();
        int fpx = cx + (int)(Math.sin(yaw) * (cr - 3));
        int fpy = cy + (int)(Math.cos(yaw) * (cr - 3));
        fillRect(fpx - 1, fpy - 1, 3, 3, 0.96f, 0.92f, 0.42f, 0.92f);

        // M216: needle removed — distance-only compass
        float dist = 0f;
        if (target != null) {
            float dx = target.x - camera.position().x, dz = target.z - camera.position().z;
            dist = (float)Math.sqrt(dx * dx + dz * dz);
        }

        // --- Status panel: right of compass ring ---
        int lx = cx + cr + 12, ly = cy - 12;

        // Header: RELICS X/3 and distance to current target
        String hdrTxt = hasAllRelics ? "VOID GATE" : "RELICS " + relicsFound + "/" + RELIC_GOAL;
        drawText(lx, ly + 54, hdrTxt, 1, 0.75f, 0.88f, 0.78f, 0.90f);
        if (target != null && dist > 0f) {
            String dstr = (dist >= 1000f) ? (Math.round(dist / 10f) * 10) + "M" : Math.round(dist) + "M";
            drawText(lx, ly + 44, dstr, 1, nr, ng, nb, 0.80f);
        }

        // Separator line
        fillRect(lx, ly + 38, 88, 1, 0.28f, 0.32f, 0.28f, 0.60f);

        // 3-row checklist (surface0, surface1, underground)
        String[] labels = { "RELIC 1", "RELIC 2", "DEEP" };
        boolean[] doneRows = { colS0, colS1, colUG };
        float[][] rowCol = {
            { 0.42f, 0.82f, 0.38f },
            { 0.88f, 0.58f, 0.18f },
            { 0.55f, 0.42f, 0.88f }
        };
        for (int i = 0; i < 3; i++) {
            boolean done   = doneRows[i];
            boolean active = (i == activeStep);
            float[] rc = rowCol[i];
            float lr = done ? 0.30f : (active ? rc[0] : 0.38f);
            float lg = done ? 0.42f : (active ? rc[1] : 0.40f);
            float lb = done ? 0.32f : (active ? rc[2] : 0.38f);
            float la = done ? 0.55f : (active ? 1.00f  : 0.55f);
            String pfx = done ? "+" : (active ? ">" : "-");
            drawText(lx, ly + 26 - i * 14, pfx + " " + labels[i], 1, lr, lg, lb, la);
        }

        // Biome indicator at bottom
        String bmTxt = switch (currentBiome) {
            case noctfield.world.ChunkGenerator.BIOME_DEAD  -> "DEAD LANDS";
            case noctfield.world.ChunkGenerator.BIOME_SWAMP -> "SWAMP";
            default -> "FOREST";
        };
        drawText(lx, ly - 24, bmTxt, 1, 0.38f, 0.44f, 0.38f, 0.65f);
    }

    // M169: screen-edge waypoint arrow toward the Void Gate at origin
    private void renderVoidWaypoint() {
        org.joml.Vector3f pos = camera.position();
        float dx = 0f - pos.x, dz = 0f - pos.z;
        float dist = (float)Math.sqrt(dx * dx + dz * dz);
        float relAngle = (float)Math.atan2(dx, dz) - camera.getYaw();

        // Determine screen-edge position for the waypoint indicator
        float sinA = (float)Math.sin(relAngle), cosA = (float)Math.cos(relAngle);
        float cx = width * 0.5f, cy = height * 0.5f;
        float edgeX = cx + sinA * (width  * 0.42f);
        float edgeY = cy - cosA * (height * 0.42f);
        // Clamp to screen with margin
        int margin = 22;
        edgeX = Math.max(margin, Math.min(width  - margin, edgeX));
        edgeY = Math.max(margin, Math.min(height - margin, edgeY));

        float pulse = 0.70f + 0.30f * (float)Math.sin((float)org.lwjgl.glfw.GLFW.glfwGetTime() * 3.0f);
        float r = 0.80f * pulse, g = 0.20f * pulse, b = 0.90f * pulse;

        // Draw a simple diamond marker at edge position
        int ex = (int)edgeX, ey = (int)edgeY, ds = 5;
        fillRect(ex - 1, ey - ds, 3, ds * 2 + 1, r, g, b, 0.85f); // vertical bar
        fillRect(ex - ds, ey - 1, ds * 2 + 1, 3, r, g, b, 0.85f); // horizontal bar

        // Distance label near the marker
        String distStr = Math.round(dist) + "m";
        int textX = Math.max(2, Math.min(width - distStr.length() * 6 - 2, ex - distStr.length() * 3));
        int textY = (ey < height / 2) ? ey + 12 : ey - 20;
        drawText(textX, textY, distStr, 1, r, g, b, 0.80f);

        // "VOID GATE" label near center-top if the gate is roughly ahead
        if (Math.abs(relAngle) < 0.5f) {
            drawText((int)(cx - 21), margin + 2, "VOID GATE", 1, 0.75f, 0.25f, 0.90f, 0.85f * pulse);
        }
    }

    // ---- M34: Sanity Visual System ----------------------------------------

    // ════════════════════════════════════════════════════════════════════
    // M159 JUMPSCARE SYSTEM
    // ════════════════════════════════════════════════════════════════════

    private void updateJumpscares(float dt) {
        if (pauseMenuOpen || worldMenuOpen || deathScreenActive || escapeActive) return;
        float horror = Math.min(1f, worldPlayTime / 600f);

        jsGlobalCooldown    = Math.max(0f, jsGlobalCooldown    - dt);
        faceJsCooldown      = Math.max(0f, faceJsCooldown      - dt);
        falseChargeCooldown = Math.max(0f, falseChargeCooldown - dt);
        behindYouCooldown   = Math.max(0f, behindYouCooldown   - dt);
        silenceCooldown     = Math.max(0f, silenceCooldown     - dt);
        falseChargeFlash    = Math.max(0f, falseChargeFlash    - dt);
        behindYouTimer      = Math.max(0f, behindYouTimer      - dt);

        // Global cooldown prevents rapid back-to-back scares
        if (jsGlobalCooldown > 0f || faceFramesLeft > 0) return;

        // ── 1. THE FACE (horror >= 70%, once per 10 min) ─────────────────────
        if (horror >= 0.70f && faceJsCooldown <= 0f) {
            if (jsRng.nextFloat() < 0.00022f) {  // ~1x per 75s at 60fps when eligible
                faceFramesLeft   = 4;   // frame4=white flash, frames3-1=face
                faceJsCooldown   = 600f;
                jsGlobalCooldown = 90f;
                if (audio != null) audio.triggerJumpscareScream();
            }
        }

        // ── 2. FALSE CHARGE (horror >= 50%, every 3-7 min) ───────────────────
        if (horror >= 0.50f && falseChargeCooldown <= 0f) {
            if (jsRng.nextFloat() < 0.00030f) {
                falseChargeFlash    = 0.65f;
                falseChargeCooldown = 180f + jsRng.nextFloat() * 240f;
                jsGlobalCooldown    = 55f;
                if (audio != null) audio.triggerFalseCharge();
            }
        }

        // ── 3. IT'S BEHIND YOU (horror >= 60%, every 4-8 min) ────────────────
        if (horror >= 0.60f && behindYouCooldown <= 0f) {
            if (jsRng.nextFloat() < 0.00018f) {
                behindYouTimer    = 2.5f;
                behindYouCooldown = 240f + jsRng.nextFloat() * 240f;
                jsGlobalCooldown  = 55f;
                renderer.spawnSentinelBehindPlayer(camera.position(), camera.forward());
                if (audio != null) audio.triggerEventSting();
            }
        }

        // ── 4. DEAD SILENCE (horror >= 30%, every 8-18 min) ─────────────────
        if (horror >= 0.30f && silenceCooldown <= 0f
                && (audio == null || !audio.isDeadSilenceActive())) {
            if (jsRng.nextFloat() < 0.00010f) {
                silenceCooldown  = 480f + jsRng.nextFloat() * 600f;
                jsGlobalCooldown = 80f;
                if (audio != null) audio.triggerDeadSilence(8.0f);
            }
        }
    }

    /** Renders THE FACE — a full-screen screaming horror face for 3 frames preceded by a white flash. */
    private void renderHorrorFace() {
        if (faceFramesLeft == 4) {
            // Frame 1: pure white flashbang
            fillRect(0, 0, width, height, 1f, 1f, 1f, 1f);
            faceFramesLeft--;
            return;
        }
        // Frames 3-1: the face (with tiny random jitter per frame for motion)
        int jx = (int)(jsRng.nextFloat() * 14f - 7f);
        int jy = (int)(jsRng.nextFloat() * 10f - 5f);

        int fw = width  * 7 / 10;
        int fh = height * 9 / 10;
        int fx = (width  - fw) / 2 + jx;
        int fy = (height - fh) / 2 + jy;
        int fcx = fx + fw / 2;

        // ── FULL SCREEN BLACK ───────────────────────────────────────────
        fillRect(0, 0, width, height, 0f, 0f, 0f, 1f);

        // ── FACE BASE (pale grey flesh) ─────────────────────────────────
        fillRect(fx, fy, fw, fh, 0.82f, 0.79f, 0.75f, 1f);
        // Edge shading — gives depth
        fillRect(fx,          fy, fw / 7, fh, 0.52f, 0.50f, 0.47f, 0.65f);
        fillRect(fx + fw*6/7, fy, fw / 7, fh, 0.52f, 0.50f, 0.47f, 0.65f);
        fillRect(fx, fy,          fw, fh / 9, 0.40f, 0.38f, 0.35f, 0.55f);
        // Forehead text burned in blood
        int ts = Math.max(1, fw / 110);
        String fTxt = "LOOK AT ME";
        drawText(fcx - fTxt.length() * 3 * ts, fy + fh / 11, fTxt, ts, 0.55f, 0.04f, 0.04f, 0.88f);

        // ── EYE SOCKETS ─────────────────────────────────────────────────
        int ew = fw / 5, eh = fh / 7;
        int eyeY = fy + fh / 4;
        // Left eye slightly larger (asymmetry feels wrong)
        int lew = ew + ew / 7, leh = eh + eh / 5;
        int lex = fx + fw / 7;
        int rex = fx + fw * 2 / 5 + ew / 8;
        // Socket shadows
        fillRect(lex - 5, eyeY - 5, lew + 10, leh + 10, 0.12f, 0.05f, 0.05f, 0.90f);
        fillRect(rex - 5, eyeY - 5, ew  + 10, eh  + 10, 0.12f, 0.05f, 0.05f, 0.90f);
        // Black voids
        fillRect(lex, eyeY, lew, leh, 0.03f, 0.01f, 0.01f, 1f);
        fillRect(rex, eyeY, ew,  eh,  0.03f, 0.01f, 0.01f, 1f);
        // Red glowing pupils
        fillRect(lex + lew/4, eyeY + leh/4, lew/2, leh/2, 0.82f, 0.04f, 0.04f, 1f);
        fillRect(rex + ew /4, eyeY + eh /4, ew /2, eh /2, 0.82f, 0.04f, 0.04f, 1f);
        // Blood dripping from eye sockets
        fillRect(lex + lew/3,   eyeY + leh,      4, fh/7,  0.55f, 0.02f, 0.02f, 0.88f);
        fillRect(lex + lew*2/3, eyeY + leh,      3, fh/11, 0.50f, 0.02f, 0.02f, 0.70f);
        fillRect(rex + ew /3,   eyeY + eh,        4, fh/9,  0.55f, 0.02f, 0.02f, 0.88f);
        fillRect(rex + ew *2/3, eyeY + eh + fh/9, 3, fh/14,0.48f, 0.02f, 0.02f, 0.65f);

        // ── SCREAMING MOUTH ──────────────────────────────────────────────
        int mw = fw * 3 / 5, mh = fh * 2 / 7;
        int mx = fx + (fw - mw) / 2;
        int my = fy + fh * 19 / 32;
        // Mouth cavity
        fillRect(mx, my, mw, mh, 0.09f, 0.01f, 0.01f, 1f);
        // Throat — deeper darkness
        fillRect(mx + mw/5, my + mh/3, mw*3/5, mh*2/3, 0.02f, 0.00f, 0.00f, 1f);
        // Upper teeth (7)
        int tw = mw / 9, th = mh / 3;
        for (int t = 0; t < 7; t++) {
            int tx = mx + mw/12 + t * (tw + 3);
            fillRect(tx, my, tw, th, 0.93f, 0.91f, 0.88f, 1f);
            fillRect(tx + 1, my + th - 3, tw - 2, 3, 0.62f, 0.60f, 0.56f, 0.8f);
        }
        // Lower teeth (6, irregular — one missing)
        for (int t = 0; t < 6; t++) {
            if (t == 2) continue; // missing tooth adds wrongness
            int tx  = mx + mw/10 + t * (tw + 4);
            int th2 = th - (t % 2 == 0 ? 0 : th / 3);
            fillRect(tx, my + mh - th2, tw, th2, 0.88f, 0.85f, 0.81f, 1f);
        }

        // ── VEINS / CRACKS ───────────────────────────────────────────────
        // Radiating from eye sockets
        fillRect(lex + lew/2 - 1, eyeY + leh, 2, fh / 5, 0.30f, 0.08f, 0.08f, 0.55f);
        fillRect(lex,             eyeY + leh/2, fw/7, 2, 0.28f, 0.07f, 0.07f, 0.50f);
        fillRect(rex + ew,        eyeY + eh /2, fw/6, 2, 0.28f, 0.07f, 0.07f, 0.50f);
        // Forehead crack
        fillRect(fcx - 1, fy, 2, fh / 3, 0.32f, 0.14f, 0.10f, 0.48f);
        fillRect(fcx - fw/5, fy + fh/6, fw*2/5, 1, 0.28f, 0.12f, 0.08f, 0.42f);
        // Chin crack
        fillRect(fcx + 10, my + mh, 2, (fy + fh) - (my + mh), 0.30f, 0.10f, 0.08f, 0.40f);

        faceFramesLeft--;
    }

    /** Renders all jumpscare overlays on top of the full frame. */
    private void renderJumpscareOverlays() {
        // ── 1. THE FACE ──────────────────────────────────────────────────
        if (faceFramesLeft > 0) {
            renderHorrorFace();
            return; // face overrides everything else this frame
        }

        // ── 2. FALSE CHARGE: deep blood-red screen flash ─────────────────
        if (falseChargeFlash > 0f) {
            float a = Math.min(1f, falseChargeFlash * 2.5f);
            fillRect(0, 0, width, height, 0.45f, 0.00f, 0.00f, a * 0.70f);
            // First 0.3s: full black punch (like a concussive hit)
            if (falseChargeFlash > 0.35f) {
                fillRect(0, 0, width, height, 0.00f, 0.00f, 0.00f, 0.50f);
            }
        }

        // ── 3. IT'S BEHIND YOU: pulsing blood-red text ───────────────────
        if (behindYouTimer > 0f) {
            float pulse = (float) Math.sin(behindYouTimer * 9.0f);
            if (pulse > -0.15f) {
                String msg = "IT'S BEHIND YOU";
                int sc = Math.max(1, width / 190);
                int tw = msg.length() * 6 * sc;
                int tx = (width  - tw) / 2;
                int ty = height / 2 - 4 * sc;
                // Dark backing plate
                fillRect(tx - 8, ty - 8, tw + 16, 14 * sc + 16, 0f, 0f, 0f, 0.80f);
                // Triple-render text for thick blood look
                for (int dx = -1; dx <= 1; dx++) {
                    drawText(tx + dx, ty,     msg, sc, 0.85f, 0.02f, 0.02f, 0.95f);
                    drawText(tx + dx, ty + 1, msg, sc, 0.55f, 0.01f, 0.01f, 0.65f);
                }
            }
        }

        // ── 4. DEAD SILENCE: closing vignette + whisper text ─────────────
        if (audio != null && audio.isDeadSilenceActive()) {
            float remaining = audio.deadSilenceRemaining(); // 1.0=just started, 0.0=ending
            float progress  = 1f - remaining;
            float vA = 0.50f + progress * 0.35f;
            int   vW = (int)(width  * (0.18f + 0.22f * progress));
            int   vH = (int)(height * (0.13f + 0.17f * progress));
            // Closing black vignette from all 4 sides
            fillRect(0,           0,            vW,             height, 0f, 0f, 0f, vA);
            fillRect(width - vW,  0,            vW,             height, 0f, 0f, 0f, vA);
            fillRect(vW,          0,            width - 2 * vW, vH,     0f, 0f, 0f, vA);
            fillRect(vW,          height - vH,  width - 2 * vW, vH,     0f, 0f, 0f, vA);
            // Barely-visible whisper text — fades in halfway through
            if (progress > 0.45f) {
                float ta = (progress - 0.45f) * 0.55f;
                String whisper = "CAN YOU HEAR ME";
                int wsc = Math.max(1, width / 260);
                int wtw = whisper.length() * 6 * wsc;
                drawText((width - wtw) / 2, height / 2 + 24, whisper, wsc,
                         0.55f, 0.55f, 0.55f, ta);
            }
        }
    }

    private void renderSanityEffects() {
        // M34: intentionally empty - all sanity overlays removed per design decision
    }

    private static final class TempStructure {
        int x, y, z;
        byte block;
        float ttl;
        TempStructure(int x, int y, int z, byte block, float ttl) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.block = block;
            this.ttl = ttl;
        }
    }
    // ─── M227: Structure tool helpers ──────────────────────────────────────────
    private void handleStructureGKey(Raycast.Hit hit) {
        if (!structCaptureMode) {
            // Enter capture mode
            structCaptureMode = true;
            structCaptureStep = 0;
            structA = null; structB = null;
            journalMessage = "STRUCTURE CAPTURE ON  [G]=MARK CORNER A";
            journalMessageTimer = 2f;
            return;
        }
        if (structCaptureStep == 0) {
            if (hit == null) { journalMessage = "AIM AT A BLOCK FIRST"; journalMessageTimer = 1.5f; return; }
            structA = new int[]{ hit.x(), hit.y(), hit.z() };
            structCaptureStep = 1;
            journalMessage = "CORNER A SET  [G]=MARK CORNER B";
            journalMessageTimer = 2f;
        } else if (structCaptureStep == 1) {
            if (hit == null) { journalMessage = "AIM AT A BLOCK FIRST"; journalMessageTimer = 1.5f; return; }
            structB = new int[]{ hit.x(), hit.y(), hit.z() };
            structCaptureStep = 2;
            int dx = Math.abs(structB[0]-structA[0])+1, dy = Math.abs(structB[1]-structA[1])+1, dz = Math.abs(structB[2]-structA[2])+1;
            journalMessage = "SELECTION " + dx + "x" + dy + "x" + dz + "  [F10]=SAVE NAME  [G]=RESET";
            journalMessageTimer = 3f;
        } else {
            // Step 2 → reset selection
            structCaptureStep = 0; structA = null; structB = null;
            journalMessage = "SELECTION RESET  [G]=MARK CORNER A";
            journalMessageTimer = 2f;
        }
    }

    private void handleStructureHKey() {
        if (!structPasteMode) {
            scanStructFiles();
            structPasteMode = true;
            structPasteIndex = 0;
            structCaptureMode = false; // exit capture mode if active
            journalMessage = savedStructNames.isEmpty() ? "NO STRUCTS SAVED" : "PASTE: " + savedStructNames.get(0);
            journalMessageTimer = 2f;
        } else {
            if (!savedStructNames.isEmpty()) {
                structPasteIndex = (structPasteIndex + 1) % savedStructNames.size();
                journalMessage = "PASTE: " + savedStructNames.get(structPasteIndex);
                journalMessageTimer = 2f;
            }
        }
    }

    private void scanStructFiles() {
        savedStructNames.clear();
        java.io.File dir = new java.io.File("worlds/structs");
        if (!dir.exists()) return;
        java.io.File[] files = dir.listFiles((d, n) -> n.endsWith(".struct"));
        if (files != null) {
            java.util.Arrays.sort(files);
            for (java.io.File f : files) {
                String n = f.getName();
                savedStructNames.add(n.substring(0, n.length() - 7)); // strip ".struct"
            }
        }
    }

    private void saveStruct(String name) {
        if (structA == null || structB == null) return;
        int x0 = Math.min(structA[0], structB[0]), x1 = Math.max(structA[0], structB[0]);
        int y0 = Math.min(structA[1], structB[1]), y1 = Math.max(structA[1], structB[1]);
        int z0 = Math.min(structA[2], structB[2]), z1 = Math.max(structA[2], structB[2]);
        java.util.List<String> lines = new java.util.ArrayList<>();
        lines.add("# " + name + "  " + (x1-x0+1) + "x" + (y1-y0+1) + "x" + (z1-z0+1));
        for (int wx = x0; wx <= x1; wx++) {
            for (int wy = y0; wy <= y1; wy++) {
                for (int wz = z0; wz <= z1; wz++) {
                    byte id = renderer.getBlock(wx, wy, wz);
                    if (id != BlockId.AIR) {
                        lines.add((wx-x0) + " " + (wy-y0) + " " + (wz-z0) + " " + (id & 0xFF));
                    }
                }
            }
        }
        try {
            java.nio.file.Path dir = java.nio.file.Path.of("worlds", "structs");
            java.nio.file.Files.createDirectories(dir);
            java.nio.file.Files.write(dir.resolve(name + ".struct"), lines, java.nio.charset.StandardCharsets.UTF_8);
            journalMessage = "SAVED: " + name + " (" + (lines.size()-1) + " blocks)";
            journalMessageTimer = 3f;
            scanStructFiles(); // refresh list
        } catch (Exception e) {
            journalMessage = "SAVE FAILED: " + e.getMessage();
            journalMessageTimer = 3f;
        }
    }

    private void stampStruct(int ox, int oy, int oz, String name) {
        try {
            java.nio.file.Path path = java.nio.file.Path.of("worlds", "structs", name + ".struct");
            if (!java.nio.file.Files.exists(path)) {
                journalMessage = "STRUCT NOT FOUND: " + name; journalMessageTimer = 2f; return;
            }
            int count = 0;
            for (String line : java.nio.file.Files.readAllLines(path, java.nio.charset.StandardCharsets.UTF_8)) {
                if (line.startsWith("#") || line.isBlank()) continue;
                String[] parts = line.split("\\s+");
                if (parts.length < 4) continue;
                int dx = Integer.parseInt(parts[0]), dy = Integer.parseInt(parts[1]), dz = Integer.parseInt(parts[2]);
                byte id = (byte) Integer.parseInt(parts[3]);
                renderer.setBlock(ox + dx, oy + dy, oz + dz, id);
                count++;
            }
            System.out.println("[Struct] Stamped " + name + " (" + count + " blocks) at " + ox + "," + oy + "," + oz);
        } catch (Exception e) {
            journalMessage = "STAMP FAILED: " + e.getMessage(); journalMessageTimer = 2f;
        }
    }
    // ───────────────────────────────────────────────────────────────────────────

    private void shutdown() {
        if (renderer != null) {
            renderer.saveEditsToDisk();
            savePlayerState();
            saveOptions();
            renderer.destroy();
        }
        if (audio != null) audio.destroy();
        if (window != NULL) glfwDestroyWindow(window);
        glfwTerminate();
        glfwSetErrorCallback(null).free();
    }
}






