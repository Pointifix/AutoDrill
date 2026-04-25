package autodrill.filler;

import arc.Core;
import arc.math.geom.Rect;
import arc.struct.ObjectIntMap;
import arc.struct.ObjectMap;
import arc.struct.Seq;
import arc.util.Log;
import mindustry.Vars;
import mindustry.content.Blocks;
import mindustry.entities.units.BuildPlan;
import mindustry.game.Team;
import mindustry.type.Item;
import mindustry.world.Tile;
import mindustry.world.blocks.environment.Floor;
import mindustry.world.blocks.production.Drill;

import static arc.Core.bundle;

public class OptimizationDrill {
    public static void fill(Tile tile, Drill drill) {
        fill(tile, drill, true);
    }

    public static void fill(Tile tile, Drill drill, boolean waterExtractorsAndPowerNodes) {
        Team team = Vars.player.team();

        // --- FIX: use correct settings key per drill type ---
        // Settings keys are: "mechanical-drill-max-tiles", "pneumatic-drill-max-tiles",
        //                     "laser-drill-max-tiles", "airblast-drill-max-tiles"
        // The original code mapped mechanicalDrill -> "laser" which was wrong.
        String drillPrefix = getDrillSettingsPrefix(drill);
        int maxTiles = Core.settings.getInt(drillPrefix + "-drill-max-tiles");

        Seq<Tile> tiles = Util.getConnectedTiles(tile, maxTiles);
        Util.expandArea(tiles, drill.size / 2);

        int minOresPerDrill = Core.settings.getInt(drillPrefix + "-drill-min-ores");

        Floor floor = tile.overlay() != Blocks.air ? tile.overlay() : tile.floor();

        ObjectMap<Tile, ObjectIntMap.Entry<Item>> tilesItemAndCount = new ObjectMap<>();
        for (Tile t : tiles) {
            tilesItemAndCount.put(t, Util.countOre(t, drill));
        }

        // Pre-filter: only keep tiles that mine the correct resource,
        // meet minimum ore count, AND whose full footprint is placeable.
        tiles.retainAll(t -> {
            ObjectIntMap.Entry<Item> itemAndCount = tilesItemAndCount.get(t);
            if (itemAndCount == null || itemAndCount.key != floor.itemDrop || itemAndCount.value < minOresPerDrill) {
                return false;
            }
            // Requirement 4: full footprint must be buildable
            if (!Util.canPlaceBlock(drill, team, t.x, t.y, 0)) {
                return false;
            }
            return true;
        }).sort(t -> {
            ObjectIntMap.Entry<Item> itemAndCount = tilesItemAndCount.get(t);
            return itemAndCount == null ? Integer.MIN_VALUE : -itemAndCount.value;
        });

        // --- Dry-run phase: build plans into a temporary list ---
        Seq<BuildPlan> allPlans = new Seq<>();
        Seq<Tile> selection = new Seq<>();

        int maxTries = Core.settings.getInt(bundle.get("auto-drill.settings.optimization-quality")) * 1000;

        recursiveMaxSearch(tiles, drill, team, tilesItemAndCount, selection, new Seq<>(), 0, new Seq<>(), maxTries, 0);

        // Build drill plans, validating against each other
        for (Tile t : selection) {
            BuildPlan plan = new BuildPlan(t.x, t.y, 0, drill);
            if (Util.canPlaceWithoutPlanCollision(plan, team, allPlans)) {
                allPlans.add(plan);
            }
        }

        // Optional water extractors and power nodes
        if (waterExtractorsAndPowerNodes
            && Core.settings.getBool(bundle.get("auto-drill.settings.place-water-extractor-and-power-nodes"))) {
            placeWaterExtractorsAndPowerNodes(selection, drill, team, allPlans);
        }

        // --- Commit phase: only now submit to the player's build queue ---
        Util.commitPlans(allPlans);

        if (Util.DEBUG) {
            Log.info("[AutoDrill] OptimizationDrill: committed " + allPlans.size
                + " plans for " + drill.name);
        }
    }

    /**
     * Recursive search for the best non-overlapping set of drill placements.
     *
     * Changed from original: uses Util.canPlaceBlock instead of raw
     * Build.validPlace, and tracks collisions via Rect overlap. This keeps
     * the optimizer's rectangle logic but ensures every candidate passed
     * the unified placement check during the pre-filter step.
     */
    private static int recursiveMaxSearch(
            Seq<Tile> tiles, Drill drill, Team team,
            ObjectMap<Tile, ObjectIntMap.Entry<Item>> tilesItemAndCount,
            Seq<Tile> selection, Seq<Rect> rects, int sum,
            Seq<Integer> triesPerLevel, final int maxTries, final int level) {

        int max = sum;
        Seq<Tile> maxSelection = selection.copy();

        if (triesPerLevel.size < level + 1) {
            triesPerLevel.setSize(level + 1);
            triesPerLevel.set(level, 0);
        }

        for (Tile tile : tiles) {
            Rect rect = Util.getBlockRect(tile, drill);

            // Rectangle overlap against already-selected drills in this branch.
            // Note: canPlaceBlock was already checked in the pre-filter, so we
            // only need the overlap test here for branch-local collision.
            if (rects.isEmpty() || rects.find(r -> r.overlaps(rect)) == null) {
                int newSum = sum + tilesItemAndCount.get(tile).value;

                Seq<Tile> newSelection = selection.copy().add(tile);
                Seq<Rect> newRects = rects.copy().add(rect);

                int newMax = recursiveMaxSearch(tiles, drill, team, tilesItemAndCount,
                    newSelection, newRects, newSum, triesPerLevel, maxTries, level + 1);

                if (newMax > max) {
                    max = newMax;
                    maxSelection = newSelection.copy();
                }

                triesPerLevel.set(level, triesPerLevel.get(level) + 1);
                if (triesPerLevel.get(level) >= maxTries / Math.pow(2, level + 1)) break;
            }
        }

        selection.clear();
        selection.addAll(maxSelection);

        return max;
    }

    /**
     * Place water extractors and power nodes adjacent to selected drills.
     *
     * Changed from original:
     * - Uses Util.canPlaceWithoutPlanCollision instead of BuildPlan.placeable + manual rect check.
     * - Validates against the shared allPlans list so support blocks don't
     *   collide with drills or with each other.
     * - Only adds plans that actually pass validation (requirement 5).
     */
    private static void placeWaterExtractorsAndPowerNodes(
            Seq<Tile> selection, Drill drill, Team team, Seq<BuildPlan> allPlans) {

        for (Tile t : selection) {
            Seq<Tile> nearby = Util.getNearbyTiles(t.x, t.y, drill.size, Blocks.waterExtractor.size);

            for (Tile n : nearby) {
                BuildPlan plan = new BuildPlan(n.x, n.y, 0, Blocks.waterExtractor);
                if (Util.canPlaceWithoutPlanCollision(plan, team, allPlans)) {
                    allPlans.add(plan);
                    break; // one water extractor per drill
                }
            }
        }

        for (Tile t : selection) {
            Seq<Tile> nearby = Util.getNearbyTiles(t.x, t.y, drill.size, Blocks.powerNode.size);

            for (Tile n : nearby) {
                BuildPlan plan = new BuildPlan(n.x, n.y, 0, Blocks.powerNode);
                if (Util.canPlaceWithoutPlanCollision(plan, team, allPlans)) {
                    allPlans.add(plan);
                    break; // one power node per drill
                }
            }
        }
    }

    /**
     * Return the correct settings key prefix for a drill.
     *
     * FIX: The original code used:
     *   (drill == Blocks.mechanicalDrill ? "laser" : "airblast") + "-drill-max-tiles"
     * which mapped mechanicalDrill to "laser-drill-max-tiles" — clearly wrong.
     * The settings UI registers keys like "mechanical-drill-max-tiles".
     */
    private static String getDrillSettingsPrefix(Drill drill) {
        if (drill == Blocks.mechanicalDrill) return "mechanical";
        if (drill == Blocks.pneumaticDrill)  return "pneumatic";
        if (drill == Blocks.laserDrill)      return "laser";
        if (drill == Blocks.blastDrill)      return "airblast";
        // Impact and eruption drills share the airblast settings in the original
        // mod since they don't have dedicated settings entries.
        if (drill == Blocks.impactDrill)     return "airblast";
        if (drill == Blocks.eruptionDrill)   return "airblast";
        return "airblast"; // fallback
    }
}
