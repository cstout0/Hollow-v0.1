package noctfield.world;

public final class BlockId {
    private BlockId() {}
    public static final byte AIR = 0;
    public static final byte GRASS = 1;
    public static final byte DIRT = 2;
    public static final byte STONE = 3;
    public static final byte LANTERN = 4;
    public static final byte WOOD = 5;
    public static final byte LEAVES = 6;
    public static final byte MUD = 7;
    public static final byte RELIC    = 8;
    public static final byte JOURNAL  = 9;
    public static final byte CAMPFIRE   = 10; // M45: surface campfire — flickering warm light
    public static final byte BONES      = 11; // M49: scattered bones — DEAD biome surface decal
    public static final byte BLOODSTAIN = 12; // M49: dark blood smear — DEAD biome, rare
    public static final byte COBWEB     = 13; // M49: cobweb mass — PINE canopy, eerie filaments
    public static final byte FUNGUS     = 14; // M84: bioluminescent cave fungus — emits dim blue-green glow
    public static final byte VOIDSTONE  = 15; // M88: ancient bedrock layer at world bottom — cannot be broken
    public static final byte WATER      = 16; // M91: cave pool / underground river — liquid, passable
    public static final byte CRYSTAL    = 17; // M94: cave crystal — emissive cyan mineral in CRYSTAL zones

    // M107: tool items (inventory/crafting only, not placeable world blocks)
    public static final byte TOOL_WOOD_PICK    = 18;
    public static final byte TOOL_STONE_PICK   = 19;
    public static final byte TOOL_CRYSTAL_PICK = 20;
    public static final byte TOOL_TORCH        = 21;
    public static final byte CRAFTING_TABLE    = 22;
    public static final byte TORCH_STAND       = 23;
    public static final byte TORCH_WALL        = 24;
    public static final byte WOOD_PLANK        = 25; // M131: crafted from 1 log → 4 planks
    public static final byte TOOL_WOOD_AXE     = 26; // M131: wood axe — fast on trees
    public static final byte DOOR_CLOSED       = 27; // M139: wooden door — blocks movement, rendered thin
    public static final byte DOOR_OPEN         = 28; // M139: wooden door open — passable, rotated
    public static final byte COAL              = 29; // M149: coal ore vein — breakable, used in crafting
    public static final byte RAW_PORK         = 30; // M150: pig meat drop — eat to restore 20 HP
    public static final byte COMPASS       = 31; // M168: craftable relic-finder
    public static final byte ARMOR_BONE    = 32; // M168: bone armor 20% dmg reduce
    public static final byte ARMOR_STONE   = 33; // M168: stone armor 35% dmg reduce
    public static final byte ARMOR_CRYSTAL = 34; // M168: crystal armor 50% dmg reduce
    public static final byte CAVE_MUSHROOM   = 35; // M173: food from fungus breaks — heals 10 HP
    public static final byte COOKED_PORK    = 36; // M184: RAW_PORK + COAL recipe — heals 50 HP
    public static final byte RAW_CHICKEN   = 37; // M192: chicken drop — eat to restore 15 HP
    public static final byte COOKED_CHICKEN  = 38; // M192: RAW_CHICKEN + COAL recipe — heals 40 HP
    public static final byte LIMINAL_PORTAL  = 39; // M225: liminal zone portal floor tile — step on to enter

    public static boolean isSolid(byte id) {
        // Non-solid = skipped by chunk mesh builder AND has no face-culling effect on neighbours.
        // Doors are rendered via emitCube (like torches), so they must NOT be solid here.
        return id != AIR && !isTool(id) && !isFood(id) && !isArmor(id) && id != TORCH_STAND && id != TORCH_WALL
                && id != DOOR_CLOSED && id != DOOR_OPEN && id != COMPASS // M181: compass passive item
                && id != CAMPFIRE   // M190: campfire uses custom geometry in renderCampfires(), not chunk mesh
                && id != FUNGUS     // M221: custom mushroom geometry in renderPlacedTorches()
                && id != BLOODSTAIN // M221: custom floor-splat geometry in renderPlacedTorches()
                && id != LANTERN    // M221: custom cage geometry in renderPlacedTorches()
                && id != BONES          // M222: custom bone-pile geometry in renderPlacedTorches()
                && id != COBWEB         // M222: custom web-mass geometry in renderPlacedTorches()
                && id != LIMINAL_PORTAL; // M225: portal floor tile, non-solid and passable
    }

    /**
     * Movement blocker: true for blocks the player CANNOT walk through.
     * Separate from isSolid so doors can block movement without appearing in the chunk mesh.
     */
    public static boolean isMovementBlocker(byte id) {
        // LIMINAL_PORTAL excluded: player walks through portal tiles freely, proximity detection triggers teleport
        return (isSolid(id) && !isLiquid(id)) || id == DOOR_CLOSED;
    }

    /**
     * Targetable by the player's crosshair raycast — includes thin/non-full blocks like doors and torches.
     */
    public static boolean isTargetable(byte id) {
        if (isLiquid(id)) return false; // M171: water cannot be targeted, outlined, or mined
        return isSolid(id) || id == DOOR_CLOSED || id == DOOR_OPEN
                || id == TORCH_STAND || id == TORCH_WALL  // M149: torches have a breakable hitbox
                || id == CAMPFIRE                         // M190: campfire breakable even though non-solid
                || id == FUNGUS || id == BLOODSTAIN       // M221: custom 3D geometry, still mineable
                || id == LANTERN                          // M221: lantern cage, still pickable
                || id == BONES || id == COBWEB;           // M222: custom 3D geometry, still mineable
    }

    /** Liquid blocks are solid for rendering (so faces appear) but passable for collision. */
    public static boolean isLiquid(byte id) {
        return id == WATER;
    }

    public static boolean isTool(byte id) {
        return id == TOOL_WOOD_PICK || id == TOOL_STONE_PICK || id == TOOL_CRYSTAL_PICK
                || id == TOOL_TORCH || id == TOOL_WOOD_AXE;
        // COMPASS intentionally excluded — passive item, never equips or drains (M181)
    }

    /** Food items — consumed on right-click to restore HP. Not placeable as world blocks. */
    public static boolean isFood(byte id) {
        return id == RAW_PORK || id == CAVE_MUSHROOM || id == COOKED_PORK
            || id == RAW_CHICKEN || id == COOKED_CHICKEN; // M192
    }

    /** HP restored when eating a food item. */
    public static int foodHealAmount(byte id) {
        return switch (id) {
            case RAW_PORK      -> 20;
            case CAVE_MUSHROOM -> 10;
            case COOKED_PORK   -> 50;
            case RAW_CHICKEN   -> 15; // M192
            case COOKED_CHICKEN -> 40; // M192
            default -> 0;
        };
    }

    /** Voidstone cannot be harvested or placed by the player. */
    public static boolean isUnbreakable(byte id) {
        return id == JOURNAL || id == VOIDSTONE;
    }

    public static boolean isEmissive(byte id) {
        return id == LANTERN || id == RELIC || id == CAMPFIRE || id == FUNGUS || id == CRYSTAL
                || id == TORCH_STAND || id == TORCH_WALL || id == LIMINAL_PORTAL;
    }

    public static boolean isArmor(byte id) {
        return id == ARMOR_BONE || id == ARMOR_STONE || id == ARMOR_CRYSTAL;
    }

    public static float armorDamageReduce(byte id) {
        return switch (id) {
            case ARMOR_BONE    -> 0.20f;
            case ARMOR_STONE   -> 0.35f;
            case ARMOR_CRYSTAL -> 0.50f;
            default -> 0f;
        };
    }

    public static String nameOf(byte id) {
        return switch (id) {
            case GRASS      -> "GRASS";
            case DIRT       -> "DIRT";
            case STONE      -> "STONE";
            case LANTERN    -> "LANTERN";
            case WOOD       -> "WOOD";
            case LEAVES     -> "LEAVES";
            case MUD        -> "MUD";
            case RELIC      -> "RELIC";
            case JOURNAL    -> "JOURNAL";
            case CAMPFIRE   -> "CAMPFIRE";
            case BONES      -> "BONES";
            case BLOODSTAIN -> "BLOODSTAIN";
            case COBWEB     -> "COBWEB";
            case FUNGUS     -> "FUNGUS";
            case VOIDSTONE  -> "VOIDSTONE";
            case WATER      -> "WATER";
            case CRYSTAL    -> "CRYSTAL";
            case TOOL_WOOD_PICK    -> "WOOD PICK";
            case TOOL_STONE_PICK   -> "STONE PICK";
            case TOOL_CRYSTAL_PICK -> "CRYSTAL PICK";
            case TOOL_TORCH        -> "TORCH";
            case CRAFTING_TABLE    -> "WORKBENCH";
            case TORCH_STAND       -> "TORCH";
            case TORCH_WALL        -> "WALL_TORCH";
            case WOOD_PLANK        -> "WOOD PLANK";
            case TOOL_WOOD_AXE     -> "WOOD AXE";
            case DOOR_CLOSED, DOOR_OPEN -> "DOOR";
            case COAL       -> "COAL";
            case RAW_PORK       -> "RAW PORK";
            case COOKED_PORK    -> "COOKED PORK";
            case RAW_CHICKEN    -> "RAW CHICKEN";   // M192
            case COOKED_CHICKEN  -> "COOKED CHICKEN"; // M192
            case LIMINAL_PORTAL  -> "LIMINAL PORTAL"; // M225
            case CAVE_MUSHROOM  -> "CAVE MUSHROOM";
            case COMPASS       -> "COMPASS";
            case ARMOR_BONE    -> "BONE ARMOR";
            case ARMOR_STONE   -> "STONE ARMOR";
            case ARMOR_CRYSTAL -> "CRYSTAL ARMOR";
            default         -> "AIR";
        };
    }
}
