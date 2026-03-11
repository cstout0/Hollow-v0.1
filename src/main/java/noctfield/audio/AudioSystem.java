package noctfield.audio;

import org.lwjgl.openal.AL;
import org.lwjgl.openal.ALC;
import org.lwjgl.openal.ALCCapabilities;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.ShortBuffer;
import java.util.Random;

import static org.lwjgl.openal.AL10.*;
import static org.lwjgl.openal.ALC10.*;

public class AudioSystem {

    private long device  = 0L;
    private long context = 0L;
    private boolean initialized = false;
    private float   masterGain  = 0.80f;  // M193: master volume (0.0-1.0), applied via AL_GAIN on listener
    private float   musicVolume = 0.80f;  // M212: music/ambient volume multiplier

    private static final int SRC_AMBIENT   = 0;
    private static final int SRC_HEARTBEAT = 1;
    private static final int SRC_STING     = 2;
    private static final int SRC_FOOTSTEP  = 3;
    private static final int SRC_BREATH    = 4; // M38 stamina exhaustion
    private static final int SRC_WEATHER   = 5; // M39 rain ambient / thunder
    private static final int SRC_DRIP      = 6; // M83 cave drip one-shots
    private static final int SRC_STALACTITE = 7; // M93 stalactite creak / crash
    private static final int SRC_HIT         = 8;  // M98 player hit impact thud
    private static final int SRC_STALKER_AMB = 9;  // M98 3D positional: stalker presence
    private static final int SRC_LURKER_AMB  = 10; // M98 3D positional: lurker presence
    private static final int SRC_AMBIENT_FILE  = 11; // M155: file-based ambient
    private static final int SRC_AMBIENT_EVENT = 12; // M155: event overlay (blood rain, dead fog)
    private static final int SRC_GHOST_EVENT   = 13; // M156: 3D positional psych events
    private static final int SRC_DEEP_AMB      = 14; // M168: THE DEEP 3D crawl
    private static final int SRC_SCREAMER      = 15; // M196: THE SCREAMER 3D one-shot scream
    private static final int SRC_NUN           = 16; // M201: THE NUN 3D footstep/strike
    private static final int SRC_VOICE         = 17; // M211: voice line one-shot (taunts + escalation)
    private static final int SRC_COUNT         = 18;

    private final int[] sources = new int[SRC_COUNT];

    // Ambient buffers
    private int bufAmbientPine, bufAmbientDead, bufAmbientSwamp;
    private int bufAmbientCave;       // M37
    // Heartbeat
    private int bufHeartSlow, bufHeartFast;
    // One-shots
    private int bufSting, bufFootstep, bufFootstepCave; // M95.1: cave echo variant
    // M38
    private int bufBreath;
    // M39
    private int bufRain, bufThunder;
    // M83
    private int bufDrip;
    // M93
    private int bufCreak, bufCrash;
    // M98
    private int bufHit;
    private int bufStalkerBreath, bufLurkerHiss;
    // M98: orientation buffer reused each frame
    private java.nio.FloatBuffer orientBuf;
    private int bufDeepCrawl; // M168
    private int bufScream   = 0; // M196: THE SCREAMER scream.wav
    private int bufNunStep  = 0; // M201: THE NUN heel-clop footstep
    private int bufNunStrike = 0; // M201: THE NUN knife strike
    // M211: voice line buffers (from sounds/)
    private int bufEsc1 = 0, bufEsc2 = 0, bufEsc3 = 0, bufEsc4 = 0;
    private int bufTauntThere = 0, bufTauntSeeme = 0, bufTauntWatching = 0;
    private int bufHideSeek = 0, bufHideSeekOver = 0; // M215: hide & seek event audio

    private int   currentBiomeAmbient = -1;
    private boolean underground        = false; // M37
    private float  currentAmbientGain  = 0f;

    // M83: drip timer (fires random drip sound underground)
    private float dripTimer = 0f;

    private float footstepTimer = 0.3f;
    private float stingCooldown = 0f;
    private float thunderCooldown = 0f;

    // M39: rain fade
    private boolean raining       = false;
    private float   rainGain      = 0f;

    // M155: file-based ambient system
    private int[] poolSurfacePine, poolSurfaceDead, poolNightLow, poolNightHigh;
    private int[] poolCaveShallow, poolCaveDeep, poolHighHorror;
    private int   bufBloodRain, bufStalkerAmb, bufHellAmbient, bufVoidGate;
    private int   bufHeartbeatFile, bufInfraSub, bufEchoVoices;
    private int   currentFileAmbient = 0;
    private int   nextFileAmbient    = 0;
    private float fileAmbientGain    = 0f;
    private float fileCrossfadeTimer = 0f;
    private static final float CROSSFADE_DURATION = 6.0f;
    private float   horrorLevel      = 0f;
    private boolean nightMode        = false;
    private boolean bloodRainActive  = false;
    private boolean deadFogActive    = false;
    private float   eventAmbientGain = 0f;
    private int     lastBiome        = 0;
    private final java.util.Random ambRng = new java.util.Random();
    private float ambientSilenceTimer = 0f; // M167: pause between tracks

    // M159: dead silence + post-silence slam
    private float deadSilenceTimer = 0f;
    private float deadSilenceTotal = 0f;
    private float postSilenceHeart = 0f; // heartbeat boost after silence ends

    private static final int SAMPLE_RATE = 22050;

    // ------------------------------------------------------------------ init

    public void init() {
        try {
            device = alcOpenDevice((ByteBuffer) null);
            if (device == 0L) { System.err.println("[Audio] No AL device."); return; }

            ALCCapabilities caps = ALC.createCapabilities(device);
            context = alcCreateContext(device, (IntBuffer) null);
            if (context == 0L) { alcCloseDevice(device); System.err.println("[Audio] No AL context."); return; }

            alcMakeContextCurrent(context);
            AL.createCapabilities(caps);

            for (int i = 0; i < SRC_COUNT; i++) {
                sources[i] = alGenSources();
                alSourcef(sources[i], AL_GAIN, 0f);
            }

            // Biome ambients
            bufAmbientPine  = alGenBuffers(); buildAmbient(bufAmbientPine,  0);
            bufAmbientDead  = alGenBuffers(); buildAmbient(bufAmbientDead,  1);
            bufAmbientSwamp = alGenBuffers(); buildAmbient(bufAmbientSwamp, 2);
            bufAmbientCave  = alGenBuffers(); buildCaveAmbient(bufAmbientCave); // M37

            // Heartbeat
            bufHeartSlow = alGenBuffers(); buildHeartbeat(bufHeartSlow, 68);
            bufHeartFast = alGenBuffers(); buildHeartbeat(bufHeartFast, 112);

            // One-shots
            bufSting         = alGenBuffers(); buildSting(bufSting);
            bufFootstep      = alGenBuffers(); buildFootstep(bufFootstep);
            bufFootstepCave  = alGenBuffers(); buildFootstepCave(bufFootstepCave); // M95.1

            // M38 / M39 / M83
            bufBreath  = alGenBuffers(); buildBreath(bufBreath);
            bufRain    = alGenBuffers(); buildRain(bufRain);
            bufThunder = alGenBuffers(); buildThunder(bufThunder);
            bufDrip    = alGenBuffers(); buildDrip(bufDrip);
            bufCreak   = alGenBuffers(); buildCreak(bufCreak);   // M93
            bufCrash   = alGenBuffers(); buildCrash(bufCrash);   // M93
            bufHit     = alGenBuffers(); buildHit(bufHit);       // M98
            bufStalkerBreath = alGenBuffers(); buildStalkerBreath(bufStalkerBreath); // M98
            bufLurkerHiss    = alGenBuffers(); buildLurkerHiss(bufLurkerHiss);       // M98
            bufDeepCrawl = alGenBuffers(); buildDeepCrawl(bufDeepCrawl);

            // Start ambient looping silently
            alSourcei(sources[SRC_AMBIENT], AL_BUFFER,  bufAmbientPine);
            alSourcei(sources[SRC_AMBIENT], AL_LOOPING, AL_TRUE);
            alSourcef(sources[SRC_AMBIENT], AL_GAIN,    0f);
            alSourcePlay(sources[SRC_AMBIENT]);

            // Heartbeat looping silently
            alSourcei(sources[SRC_HEARTBEAT], AL_BUFFER,  bufHeartSlow);
            alSourcei(sources[SRC_HEARTBEAT], AL_LOOPING, AL_TRUE);
            alSourcef(sources[SRC_HEARTBEAT], AL_GAIN,    0f);
            alSourcePlay(sources[SRC_HEARTBEAT]);

            // Rain looping silently
            alSourcei(sources[SRC_WEATHER], AL_BUFFER,  bufRain);
            alSourcei(sources[SRC_WEATHER], AL_LOOPING, AL_TRUE);
            alSourcef(sources[SRC_WEATHER], AL_GAIN,    0f);
            alSourcePlay(sources[SRC_WEATHER]);

            // M98: 3D positional audio setup
            // All non-entity sources: source-relative (always "in the player's head")
            int[] headSources = {SRC_AMBIENT, SRC_HEARTBEAT, SRC_STING, SRC_FOOTSTEP,
                                  SRC_BREATH, SRC_WEATHER, SRC_DRIP, SRC_STALACTITE, SRC_HIT,
                                  SRC_AMBIENT_FILE, SRC_AMBIENT_EVENT, SRC_VOICE}; // M155/M211
            for (int s : headSources) {
                alSourcei(sources[s], AL_SOURCE_RELATIVE, AL_TRUE);
                alSource3f(sources[s], AL_POSITION, 0f, 0f, 0f);
            }
            // Entity sources: world-space positional, looping at low gain until active
            alDistanceModel(AL_INVERSE_DISTANCE_CLAMPED);
            for (int s : new int[]{SRC_STALKER_AMB, SRC_LURKER_AMB}) {
                alSourcei(sources[s], AL_SOURCE_RELATIVE, AL_FALSE);
                alSourcef(sources[s], AL_REFERENCE_DISTANCE, 3f);   // full vol within 3 blocks
                alSourcef(sources[s], AL_MAX_DISTANCE, 12f);         // inaudible beyond 12 blocks (M100 fix)
                alSourcef(sources[s], AL_ROLLOFF_FACTOR, 2.5f);      // steep rolloff -- only near-range
                alSourcei(sources[s], AL_LOOPING, AL_TRUE);
                alSourcef(sources[s], AL_GAIN, 0f);                  // silent until active
            }
            alSourcei(sources[SRC_STALKER_AMB], AL_BUFFER, bufStalkerBreath);
            alSourcei(sources[SRC_LURKER_AMB],  AL_BUFFER, bufLurkerHiss);
            alSourcePlay(sources[SRC_STALKER_AMB]);
            alSourcePlay(sources[SRC_LURKER_AMB]);
            // M156: ghost event source -- world-space positional, one-shot
            alSourcei(sources[SRC_GHOST_EVENT], AL_SOURCE_RELATIVE, AL_FALSE);
            alSourcef(sources[SRC_GHOST_EVENT], AL_REFERENCE_DISTANCE, 1f);
            alSourcef(sources[SRC_GHOST_EVENT], AL_MAX_DISTANCE, 12f);
            alSourcef(sources[SRC_GHOST_EVENT], AL_ROLLOFF_FACTOR, 1.5f);
            alSourcef(sources[SRC_GHOST_EVENT], AL_GAIN, 0f);
            // M168: THE DEEP 3D ambient -- world-space positional
            alSourcei(sources[SRC_DEEP_AMB], AL_SOURCE_RELATIVE, AL_FALSE);
            alSourcef(sources[SRC_DEEP_AMB], AL_GAIN, 0f);
            // M196: THE SCREAMER 3D one-shot — world-space positional, fired when entity screams
            bufScream = AudioFileLoader.loadWav("sounds/scream.wav");
            alSourcei(sources[SRC_SCREAMER], AL_SOURCE_RELATIVE, AL_FALSE);
            alSourcef(sources[SRC_SCREAMER], AL_REFERENCE_DISTANCE, 6f);
            alSourcef(sources[SRC_SCREAMER], AL_MAX_DISTANCE,       60f);
            alSourcef(sources[SRC_SCREAMER], AL_ROLLOFF_FACTOR,     1.2f);
            alSourcef(sources[SRC_SCREAMER], AL_GAIN, 0f);
            // M201: THE NUN 3D positional one-shot (footstep + strike share one source)
            bufNunStep   = alGenBuffers(); buildNunStep(bufNunStep);
            bufNunStrike = alGenBuffers(); buildNunStrike(bufNunStrike);
            alSourcei(sources[SRC_NUN], AL_SOURCE_RELATIVE, AL_FALSE);
            alSourcef(sources[SRC_NUN], AL_REFERENCE_DISTANCE, 4f);
            alSourcef(sources[SRC_NUN], AL_MAX_DISTANCE,       40f);
            alSourcef(sources[SRC_NUN], AL_ROLLOFF_FACTOR,     1.4f);
            alSourcef(sources[SRC_NUN], AL_GAIN, 0f);
            // Orientation buffer (forward[0-2] + up[3-5])
            orientBuf = MemoryUtil.memAllocFloat(6);

            alListenerf(AL_GAIN, masterGain); // M193: apply initial master volume
            initialized = true;
            loadAudioFiles(); // M155: load klankbeeld ambient files
            System.out.println("[Audio] OpenAL initialized (17 sources, procedural + file-based ambient, 3D positional).");
        } catch (Exception e) {
            System.err.println("[Audio] Init failed: " + e.getMessage());
        }
    }

    // ----------------------------------------------------------------- update

    public void update(float dt, int biome, float sanity01,
                       boolean stalked, boolean onGround, boolean moving) {
        if (!initialized) return;

        if (allSourcesMuted) return; // M102: full silence test
        lastBiome = biome; // M155: track for scheduleNextAmbient
        stingCooldown   = Math.max(0f, stingCooldown - dt);
        thunderCooldown = Math.max(0f, thunderCooldown - dt);

        // ---- Ambient (surface vs cave) ----
        int wantBuf = underground
                ? bufAmbientCave
                : switch (biome) {
                    case 1  -> bufAmbientDead;
                    case 2  -> bufAmbientSwamp;
                    default -> bufAmbientPine;
                };
        int wantBiomeKey = underground ? 99 : biome;
        if (currentBiomeAmbient != wantBiomeKey) {
            currentBiomeAmbient = wantBiomeKey;
            alSourceStop(sources[SRC_AMBIENT]);
            alSourcei(sources[SRC_AMBIENT], AL_BUFFER, wantBuf);
            alSourcef(sources[SRC_AMBIENT], AL_GAIN,   0f);
            alSourcePlay(sources[SRC_AMBIENT]);
            currentAmbientGain = 0f;
        }
        float targetGain = underground ? 0.08f : 0.20f; // M175: -35% // M100: cave ambient quieter -- 42Hz drone was too loud
        if (caveAmbientMuted && underground) targetGain = 0f; // M102: mute test
        currentAmbientGain += (targetGain - currentAmbientGain) * Math.min(1f, dt * 1.4f);
        alSourcef(sources[SRC_AMBIENT], AL_GAIN, currentAmbientGain);

        // ---- Heartbeat ----
        int   wantHBuf  = stalked ? bufHeartFast : bufHeartSlow;
        float heartGain = stalked ? 0.60f
                : (sanity01 < 0.45f ? (0.45f - sanity01) / 0.45f * 0.42f : 0f);
        if (alGetSourcei(sources[SRC_HEARTBEAT], AL_BUFFER) != wantHBuf) {
            alSourceStop(sources[SRC_HEARTBEAT]);
            alSourcei(sources[SRC_HEARTBEAT], AL_BUFFER, wantHBuf);
            alSourcePlay(sources[SRC_HEARTBEAT]);
        }
        // M159: post-silence heartbeat slam (fades out over ~3s)
        if (postSilenceHeart > 0f) {
            postSilenceHeart = Math.max(0f, postSilenceHeart - dt);
            heartGain = Math.min(1.6f, Math.max(heartGain, 0.35f) * (1f + postSilenceHeart * 1.5f));
        }
        alSourcef(sources[SRC_HEARTBEAT], AL_GAIN, heartGain);

        // ---- Footsteps ----
        if (onGround && moving) {
            footstepTimer -= dt;
            if (footstepTimer <= 0f) {
                // M95.1: slower cadence underground (cautious + echo tail needs room)
                footstepTimer = underground ? 0.52f + new Random().nextFloat() * 0.12f
                                            : 0.40f + new Random().nextFloat() * 0.10f;
                int fsBuf = underground ? bufFootstepCave : bufFootstep;
                playOneShot(SRC_FOOTSTEP, fsBuf, 0.30f + new Random().nextFloat() * 0.08f);
            }
        } else {
            footstepTimer = 0.15f;
        }

        // ---- Cave drips (M83) ----
        if (underground) {
            dripTimer -= dt;
            if (dripTimer <= 0f) {
                playOneShot(SRC_DRIP, bufDrip, 0.30f + new Random().nextFloat() * 0.20f);
                dripTimer = 3.0f + new Random().nextFloat() * 6.0f; // next drip in 3-9 seconds
            }
        } else {
            dripTimer = 1.5f; // reset so first drip happens quickly after entering a cave
        }

        // ---- Rain ambient (M39) ----
        float rainTarget = raining ? 0.26f : 0f; // M175: -35%
        rainGain += (rainTarget - rainGain) * Math.min(1f, dt * 0.6f);
        alSourcef(sources[SRC_WEATHER], AL_GAIN, rainGain);

        // ---- M167: File-based ambient (SRC_AMBIENT_FILE) ----
        // Silence gap: count down after a track ends before starting the next
        if (ambientSilenceTimer > 0f) {
            ambientSilenceTimer -= dt;
            if (ambientSilenceTimer <= 0f) {
                // Silence over — start the queued next track
                currentFileAmbient = nextFileAmbient;
                if (currentFileAmbient != 0) {
                    alSourceStop(sources[SRC_AMBIENT_FILE]);
                    alSourcei(sources[SRC_AMBIENT_FILE], AL_BUFFER, currentFileAmbient);
                    alSourcei(sources[SRC_AMBIENT_FILE], AL_LOOPING, AL_FALSE);
                    alSourcef(sources[SRC_AMBIENT_FILE], AL_GAIN, 0f);
                    alSourcePlay(sources[SRC_AMBIENT_FILE]);
                    fileCrossfadeTimer = 0f;
                    scheduleNextAmbient();
                }
            }
        } else if (currentFileAmbient == 0 && nextFileAmbient != 0) {
            // Very first track: start immediately (no silence at boot)
            currentFileAmbient = nextFileAmbient;
            alSourceStop(sources[SRC_AMBIENT_FILE]);
            alSourcei(sources[SRC_AMBIENT_FILE], AL_BUFFER, currentFileAmbient);
            alSourcei(sources[SRC_AMBIENT_FILE], AL_LOOPING, AL_FALSE);
            alSourcef(sources[SRC_AMBIENT_FILE], AL_GAIN, 0f);
            alSourcePlay(sources[SRC_AMBIENT_FILE]);
            fileCrossfadeTimer = 0f;
            scheduleNextAmbient();
        } else if (currentFileAmbient != 0) {
            int srcState = alGetSourcei(sources[SRC_AMBIENT_FILE], AL_SOURCE_STATE);
            if (srcState == AL_STOPPED) {
                // Track finished — enter silence gap before next track
                currentFileAmbient = 0;
                fileAmbientGain = 0f;
                alSourcef(sources[SRC_AMBIENT_FILE], AL_GAIN, 0f);
                scheduleNextAmbient(); // pre-pick next track during silence
                ambientSilenceTimer = 15f + ambRng.nextFloat() * 35f; // 15-50s quiet gap
            }
        }
        if (currentFileAmbient != 0) {
            fileCrossfadeTimer = Math.min(fileCrossfadeTimer + dt, CROSSFADE_DURATION);
            float targetFileGain = 0.40f; // M167: quieter — was 0.85
            fileAmbientGain = fileCrossfadeTimer / CROSSFADE_DURATION * targetFileGain;
            alSourcef(sources[SRC_AMBIENT_FILE], AL_GAIN, fileAmbientGain * musicVolume);
        }

        // ---- M155: Event ambient (SRC_AMBIENT_EVENT: blood rain / dead fog) ----
        int wantEventBuf = 0;
        if (bloodRainActive)    wantEventBuf = bufBloodRain;
        else if (deadFogActive) wantEventBuf = bufInfraSub;

        if (wantEventBuf != 0) {
            // Ensure correct buffer is playing
            int curEvBuf = alGetSourcei(sources[SRC_AMBIENT_EVENT], AL_BUFFER);
            if (curEvBuf != wantEventBuf) {
                alSourceStop(sources[SRC_AMBIENT_EVENT]);
                alSourcei(sources[SRC_AMBIENT_EVENT], AL_BUFFER, wantEventBuf);
                alSourcei(sources[SRC_AMBIENT_EVENT], AL_LOOPING, AL_TRUE);
                alSourcef(sources[SRC_AMBIENT_EVENT], AL_GAIN, 0f);
                alSourcePlay(sources[SRC_AMBIENT_EVENT]);
                eventAmbientGain = 0f;
            }
            float evTarget = bloodRainActive ? 0.33f : 0.20f; // M175: -35%
            eventAmbientGain += (evTarget - eventAmbientGain) * Math.min(1f, dt * 0.8f);
            alSourcef(sources[SRC_AMBIENT_EVENT], AL_GAIN, eventAmbientGain * musicVolume);
        } else {
            eventAmbientGain -= eventAmbientGain * Math.min(1f, dt * 1.5f);
            alSourcef(sources[SRC_AMBIENT_EVENT], AL_GAIN, eventAmbientGain * musicVolume);
            if (eventAmbientGain < 0.005f) {
                eventAmbientGain = 0f;
                alSourceStop(sources[SRC_AMBIENT_EVENT]);
            }
        }

        // M159: DEAD SILENCE — overrides all gains to zero; triggers heartbeat slam on expiry
        if (deadSilenceTimer > 0f) {
            deadSilenceTimer -= dt;
            for (int i = 0; i < SRC_COUNT; i++) alSourcef(sources[i], AL_GAIN, 0f);
            if (deadSilenceTimer <= 0f) {
                deadSilenceTimer = 0f;
                postSilenceHeart = 3.0f; // heartbeat roars back at triple volume for 3s
            }
        }
    }

    /** M33: watcher event sting */
    public void triggerEventSting() {
        if (!initialized || stingCooldown > 0f) return;
        playOneShot(SRC_STING, bufSting, 0.55f);
        stingCooldown = 3.0f;
    }

    /** M156: Ghost footstep — plays behind the player at low volume (psychological horror). */
    public void triggerPsychGhostStep(float px, float py, float pz, float fx, float fz) {
        if (!initialized) return;
        // Position 3-5 blocks behind the player with a small random lateral offset
        float dist    = 3.5f + (float)Math.random() * 2f;
        float side    = ((float)Math.random() - 0.5f) * 1.5f;
        float perpX   = -fz, perpZ = fx; // perpendicular to forward
        float gx = px - fx * dist + perpX * side;
        float gz = pz - fz * dist + perpZ * side;
        alSource3f(sources[SRC_GHOST_EVENT], AL_POSITION, gx, py, gz);
        alSourcef(sources[SRC_GHOST_EVENT],  AL_GAIN, 0f);
        alSourcei(sources[SRC_GHOST_EVENT],  AL_BUFFER,  bufFootstep);
        alSourcei(sources[SRC_GHOST_EVENT],  AL_LOOPING, AL_FALSE);
        alSourcef(sources[SRC_GHOST_EVENT],  AL_GAIN,    0.45f);
        alSourcePlay(sources[SRC_GHOST_EVENT]);
    }

    /** M156: Overhead creak — plays 3-4 blocks above the player (something is above you). */
    public void triggerPsychCreak(float px, float py, float pz) {
        if (!initialized) return;
        alSource3f(sources[SRC_GHOST_EVENT], AL_POSITION, px, py, pz);
        alSourcei(sources[SRC_GHOST_EVENT],  AL_BUFFER,  bufCreak);
        alSourcei(sources[SRC_GHOST_EVENT],  AL_LOOPING, AL_FALSE);
        alSourcef(sources[SRC_GHOST_EVENT],  AL_GAIN,    0.55f);
        alSourcePlay(sources[SRC_GHOST_EVENT]);
    }

    /** M38: play exhaustion breath (called when stamina bottoms out) */
    public void triggerBreath() {
        if (!initialized) return;
        playOneShot(SRC_BREATH, bufBreath, 0.65f);
    }

    /** M39: single thunder crack */
    public void triggerThunder() {
        if (!initialized || thunderCooldown > 0f) return;
        playOneShot(SRC_WEATHER, bufThunder, 0.80f);
        thunderCooldown = 2.0f;
    }

    /** M37: switch ambient to cave drone when underground */
    public void setUnderground(boolean v) { underground = v; }

    /** M39: fade rain ambient in/out */
    public void setRaining(boolean v) { raining = v; }

    /** M102: silence every managed source for isolation testing */
    private boolean allSourcesMuted = false;
    public void toggleAllSourcesMute() {
        allSourcesMuted = !allSourcesMuted;
        System.out.println("[Audio] ALL sources " + (allSourcesMuted ? "MUTED" : "UNMUTED"));
        if (allSourcesMuted) {
            for (int i = 0; i < SRC_COUNT; i++) alSourceStop(sources[i]);
        } else {
            // restart the expected looping sources
            alSourcePlay(sources[SRC_AMBIENT]);
            alSourcePlay(sources[SRC_HEARTBEAT]);
            alSourcePlay(sources[SRC_WEATHER]);
            alSourcePlay(sources[SRC_STALKER_AMB]);
            alSourcePlay(sources[SRC_LURKER_AMB]);
            // M156: ghost event source -- world-space positional, one-shot
            alSourcei(sources[SRC_GHOST_EVENT], AL_SOURCE_RELATIVE, AL_FALSE);
            alSourcef(sources[SRC_GHOST_EVENT], AL_REFERENCE_DISTANCE, 1f);
            alSourcef(sources[SRC_GHOST_EVENT], AL_MAX_DISTANCE, 12f);
            alSourcef(sources[SRC_GHOST_EVENT], AL_ROLLOFF_FACTOR, 1.5f);
            alSourcef(sources[SRC_GHOST_EVENT], AL_GAIN, 0f);
        }
    }
    public boolean isAllSourcesMuted() { return allSourcesMuted; }

    /** M102: toggle cave ambient mute for isolation testing */
    private boolean caveAmbientMuted = false;
    public void toggleCaveAmbientMute() {
        caveAmbientMuted = !caveAmbientMuted;
        System.out.println("[Audio] Cave ambient " + (caveAmbientMuted ? "MUTED" : "UNMUTED"));
        if (caveAmbientMuted && underground) alSourcef(sources[SRC_AMBIENT], AL_GAIN, 0f);
    }
    public boolean isCaveAmbientMuted() { return caveAmbientMuted; }

    /** M100: dump all source states/gains to console for debug */
    public void debugPrintState() {
        if (!initialized) { System.out.println("[AudioDbg] Not initialized"); return; }
        String[] names = {"AMBIENT","HEARTBEAT","STING","FOOTSTEP","BREATH",
                          "WEATHER","DRIP","STALACTITE","HIT","STALKER_AMB","LURKER_AMB","AMBIENT_FILE","AMBIENT_EVENT"};
        System.out.println("=== AUDIO DEBUG ===");
        for (int i = 0; i < SRC_COUNT; i++) {
            int state = alGetSourcei(sources[i], AL_SOURCE_STATE);
            float gain = alGetSourcef(sources[i], AL_GAIN);
            boolean rel  = alGetSourcei(sources[i], AL_SOURCE_RELATIVE) == AL_TRUE;
            String stateStr = switch (state) {
                case 0x1011 -> "INITIAL";
                case 0x1012 -> "PLAYING";
                case 0x1013 -> "PAUSED";
                case 0x1014 -> "STOPPED";
                default     -> "UNKNOWN:" + state;
            };
            System.out.printf("  SRC[%2d] %-12s  gain=%.3f  %-7s  rel=%b%n",
                    i, names[i], gain, stateStr, rel);
        }
        java.nio.FloatBuffer lbuf = MemoryUtil.memAllocFloat(3);
        alGetListenerfv(AL_POSITION, lbuf);
        System.out.printf("  LISTENER pos=(%.1f, %.1f, %.1f)%n", lbuf.get(0), lbuf.get(1), lbuf.get(2));
        MemoryUtil.memFree(lbuf);
        System.out.println("==================");
    }

    /** M100: return a summary line per source for HUD overlay */
    public String[] debugStateLines() {
        if (!initialized) return new String[]{"[Audio] not initialized"};
        String[] names = {"AMB","HEART","STING","STEP","BREATH",
                          "WEATHER","DRIP","STALACT","HIT","STLKR","LRKR","AMBFILE","AMBEVT"};
        String[] lines = new String[SRC_COUNT + 1];
        for (int i = 0; i < SRC_COUNT; i++) {
            int state = alGetSourcei(sources[i], AL_SOURCE_STATE);
            float gain = alGetSourcef(sources[i], AL_GAIN);
            String s = state == 0x1012 ? "PLAY" : state == 0x1013 ? "PAUSE"
                     : state == 0x1014 ? "STOP" : "INIT";
            lines[i] = String.format("%-7s %s g=%.2f", names[i], s, gain);
        }
        java.nio.FloatBuffer lpBuf = MemoryUtil.memAllocFloat(3);
        alGetListenerfv(AL_POSITION, lpBuf);
        lines[SRC_COUNT] = String.format("Lstnr (%.0f,%.0f,%.0f)", lpBuf.get(0), lpBuf.get(1), lpBuf.get(2));
        MemoryUtil.memFree(lpBuf);
        return lines;
    }

    /** M98: update listener world position; M102: orientation removed -- caused driver artifacts */
    public void setListenerTransform(float x, float y, float z,
                                     float fwdX, float fwdY, float fwdZ) {
        if (!initialized) return;
        alListener3f(AL_POSITION, x, y, z);
        // NOTE: orientation update removed in M102 -- calling alListenerfv every frame caused
        // audio driver resampling artifacts on Windows OpenAL. Entity position uses world coords
        // so left/right panning is still correct relative to listener position.
    }

    /** M98: update world-space positions and gain for enemy presence loops */
    public void updateEnemyPositions(float sx, float sy, float sz, boolean stalkerActive,
                                     float lx, float ly, float lz, boolean lurkerActive) {
        if (!initialized) return;
        if (stalkerActive) {
            alSource3f(sources[SRC_STALKER_AMB], AL_POSITION, sx, sy, sz);
            alSourcef(sources[SRC_STALKER_AMB], AL_GAIN, 0.28f); // M100: reduced -- only near-range
        } else {
            alSourcef(sources[SRC_STALKER_AMB], AL_GAIN, 0f);
        }
        if (lurkerActive) {
            alSource3f(sources[SRC_LURKER_AMB], AL_POSITION, lx, ly, lz);
            alSourcef(sources[SRC_LURKER_AMB], AL_GAIN, 0.22f); // M100: reduced
        } else {
            alSourcef(sources[SRC_LURKER_AMB], AL_GAIN, 0f);
        }
    }

    public void setDeepState(float x, float y, float z, boolean active, boolean hunting) {
        if (!initialized) return;
        if (active && hunting) {
            int st = alGetSourcei(sources[SRC_DEEP_AMB], AL_SOURCE_STATE);
            if (st != AL_PLAYING) {
                alSourcei(sources[SRC_DEEP_AMB], AL_BUFFER,  bufDeepCrawl);
                alSourcei(sources[SRC_DEEP_AMB], AL_LOOPING, AL_TRUE);
                alSourcef(sources[SRC_DEEP_AMB], AL_GAIN,    0f);
                alSourcePlay(sources[SRC_DEEP_AMB]);
            }
            alSource3f(sources[SRC_DEEP_AMB], AL_POSITION, x, y, z);
            alSourcef(sources[SRC_DEEP_AMB],  AL_GAIN,     0.55f);
            alSourcef(sources[SRC_DEEP_AMB],  AL_REFERENCE_DISTANCE, 4f);
            alSourcef(sources[SRC_DEEP_AMB],  AL_MAX_DISTANCE,       22f);
            alSourcef(sources[SRC_DEEP_AMB],  AL_ROLLOFF_FACTOR,     1.4f);
        } else {
            alSourcef(sources[SRC_DEEP_AMB], AL_GAIN, 0f);
            if (!active) alSourceStop(sources[SRC_DEEP_AMB]);
        }
    }

    /** M98: player took a hit — short sharp impact thud */
    public void triggerHitImpact() {
        if (!initialized) return;
        playOneShot(SRC_HIT, bufHit, 0.70f);
    }

    // ---- M159: jumpscare audio triggers ----

    /** Fires THE FACE scream: sting at max volume + crash — pure adrenaline jolt. */
    public void triggerJumpscareScream() {
        if (!initialized) return;
        alSourceStop(sources[SRC_STING]);
        alSourcei(sources[SRC_STING], AL_LOOPING, AL_FALSE);
        alSourcei(sources[SRC_STING], AL_BUFFER,  bufSting);
        alSourcef(sources[SRC_STING], AL_GAIN,    1.8f);
        alSourcePlay(sources[SRC_STING]);
        alSourceStop(sources[SRC_STALACTITE]);
        alSourcei(sources[SRC_STALACTITE], AL_LOOPING, AL_FALSE);
        alSourcei(sources[SRC_STALACTITE], AL_BUFFER,  bufCrash);
        alSourcef(sources[SRC_STALACTITE], AL_GAIN,    1.2f);
        alSourcePlay(sources[SRC_STALACTITE]);
        stingCooldown = 6.0f;
    }

    /** M196: THE SCREAMER — plays real scream.wav at 3D world position. */
    public void triggerScreamerScream(float x, float y, float z) {
        if (!initialized || bufScream == 0) return;
        alSourceStop(sources[SRC_SCREAMER]);
        alSource3f(sources[SRC_SCREAMER], AL_POSITION, x, y, z);
        alSourcei(sources[SRC_SCREAMER], AL_BUFFER,  bufScream);
        alSourcei(sources[SRC_SCREAMER], AL_LOOPING, AL_FALSE);
        alSourcef(sources[SRC_SCREAMER], AL_GAIN,    masterGain * 2.0f);
        alSourcePlay(sources[SRC_SCREAMER]);
    }

    /** M201: THE NUN — heel-clop footstep at 3D world position. */
    public void triggerNunStep(float x, float y, float z) {
        if (!initialized || bufNunStep == 0) return;
        alSourceStop(sources[SRC_NUN]);
        alSource3f(sources[SRC_NUN], AL_POSITION, x, y, z);
        alSourcei(sources[SRC_NUN], AL_BUFFER,  bufNunStep);
        alSourcei(sources[SRC_NUN], AL_LOOPING, AL_FALSE);
        alSourcef(sources[SRC_NUN], AL_GAIN,    masterGain * 1.4f);
        alSourcePlay(sources[SRC_NUN]);
    }

    /** M201: THE NUN — knife strike impact at 3D world position. */
    public void triggerNunStrike(float x, float y, float z) {
        if (!initialized || bufNunStrike == 0) return;
        alSourceStop(sources[SRC_NUN]);
        alSource3f(sources[SRC_NUN], AL_POSITION, x, y, z);
        alSourcei(sources[SRC_NUN], AL_BUFFER,  bufNunStrike);
        alSourcei(sources[SRC_NUN], AL_LOOPING, AL_FALSE);
        alSourcef(sources[SRC_NUN], AL_GAIN,    masterGain * 1.8f);
        alSourcePlay(sources[SRC_NUN]);
    }

    /** Fires FALSE CHARGE: loud hit + sting — sounds like a sprint attack that never lands. */
    public void triggerFalseCharge() {
        if (!initialized) return;
        alSourceStop(sources[SRC_HIT]);
        alSourcei(sources[SRC_HIT], AL_LOOPING, AL_FALSE);
        alSourcei(sources[SRC_HIT], AL_BUFFER,  bufHit);
        alSourcef(sources[SRC_HIT], AL_GAIN,    1.5f);
        alSourcePlay(sources[SRC_HIT]);
        alSourceStop(sources[SRC_STING]);
        alSourcei(sources[SRC_STING], AL_LOOPING, AL_FALSE);
        alSourcei(sources[SRC_STING], AL_BUFFER,  bufSting);
        alSourcef(sources[SRC_STING], AL_GAIN,    0.9f);
        alSourcePlay(sources[SRC_STING]);
        stingCooldown = 4.0f;
    }

    /** Triggers DEAD SILENCE: mutes all audio for {@code duration} seconds, then slams heartbeat back. */
    public void triggerDeadSilence(float duration) {
        if (!initialized) return;
        deadSilenceTimer = duration;
        deadSilenceTotal = duration;
    }

    /** @return true if dead silence is currently active. */
    public boolean isDeadSilenceActive() { return initialized && deadSilenceTimer > 0f; }

    /** @return fraction of dead silence remaining (1.0 = just started, 0.0 = about to end). */
    public float deadSilenceRemaining() {
        if (deadSilenceTotal <= 0f) return 0f;
        return Math.max(0f, deadSilenceTimer / deadSilenceTotal);
    }

    /** M93: stalactite starting to fall — low warning creak */
    public void triggerStalactiteCreak() {
        if (!initialized) return;
        playOneShot(SRC_STALACTITE, bufCreak, 0.55f);
    }

    /** M93: stalactite impact — sharp crash */
    public void triggerStalactiteCrash() {
        if (!initialized) return;
        playOneShot(SRC_STALACTITE, bufCrash, 0.75f);
    }


    // ----------------------------------------------------------------- M155 setters

    /** M155: set horror escalation level 0..1 (affects file ambient selection) */
    public void setHorrorLevel(float h) { horrorLevel = Math.max(0f, Math.min(1f, h)); }

    /** M155: tell audio whether blood rain is active; swaps event source if changed */
    public void setBloodRain(boolean active) {
        if (active != bloodRainActive) {
            bloodRainActive = active;
            // Force event source swap on next update by resetting gain if turning off
            if (!active) eventAmbientGain = Math.min(eventAmbientGain, 0.1f);
        }
    }

    /** M155: tell audio whether DEAD_FOG infrasound layer is active */
    public void setDeadFog(boolean active) { deadFogActive = active; }

    /** M155: pass current night/day state for ambient selection */
    public void setNightMode(boolean night) { nightMode = night; }

    // ----------------------------------------------------------------- M155 audio file loader

    /** M155: load all klankbeeld ambient files and build pools. Called from init(). */
    private void loadAudioFiles() {
        String base = "audio/";

        // Surface PINE pools (day)
        int b01 = AudioFileLoader.loadWav(base + "170310__klankbeeld__horror-ambience-01.wav");
        int b02 = AudioFileLoader.loadWav(base + "170317__klankbeeld__horror-ambience-02.wav");
        int b03 = AudioFileLoader.loadWav(base + "170318__klankbeeld__horror-ambience-03.wav");
        poolSurfacePine = filterPool(new int[]{b01, b02, b03});

        // Surface DEAD/SWAMP pools (day)
        int b04 = AudioFileLoader.loadWav(base + "170323__klankbeeld__horror-ambience-04.wav");
        int b06 = AudioFileLoader.loadWav(base + "170329__klankbeeld__horror-ambience-06.wav");
        poolSurfaceDead = filterPool(new int[]{b04, b06});

        // Night low horror
        int b11 = AudioFileLoader.loadWav(base + "133442__klankbeeld__horror-ambience-11.wav");
        int b12 = AudioFileLoader.loadWav(base + "133659__klankbeeld__horror-ambience-12.wav");
        poolNightLow = filterPool(new int[]{b11, b12});

        // Night medium/high horror  (OGG + WAV mix)
        int b14  = AudioFileLoader.loadWav(base + "135836__klankbeeld__horror-ambience-14.wav");
        int b26  = AudioFileLoader.loadWav(base + "172036__klankbeeld__horror-ambience-26.wav");
        int b27  = AudioFileLoader.loadWav(base + "172147__klankbeeld__horror-ambience-27.wav");
        int o64  = AudioFileLoader.loadOgg(base + "202799__klankbeeld__horror-ambience-64-130709_01.ogg");
        int o75  = AudioFileLoader.loadOgg(base + "237275__klankbeeld__horror-ambience-75-140328_143.ogg");
        poolNightHigh = filterPool(new int[]{b26, b27, o75});

        // High horror pools (OGG)
        int o80  = AudioFileLoader.loadOgg(base + "261399__klankbeeld__horror-ambience-80-150112_0504.ogg");
        poolHighHorror = filterPool(new int[]{b14, o64, o80});

        // Cave shallow
        int b17 = AudioFileLoader.loadWav(base + "170354__klankbeeld__horror-ambience-17.wav");
        int b18 = AudioFileLoader.loadWav(base + "170368__klankbeeld__horror-ambience-18.wav");
        poolCaveShallow = filterPool(new int[]{b17, b18});

        // Cave deep
        int b19 = AudioFileLoader.loadWav(base + "170375__klankbeeld__horror-ambience-19.wav");
        int b29 = AudioFileLoader.loadWav(base + "172405__klankbeeld__horror-ambience-29.wav");
        int b30 = AudioFileLoader.loadWav(base + "172642__klankbeeld__horror-ambience-30.wav");
        poolCaveDeep = filterPool(new int[]{b19, b29, b30});

        // Standalone event buffers
        bufBloodRain    = AudioFileLoader.loadWav(base + "126323__klankbeeld__horror-zombie-hell-atmos.wav");
        bufStalkerAmb   = AudioFileLoader.loadWav(base + "179845__klankbeeld__horror-ambience-40-mosters-172648.wav");
        bufHellAmbient  = AudioFileLoader.loadWav(base + "180964__klankbeeld__horror-ambience-41-hell-130228_00.wav");
        bufVoidGate     = AudioFileLoader.loadWav(base + "234122__klankbeeld__horror-prayer-roman-catholic-02-120306_01.wav");
        bufHeartbeatFile= AudioFileLoader.loadWav(base + "182288__klankbeeld__horror-heart-beat-8bpm.wav");
        bufInfraSub     = AudioFileLoader.loadWav(base + "182308__klankbeeld__horror-infra-sound-sub-aural.wav");
        bufEchoVoices   = AudioFileLoader.loadWav(base + "367197__klankbeeld__horror-echo-voices-160925_0978.wav");
        int bufHellBg   = AudioFileLoader.loadWav(base + "400635__klankbeeld__hell-background-170817.wav");
        poolHighHorror  = appendToPool(poolHighHorror, bufHellBg);

        System.out.println("[Audio] M155 file pools built: pine=" + poolSurfacePine.length
                + " dead=" + poolSurfaceDead.length + " nightLow=" + poolNightLow.length
                + " nightHi=" + poolNightHigh.length + " caveShallow=" + poolCaveShallow.length
                + " caveDeep=" + poolCaveDeep.length + " highHorror=" + poolHighHorror.length);

        // M211: voice lines from sounds/
        bufEsc1          = AudioFileLoader.loadWav("sounds/esc_1.wav");
        bufEsc2          = AudioFileLoader.loadWav("sounds/esc_2.wav");
        bufEsc3          = AudioFileLoader.loadWav("sounds/esc_3.wav");
        bufEsc4          = AudioFileLoader.loadWav("sounds/esc_4.wav");
        bufTauntThere    = AudioFileLoader.loadWav("sounds/taunt_there.wav");
        bufTauntSeeme    = AudioFileLoader.loadWav("sounds/taunt_seeme.wav");
        bufTauntWatching = AudioFileLoader.loadWav("sounds/taunt_watching.wav");
        bufHideSeek      = AudioFileLoader.loadWav("sounds/event_hide.wav");     // M215
        bufHideSeekOver  = AudioFileLoader.loadWav("sounds/event_hide_over.wav"); // M215

        // M211: voice lines from sounds/
        bufEsc1          = AudioFileLoader.loadWav("sounds/esc_1.wav");
        bufEsc2          = AudioFileLoader.loadWav("sounds/esc_2.wav");
        bufEsc3          = AudioFileLoader.loadWav("sounds/esc_3.wav");
        bufEsc4          = AudioFileLoader.loadWav("sounds/esc_4.wav");
        bufTauntThere    = AudioFileLoader.loadWav("sounds/taunt_there.wav");
        bufTauntSeeme    = AudioFileLoader.loadWav("sounds/taunt_seeme.wav");
        bufTauntWatching = AudioFileLoader.loadWav("sounds/taunt_watching.wav");
        bufHideSeek      = AudioFileLoader.loadWav("sounds/event_hide.wav");     // M215
        bufHideSeekOver  = AudioFileLoader.loadWav("sounds/event_hide_over.wav"); // M215

        scheduleNextAmbient();
    }

    /** Pick next file ambient buffer based on current state. */
    private void scheduleNextAmbient() {
        int[] candidates;
        if (bloodRainActive) {
            nextFileAmbient = bufBloodRain;
            return;
        } else if (underground && horrorLevel > 0.6f) {
            candidates = appendToPool(poolCaveDeep, bufHellAmbient);
        } else if (underground) {
            candidates = poolCaveShallow.length > 0 ? poolCaveShallow : poolCaveDeep;
        } else if (nightMode && horrorLevel > 0.6f) {
            candidates = appendToPool(poolNightHigh, poolHighHorror);
        } else if (nightMode) {
            candidates = poolNightLow.length > 0 ? poolNightLow : poolNightHigh;
        } else if (lastBiome == 1 || lastBiome == 2) {
            candidates = poolSurfaceDead.length > 0 ? poolSurfaceDead : poolSurfacePine;
        } else {
            candidates = poolSurfacePine.length > 0 ? poolSurfacePine : poolSurfaceDead;
        }
        candidates = filterPool(candidates);
        if (candidates.length == 0) { nextFileAmbient = 0; return; }
        // Avoid repeating current track if possible
        int pick = candidates[ambRng.nextInt(candidates.length)];
        if (candidates.length > 1 && pick == currentFileAmbient) {
            pick = candidates[ambRng.nextInt(candidates.length)];
        }
        nextFileAmbient = pick;
    }

    /** Remove 0 entries from a buffer ID pool */
    private static int[] filterPool(int[] pool) {
        int count = 0;
        for (int v : pool) if (v != 0) count++;
        int[] out = new int[count];
        int j = 0;
        for (int v : pool) if (v != 0) out[j++] = v;
        return out;
    }

    /** Append a single buffer ID to a pool (ignores 0) */
    private static int[] appendToPool(int[] pool, int extra) {
        if (extra == 0) return pool;
        int[] out = new int[pool.length + 1];
        System.arraycopy(pool, 0, out, 0, pool.length);
        out[pool.length] = extra;
        return out;
    }

    /** Append two pools together */
    private static int[] appendToPool(int[] a, int[] b) {
        int[] out = new int[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return filterPool(out);
    }
    public boolean isInitialized() { return initialized; }

    // -------------------------------------------------------------- destroy

    // M193: master volume control
    public float getMasterVolume()             { return masterGain; }
    public void  setMasterVolume(float v) {
        masterGain = Math.max(0f, Math.min(1f, v));
        if (initialized) alListenerf(AL_GAIN, masterGain);
    }
    public void  adjustMasterVolume(float delta) { setMasterVolume(masterGain + delta); }
    public float getMusicVolume()         { return musicVolume; }
    public void  setMusicVolume(float v)  { musicVolume = Math.max(0f, Math.min(1f, v)); }
    public void  adjustMusicVolume(float delta) { setMusicVolume(musicVolume + delta); }

    public void destroy() {
        if (!initialized) return;
        for (int s : sources) { alSourceStop(s); alDeleteSources(s); }
        if (orientBuf != null) { MemoryUtil.memFree(orientBuf); orientBuf = null; }
        for (int b : new int[]{
                bufAmbientPine, bufAmbientDead, bufAmbientSwamp, bufAmbientCave,
                bufHeartSlow, bufHeartFast, bufSting, bufFootstep, bufFootstepCave,
                bufBreath, bufRain, bufThunder, bufDrip, bufCreak, bufCrash, bufHit,
                bufStalkerBreath, bufLurkerHiss, bufDeepCrawl}) {
            alDeleteBuffers(b);
        }
        // M155: delete file-based ambient buffers
        for (int[] pool : new int[][]{poolSurfacePine, poolSurfaceDead, poolNightLow,
                poolNightHigh, poolCaveShallow, poolCaveDeep, poolHighHorror}) {
            if (pool != null) for (int b : pool) if (b != 0) alDeleteBuffers(b);
        }
        for (int b : new int[]{bufBloodRain, bufStalkerAmb, bufHellAmbient, bufVoidGate,
                bufHeartbeatFile, bufInfraSub, bufEchoVoices}) {
            if (b != 0) alDeleteBuffers(b);
        }
        alcMakeContextCurrent(0L);
        alcDestroyContext(context);
        alcCloseDevice(device);
        initialized = false;
    }

    // ------------------------------------------------ private helpers

    private void playOneShot(int srcIdx, int buf, float gain) {
        alSourceStop(sources[srcIdx]);
        alSourcei(sources[srcIdx], AL_LOOPING, AL_FALSE);
        alSourcei(sources[srcIdx], AL_BUFFER,  buf);
        alSourcef(sources[srcIdx], AL_GAIN,    gain);
        alSourcePlay(sources[srcIdx]);
    }

    // ----------------------------------------- procedural buffer builders

    private static void buildAmbient(int bufId, int biome) {
        int secs = 4;
        int n    = SAMPLE_RATE * secs;
        ShortBuffer sb = MemoryUtil.memAllocShort(n);
        double base = switch (biome) {
            case 1  -> 55.0;
            case 2  -> 78.0;
            default -> 70.0;
        };
        for (int i = 0; i < n; i++) {
            double t = (double) i / SAMPLE_RATE;
            double v = Math.sin(2*Math.PI*base*t)*0.40
                     + Math.sin(2*Math.PI*base*0.5*t)*0.22
                     + Math.sin(2*Math.PI*base*1.5*t)*0.14;
            v *= 0.85 + 0.15*Math.sin(2*Math.PI*0.28*t);
            if (biome == 1) v += Math.sin(2*Math.PI*base*3.1*t)*0.09;
            else if (biome == 2) v += Math.sin(2*Math.PI*(base*0.25+Math.sin(t*0.7)*7)*t)*0.11;
            double fadeIn  = Math.min(1.0, t/0.08);
            double fadeOut = Math.min(1.0, (secs-t)/0.08);
            v *= fadeIn*fadeOut;
            sb.put(i, (short)Math.max(-32767, Math.min(32767, (int)(v*13000))));
        }
        alBufferData(bufId, AL_FORMAT_MONO16, sb, SAMPLE_RATE);
        MemoryUtil.memFree(sb);
    }

    /** M37: deep cave drone — lower, more reverberant feel */
    private static void buildCaveAmbient(int bufId) {
        int secs = 5;
        int n    = SAMPLE_RATE * secs;
        ShortBuffer sb = MemoryUtil.memAllocShort(n);
        double base = 42.0; // very low fundamental
        for (int i = 0; i < n; i++) {
            double t = (double) i / SAMPLE_RATE;
            double v = Math.sin(2*Math.PI*base*t)*0.45
                     + Math.sin(2*Math.PI*base*1.5*t)*0.18
                     + Math.sin(2*Math.PI*base*2.0*t)*0.10;
            // slow hollow tremolo -- M100 fix: 0.20Hz = 1 cycle per 5s buffer (no phase jump at loop)
            v *= 0.80 + 0.20*Math.sin(2*Math.PI*0.20*t);
            // distant drip-like overtone -- M100 fix: 0.40Hz = 2 cycles per 5s (clean loop)
            v += Math.sin(2*Math.PI*base*4.2*t)*0.05*(0.5+0.5*Math.sin(2*Math.PI*0.40*t));
            double fadeIn  = Math.min(1.0, t/0.12);
            double fadeOut = Math.min(1.0, (secs-t)/0.12);
            v *= fadeIn*fadeOut;
            sb.put(i, (short)Math.max(-32767, Math.min(32767, (int)(v*14000))));
        }
        alBufferData(bufId, AL_FORMAT_MONO16, sb, SAMPLE_RATE);
        MemoryUtil.memFree(sb);
    }

    private static void buildHeartbeat(int bufId, int bpm) {
        double period = 60.0 / bpm;
        int    n      = (int)(SAMPLE_RATE * period * 2);
        ShortBuffer sb = MemoryUtil.memAllocShort(n);
        double lub = period*0.08, dubStart = lub+period*0.14, dub = period*0.06;
        for (int i = 0; i < n; i++) {
            double t = (double)i/SAMPLE_RATE, lp = t%period, v = 0.0;
            if (lp < lub) { double env = Math.sin(Math.PI*lp/lub); v = Math.sin(2*Math.PI*58*lp)*env; }
            if (lp>=dubStart&&lp<dubStart+dub) { double lt=lp-dubStart; v=Math.sin(2*Math.PI*52*lt)*Math.sin(Math.PI*lt/dub)*0.75; }
            sb.put(i, (short)Math.max(-32767, Math.min(32767, (int)(v*24000))));
        }
        alBufferData(bufId, AL_FORMAT_MONO16, sb, SAMPLE_RATE);
        MemoryUtil.memFree(sb);
    }

    private static void buildSting(int bufId) {
        double dur = 1.1; int n = (int)(SAMPLE_RATE*dur);
        ShortBuffer sb = MemoryUtil.memAllocShort(n);
        for (int i = 0; i < n; i++) {
            double t=i/(double)SAMPLE_RATE, freq=880-t*560, env=Math.exp(-t*4.8);
            double v=(Math.sin(2*Math.PI*freq*t)*0.55+Math.sin(2*Math.PI*freq*2.05*t)*0.22)*env;
            sb.put(i, (short)Math.max(-32767, Math.min(32767, (int)(v*20000))));
        }
        alBufferData(bufId, AL_FORMAT_MONO16, sb, SAMPLE_RATE);
        MemoryUtil.memFree(sb);
    }

    private static void buildFootstep(int bufId) {
        double dur = 0.11; int n = (int)(SAMPLE_RATE*dur);
        ShortBuffer sb = MemoryUtil.memAllocShort(n);
        Random rng = new Random(0xF007L);
        for (int i = 0; i < n; i++) {
            double t=i/(double)SAMPLE_RATE, env=Math.exp(-t*38.0);
            double v=((rng.nextDouble()*2-1)*0.38+Math.sin(2*Math.PI*88*t)*0.62)*env;
            sb.put(i, (short)Math.max(-32767, Math.min(32767, (int)(v*17000))));
        }
        alBufferData(bufId, AL_FORMAT_MONO16, sb, SAMPLE_RATE);
        MemoryUtil.memFree(sb);
    }

    /** M95.1: cave footstep — same click with stone resonance + two echo copies at 0.12s and 0.28s */
    private static void buildFootstepCave(int bufId) {
        double dur = 0.55;
        int n = (int)(SAMPLE_RATE * dur);
        // Pre-generate base click and stone resonance as double arrays
        double[] click = new double[n];
        double[] stone = new double[n];
        Random rngClick = new Random(0xF007L);  // same seed as surface footstep for consistent transient
        Random rngStone = new Random(0xCA4E3DEL);
        for (int i = 0; i < n; i++) {
            double t = i / (double) SAMPLE_RATE;
            double clickEnv = Math.exp(-t * 38.0);
            click[i] = ((rngClick.nextDouble() * 2 - 1) * 0.38 + Math.sin(2 * Math.PI * 88 * t) * 0.62) * clickEnv;
            // Stone thud: deep low resonance of cave floor
            double stoneEnv = Math.exp(-t * 9.0);
            stone[i] = (Math.sin(2 * Math.PI * 52 * t) * 0.5 + Math.sin(2 * Math.PI * 78 * t) * 0.3
                      + (rngStone.nextDouble() * 2 - 1) * 0.2) * stoneEnv * 0.45;
        }
        int echo1Offset = (int)(0.13 * SAMPLE_RATE); // first echo at 130ms
        int echo2Offset = (int)(0.30 * SAMPLE_RATE); // second echo at 300ms
        ShortBuffer sb = MemoryUtil.memAllocShort(n);
        for (int i = 0; i < n; i++) {
            double v = click[i] + stone[i];
            if (i >= echo1Offset) v += (click[i - echo1Offset] + stone[i - echo1Offset]) * 0.40;
            if (i >= echo2Offset) v += click[i - echo2Offset] * 0.16;
            sb.put(i, (short) Math.max(-32767, Math.min(32767, (int)(v * 17000))));
        }
        alBufferData(bufId, AL_FORMAT_MONO16, sb, SAMPLE_RATE);
        MemoryUtil.memFree(sb);
    }

    /** M38: heavy exhausted breathing — two slow exhales */
    private static void buildBreath(int bufId) {
        double dur = 2.2; int n = (int)(SAMPLE_RATE*dur);
        ShortBuffer sb = MemoryUtil.memAllocShort(n);
        Random rng = new Random(0xB4EAL);
        for (int i = 0; i < n; i++) {
            double t = i/(double)SAMPLE_RATE;
            // two exhale pulses at t=0.3 and t=1.4
            double e1 = Math.exp(-Math.pow((t-0.30)/0.14, 2)*8.0);
            double e2 = Math.exp(-Math.pow((t-1.40)/0.16, 2)*8.0);
            double noise = (rng.nextDouble()*2-1);
            double v = noise*(e1*0.9+e2*0.7)*0.6;
            sb.put(i, (short)Math.max(-32767, Math.min(32767, (int)(v*18000))));
        }
        alBufferData(bufId, AL_FORMAT_MONO16, sb, SAMPLE_RATE);
        MemoryUtil.memFree(sb);
    }

    /** M39: rain — white noise filtered low, 6 second loop */
    private static void buildRain(int bufId) {
        int secs = 6, n = SAMPLE_RATE*secs;
        ShortBuffer sb = MemoryUtil.memAllocShort(n);
        Random rng = new Random(0xA1B2C3L);
        double lp = 0;
        for (int i = 0; i < n; i++) {
            double noise = rng.nextDouble()*2-1;
            lp = lp*0.82 + noise*0.18; // simple lowpass
            double t = i/(double)SAMPLE_RATE;
            double fade = Math.min(1.0, Math.min(t/0.3, (secs-t)/0.3));
            sb.put(i, (short)Math.max(-32767, Math.min(32767, (int)(lp*fade*22000))));
        }
        alBufferData(bufId, AL_FORMAT_MONO16, sb, SAMPLE_RATE);
        MemoryUtil.memFree(sb);
    }

    /** M83: single cave drip — sharp plink with short metallic resonance */
    private static void buildDrip(int bufId) {
        double dur = 0.55; int n = (int)(SAMPLE_RATE * dur);
        ShortBuffer sb = MemoryUtil.memAllocShort(n);
        for (int i = 0; i < n; i++) {
            double t = i / (double) SAMPLE_RATE;
            // Main drip plink: decaying high tone
            double env  = Math.exp(-t * 18.0);
            double plink = Math.sin(2 * Math.PI * 1200 * t) * env * 0.7
                         + Math.sin(2 * Math.PI * 1800 * t) * env * 0.3;
            // Short resonant tail
            double tail = Math.sin(2 * Math.PI * 900 * t) * Math.exp(-t * 6.0) * 0.15;
            double v = plink + tail;
            sb.put(i, (short) Math.max(-32767, Math.min(32767, (int)(v * 18000))));
        }
        alBufferData(bufId, AL_FORMAT_MONO16, sb, SAMPLE_RATE);
        MemoryUtil.memFree(sb);
    }

    /** M98: stalker ambient presence loop — slow wet breathing, 2.5s cycle */
    private static void buildStalkerBreath(int bufId) {
        double dur = 2.5; int n = (int)(SAMPLE_RATE * dur);
        ShortBuffer sb = MemoryUtil.memAllocShort(n);
        Random rng = new Random(0x57A1C3BL);
        double lp = 0;
        for (int i = 0; i < n; i++) {
            double t = i / (double) SAMPLE_RATE;
            // Inhale 0→1s, exhale 1.2→2.2s, silence 2.2→2.5s
            double env;
            if      (t < 0.9)  env = t / 0.9;                     // inhale ramp up
            else if (t < 1.1)  env = 1.0;                          // peak
            else if (t < 2.1)  env = 1.0 - (t - 1.1) / 1.0;       // exhale ramp down
            else               env = 0.0;
            double noise = rng.nextDouble() * 2 - 1;
            lp = lp * 0.92 + noise * 0.08; // strong low-pass for deep wet sound
            double v = (lp * 0.7 + Math.sin(2 * Math.PI * 68 * t) * 0.3) * env * 0.6;
            sb.put(i, (short) Math.max(-32767, Math.min(32767, (int)(v * 18000))));
        }
        alBufferData(bufId, AL_FORMAT_MONO16, sb, SAMPLE_RATE);
        MemoryUtil.memFree(sb);
    }

    /** M98: lurker ambient presence loop — high chitinous hiss/creak, 2s cycle */
    private static void buildLurkerHiss(int bufId) {
        double dur = 2.0; int n = (int)(SAMPLE_RATE * dur);
        ShortBuffer sb = MemoryUtil.memAllocShort(n);
        Random rng = new Random(0x1EEECABL);
        double lp = 0;
        for (int i = 0; i < n; i++) {
            double t = i / (double) SAMPLE_RATE;
            // Faint hiss with slow modulation
            double mod = 0.55 + Math.sin(2 * Math.PI * 0.7 * t) * 0.45;
            double noise = rng.nextDouble() * 2 - 1;
            // High-pass filter: emphasize scraping high frequencies
            lp = lp * 0.4 + noise * 0.6;
            double hp = noise - lp;
            double v = hp * mod * 0.28;
            sb.put(i, (short) Math.max(-32767, Math.min(32767, (int)(v * 14000))));
        }
        alBufferData(bufId, AL_FORMAT_MONO16, sb, SAMPLE_RATE);
        MemoryUtil.memFree(sb);
    }

    private static void buildDeepCrawl(int bufId) {
        int sr = SAMPLE_RATE, len = sr * 2;
        short[] s = new short[len];
        java.util.Random rng = new java.util.Random(0xDEEF1L);
        for (int i = 0; i < len; i++) {
            double t = (double)i / sr;
            double noise  = rng.nextDouble() * 2.0 - 1.0;
            double scrape = noise * Math.sin(t * Math.PI * 2.2) * 0.55;
            double growl  = Math.sin(2*Math.PI*55*t)*0.30 + Math.sin(2*Math.PI*110*t)*0.12;
            double phase  = (t % 0.6) / 0.6;
            double env    = Math.sin(phase * Math.PI) * 0.85 + 0.15;
            double samp   = (scrape + growl) * env;
            double fi = Math.min(1.0, t / 0.08), fo = Math.min(1.0, (len-1-i)/(sr*0.08));
            samp *= fi * fo;
            s[i] = (short)Math.max(-32768, Math.min(32767, (int)(samp * 26000)));
        }
        ShortBuffer buf = MemoryUtil.memAllocShort(len);
        try { buf.put(s).flip(); alBufferData(bufId, AL_FORMAT_MONO16, buf, sr); }
        finally { MemoryUtil.memFree(buf); }
    }

    /** M201: THE NUN — heel-clop footstep: sharp click transient + low resonant thud, ~0.18s */
    // M211: Voice line playback -----------------------------------------------
    /** M215: Play hide & seek start announcement. */
    public void playHideSeek()     { playVoice(bufHideSeek); }
    /** M215: Play hide & seek end announcement. */
    public void playHideSeekOver() { playVoice(bufHideSeekOver); }



    /** Play a non-positional voice line; stops any current voice first. */
    private void playVoice(int bufId) {
        if (!initialized || bufId == 0) return;
        alSourceStop(sources[SRC_VOICE]);
        alSourcei(sources[SRC_VOICE], AL_LOOPING, AL_FALSE);
        alSourcei(sources[SRC_VOICE], AL_BUFFER,  bufId);
        alSourcef(sources[SRC_VOICE], AL_GAIN,    masterGain * 0.95f);
        alSourcePlay(sources[SRC_VOICE]);

    }

    /** M211: Play escalation milestone voice line (milestone 1-4). */
    public void playEscalation(int milestone) {
        int buf = switch (milestone) {
            case 1 -> bufEsc1;
            case 2 -> bufEsc2;
            case 3 -> bufEsc3;
            case 4 -> bufEsc4;
            default -> 0;
        };
        playVoice(buf);
    }

    /** M211: Play a taunt voice line (0=watching/30%, 1=there/85%, 2=seeme/50%). */
    public void playTaunt(int which) {
        int buf = switch (which) {
            case 0 -> bufTauntWatching;
            case 1 -> bufTauntThere;
            case 2 -> bufTauntSeeme;
            default -> 0;
        };
        playVoice(buf);
    }
    private static void buildNunStep(int bufId) {
        int sr = SAMPLE_RATE;
        double dur = 0.18;
        int n = (int)(sr * dur);
        ShortBuffer sb = MemoryUtil.memAllocShort(n);
        try {
            for (int i = 0; i < n; i++) {
                double t = (double)i / sr;
                // Sharp click: high-freq burst at t=0
                double click = Math.sin(2 * Math.PI * 2800 * t) * Math.exp(-t * 180.0);
                // Low thud: 60 Hz body resonance
                double thud  = Math.sin(2 * Math.PI * 60 * t) * Math.exp(-t * 30.0) * 0.55;
                // Mid woody knock: 420 Hz
                double knock = Math.sin(2 * Math.PI * 420 * t) * Math.exp(-t * 90.0) * 0.40;
                double samp  = click + thud + knock;
                sb.put(i, (short)Math.max(-32768, Math.min(32767, (int)(samp * 24000))));
            }
            alBufferData(bufId, AL_FORMAT_MONO16, sb, sr);
        } finally { MemoryUtil.memFree(sb); }
    }

    /** M201: THE NUN — knife strike: meaty thwack + brief metallic scrape, ~0.22s */
    private static void buildNunStrike(int bufId) {
        int sr = SAMPLE_RATE;
        double dur = 0.22;
        int n = (int)(sr * dur);
        ShortBuffer sb = MemoryUtil.memAllocShort(n);
        java.util.Random rng = new java.util.Random(0xB1ADEL);
        try {
            for (int i = 0; i < n; i++) {
                double t = (double)i / sr;
                // Meaty impact: broad-band noise burst with low-mid body
                double noise  = rng.nextDouble() * 2.0 - 1.0;
                double impact = noise * Math.exp(-t * 55.0) * 0.80;
                // Low body: 90 Hz thump
                double body   = Math.sin(2 * Math.PI * 90 * t) * Math.exp(-t * 28.0) * 0.50;
                // Metallic scrape: 1400 Hz decaying shimmer
                double scrape = Math.sin(2 * Math.PI * 1400 * t) * Math.exp(-t * 70.0) * 0.22;
                double samp   = impact + body + scrape;
                double fi = Math.min(1.0, t / 0.003); // 3 ms fade-in
                samp *= fi;
                sb.put(i, (short)Math.max(-32768, Math.min(32767, (int)(samp * 26000))));
            }
            alBufferData(bufId, AL_FORMAT_MONO16, sb, sr);
        } finally { MemoryUtil.memFree(sb); }
    }

    /** M98: player hit impact — deep thump + distorted crack */
    private static void buildHit(int bufId) {
        double dur = 0.28; int n = (int)(SAMPLE_RATE * dur);
        ShortBuffer sb = MemoryUtil.memAllocShort(n);
        Random rng = new Random(0x417DEF1L);
        for (int i = 0; i < n; i++) {
            double t = i / (double) SAMPLE_RATE;
            // Deep body thump
            double thump = Math.sin(2 * Math.PI * 72 * t) * Math.exp(-t * 22.0);
            // Sharp crack on top
            double crack = (rng.nextDouble() * 2 - 1) * Math.exp(-t * 55.0);
            // Low rumble sustain
            double rumble = Math.sin(2 * Math.PI * 38 * t) * Math.exp(-t * 8.0) * 0.4;
            double v = thump * 0.7 + crack * 0.5 + rumble;
            // Hard clip for that punchy distorted feel
            v = Math.max(-0.9, Math.min(0.9, v * 1.4));
            sb.put(i, (short) Math.max(-32767, Math.min(32767, (int)(v * 28000))));
        }
        alBufferData(bufId, AL_FORMAT_MONO16, sb, SAMPLE_RATE);
        MemoryUtil.memFree(sb);
    }

    /** M93: stalactite creak warning — slow wood/stone groan */
    private static void buildCreak(int bufId) {
        double dur = 0.45; int n = (int)(SAMPLE_RATE * dur);
        ShortBuffer sb = MemoryUtil.memAllocShort(n);
        for (int i = 0; i < n; i++) {
            double t = i / (double) SAMPLE_RATE;
            // Low-frequency grinding: two close pitches beating against each other
            double env = Math.exp(-t * 2.5) * Math.sin(Math.PI * t / dur);
            double v = (Math.sin(2 * Math.PI * 88 * t) * 0.6
                      + Math.sin(2 * Math.PI * 93 * t) * 0.4) * env;
            sb.put(i, (short) Math.max(-32767, Math.min(32767, (int)(v * 20000))));
        }
        alBufferData(bufId, AL_FORMAT_MONO16, sb, SAMPLE_RATE);
        MemoryUtil.memFree(sb);
    }

    /** M93: stalactite crash impact — sharp stone burst */
    private static void buildCrash(int bufId) {
        double dur = 0.35; int n = (int)(SAMPLE_RATE * dur);
        ShortBuffer sb = MemoryUtil.memAllocShort(n);
        Random rng = new Random(0xC4A5EEL);
        double lp = 0;
        for (int i = 0; i < n; i++) {
            double t = i / (double) SAMPLE_RATE;
            double noise = rng.nextDouble() * 2 - 1;
            lp = lp * 0.55 + noise * 0.45; // mid-range bandpass feel
            double env = Math.exp(-t * 14.0) + Math.exp(-t * 3.5) * 0.3;
            double v = (lp * 0.7 + Math.sin(2 * Math.PI * 120 * t) * 0.3) * env;
            sb.put(i, (short) Math.max(-32767, Math.min(32767, (int)(v * 26000))));
        }
        alBufferData(bufId, AL_FORMAT_MONO16, sb, SAMPLE_RATE);
        MemoryUtil.memFree(sb);
    }

    /** M39: single thunder crack — sharp bang with long decay rumble */
    private static void buildThunder(int bufId) {
        double dur = 3.5; int n = (int)(SAMPLE_RATE*dur);
        ShortBuffer sb = MemoryUtil.memAllocShort(n);
        Random rng = new Random(0xDEADL);
        double lp = 0;
        for (int i = 0; i < n; i++) {
            double t = i/(double)SAMPLE_RATE;
            double crack  = (rng.nextDouble()*2-1)*Math.exp(-t*18.0);
            double rumble = (rng.nextDouble()*2-1)*Math.exp(-t*1.4)*0.5;
            double noise  = crack+rumble;
            lp = lp*0.70+noise*0.30;
            sb.put(i, (short)Math.max(-32767, Math.min(32767, (int)(lp*26000))));
        }
        alBufferData(bufId, AL_FORMAT_MONO16, sb, SAMPLE_RATE);
        MemoryUtil.memFree(sb);
    }
}
