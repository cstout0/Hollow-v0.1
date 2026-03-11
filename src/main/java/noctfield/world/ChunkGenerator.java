package noctfield.world;

public final class ChunkGenerator {
    private ChunkGenerator() {}

    // M184: world boundary â€” blocks outside this square radius generate as solid VOIDSTONE wall
    public static final int WORLD_RADIUS = 200;

    // M225: liminal zone flag â€” when true, baseBlockAt() generates the liminal interior instead of overworld
    public static volatile boolean liminalMode   = false;  // legacy compat
    public static volatile int     liminalZoneId = 0;      // M229: 0=overworld 1=meadow 2=darkroom

    // Cave tuning (Milestone 15)
    private static float caveThreshold = 0.68f;
    private static int caveBandMinY = 3;  // just above VOIDSTONE floor
    private static int caveBandMaxY = 65; // M90: expanded to fill the ~55-block depth below terrain
    private static float caveFreqA = 0.070f;
    private static float caveFreqB = 0.036f;
    // ---- M204: relic system â€” 2 surface + multiple underground -------------------------
    private static long    cachedRelicSeed       = Long.MIN_VALUE;
    private static int[][] cachedRelicPos         = null;
    private static long    cachedSurfaceRelicSeed = Long.MIN_VALUE;
    private static int[]   cachedSurfaceRelic0    = null;
    private static int[]   cachedSurfaceRelic1    = null;

    /** Deterministic surface relic position (on terrain, not underground). Cached per seed. */
    public static int[] getSurfaceRelicPos(long seed, int idx) {
        if (cachedSurfaceRelicSeed != seed || cachedSurfaceRelic0 == null) {
            cachedSurfaceRelic0 = buildSurfaceRelicPos(seed, 0);
            cachedSurfaceRelic1 = buildSurfaceRelicPos(seed, 1);
            cachedSurfaceRelicSeed = seed;
        }
        return idx == 0 ? cachedSurfaceRelic0 : cachedSurfaceRelic1;
    }

    private static int[] buildSurfaceRelicPos(long seed, int idx) {
        long mix = seed ^ (0xC0FFEE1A2B3C4DL + (long)idx * 0x7EADBEEF753L);
        int maxR = WORLD_RADIUS - 30;
        // Two relics in opposite quadrants
        double baseAngle = idx * Math.PI + hash01_2d(idx * 7 + 3, idx * 5 + 4, mix, 101) * (Math.PI * 0.5);
        double dist      = 55.0 + hash01_2d(idx * 3 + 1, idx * 4 + 2, mix, 97) * (maxR - 65.0);
        int rx = Math.max(-maxR, Math.min(maxR, (int)(Math.cos(baseAngle) * dist)));
        int rz = Math.max(-maxR, Math.min(maxR, (int)(Math.sin(baseAngle) * dist)));
        int ry = heightAt(rx, rz, seed) + 1; // one block above surface
        return new int[]{rx, ry, rz};
    }

    /** True if this block position is a surface relic (checked per-block in baseBlockAt). */
    public static boolean isSurfaceRelicPos(int wx, int wy, int wz, long seed) {
        int[] p0 = getSurfaceRelicPos(seed, 0);
        if (wx == p0[0] && wz == p0[2]) return wy == p0[1];
        int[] p1 = getSurfaceRelicPos(seed, 1);
        if (wx == p1[0] && wz == p1[2]) return wy == p1[1];
        return false;
    }

    /** All relic positions for this seed: surface relics first, then in-world underground relics. */
    public static int[][] getRelicPositions(long seed) {
        if (seed == cachedRelicSeed && cachedRelicPos != null) return cachedRelicPos;
        java.util.List<int[]> found = new java.util.ArrayList<>();
        // Surface relics â€” steps 1 & 2, above ground and navigable
        found.add(getSurfaceRelicPos(seed, 0));
        found.add(getSurfaceRelicPos(seed, 1));
        // Underground relics â€” scatter multiple so step 3 is easier to complete
        for (int ax = -1; ax <= 0; ax++) {
            for (int az = -1; az <= 0; az++) {
                int[] rp = relicPosForArea(ax * 256 + 128, az * 256 + 128, seed);
                if (Math.abs(rp[0]) <= WORLD_RADIUS - 10 && Math.abs(rp[2]) <= WORLD_RADIUS - 10) {
                    found.add(rp);
                }
            }
        }
        int[][] pos    = found.toArray(new int[0][]);
        cachedRelicPos  = pos;
        cachedRelicSeed = seed;
        return pos;
    }

    private static int[] findBiomeRelic(long seed, int idx, int biomeTarget) {
        int maxR = WORLD_RADIUS - 30; // stay 30 blocks inside boundary
        for (int i = 0; i < 300; i++) {
            float angle = i * 2.399963f + idx * 2.094f;
            float dist  = 50f + i * 0.5f;                // 50 â†’ 200 over 300 steps
            if (dist > maxR) dist = 55f + (dist % (maxR - 55f)); // wrap within safe range
            int rx = (int)(Math.cos(angle) * dist);
            int rz = (int)(Math.sin(angle) * dist);
            // Hard clamp â€” relic must never be outside world boundary
            rx = Math.max(-maxR, Math.min(maxR, rx));
            rz = Math.max(-maxR, Math.min(maxR, rz));
            int biome = biomeAtWorld(rx, rz, seed);
            if (biomeTarget == BIOME_PINE && biome == BIOME_PINE)
                return new int[]{rx, caveBandMinY + 4, rz};
            if (biomeTarget < 0 && (biome == BIOME_DEAD || biome == BIOME_SWAMP))
                return new int[]{rx, caveBandMinY + 4, rz};
        }
        int fb = idx == 0 ? 120 : -120; // fallback: well within 200-radius world
        return new int[]{fb, caveBandMinY + 4, fb};
    }

    private static int[] computeUndergroundRelic(long seed, int idx) {
        // Different seed mix per index so the 3 underground relics land in different quadrants
        long sa = seed ^ (0xC0DEBAD1CA7EL + idx * 0x1337DEADL);
        long sb = seed ^ (0xDABBED2F00D1L + idx * 0x1234ABCDL);
        int maxR = WORLD_RADIUS - 35;
        int ux = (int)((hash01_2d(idx, idx + 1, sa, 97 + idx * 4) - 0.5f) * (maxR * 2f));
        int uz = (int)((hash01_2d(idx + 1, idx, sb, 101 + idx * 4) - 0.5f) * (maxR * 2f));
        if (Math.abs(ux) < 60)  ux = ux >= 0 ?  60 : -60;
        if (Math.abs(uz) < 60)  uz = uz >= 0 ?  60 : -60;
        // Hard clamp inside world
        ux = Math.max(-maxR, Math.min(maxR, ux));
        uz = Math.max(-maxR, Math.min(maxR, uz));
        return new int[]{ux, caveBandMinY + 4, uz};
    }
    // ---- end M174 relic helpers --------------------------------------


    public static final int BIOME_PINE = 0;
    public static final int BIOME_DEAD = 1;
    public static final int BIOME_SWAMP = 2;
    private static int forcedBiome = -1;

    public static void adjustCaveThreshold(float d) { caveThreshold = clamp(caveThreshold + d, 0.50f, 0.86f); }
    public static void adjustCaveBandMin(int d) { caveBandMinY = (int) clamp(caveBandMinY + d, 2, VoxelChunk.SIZE_Y - 8); }
    public static void adjustCaveBandMax(int d) { caveBandMaxY = (int) clamp(caveBandMaxY + d, caveBandMinY + 2, VoxelChunk.SIZE_Y - 2); }
    public static float caveThreshold() { return caveThreshold; }
    public static int caveBandMinY() { return caveBandMinY; }
    public static int caveBandMaxY() { return caveBandMaxY; }

    public static String biomeName(int biome) {
        return switch (biome) {
            case BIOME_PINE -> "PINE";
            case BIOME_DEAD -> "DEAD";
            case BIOME_SWAMP -> "SWAMP";
            default -> "UNKNOWN";
        };
    }

    public static int biomeAtWorld(int x, int z, long seed) {
        return biomeAt(x, z, seed);
    }

    public static float movementMultiplierAt(int x, int z, long seed) {
        float[] w = biomeWeightsAt(x, z, seed);
        return w[BIOME_PINE] * 1.04f + w[BIOME_DEAD] * 0.97f + w[BIOME_SWAMP] * 0.86f;
    }

    public static float sanityDrainMultiplierAt(int x, int z, long seed) {
        float[] w = biomeWeightsAt(x, z, seed);
        return w[BIOME_PINE] * 0.90f + w[BIOME_DEAD] * 1.06f + w[BIOME_SWAMP] * 1.20f;
    }

    public static float watcherAggroMultiplierAt(int x, int z, long seed) {
        float[] w = biomeWeightsAt(x, z, seed);
        return w[BIOME_PINE] * 0.92f + w[BIOME_DEAD] * 1.08f + w[BIOME_SWAMP] * 1.18f;
    }

    public static String cycleForcedBiome() {
        forcedBiome++;
        if (forcedBiome > BIOME_SWAMP) forcedBiome = -1;
        return forcedBiome == -1 ? "AUTO" : biomeName(forcedBiome);
    }

    // M85: cave zone types (per large-scale noise region)
    public static final int CAVE_ZONE_FLOODED = 0;  // slow, wet, dark
    public static final int CAVE_ZONE_CRYSTAL  = 1;  // bright, dense fungus
    public static final int CAVE_ZONE_DEAD     = 2;  // sanity drain, barren

    public static int caveZoneAt(int wx, int wz, long seed) {
        float n = valueNoise2(wx * 0.0028f, wz * 0.0028f, seed ^ 0xCA1EF00DL);
        if (n < 0.33f) return CAVE_ZONE_FLOODED;
        if (n < 0.66f) return CAVE_ZONE_CRYSTAL;
        return CAVE_ZONE_DEAD;
    }

    public static VoxelChunk generate(ChunkPos pos, long seed) {
        VoxelChunk c = new VoxelChunk(pos);
        int baseX = pos.x() * Chunk.SIZE;
        int baseZ = pos.z() * Chunk.SIZE;

        for (int z = 0; z < Chunk.SIZE; z++) {
            for (int x = 0; x < Chunk.SIZE; x++) {
                int wx = baseX + x;
                int wz = baseZ + z;
                for (int y = 0; y < VoxelChunk.SIZE_Y; y++) {
                    c.set(x, y, z, baseBlockAt(wx, y, wz, seed));
                }
            }
        }

        // M89: Secondary wide-chamber pass â€” carve larger open spaces at intervals
        for (int z = 0; z < Chunk.SIZE; z++) {
            for (int x = 0; x < Chunk.SIZE; x++) {
                int wx = baseX + x;
                int wz = baseZ + z;
                for (int y = caveBandMinY + 1; y < caveBandMaxY - 2; y++) {
                    if (c.get(x, y, z) == BlockId.AIR) {
                        // Widen: if this is already air, try to carve the two blocks above too (chamber height)
                        float chamber = valueNoise3(wx * 0.022f, y * 0.030f, wz * 0.022f, seed ^ 0xC0FFEE1234L);
                        if (chamber > 0.74f) {
                            if (c.get(x, y + 1, z) == BlockId.STONE) c.set(x, y + 1, z, BlockId.AIR);
                            if (c.get(x, y + 2, z) == BlockId.STONE) c.set(x, y + 2, z, BlockId.AIR);
                        }
                    }
                }
            }
        }

        // M84/M89/M94/M177: Cave environment pass - fungus, crystals, stalactites, zone details
        for (int z = 0; z < Chunk.SIZE; z++) {
            for (int x = 0; x < Chunk.SIZE; x++) {
                int wx = baseX + x;
                int wz = baseZ + z;
                int zone = caveZoneAt(wx, wz, seed);
                float fungDensity = (zone == CAVE_ZONE_CRYSTAL) ? 0.10f : 0.04f;
                for (int y = caveBandMinY + 1; y < caveBandMaxY; y++) {
                    // ---- FLOOR surfaces (STONE below AIR) ----
                    if (c.get(x, y, z) == BlockId.STONE && c.get(x, y + 1, z) == BlockId.AIR) {
                        float fn = hash01_3d(wx, y, wz, seed ^ 0xF0A1C3DEL);

                        // M177: FLOODED zone - MUD pools on lowest cave floor rows
                        if (zone == CAVE_ZONE_FLOODED && y <= caveBandMinY + 4 && fn < 0.28f) {
                            c.set(x, y, z, BlockId.MUD); // muddy floor instead of removed water
                        }
                        // M177: DEAD zone - bone and bloodstain scatter on floor
                        else if (zone == CAVE_ZONE_DEAD) {
                            float dd = hash01_3d(wx, y, wz, seed ^ 0xDEADB0DEL);
                            if (dd < 0.010f)      c.set(x, y, z, BlockId.BLOODSTAIN); // M223: was 0.03
                            else if (dd < 0.040f) c.set(x, y, z, BlockId.BONES);      // M223: was 0.13
                            else if (fn < fungDensity) c.set(x, y, z, BlockId.FUNGUS);
                        }
                        // Normal fungus placement
                        else if (fn < fungDensity) {
                            c.set(x, y, z, BlockId.FUNGUS);
                        }
                        // Stalagmites - up to 3 blocks tall (M89 extended M177)
                        else if (fn < fungDensity + 0.04f) {
                            if (c.get(x, y + 1, z) == BlockId.AIR) {
                                c.set(x, y + 1, z, BlockId.STONE); // 1 block
                                float tall = hash01_3d(wx, y + 500, wz, seed ^ 0xF0A1C3DEL);
                                if (tall < 0.50f && c.get(x, y + 2, z) == BlockId.AIR)
                                    c.set(x, y + 2, z, BlockId.STONE); // 2 blocks
                                if (tall < 0.20f && c.get(x, y + 3, z) == BlockId.AIR)
                                    c.set(x, y + 3, z, BlockId.STONE); // 3 blocks (rare)
                            }
                        }

                        // M94: crystals on floor in CRYSTAL zones
                        if (zone == CAVE_ZONE_CRYSTAL) {
                            float cn = hash01_3d(wx, y, wz, seed ^ 0xC4175741L);
                            if (cn < 0.04f) c.set(x, y, z, BlockId.CRYSTAL);
                        }
                    }

                    // ---- CEILING surfaces (AIR below STONE) ----
                    if (c.get(x, y, z) == BlockId.AIR && c.get(x, y + 1, z) == BlockId.STONE) {
                        float sn = hash01_3d(wx, y, wz, seed ^ 0x57A1AC711EL);
                        // M177: multi-block stalactites (1-3 blocks)
                        if (sn < 0.07f) {
                            c.set(x, y, z, BlockId.STONE); // 1 block
                            float longer = hash01_3d(wx, y + 700, wz, seed ^ 0x57A1AC711EL);
                            if (longer < 0.50f && c.get(x, y - 1, z) == BlockId.AIR)
                                c.set(x, y - 1, z, BlockId.STONE); // 2 blocks
                            if (longer < 0.20f && c.get(x, y - 2, z) == BlockId.AIR)
                                c.set(x, y - 2, z, BlockId.STONE); // 3 blocks (rare)
                        }
                        // M94: crystal stalactite variant
                        if (zone == CAVE_ZONE_CRYSTAL) {
                            float cn2 = hash01_3d(wx, y + 1000, wz, seed ^ 0xC4175741L);
                            if (cn2 < 0.022f) c.set(x, y, z, BlockId.CRYSTAL);
                        }
                        // M177: DEAD zone ceiling cobwebs (M223: was sn>0.90 ~10%, now sn>0.96 ~4%)
                        if (zone == CAVE_ZONE_DEAD && sn > 0.96f) {
                            c.set(x, y, z, BlockId.COBWEB);
                        }
                    }

                    // ---- WALL surfaces (STONE next to AIR) ----
                    if (c.get(x, y, z) == BlockId.STONE) {
                        boolean exposed = c.get(x+1,y,z) == BlockId.AIR || c.get(x-1,y,z) == BlockId.AIR
                                       || c.get(x,y,z+1) == BlockId.AIR || c.get(x,y,z-1) == BlockId.AIR;
                        if (exposed) {
                            // M94: crystal veins on CRYSTAL zone walls
                            if (zone == CAVE_ZONE_CRYSTAL) {
                                float cn3 = hash01_3d(wx, y, wz, seed ^ 0xC4175742L);
                                if (cn3 < 0.032f) c.set(x, y, z, BlockId.CRYSTAL);
                            }
                            // M177: DEAD zone wall cobwebs
                            if (zone == CAVE_ZONE_DEAD) {
                                float cw = hash01_3d(wx, y + 200, wz, seed ^ 0xC0BE3B1EL);
                                if (cw < 0.03f) c.set(x, y, z, BlockId.COBWEB);
                            }
                        }
                    }
                }
            }
        }

        // M177: Underground encampments
        applyUndergroundEncampments(c, baseX, baseZ, seed);
        // M178: Crystal geodes + horror chasms
        applyGeodes(c, baseX, baseZ, seed);
        applyChasms(c, baseX, baseZ, seed);

                        return c;
    }

    // ---- M35: Relic placement ----

    /**
     * M153: Surface monument above each relic â€” VOIDSTONE obelisk (4 tall) capped with CRYSTAL.
     * Visible above-ground landmark that guides the player to the relic buried below.
     * Layout: center pillar h+1..h+4=VOIDSTONE, h+5=CRYSTAL; cardinal arms at h+1=VOIDSTONE;
     * diagonal corners at h+1=BONES.
     */
    private static byte relicMonumentAt(int wx, int wy, int wz, long seed) {
        // Same seeding as isRelicPos â€” guaranteed to match the relic directly below
        int areaX = Math.floorDiv(wx, 256);
        int areaZ = Math.floorDiv(wz, 256);
        int localX = 18 + (int)(hash01_2d(areaX, areaZ, seed ^ 0xDEADF00DC0DEL, 77) * 220f);
        int localZ = 18 + (int)(hash01_2d(areaX, areaZ, seed ^ 0xBEEFBABE5678L, 79) * 220f);
        int relicX = areaX * 256 + localX;
        int relicZ = areaZ * 256 + localZ;
        int dx = wx - relicX, dz = wz - relicZ;
        if (Math.abs(dx) > 2 || Math.abs(dz) > 2) return BlockId.AIR;
        int h = heightAt(relicX, relicZ, seed);
        if (wy <= h || wy > h + 5) return BlockId.AIR;
        // Central obelisk
        if (dx == 0 && dz == 0) {
            if (wy <= h + 4) return BlockId.VOIDSTONE;
            return BlockId.CRYSTAL; // glowing cap
        }
        if (wy == h + 1) {
            // Cardinal cross arms (N/S/E/W at radius 1)
            if ((Math.abs(dx) == 1 && dz == 0) || (dx == 0 && Math.abs(dz) == 1)) return BlockId.VOIDSTONE;
            // Diagonal corner markers
            if (Math.abs(dx) == 1 && Math.abs(dz) == 1) return BlockId.BONES;
            // Outer cardinal anchors at radius 2
            if ((Math.abs(dx) == 2 && dz == 0) || (dx == 0 && Math.abs(dz) == 2)) return BlockId.VOIDSTONE;
        }
        return BlockId.AIR;
    }

    /** Returns the deterministic relic world position for the 256x256 area containing (wx, wz). */
    public static int[] relicPosForArea(int wx, int wz, long seed) {
        int areaX = Math.floorDiv(wx, 256);
        int areaZ = Math.floorDiv(wz, 256);
        int localX = 18 + (int)(hash01_2d(areaX, areaZ, seed ^ 0xDEADF00DC0DEL, 77) * 220f);
        int localZ = 18 + (int)(hash01_2d(areaX, areaZ, seed ^ 0xBEEFBABE5678L, 79) * 220f);
        int relicX = areaX * 256 + localX;
        int relicZ = areaZ * 256 + localZ;
        int relicY = caveBandMinY + 4;
        return new int[]{relicX, relicY, relicZ};
    }

    private static boolean isRelicPos(int wx, int wy, int wz, long seed) {
        int areaX = Math.floorDiv(wx, 256);
        int areaZ = Math.floorDiv(wz, 256);
        int localX = 18 + (int)(hash01_2d(areaX, areaZ, seed ^ 0xDEADF00DC0DEL, 77) * 220f);
        int localZ = 18 + (int)(hash01_2d(areaX, areaZ, seed ^ 0xBEEFBABE5678L, 79) * 220f);
        int relicX = areaX * 256 + localX;
        int relicZ = areaZ * 256 + localZ;
        int relicY = caveBandMinY + 4;
        return wx == relicX && wz == relicZ && wy == relicY;
    }

    /** Returns deterministic journal stone world position for 128x128 area containing (wx,wz). */
    public static int[] journalPosForArea(int wx, int wz, long seed) {
        int areaX = Math.floorDiv(wx, 128);
        int areaZ = Math.floorDiv(wz, 128);
        int localX = 12 + (int)(hash01_2d(areaX, areaZ, seed ^ 0xABCD1234EF56L, 83) * 104f);
        int localZ = 12 + (int)(hash01_2d(areaX, areaZ, seed ^ 0x5678FEDC9012L, 89) * 104f);
        int jx = areaX * 128 + localX;
        int jz = areaZ * 128 + localZ;
        int jy = caveBandMinY + 6;
        return new int[]{jx, jy, jz};
    }

    private static boolean isJournalPos(int wx, int wy, int wz, long seed) {
        int areaX = Math.floorDiv(wx, 128);
        int areaZ = Math.floorDiv(wz, 128);
        int localX = 12 + (int)(hash01_2d(areaX, areaZ, seed ^ 0xABCD1234EF56L, 83) * 104f);
        int localZ = 12 + (int)(hash01_2d(areaX, areaZ, seed ^ 0x5678FEDC9012L, 89) * 104f);
        int jx = areaX * 128 + localX;
        int jz = areaZ * 128 + localZ;
        int jy = caveBandMinY + 6;
        return wx == jx && wz == jz && wy == jy;
    }

    // M153: VOID GATE â€” VOIDSTONE shrine at world origin. Always present. Escape point when relics complete.
    private static byte voidGateAt(int wx, int wy, int wz, long seed) {
        if (Math.abs(wx) > 6 || Math.abs(wz) > 6) return BlockId.AIR;
        int h = heightAt(0, 0, seed);
        // Central CRYSTAL altar â€” the focal point
        if (wx == 0 && wz == 0 && wy == h + 1) return BlockId.CRYSTAL;
        // 8 VOIDSTONE pillars in an octagon at radius ~4, 4 blocks tall
        final int[][] pillars = {{4,0},{-4,0},{0,4},{0,-4},{3,3},{-3,3},{3,-3},{-3,-3}};
        for (int[] p : pillars) {
            if (wx == p[0] && wz == p[1] && wy >= h + 1 && wy <= h + 4) return BlockId.VOIDSTONE;
        }
        // Connecting floor ring on the ground plane
        double r = Math.sqrt((double)wx * wx + (double)wz * wz);
        if (wy == h + 1 && r >= 3.3 && r <= 4.7) return BlockId.VOIDSTONE;
        return BlockId.AIR;
    }

    public static byte baseBlockAt(int wx, int wy, int wz, long seed) {
        // M225: liminal zone â€” bypass all overworld generation
        if (liminalZoneId == 1) return meadowBlockAt(wx, wy, wz, seed);
        if (liminalZoneId == 2) return darkRoomBlockAt(wx, wy, wz, seed);

        // M88: VOIDSTONE â€” ancient bedrock, absolute world floor (Y=0..2)
        if (wy <= 2) return BlockId.VOIDSTONE;

        // M184: world boundary â€” solid VOIDSTONE wall beyond Â±WORLD_RADIUS
        if (Math.abs(wx) > WORLD_RADIUS || Math.abs(wz) > WORLD_RADIUS) return BlockId.VOIDSTONE;

        // Relic/Journal checks first â€” override everything at their deterministic positions
        if (isSurfaceRelicPos(wx, wy, wz, seed)) return BlockId.RELIC; // M204: surface relics
        if (isRelicPos(wx, wy, wz, seed))        return BlockId.RELIC;
        if (isJournalPos(wx, wy, wz, seed)) return BlockId.JOURNAL;

        // M153: VOID GATE shrine at world origin â€” checked before surface objects
        {
            byte vg = voidGateAt(wx, wy, wz, seed);
            if (vg != BlockId.AIR) return vg;
        }

        // M225: liminal portal arch â€” checked before terrain so it carves through hills
        {
            byte pa = portalArchAt(wx, wy, wz, seed);
            if (pa != Byte.MIN_VALUE) return pa;
        }

        int h = heightAt(wx, wz, seed);
        if (wy > h) {
            // M229: DEAD biome surface scatter — BONES/BLOODSTAIN sit ON TOP of solid terrain (no holes)
            if (wy == h + 1 && biomeAt(wx, wz, seed) == BIOME_DEAD) {
                float dr = hash01_3d(wx, wy, wz, seed ^ 0xB0DE5B1ADL);
                if (dr < 0.007f)      return BlockId.BLOODSTAIN;
                if (dr < 0.028f)      return BlockId.BONES;
            }
            // M153: relic monument has priority over all other surface objects
            byte rm = relicMonumentAt(wx, wy, wz, seed);
            if (rm != BlockId.AIR) return rm;
            byte sr = surfaceRuinAt(wx, wy, wz, seed);
            if (sr != BlockId.AIR) return sr;
            byte cf = campfireBlockAt(wx, wy, wz, seed);
            if (cf != BlockId.AIR) return cf;
            byte lm = landmarkBlockAt(wx, wy, wz, seed);
            if (lm != BlockId.AIR) return lm;
            return treeBlockAt(wx, wy, wz, seed);
        }

        int biome = biomeAt(wx, wz, seed);

        byte id;
        if (wy == h) {
            id = switch (biome) {
                case BIOME_SWAMP -> BlockId.MUD;
                // M229: DEAD surface is always DIRT at wy==h; decorative scatter placed at h+1 below
                case BIOME_DEAD -> BlockId.DIRT;
                default -> BlockId.GRASS;
            };
        } else if (wy >= h - 3) {
            id = biome == BIOME_SWAMP ? BlockId.MUD : BlockId.DIRT;
        } else {
            id = BlockId.STONE;
        }

        // M146: surface ruins (checked before terrain + dungeon)
        {
            byte sr = surfaceRuinAt(wx, wy, wz, seed);
            if (sr != BlockId.AIR) return sr;
        }

        // M50: dungeon rooms â€” override terrain before cave carving to guarantee geometry
        {
            byte dr = dungeonRoomBlockAt(wx, wy, wz, seed);
            if (dr != Byte.MIN_VALUE) return dr;
        }
        // M95: deep cave loot cache
        {
            byte dc = deepCacheBlockAt(wx, wy, wz, seed);
            if (dc != Byte.MIN_VALUE) return dc;
        }
        // M172: secret 666 chamber easter egg
        {
            byte s6 = secret666At(wx, wy, wz);
            if (s6 != Byte.MIN_VALUE) return s6;
        }

        // M149: coal ore veins â€” smooth noise clusters through any stone below surface
        if (id == BlockId.STONE && wy < h - 3) {
            float coalV = valueNoise3(wx * 0.15f, wy * 0.20f, wz * 0.15f, seed ^ 0xC0A1C0A1C0L);
            if (coalV > 0.76f) id = BlockId.COAL;
        }

        // M165: cave entrance â€” wide bowl at surface funneling to narrow shaft, stone collar rim
        {
            byte ce = caveEntranceAt(wx, wy, wz, seed);
            if (ce != Byte.MIN_VALUE) return ce;
        }

        if (id != BlockId.AIR && wy >= caveBandMinY && wy <= caveBandMaxY && wy < h - 3) {
            float a = valueNoise3(wx * caveFreqA, wy * (caveFreqA * 1.12f), wz * caveFreqA, seed ^ 0xBADC0FFEE0DDF00DL);
            float b = valueNoise3(wx * caveFreqB + 311f, wy * (caveFreqB * 1.35f) - 177f, wz * caveFreqB - 733f, seed ^ 0xCAFEBABE1234L);
            float cField = a * 0.72f + b * 0.58f;
            float surfaceBias = (h - wy <= 6) ? 0.08f : 0f;
            if (cField > caveThreshold + surfaceBias) id = BlockId.AIR;
        }

        return id;
    }

    // M50: dungeon rooms â€” underground carved rooms with stone floors, bone arrangements, lanterns, journals
    // Returns Byte.MIN_VALUE if this position is not inside any dungeon room/vault.
    // M95: deep cave loot cache - rare crystal-ringed altars with relics/bones
    // Returns Byte.MIN_VALUE if not inside any cache.
    private static byte deepCacheBlockAt(int wx, int wy, int wz, long seed) {
        int ax = Math.floorDiv(wx, 128);
        int az = Math.floorDiv(wz, 128);
        if (hash01_2d(ax, az, seed ^ 0xD33CA4C0EL, 97) >= 0.08f) return Byte.MIN_VALUE;
        int cx = ax * 128 + 10 + (int)(hash01_2d(ax, az, seed ^ 0xDE3CA4C1EL, 61) * 108f);
        int cz = az * 128 + 10 + (int)(hash01_2d(ax, az, seed ^ 0xDE3CA4C2EL, 67) * 108f);
        int cy = caveBandMinY + 2;
        int adx = Math.abs(wx - cx);
        int adz = Math.abs(wz - cz);
        int dy  = wy - cy;
        if (adx > 3 || adz > 3 || dy < -1 || dy > 3) return Byte.MIN_VALUE;
        if (dy == -1) return BlockId.STONE;
        if (adx == 3 && adz == 3 && dy <= 2) return BlockId.CRYSTAL;
        if (adx == 0 && adz == 0 && dy == 0) return BlockId.RELIC;
        if (dy == 0 && adx <= 2 && adz <= 2 && !(adx == 0 && adz == 0)) {
            float roll = hash01_3d(wx, wy, wz, seed ^ 0xB0DE4CA1EL);
            if (roll < 0.50f) return BlockId.BONES;
        }
        if (dy == 0 && (wx - cx) == 2 && (wz - cz) == -2) return BlockId.LANTERN;
        if (dy == 0 && (wx - cx) == -2 && (wz - cz) == 2) return BlockId.LANTERN;
        if (dy == 0 && (wx - cx) == 2 && (wz - cz) == 2
                && hash01_2d(ax, az, seed ^ 0xDE4DB33FL, 83) < 0.60f) {
            return BlockId.JOURNAL;
        }
        if (dy == 0 && (adx == 2 || adz == 2)) {
            float fn = hash01_3d(wx, wy, wz, seed ^ 0xF0A1D3E4L);
            if (fn < 0.22f) return BlockId.FUNGUS;
        }
        return BlockId.AIR;
    }


    /**
     * M172 Easter Egg: The 666 Chamber.
     * A hidden VOIDSTONE-walled room deep underground at world coords (666,y,666).
     * Glowing CRYSTAL pillars, a JOURNAL at the center.
     * Returns Byte.MIN_VALUE outside the area, a block id inside.
     */
    private static byte secret666At(int wx, int wy, int wz) {
        int dx = wx - 666, dz = wz - 666;
        int adx = Math.abs(dx), adz = Math.abs(dz);
        if (adx > 3 || adz > 3) return Byte.MIN_VALUE;
        int floor = caveBandMinY + 1; // room floor Y
        int dy = wy - floor;
        if (dy < -1 || dy > 4) return Byte.MIN_VALUE;
        if (dy == -1) return BlockId.STONE;              // solid subfloor
        if (dy == 0 || dy == 4) return BlockId.VOIDSTONE; // floor and ceiling
        // Interior height dy=1,2,3
        if (adx == 3 || adz == 3) return BlockId.VOIDSTONE; // outer walls
        if (adx == 2 && adz == 2) return BlockId.CRYSTAL;   // glowing corner pillars
        if (dy == 1 && dx == 0 && dz == 2) return BlockId.JOURNAL; // south journal
        return BlockId.AIR;
    }
    // M177: Underground encampments - old campfire sites, dead campfires with bone rings and journals
    // One per 64x64 area, ~22% chance. Placed on cave floor after carving.
    private static void applyUndergroundEncampments(VoxelChunk c, int baseX, int baseZ, long seed) {
        // Check which 64x64 areas intersect this chunk
        int aX0 = Math.floorDiv(baseX, 64);
        int aZ0 = Math.floorDiv(baseZ, 64);
        int aX1 = Math.floorDiv(baseX + Chunk.SIZE - 1, 64);
        int aZ1 = Math.floorDiv(baseZ + Chunk.SIZE - 1, 64);
        for (int ax = aX0; ax <= aX1; ax++) {
            for (int az = aZ0; az <= aZ1; az++) {
                if (hash01_2d(ax, az, seed ^ 0xCA1A5173L, 53) >= 0.22f) continue; // 22% chance
                int campX = ax * 64 + 4 + (int)(hash01_2d(ax, az, seed ^ 0xCA1A5EEDL, 61) * 56f);
                int campZ = az * 64 + 4 + (int)(hash01_2d(ax, az, seed ^ 0xCA1A5EEDL, 67) * 56f);
                // Find cave floor at this XZ position (scan down from caveBandMaxY)
                int campY = -1;
                int lx = campX - baseX, lz = campZ - baseZ;
                if (lx < 0 || lx >= Chunk.SIZE || lz < 0 || lz >= Chunk.SIZE) continue;
                for (int sy = caveBandMinY + 5; sy > caveBandMinY; sy--) {
                    if (c.get(lx, sy, lz) == BlockId.STONE && c.get(lx, sy + 1, lz) == BlockId.AIR
                            && c.get(lx, sy + 2, lz) == BlockId.AIR) {
                        campY = sy + 1; // floor level (AIR above solid stone)
                        break;
                    }
                }
                if (campY < 0) continue; // no cave floor here
                // Place campfire at center
                c.set(lx, campY, lz, BlockId.CAMPFIRE);
                // Bone ring - 4 cardinal spots
                int[][] ring = {{1,0},{-1,0},{0,1},{0,-1}};
                for (int[] d : ring) {
                    int bx = lx + d[0], bz = lz + d[1];
                    if (bx >= 0 && bx < Chunk.SIZE && bz >= 0 && bz < Chunk.SIZE
                            && c.get(bx, campY - 1, bz) == BlockId.STONE
                            && c.get(bx, campY, bz) == BlockId.AIR) {
                        c.set(bx, campY, bz, BlockId.BONES);
                    }
                }
                // Optional journal leaning at +2 diag (60% chance)
                if (hash01_2d(ax, az, seed ^ 0xCA1AE07AL, 71) < 0.60f) {
                    int jx = lx + 2, jz = lz - 1;
                    if (jx >= 0 && jx < Chunk.SIZE && jz >= 0 && jz < Chunk.SIZE
                            && c.get(jx, campY - 1, jz) == BlockId.STONE
                            && c.get(jx, campY, jz) == BlockId.AIR) {
                        c.set(jx, campY, jz, BlockId.JOURNAL);
                    }
                }
                // Optional lantern at -2 diag (45% chance)
                if (hash01_2d(ax, az, seed ^ 0xCA1A1A17L, 79) < 0.45f) {
                    int nx = lx - 1, nz = lz + 2;
                    if (nx >= 0 && nx < Chunk.SIZE && nz >= 0 && nz < Chunk.SIZE
                            && c.get(nx, campY - 1, nz) == BlockId.STONE
                            && c.get(nx, campY, nz) == BlockId.AIR) {
                        c.set(nx, campY, nz, BlockId.LANTERN);
                    }
                }
            }
        }
    }

        // M178: Crystal geodes - hollow sphere pockets lined with CRYSTAL in CRYSTAL zones
    // ~4% of 96x96 areas in CRYSTAL zone. Creates a hollow chamber you can walk inside.
    private static void applyGeodes(VoxelChunk c, int baseX, int baseZ, long seed) {
        int aX0 = Math.floorDiv(baseX, 96), aZ0 = Math.floorDiv(baseZ, 96);
        int aX1 = Math.floorDiv(baseX + Chunk.SIZE - 1, 96);
        int aZ1 = Math.floorDiv(baseZ + Chunk.SIZE - 1, 96);
        for (int ax = aX0; ax <= aX1; ax++) {
            for (int az = aZ0; az <= aZ1; az++) {
                if (hash01_2d(ax, az, seed ^ 0x6E0DE5EDL, 53) >= 0.04f) continue;
                // Only spawn in CRYSTAL zones
                int gx = ax * 96 + 8 + (int)(hash01_2d(ax, az, seed ^ 0x6E0DE001L, 61) * 80f);
                int gz = az * 96 + 8 + (int)(hash01_2d(ax, az, seed ^ 0x6E0DE002L, 67) * 80f);
                if (caveZoneAt(gx, gz, seed) != CAVE_ZONE_CRYSTAL) continue;
                int gy = caveBandMinY + 8 + (int)(hash01_2d(ax, az, seed ^ 0x6E0DE003L, 71) * 12f);
                float outerR = 3.5f, innerR = 2.2f;
                for (int lz = 0; lz < Chunk.SIZE; lz++) {
                    for (int lx = 0; lx < Chunk.SIZE; lx++) {
                        int wx = baseX + lx, wz = baseZ + lz;
                        float dxF = wx - gx, dzF = wz - gz;
                        if (dxF * dxF + dzF * dzF > (outerR + 1) * (outerR + 1)) continue;
                        for (int y = Math.max(caveBandMinY + 1, gy - (int)outerR - 1);
                                 y <= Math.min(caveBandMaxY - 1, gy + (int)outerR + 1); y++) {
                            float dx2 = wx - gx, dy2 = y - gy, dz2 = wz - gz;
                            float dist = (float)Math.sqrt(dx2*dx2 + dy2*dy2*1.4f + dz2*dz2);
                            if (dist <= innerR) {
                                c.set(lx, y, lz, BlockId.AIR); // hollow interior
                            } else if (dist <= outerR) {
                                c.set(lx, y, lz, BlockId.CRYSTAL); // crystal shell
                            }
                        }
                    }
                }
            }
        }
    }

    // M178: Horror chasms - narrow shafts in DEAD zones going down to VOIDSTONE floor
    // ~7% of 48x48 areas in DEAD zone. Player can fall in or peer into darkness.
    private static void applyChasms(VoxelChunk c, int baseX, int baseZ, long seed) {
        int aX0 = Math.floorDiv(baseX, 48), aZ0 = Math.floorDiv(baseZ, 48);
        int aX1 = Math.floorDiv(baseX + Chunk.SIZE - 1, 48);
        int aZ1 = Math.floorDiv(baseZ + Chunk.SIZE - 1, 48);
        for (int ax = aX0; ax <= aX1; ax++) {
            for (int az = aZ0; az <= aZ1; az++) {
                if (hash01_2d(ax, az, seed ^ 0xC4A51010L, 53) >= 0.07f) continue;
                int cx = ax * 48 + 4 + (int)(hash01_2d(ax, az, seed ^ 0xC4A5A001L, 61) * 40f);
                int cz = az * 48 + 4 + (int)(hash01_2d(ax, az, seed ^ 0xC4A5A002L, 67) * 40f);
                if (caveZoneAt(cx, cz, seed) != CAVE_ZONE_DEAD) continue;
                int radius = 1 + (int)(hash01_2d(ax, az, seed ^ 0xC4A5A003L, 71) * 2f); // 1-2
                int lx = cx - baseX, lz = cz - baseZ;
                for (int dx = -radius; dx <= radius; dx++) {
                    for (int dz = -radius; dz <= radius; dz++) {
                        if (dx*dx + dz*dz > radius*radius + 1) continue; // rough circle
                        int bx = lx + dx, bz2 = lz + dz;
                        if (bx < 0 || bx >= Chunk.SIZE || bz2 < 0 || bz2 >= Chunk.SIZE) continue;
                        // Carve all the way from caveBandMinY down to VOIDSTONE
                        for (int y = 3; y <= caveBandMinY + 5; y++) {
                            c.set(bx, y, bz2, BlockId.AIR);
                        }
                        // BLOODSTAIN on the rim (top of chasm)
                        int rimY = caveBandMinY + 1;
                        if (c.get(bx, rimY, bz2) == BlockId.STONE || c.get(bx, rimY, bz2) == BlockId.AIR) {
                            if (hash01_3d(cx + dx, 0, cz + dz, seed ^ 0xC4A5E1A1L) < 0.35f)
                                c.set(bx, rimY, bz2, BlockId.BLOODSTAIN);
                        }
                    }
                }
            }
        }
    }

        private static byte dungeonRoomBlockAt(int wx, int wy, int wz, long seed) {

        // === VAULT (M174: one per relic, iterate all 3 world relics) ===
        for (int[] rp : getRelicPositions(seed)) {
            int rx = rp[0], ry = rp[1], rz = rp[2];
            int adx = Math.abs(wx - rx);
            int adz = Math.abs(wz - rz);
            int dy  = wy - ry;
            if (adx <= 4 && adz <= 4 && dy >= -1 && dy < 5) {
                if (dy == -1) return BlockId.STONE;
                if (adx == 4 && adz == 4) return BlockId.STONE;
                if (dy == 0 && adx == 3 && adz == 0) return BlockId.LANTERN;
                if (dy == 0 && adx == 0 && adz == 3) return BlockId.LANTERN;
                if (dy == 0 && adx <= 2 && adz <= 2 && !(adx == 0 && adz == 0)) {
                    if (hash01_3d(wx, wy, wz, seed ^ 0xB0DEA1F1EL) < 0.18f) return BlockId.BONES; // M223: was 0.55
                }
                if (dy == 0 && (wx - rx) == 3 && (wz - rz) == 3) return BlockId.JOURNAL;
                return BlockId.AIR;
            }
        }
        // === REGULAR DUNGEON ROOMS (per 64ï¿½-64 area, 38% chance) ===
        {
            int ax = Math.floorDiv(wx, 64);
            int az = Math.floorDiv(wz, 64);
            if (hash01_2d(ax, az, seed ^ 0xD00D5E3DL, 53) >= 0.38f) return Byte.MIN_VALUE;

            int cx  = ax * 64 + 10 + (int)(hash01_2d(ax, az, seed ^ 0xF00DB33FL, 61) * 44f);
            int cz  = az * 64 + 10 + (int)(hash01_2d(ax, az, seed ^ 0xACE330F3L, 67) * 44f);
            int cy  = caveBandMinY + 2;                                   // room floor level
            int hw  = 3 + (int)(hash01_2d(ax, az, seed ^ 0xB00B5E1DL, 71) * 2f); // 3 or 4
            int hd  = 3 + (int)(hash01_2d(ax, az, seed ^ 0xDEA3B33FL, 73) * 2f); // 3 or 4
            int rh  = 4;                                                   // room height in blocks

            int adx = Math.abs(wx - cx);
            int adz = Math.abs(wz - cz);
            int dy  = wy - cy;

            if (adx <= hw && adz <= hd && dy >= -1 && dy < rh) {
                if (dy == -1) return BlockId.STONE;                        // guaranteed solid floor
                // Lanterns in two diagonal corners at floor level
                if (dy == 0 && adx == hw && adz == hd) return BlockId.LANTERN;
                // Central bone pile
                if (dy == 0 && adx == 0 && adz == 0) return BlockId.BONES;
                // Scattered floor details
                if (dy == 0) {
                    float roll = hash01_3d(wx, wy, wz, seed ^ 0xBEEFB0DEL);
                    if (roll < 0.015f) return BlockId.BLOODSTAIN; // M223: was 0.04
                    if (roll < 0.060f) return BlockId.BONES;      // M223: was 0.16
                }
                // Journal in one inner-corner of ~55% of rooms
                if (dy == 0 && adx == hw - 1 && adz == hd - 1
                        && hash01_2d(ax, az, seed ^ 0xDEADBEEFL, 79) < 0.55f) {
                    return BlockId.JOURNAL;
                }
                return BlockId.AIR;
            }
        }

        return Byte.MIN_VALUE;
    }

    // M45: campfire placement â€” one ember per 64x64 area, surface only, PINE/DEAD biomes only
    private static byte campfireBlockAt(int wx, int wy, int wz, long seed) {
        int cx = Math.floorDiv(wx, 64);
        int cz = Math.floorDiv(wz, 64);
        // Only 20% of 64x64 areas have a campfire
        if (hash01_2d(cx, cz, seed ^ 0xDEADCAFE5CA1L, 67) >= 0.20f) return BlockId.AIR;

        int centerX = cx * 64 + 32;
        int centerZ = cz * 64 + 32;
        int biome = biomeAt(centerX, centerZ, seed);
        if (biome == BIOME_SWAMP) return BlockId.AIR; // no campfires in swamp

        // Place exactly one CAMPFIRE block at surface + 1
        if (wx == centerX && wz == centerZ) {
            int ground = heightAt(wx, wz, seed);
            if (wy == ground + 1) return BlockId.CAMPFIRE;
        }
        return BlockId.AIR;
    }

    // M146: surface ruins â€” small stone cabins with journals, bones, campfires.
    // One ruin per 80Ã—80 world area (~18% chance), skips SWAMP biome.
    // localH = heightAt(wx, wz, seed) already computed by caller (avoids re-call).
    private static byte surfaceRuinAt(int wx, int wy, int wz, long seed) {
        int ax = Math.floorDiv(wx, 80);
        int az = Math.floorDiv(wz, 80);
        if (hash01_2d(ax, az, seed ^ 0xD0AD50FE7L, 71) >= 0.18f) return BlockId.AIR;

        // Ruin center inside the area (10..70 offset avoids chunk boundaries)
        int cx = ax * 80 + 10 + (int)(hash01_2d(ax, az, seed ^ 0x1C3A8B9DL, 67) * 60f);
        int cz = az * 80 + 10 + (int)(hash01_2d(az, ax, seed ^ 0x9F7E2A4CL, 53) * 60f);

        int dx = wx - cx, dz = wz - cz;
        if (Math.abs(dx) > 2 || Math.abs(dz) > 2) return BlockId.AIR; // outside 5Ã—5 footprint

        int biome = biomeAt(cx, cz, seed);
        if (biome == BIOME_SWAMP) return BlockId.AIR; // no ruins in swamp

        int ground = heightAt(cx, cz, seed); // ruin center surface height
        int dy = wy - (ground + 1);          // dy=0 one block above ruin floor
        if (dy < 0 || dy > 2) return BlockId.AIR;

        boolean isPerimeter = (Math.abs(dx) == 2 || Math.abs(dz) == 2);
        // Doorway: north face (dz==-2), centre 3 cols, bottom 2 rows
        boolean isDoorway = (dz == -2 && Math.abs(dx) <= 1 && dy <= 1);

        if (isPerimeter && !isDoorway) {
            // DEAD biome: upper wall crumbles away ~45% of the time
            if (biome == BIOME_DEAD && dy == 2
                    && hash01_3d(wx, wy, wz, seed ^ 0xCCA4F00DL) < 0.45f) return BlockId.AIR;
            return BlockId.STONE;
        }

        // Interior floor objects (one block above ruin-center ground level)
        if (!isPerimeter && dy == 0) {
            if (dx == 0 && dz == 0) return BlockId.JOURNAL;              // centrepiece
            if (dx == 1 && dz == 1 && biome == BIOME_PINE) return BlockId.CAMPFIRE;
            float r = hash01_3d(wx, wy, wz, seed ^ 0xB01E5DEEDL);
            if (biome == BIOME_DEAD && r < 0.03f) return BlockId.BLOODSTAIN; // M223: was 0.07 (check first)
            if (biome == BIOME_DEAD && r < 0.09f) return BlockId.BONES;      // M223: was 0.22
        }
        return BlockId.AIR;
    }

    private static byte landmarkBlockAt(int wx, int wy, int wz, long seed) {
        int cx = Math.floorDiv(wx, 32);
        int cz = Math.floorDiv(wz, 32);
        float roll = hash01_2d(cx, cz, seed ^ 0x1A2B3C4DL, 57);
        if (roll < 0.93f) return BlockId.AIR;

        int centerX = cx * 32 + 16;
        int centerZ = cz * 32 + 16;
        int biome = biomeAt(centerX, centerZ, seed);
        int ground = heightAt(centerX, centerZ, seed);

        int dx = Math.abs(wx - centerX);
        int dz = Math.abs(wz - centerZ);

        if (biome == BIOME_DEAD) {
            // broken stone pillar landmark
            int h = 5 + (int) (hash01_2d(cx, cz, seed ^ 0x9090F00DL, 59) * 6f);
            if (dx == 0 && dz == 0 && wy >= ground + 1 && wy <= ground + h) return BlockId.STONE;
            if (wy == ground + 1 && dx <= 1 && dz <= 1) return BlockId.STONE;
            return BlockId.AIR;
        }

        if (biome == BIOME_SWAMP) {
            // shack footprint / reeds landmark
            if (wy == ground + 1 && dx <= 2 && dz <= 2 && (dx == 2 || dz == 2)) return BlockId.WOOD;
            if (wy >= ground + 2 && wy <= ground + 4 && dx == 2 && dz == 2) return BlockId.WOOD;
            if (wy == ground + 2 && dx <= 1 && dz <= 1) return BlockId.LEAVES;
            return BlockId.AIR;
        }

        // pine biome cairn/log marker
        if (wy == ground + 1 && dx <= 2 && dz <= 1) return BlockId.WOOD;
        if (wy == ground + 2 && dx == 0 && dz == 0) return BlockId.STONE;
        return BlockId.AIR;
    }

    private static byte treeBlockAt(int wx, int wy, int wz, long seed) {
        int tx = Math.floorDiv(wx, 5);
        int tz = Math.floorDiv(wz, 5);

        float spawn = hash01_2d(tx, tz, seed ^ 0xA11CE5EEDL, 29);
        int biome = biomeAt(wx, wz, seed);
        float chance = switch (biome) {
            case BIOME_PINE -> 0.34f;
            case BIOME_DEAD -> 0.24f;
            default -> 0.30f;
        };
        if (spawn > chance) return BlockId.AIR;

        int centerX = tx * 5 + 2;
        int centerZ = tz * 5 + 2;
        if (wx != centerX || wz != centerZ) {
            // canopy check
            int trunkBase = heightAt(centerX, centerZ, seed) + 1;
            int trunkH = 4 + (int) (hash01_2d(tx, tz, seed ^ 0x55AAEE11L, 31) * 4f);
            int top = trunkBase + trunkH;
            int dx = Math.abs(wx - centerX);
            int dz = Math.abs(wz - centerZ);
            int cheb = Math.max(dx, dz);
            if (wy >= top - 2 && wy <= top + 1 && cheb <= 2) {
                if (!(wy == top + 1 && cheb > 1) && !(dx == 2 && dz == 2)) {
                    return BlockId.LEAVES; // M224: cobwebs removed from tree canopy
                }
            }

            // biome props around tree anchors (dead logs / swamp reeds)
            int ground = heightAt(wx, wz, seed);
            if (wy == ground + 1) {
                if (biome == BIOME_DEAD && hash01_2d(tx, tz, seed ^ 0xD34D10L, 41) > 0.86f && (dx <= 1 || dz <= 1)) {
                    return BlockId.WOOD;
                }
                if (biome == BIOME_SWAMP && hash01_2d(tx, tz, seed ^ 0x5A77A77L, 43) > 0.78f && cheb <= 1) {
                    return BlockId.LEAVES;
                }
            }
            return BlockId.AIR;
        }

        int trunkBase = heightAt(wx, wz, seed) + 1;
        int trunkH = 4 + (int) (hash01_2d(tx, tz, seed ^ 0x55AAEE11L, 31) * 4f);
        if (wy >= trunkBase && wy <= trunkBase + trunkH) return BlockId.WOOD;

        return BlockId.AIR;
    }

    // M165: cave entrance â€” natural bowl/funnel shape at surface connects to underground.
    // One entrance per 96x96 area with ~25% probability (sparser than the old 64/40%).
    // Shape: wide bowl (~4 block radius) at surface funnels down to a ~1.5-block shaft.
    // Returns BlockId.AIR inside the carved zone, BlockId.STONE for the surface stone collar,
    // or Byte.MIN_VALUE for normal terrain generation.
    private static byte caveEntranceAt(int wx, int wy, int wz, long seed) {
        int ax = Math.floorDiv(wx, 96);
        int az = Math.floorDiv(wz, 96);
        if (hash01_2d(ax, az, seed ^ 0xCA1EE1ACEL, 97) >= 0.25f) return Byte.MIN_VALUE;

        int ex = ax * 96 + 8 + (int)(hash01_2d(ax, az, seed ^ 0xE1A1CE7ACL, 61) * 80f);
        int ez = az * 96 + 8 + (int)(hash01_2d(ax, az, seed ^ 0xACE17A1CEL, 67) * 80f);

        int h = heightAt(ex, ez, seed);
        int depth = h - wy; // 0 = surface top block, positive = deeper underground

        if (depth < 0 || depth > 24) return Byte.MIN_VALUE;

        // Bowl: wide at surface (radius 4.0), funnels down to shaft (radius 1.5) by depth 4
        float carvR = (depth < 4)
                ? Math.max(1.5f, 4.0f - depth * 0.65f)  // 4.00, 3.35, 2.70, 2.05 then shaft
                : 1.5f;
        float rimR = carvR + 1.5f; // stone collar extends 1.5 blocks beyond carved edge

        int dx = wx - ex, dz = wz - ez;
        float d2 = dx * dx + dz * dz;

        if (d2 <= carvR * carvR) return BlockId.AIR;                 // inside bowl/shaft
        if (depth <= 1 && d2 <= rimR * rimR) return BlockId.STONE;   // stone rim at surface
        return Byte.MIN_VALUE;
    }

    private static int biomeAt(int x, int z, long seed) {
        float[] w = biomeWeightsAt(x, z, seed);
        if (w[BIOME_SWAMP] >= w[BIOME_PINE] && w[BIOME_SWAMP] >= w[BIOME_DEAD]) return BIOME_SWAMP;
        if (w[BIOME_PINE] >= w[BIOME_DEAD]) return BIOME_PINE;
        return BIOME_DEAD;
    }

    public static float[] biomeWeightsAt(int x, int z, long seed) {
        if (forcedBiome >= BIOME_PINE && forcedBiome <= BIOME_SWAMP) {
            float[] one = new float[]{0f, 0f, 0f};
            one[forcedBiome] = 1f;
            return one;
        }

        float t = valueNoise2(x * 0.010f + 120f, z * 0.010f - 90f, seed ^ 0x33337777AAAA5555L);
        float m = valueNoise2(x * 0.013f - 340f, z * 0.013f + 260f, seed ^ 0x9999DDDDEEEE1111L);

        float swamp = smoothstep(0.50f, 0.78f, m);
        float pineCold = 1f - smoothstep(0.30f, 0.58f, t);

        float land = 1f - swamp;
        float pine = land * pineCold;
        float dead = land * (1f - pineCold);

        float sum = pine + dead + swamp;
        if (sum <= 0.0001f) return new float[]{0.33f, 0.34f, 0.33f};
        return new float[]{pine / sum, dead / sum, swamp / sum};
    }

    public static int heightAt(int x, int z, long seed) {
        float[] w = biomeWeightsAt(x, z, seed);

        float n = valueNoise2(x * 0.022f, z * 0.022f, seed ^ 0x1234ABCD9876L) - 0.5f;
        float n2 = valueNoise2(x * 0.007f + 200f, z * 0.007f - 120f, seed ^ 0x777733339999L) - 0.5f;
        float n3 = valueNoise2(x * 0.042f - 88f, z * 0.042f + 64f, seed ^ 0x1111222233334444L) - 0.5f;

        // M90: raised terrain to Yâ‰ˆ70-80 so there are ~55 blocks of depth to VOIDSTONE floor
        float base = w[BIOME_PINE] * 72f + w[BIOME_DEAD] * 68f + w[BIOME_SWAMP] * 64f;
        float amp1 = w[BIOME_PINE] * 9f + w[BIOME_DEAD] * 8f + w[BIOME_SWAMP] * 5f;
        float amp2 = w[BIOME_PINE] * 10f + w[BIOME_DEAD] * 9f + w[BIOME_SWAMP] * 6f;

        // Cleaner biome boundaries: flatten sharp changes where two biomes strongly mix.
        float maxW = Math.max(w[BIOME_PINE], Math.max(w[BIOME_DEAD], w[BIOME_SWAMP]));
        float boundaryBlend = 1f - maxW; // near 0 inside biome, larger near boundaries
        float erosion = smoothstep(0.12f, 0.34f, boundaryBlend);

        float riverNoise = valueNoise2(x * 0.004f + 600f, z * 0.004f - 420f, seed ^ 0xABCDEF102030L);
        float riverMask = 1f - Math.abs(riverNoise - 0.5f) * 2f;
        riverMask = smoothstep(0.72f, 0.95f, riverMask);

        // Swamp basins and gentler transitions.
        float swampBasin = w[BIOME_SWAMP] * (1.6f + riverMask * 2.4f);

        float hRaw = base + (n * amp1 + n2 * amp2 + n3 * 3.2f) * (1f - erosion * 0.30f) - riverMask * 3.8f - swampBasin;
        int h = Math.round(hRaw);
        if (h < 4) h = 4;
        if (h > VoxelChunk.SIZE_Y - 6) h = VoxelChunk.SIZE_Y - 6;
        return h;
    }

    private static float valueNoise2(float x, float z, long seed) {
        int x0 = fastFloor(x), z0 = fastFloor(z);
        int x1 = x0 + 1, z1 = z0 + 1;
        float tx = x - x0, tz = z - z0;
        float u = smooth(tx), v = smooth(tz);

        float h00 = hash01_2d(x0, z0, seed, 13);
        float h10 = hash01_2d(x1, z0, seed, 13);
        float h01 = hash01_2d(x0, z1, seed, 13);
        float h11 = hash01_2d(x1, z1, seed, 13);

        float hx0 = lerp(h00, h10, u);
        float hx1 = lerp(h01, h11, u);
        return lerp(hx0, hx1, v);
    }

    private static float valueNoise3(float x, float y, float z, long seed) {
        int x0 = fastFloor(x), y0 = fastFloor(y), z0 = fastFloor(z);
        int x1 = x0 + 1, y1 = y0 + 1, z1 = z0 + 1;
        float tx = x - x0, ty = y - y0, tz = z - z0;
        float u = smooth(tx), v = smooth(ty), w = smooth(tz);

        float c000 = hash01_3d(x0, y0, z0, seed);
        float c100 = hash01_3d(x1, y0, z0, seed);
        float c010 = hash01_3d(x0, y1, z0, seed);
        float c110 = hash01_3d(x1, y1, z0, seed);
        float c001 = hash01_3d(x0, y0, z1, seed);
        float c101 = hash01_3d(x1, y0, z1, seed);
        float c011 = hash01_3d(x0, y1, z1, seed);
        float c111 = hash01_3d(x1, y1, z1, seed);

        float x00 = lerp(c000, c100, u);
        float x10 = lerp(c010, c110, u);
        float x01 = lerp(c001, c101, u);
        float x11 = lerp(c011, c111, u);
        float y0m = lerp(x00, x10, v);
        float y1m = lerp(x01, x11, v);
        return lerp(y0m, y1m, w);
    }

    private static float smooth(float t) { return t * t * (3f - 2f * t); }
    private static float smoothstep(float edge0, float edge1, float x) {
        float t = clamp((x - edge0) / (edge1 - edge0), 0f, 1f);
        return t * t * (3f - 2f * t);
    }
    private static float lerp(float a, float b, float t) { return a + (b - a) * t; }
    private static float clamp(float v, float lo, float hi) { return Math.max(lo, Math.min(hi, v)); }

    private static int fastFloor(float v) {
        int i = (int) v;
        return (v < i) ? (i - 1) : i;
    }

    private static float hash01_2d(int x, int z, long seed, int s) {
        long h = (x * 374761393L) ^ (z * 668265263L) ^ (seed * 1442695040888963407L) ^ (s * 1274126177L);
        h = (h ^ (h >>> 29)) * 6364136223846793005L;
        h ^= (h >>> 33);
        return (h & 0x00ffffffL) / (float) 0x01000000;
    }

    private static float hash01_3d(int x, int y, int z, long seed) {
        long h = (x * 374761393L) ^ (y * 668265263L) ^ (z * 1274126177L) ^ (seed * 1099511628211L);
        h = (h ^ (h >>> 29)) * 6364136223846793005L;
        h ^= (h >>> 33);
        return (h & 0x00ffffffL) / (float) 0x01000000;
    }

    // ---------------------------------------------------------------- M225: LIMINAL ZONE

    /**
     * Generates the liminal space interior Ã¢â‚¬â€ an infinite pale-lit corridor grid.
     * Floor=WOOD_PLANK, walls=STONE on a 12-block grid with hash-gapped doorways,
     * ceiling=WOOD_PLANK with LANTERN at room centres. Return portal near (50,0,50).
     */
    private static byte liminalBlockAt(int wx, int wy, int wz, long seed) {
        // Solid basement below floor
        if (wy < 0) return BlockId.STONE;

        // Return portal cluster Ã¢â‚¬â€ 3x3 LIMINAL_PORTAL at (49-51, 0, 49-51)
        if (wy == 0 && wx >= 49 && wx <= 51 && wz >= 49 && wz <= 51)
            return BlockId.LIMINAL_PORTAL;

        // Floor
        if (wy == 0) return BlockId.WOOD_PLANK;

        // Ceiling (Y=4): LANTERN at room centre (6,6 within the 12-block cell), else WOOD_PLANK
        if (wy == 4) {
            int lx = Math.floorMod(wx, 12);
            int lz = Math.floorMod(wz, 12);
            return (lx == 6 && lz == 6) ? BlockId.LANTERN : BlockId.WOOD_PLANK;
        }

        // Above ceiling: open void
        if (wy > 4) return BlockId.AIR;

        // Y=1..3: corridor walls on a 12-block grid
        boolean onXWall = (Math.floorMod(wx, 12) == 0);
        boolean onZWall = (Math.floorMod(wz, 12) == 0);

        // Open interior
        if (!onXWall && !onZWall) return BlockId.AIR;

        // Corner pillars Ã¢â‚¬â€ always solid (prevents diagonal see-through)
        if (onXWall && onZWall) return BlockId.STONE;

        // Wall with doorway gap Ã¢â‚¬â€ 2 blocks wide, hash-positioned at offset 3..9 in the 11-block span
        if (onXWall) {
            // Wall runs along Z; doorway cuts through at a Z offset within the room cell
            int roomX  = Math.floorDiv(wx, 12);  // which room boundary (X axis)
            int roomZ  = Math.floorDiv(wz, 12);  // which room cell (Z axis)
            int lz     = Math.floorMod(wz, 12);  // local Z within cell (1..11 for non-corner)
            int gap    = 2 + (int)(hash01_2d(roomX, roomZ, seed ^ 0x1AB2CD3EL, 37) * 8f); // 2..9
            if (lz == gap || lz == gap + 1) return BlockId.AIR; // doorway
        } else { // onZWall
            int roomX  = Math.floorDiv(wx, 12);
            int roomZ  = Math.floorDiv(wz, 12);
            int lx     = Math.floorMod(wx, 12);
            int gap    = 2 + (int)(hash01_2d(roomX, roomZ, seed ^ 0x2BC3DE4FL, 41) * 8f);
            if (lx == gap || lx == gap + 1) return BlockId.AIR;
        }
        return BlockId.STONE;
    }

    /**
     * Overworld portal arch Ã¢â‚¬â€ one per 512x512 area.
     * Returns Byte.MIN_VALUE if this position is not part of any arch.
     * Returns AIR to carve the inner space, VOIDSTONE for frame, LIMINAL_PORTAL for floor tiles.
     */
    private static byte portalArchAt(int wx, int wy, int wz, long seed) {
        // M229: 4 portals near boundary walls — one per cardinal side, 5 blocks from WORLD_RADIUS
        final int DIST = WORLD_RADIUS - 5; // 395 blocks from origin — right next to the void walls
        // Each arch: [centerX, centerZ, facingZ] — facingZ=1 means arch face is along Z axis
        int[][] arches = { {0, -DIST, 1}, {0, DIST, 1}, {-DIST, 0, 0}, {DIST, 0, 0} };
        for (int[] a : arches) {
            int cx = a[0], cz = a[1];
            boolean facingZ = (a[2] == 1);
            int dx = wx - cx, dz = wz - cz;
            int slab = facingZ ? dx : dz;
            int depth = facingZ ? dz : dx;
            if (depth != 0) continue;
            if (Math.abs(slab) > 2) continue;
            int h = heightAt(cx, cz, seed);
            int dy = wy - (h + 1); // M229+: arch raised 1 tile above surface so portal tiles sit on top
            if (dy < 0 || dy > 5) continue;
            if (dy == 0 && Math.abs(slab) == 2) return BlockId.VOIDSTONE;
            if (dy == 0 && Math.abs(slab) <= 1) return BlockId.LIMINAL_PORTAL;
            if (dy >= 1 && dy <= 4 && Math.abs(slab) == 2) return BlockId.VOIDSTONE;
            if (dy >= 1 && dy <= 4 && Math.abs(slab) <= 1) return BlockId.AIR;
            if (dy == 5 && Math.abs(slab) <= 2) return BlockId.VOIDSTONE;
        }
        return Byte.MIN_VALUE;
    }

    // ---------------------------------------------------------------- M229: ZONE 1 — MEADOW
    private static byte meadowBlockAt(int wx, int wy, int wz, long seed) {
        final int GROUND   = 4;
        final int TREE_GAP = 18;  // M231: wider spacing between trees
        final int BX       = 80, BZ = 80, BHALF = 7;
        if (wy < 0)  return BlockId.VOIDSTONE;
        // Return portal (floor tiles, 3x3 near meadow spawn)
        if (wy == GROUND && wx >= 49 && wx <= 51 && wz >= 49 && wz <= 51) return BlockId.LIMINAL_PORTAL;
        if (wy >= 1 && wy <= GROUND - 1) return BlockId.DIRT;
        if (wy == GROUND) return BlockId.GRASS;

        // M231: building entrance portal arch at south wall — checked before wall code
        // Z-facing arch: slab=wx-BX (-2..+2), ady=wy-(GROUND+1) (0..5)
        if (wz == BZ - BHALF) {
            int slab = wx - BX;
            int ady  = wy - (GROUND + 1);
            if (Math.abs(slab) <= 2 && ady >= 0 && ady <= 5) {
                if (ady == 0 && Math.abs(slab) == 2) return BlockId.VOIDSTONE;   // pillar base corners
                if (ady == 0 && Math.abs(slab) <= 1) return BlockId.LIMINAL_PORTAL; // portal floor tiles
                if (ady >= 1 && ady <= 4 && Math.abs(slab) == 2) return BlockId.VOIDSTONE; // pillars
                if (ady >= 1 && ady <= 4 && Math.abs(slab) <= 1) return BlockId.AIR;  // open passage
                if (ady == 5) return BlockId.VOIDSTONE; // cap
            }
        }

        boolean inBuildX   = (wx >= BX - BHALF && wx <= BX + BHALF);
        boolean inBuildZ   = (wz >= BZ - BHALF && wz <= BZ + BHALF);
        boolean onWallX    = (wx == BX - BHALF || wx == BX + BHALF);
        boolean onWallZ    = (wz == BZ - BHALF || wz == BZ + BHALF);
        boolean insideBuild = inBuildX && inBuildZ && !onWallX && !onWallZ;
        // Door: south wall gap for the arch width (already handled by arch code above, just keep wider opening)
        boolean isDoor = (wz == BZ - BHALF && Math.abs(wx - BX) <= 2 && wy >= GROUND + 1 && wy <= GROUND + 4);
        if (inBuildX && inBuildZ) {
            if (wy == GROUND + 5 && insideBuild) return BlockId.WOOD_PLANK;
            if (!isDoor && (onWallX || onWallZ) && wy >= GROUND + 1 && wy <= GROUND + 4) return BlockId.WOOD_PLANK;
        }

        int tx = (int) Math.round((double) wx / TREE_GAP) * TREE_GAP;
        int tz = (int) Math.round((double) wz / TREE_GAP) * TREE_GAP;
        int tdx = wx - tx, tdz = wz - tz;
        boolean nearSpawn = (Math.abs(tx - 50) <= 10 && Math.abs(tz - 50) <= 10);
        boolean nearBuild = (Math.abs(tx - BX) <= BHALF + 6 && Math.abs(tz - BZ) <= BHALF + 6);
        if (!nearSpawn && !nearBuild) {
            if (tdx == 0 && tdz == 0 && wy >= GROUND + 1 && wy <= GROUND + 4) return BlockId.WOOD;
            // M231: shorter leaves (3 layers instead of 5, tighter radius)
            if (wy >= GROUND + 3 && wy <= GROUND + 5) {
                float dist = tdx * tdx + (wy - (GROUND + 4)) * (wy - (GROUND + 4)) * 1.2f + tdz * tdz;
                if (dist < 7.0f) return BlockId.LEAVES;
            }
        }
        return BlockId.AIR;
    }

    // ---------------------------------------------------------------- M240: ZONE 2 — HOLLOWAY MANOR (3-storey)
    /**
     * Three-storey fully-sealed wooden mansion on a grassy plain.
     * Ground floor: y=0 floor, y=1-5 interior, y=6 ceiling slab (GFC).
     * Second floor: y=7-11 interior, y=12 ceiling slab (SFC).
     * Third floor:  y=13-17 interior, y=18 ceiling slab (TFC).
     * Roof cap: y=19 perimeter only.
     * Outer footprint: x=-18..18, z=-48..2. ALL outer walls sealed.
     * Staircase 1 (NW, ground→second): x=-17..-15, z=-9..-4.
     * Staircase 2 (NE, second→third):  x=15..17,   z=-9..-4.
     * Portal: upright on back wall (wz=OZ0), seed-random x, 2 wide × 3 tall.
     * No doors — open archways only.
     */
    private static byte darkRoomBlockAt(int wx, int wy, int wz, long seed) {
        final int OX0=-18, OX1=18, OZ0=-48, OZ1=2;
        final int GFC=6;   // ground→second ceiling slab
        final int SFC=12;  // second→third ceiling slab
        final int TFC=18;  // third-floor ceiling slab
        final int ROOF=19; // roof perimeter cap

        if (wy < -1)    return BlockId.STONE;
        if (wy > ROOF)  return BlockId.AIR;
        if (wy == -1)   return BlockId.DIRT;

        // y=0: floor slab
        if (wy == 0) {
            if (wx >= OX0 && wx <= OX1 && wz >= OZ0 && wz <= OZ1) return BlockId.WOOD_PLANK;
            return BlockId.GRASS;
        }

        // Outside footprint: air
        if (wx < OX0 || wx > OX1 || wz < OZ0 || wz > OZ1) return BlockId.AIR;

        boolean onOuterX = (wx == OX0 || wx == OX1);
        boolean onOuterZ = (wz == OZ0 || wz == OZ1);

        // Roof cap: perimeter only
        if (wy == ROOF) return (onOuterX || onOuterZ) ? BlockId.WOOD_PLANK : BlockId.AIR;

        // Third-floor ceiling: full slab
        if (wy == TFC) return BlockId.WOOD_PLANK;

        // Second-floor ceiling — open above NE staircase (x=15..17, z=-9..-4)
        if (wy == SFC) {
            if (wx >= 15 && wx <= 17 && wz >= -9 && wz <= -4) return BlockId.AIR;
            return BlockId.WOOD_PLANK;
        }

        // Ground-floor ceiling — open above NW staircase (x=-17..-15, z=-9..-4)
        if (wy == GFC) {
            if (wx >= -17 && wx <= -15 && wz >= -9 && wz <= -4) return BlockId.AIR;
            return BlockId.WOOD_PLANK;
        }

        // Portal: upright 2×3 on back wall, seed-random x position
        int portalX = -15 + (int)(hash01_2d(2, 3, seed ^ 0x50A71AL, 17) * 10f); // x: -15..-6
        if (wz == OZ0 && (wx == portalX || wx == portalX + 1) && wy >= 1 && wy <= 3) {
            return BlockId.LIMINAL_PORTAL;
        }

        // All outer walls are fully sealed
        if (onOuterX || onOuterZ) return BlockId.WOOD_PLANK;

        // ---- STAIRCASE 1: ground→second (NW foyer: x=-17..-15, z=-9..-4) ----
        if (wx >= -17 && wx <= -15 && wz >= -9 && wz <= -4) {
            int stepH = 1 + (-4 - wz); // z=-4→y=1 ... z=-9→y=6
            if (wy <= stepH) return BlockId.WOOD_PLANK;
            return BlockId.AIR;
        }

        // ---- STAIRCASE 2: second→third (NE foyer: x=15..17, z=-9..-4) ----
        if (wx >= 15 && wx <= 17 && wz >= -9 && wz <= -4) {
            int stepH = 7 + (-4 - wz); // z=-4→y=7 ... z=-9→y=12
            if (wy <= stepH) return BlockId.WOOD_PLANK;
            return BlockId.AIR;
        }

        // Interior ranges
        boolean isGroundInt = (wy >= 1 && wy <= GFC - 1);  // y=1..5
        boolean isUpperInt  = (wy >= 7 && wy <= SFC - 1);  // y=7..11
        boolean isThirdInt  = (wy >= 13 && wy <= TFC - 1); // y=13..17
        if (!isGroundInt && !isUpperInt && !isThirdInt) return BlockId.AIR;

        // ---- INTERNAL WALLS — open archways, no doors ----

        // z=-16: foyer / mid-section divider — archways at x=-12..-11 and x=10..11
        if (wz == -16) {
            if (wx >= -12 && wx <= -11) return BlockId.AIR;
            if (wx >=  10 && wx <=  11) return BlockId.AIR;
            return BlockId.WOOD_PLANK;
        }

        // z=-36: mid / back-hall divider — archway at x=-2..-1
        if (wz == -36) {
            if (wx >= -2 && wx <= -1) return BlockId.AIR;
            return BlockId.WOOD_PLANK;
        }

        // x=-5: left-chamber / corridor — archway at z=-25..-24
        if (wx == -5 && wz > -36 && wz < -16) {
            if (wz >= -25 && wz <= -24) return BlockId.AIR;
            return BlockId.WOOD_PLANK;
        }

        // x=5: right-chamber / corridor — archway at z=-25..-24
        if (wx == 5 && wz > -36 && wz < -16) {
            if (wz >= -25 && wz <= -24) return BlockId.AIR;
            return BlockId.WOOD_PLANK;
        }

        // ---- FLOOR DECORATIONS: lanterns (rare), bones, cobwebs ----
        if (wy == 1 || wy == 7 || wy == 13) {
            float deco = hash01_2d(wx, wz, seed ^ 0xB01E5AL, 11);
            if (deco < 0.005f) return BlockId.LANTERN; // ~0.5% — sparse floor lanterns
            if (deco < 0.022f) return BlockId.BONES;
        }
        if (wy == 5 || wy == 11 || wy == 17) {
            float web = hash01_2d(wx, wz, seed ^ 0xC0B5EL, 13);
            if (web < 0.020f) return BlockId.COBWEB;
        }

        return BlockId.AIR;
    }


}