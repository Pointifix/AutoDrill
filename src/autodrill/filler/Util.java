package autodrill.filler;

import arc.math.geom.Point2;
import arc.math.geom.Rect;
import arc.struct.ObjectIntMap;
import arc.struct.Queue;
import arc.struct.Seq;
import arc.util.Log;
import mindustry.Vars;
import mindustry.content.Blocks;
import mindustry.entities.units.BuildPlan;
import mindustry.game.Team;
import mindustry.type.Item;
import mindustry.world.Block;
import mindustry.world.Build;
import mindustry.world.Edges;
import mindustry.world.Tile;
import mindustry.world.blocks.production.Drill;

import static mindustry.Vars.world;

/**
 * Utility class for AutoDrill placement validation and tile helpers.
 *
 * All placement checks funnel through a small set of methods so that
 * every caller (OptimizationDrill, BridgeDrill, WallDrill) uses the
 * same rules. The strategy is:
 *
 * 1. {@link #isWithinWorld} checks the full footprint is inside map bounds.
 * 2. {@link #canPlaceBlock} delegates to Mindustry's Build.validPlace
 *    which already checks floor, solidity, team, environment, and existing
 *    buildings, then adds our own world-bounds guard for multi-tile blocks.
 * 3. {@link #footprintOverlapsPlanned} tests rectangle overlap against
 *    previously accepted plans from the same AutoDrill run.
 * 4. {@link #canPlaceWithoutPlanCollision} is the single top-level gate
 *    every caller should use before accepting a plan.
 *
 * Debug logging is controlled by the static DEBUG flag.
 */
public class Util {

    /** Set to true to log rejection reasons to the Mindustry console. */
    static boolean DEBUG = false;

    // === placement validation ================================================

    /**
     * Check whether every tile in the block's footprint falls inside the
     * world. Build.validPlace already rejects some out-of-bounds cases,
     * but it does not always account for the full footprint of multi-tile
     * blocks near the map edge.
     */
    public static boolean isWithinWorld(Block block, int x, int y) {
        int halfLow  = (block.size - 1) / 2;
        int halfHigh = block.size / 2;
        int minX = x - halfLow;
        int minY = y - halfLow;
        int maxX = x + halfHigh;
        int maxY = y + halfHigh;
        return minX >= 0 && minY >= 0
            && maxX < Vars.world.width()
            && maxY < Vars.world.height();
    }

    /**
     * Core single-block placement check. Combines:
     * - Full footprint world bounds (our own check).
     * - Mindustry's Build.validPlace which covers floor type,
     *   solid terrain, existing buildings, team ownership, and
     *   environment buildability.
     */
    public static boolean canPlaceBlock(Block block, Team team, int x, int y, int rotation) {
        if (!isWithinWorld(block, x, y)) {
            debugLog("REJECT", block.name, x, y, "out of world bounds");
            return false;
        }
        if (!Build.validPlace(block, team, x, y, rotation)) {
            debugLog("REJECT", block.name, x, y, "Mindustry Build.validPlace failed");
            return false;
        }
        return true;
    }

    /**
     * Convenience wrapper that extracts fields from a BuildPlan.
     */
    public static boolean canPlacePlan(BuildPlan plan, Team team) {
        return canPlaceBlock(plan.block, team, plan.x, plan.y, plan.rotation);
    }

    /**
     * Check whether a block's footprint rectangle overlaps any plan
     * already in the planned list.
     */
    public static boolean footprintOverlapsPlanned(Block block, int x, int y, Seq<BuildPlan> planned) {
        Rect candidate = getBlockRect(x, y, block);
        for (int i = 0; i < planned.size; i++) {
            BuildPlan p = planned.get(i);
            Rect existing = getBlockRect(p.x, p.y, p.block);
            if (candidate.overlaps(existing)) {
                debugLog("OVERLAP", block.name, x, y,
                    "collides with planned " + p.block.name + " at " + p.x + "," + p.y);
                return true;
            }
        }
        return false;
    }

    /**
     * The single top-level gate. Returns true only if:
     * 1. The plan passes Mindustry's own placement rules.
     * 2. The plan's footprint does not overlap any already-accepted plan
     *    from this AutoDrill run.
     *
     * Every caller should use this before adding a plan to the accepted list.
     */
    public static boolean canPlaceWithoutPlanCollision(BuildPlan plan, Team team, Seq<BuildPlan> planned) {
        if (!canPlacePlan(plan, team)) {
            return false;
        }
        if (footprintOverlapsPlanned(plan.block, plan.x, plan.y, planned)) {
            return false;
        }
        return true;
    }

    // === dry-run commit helper ================================================

    /**
     * Submit a validated list of plans to the player's build queue.
     * Only called after all plans have passed validation.
     */
    public static void commitPlans(Seq<BuildPlan> plans) {
        for (int i = 0; i < plans.size; i++) {
            Vars.player.unit().addBuild(plans.get(i));
        }
        if (DEBUG) {
            Log.info("[AutoDrill] Committed " + plans.size + " build plans.");
        }
    }

    // === rect helpers ========================================================

    /**
     * Build a Rect for a block placed at tile (x,y).
     * The rect covers the full footprint in tile coordinates.
     */
    public static Rect getBlockRect(int x, int y, Block block) {
        int offset = (block.size - 1) / 2;
        return new Rect(x - offset, y - offset, block.size, block.size);
    }

    /** Overload that takes a Tile for backwards compatibility. */
    protected static Rect getBlockRect(Tile tile, Block block) {
        return getBlockRect(tile.x, tile.y, block);
    }

    // === tile collection helpers (unchanged logic) ============================

    protected static Seq<Tile> getNearbyTiles(int x, int y, Block block) {
        return getNearbyTiles(x, y, block.size);
    }

    protected static Seq<Tile> getNearbyTiles(int x, int y, int size) {
        Seq<Tile> nearbyTiles = new Seq<>();

        Point2[] nearby = Edges.getEdges(size);
        for (Point2 point2 : nearby) {
            Tile t = world.tile(x + point2.x, y + point2.y);
            if (t != null) nearbyTiles.add(t);
        }

        return nearbyTiles;
    }

    protected static Seq<Tile> getNearbyTiles(int x, int y, int size1, int size2) {
        int offset1 = (size1 % 2 == 1 && size2 % 2 == 0) ? 1 : 0;
        int offset2 = ((size2 * 2 - 1) / 2);

        return getNearbyTiles(x - offset1, y - offset1, size1 + offset2);
    }

    protected static ObjectIntMap.Entry<Item> countOre(Tile tile, Drill drill) {
        ObjectIntMap<Item> oreCount = new ObjectIntMap<>();
        Seq<Item> itemArray = new Seq<>();

        for (Tile other : tile.getLinkedTilesAs(drill, new Seq<>())) {
            if (drill.canMine(other)) {
                oreCount.increment(drill.getDrop(other), 0, 1);
            }
        }

        for (Item i : oreCount.keys()) {
            itemArray.add(i);
        }

        itemArray.sort((item1, item2) -> {
            int type = Boolean.compare(!item1.lowPriority, !item2.lowPriority);
            if (type != 0) return type;
            int amounts = Integer.compare(oreCount.get(item1, 0), oreCount.get(item2, 0));
            if (amounts != 0) return amounts;
            return Integer.compare(item1.id, item2.id);
        });

        if (itemArray.size == 0) {
            return null;
        }

        Item item = itemArray.peek();
        int count = oreCount.get(itemArray.peek(), 0);

        ObjectIntMap.Entry<Item> itemAndCount = new ObjectIntMap.Entry<>();
        itemAndCount.key = item;
        itemAndCount.value = count;

        return itemAndCount;
    }

    protected static void expandArea(Seq<Tile> tiles, int radius) {
        Seq<Tile> expandedTiles = new Seq<>();

        for (Tile tile : tiles) {
            for (int dx = -radius; dx < radius; dx++) {
                for (int dy = -radius; dy < radius; dy++) {
                    if (dx == 0 && dy == 0) continue;

                    Tile nearby = tile.nearby(dx, dy);
                    if (nearby == null) continue;

                    if (!tiles.contains(nearby) && !expandedTiles.contains(nearby)) {
                        expandedTiles.add(nearby);
                    }
                }
            }
        }

        tiles.add(expandedTiles);
    }

    protected static Seq<Tile> getConnectedTiles(Tile tile, int maxTiles) {
        Queue<Tile> queue = new Queue<>();
        Seq<Tile> tiles = new Seq<>();
        Seq<Tile> visited = new Seq<>();

        queue.addLast(tile);

        Item sourceItem = tile.drop();

        while (!queue.isEmpty() && tiles.size < maxTiles) {
            Tile currentTile = queue.removeFirst();

            if (!Build.validPlace(
                    Blocks.copperWall.environmentBuildable() ? Blocks.copperWall : Blocks.berylliumWall,
                    Vars.player.team(), currentTile.x, currentTile.y, 0)
                || visited.contains(currentTile))
                continue;

            if (currentTile.drop() == sourceItem) {
                for (int x = -1; x <= 1; x++) {
                    for (int y = -1; y <= 1; y++) {
                        if (!(x == 0 && y == 0)) {
                            Tile neighbor = currentTile.nearby(x, y);
                            if (neighbor == null) continue;

                            if (!visited.contains(neighbor)) {
                                queue.addLast(neighbor);
                            }
                        }
                    }
                }

                tiles.add(currentTile);
            }

            visited.add(currentTile);
        }

        tiles.sort(Tile::pos);

        return tiles;
    }

    protected static Point2 tileToPoint2(Tile tile) {
        return new Point2(tile.x, tile.y);
    }

    // === debug helper ========================================================

    private static void debugLog(String tag, String blockName, int x, int y, String reason) {
        if (DEBUG) {
            Log.info("[AutoDrill] " + tag + ": " + blockName + " @ (" + x + "," + y + ") - " + reason);
        }
    }
}
