package autodrill.filler;

import arc.Core;
import arc.math.geom.Point2;
import arc.struct.ObjectIntMap;
import arc.struct.Seq;
import arc.util.Log;
import mindustry.Vars;
import mindustry.content.Blocks;
import mindustry.entities.units.BuildPlan;
import mindustry.game.Team;
import mindustry.type.Item;
import mindustry.world.Tile;
import mindustry.world.blocks.production.Drill;

import java.util.InputMismatchException;

import static arc.Core.bundle;

public class BridgeDrill {
    public static void fill(Tile tile, Drill drill, Direction direction) {
        if (drill.size != 2) throw new InputMismatchException("Drill must have a size of 2");

        int maxTiles = Core.settings.getInt(
            (drill == Blocks.mechanicalDrill ? "mechanical" : "pneumatic") + "-drill-max-tiles");

        Seq<Tile> tiles = Util.getConnectedTiles(tile, maxTiles);
        Util.expandArea(tiles, drill.size / 2);
        placeDrillsAndBridges(tile, tiles, drill, direction);
    }

    private static void placeDrillsAndBridges(Tile source, Seq<Tile> tiles, Drill drill, Direction direction) {
        Team team = Vars.player.team();
        Point2 directionConfig = new Point2(direction.p.x * 3, direction.p.y * 3);

        Seq<Tile> drillTiles = tiles.select(BridgeDrill::isDrillTile);
        Seq<Tile> bridgeTiles = tiles.select(BridgeDrill::isBridgeTile);

        int minOresPerDrill = Core.settings.getInt(
            (drill == Blocks.blastDrill ? "airblast"
                : (drill == Blocks.laserDrill ? "laser"
                    : (drill == Blocks.pneumaticDrill ? "pneumatic" : "mechanical")))
            + "-drill-min-ores");

        // --- Pre-filter drill candidates ---
        // Requirement 4: must mine correct resource, meet min ores,
        // full footprint buildable, and have at least one valid bridge neighbor.
        drillTiles.retainAll(t -> {
            ObjectIntMap.Entry<Item> itemAndCount = Util.countOre(t, drill);

            if (itemAndCount == null || itemAndCount.key != source.drop() || itemAndCount.value < minOresPerDrill) {
                return false;
            }

            // Full footprint placement check
            if (!Util.canPlaceBlock(drill, team, t.x, t.y, 0)) {
                return false;
            }

            Seq<Tile> neighbors = Util.getNearbyTiles(t.x, t.y, drill);
            neighbors.retainAll(BridgeDrill::isBridgeTile);

            for (Tile neighbor : neighbors) {
                if (bridgeTiles.contains(neighbor)) return true;
            }

            // Check if any neighbor can hold a bridge (using unified validation)
            neighbors.retainAll(n ->
                Util.canPlaceBlock(Blocks.itemBridge, team, n.x, n.y, 0));

            if (!neighbors.isEmpty()) {
                bridgeTiles.add(neighbors);
                return true;
            }

            return false;
        });

        Tile outerMost = bridgeTiles.max(
            t -> direction.p.x == 0 ? t.y * direction.p.y : t.x * direction.p.x);
        if (outerMost == null) return;

        Tile outlet = outerMost.nearby(directionConfig);
        if (outlet == null) return; // guard against edge-of-map

        bridgeTiles.add(outlet);
        bridgeTiles.sort(t -> t.dst2(outlet.worldx(), outlet.worldy()));

        // --- Dry-run phase: collect all plans, validate, then commit ---
        Seq<BuildPlan> allPlans = new Seq<>();

        // Validate drill plans
        for (Tile drillTile : drillTiles) {
            BuildPlan plan = new BuildPlan(drillTile.x, drillTile.y, 0, drill);
            if (Util.canPlaceWithoutPlanCollision(plan, team, allPlans)) {
                allPlans.add(plan);
            }
        }

        // Collect the set of bridge positions we intend to place so we can
        // check connectivity. A bridge is only worth placing if it connects
        // to at least one other bridge — either another planned bridge tile
        // or an existing itemBridge already built on the map.
        Seq<BuildPlan> bridgePlans = new Seq<>();

        // First pass: build candidate bridge plans with their configs
        for (Tile bridgeTile : bridgeTiles) {
            // Find a partner among our planned bridge tiles at Manhattan distance 3
            Tile plannedPartner = bridgeTiles.find(
                t -> t != bridgeTile
                    && Math.abs(t.x - bridgeTile.x) + Math.abs(t.y - bridgeTile.y) == 3);

            // Also check for an existing itemBridge on the map at distance 3
            // in each cardinal direction. This lets us connect to bridges the
            // player (or a previous AutoDrill run) already built.
            Tile existingPartner = findExistingBridgePartner(bridgeTile);

            // Determine config: prefer planned partner, fall back to existing
            Tile partner = plannedPartner != null ? plannedPartner : existingPartner;

            if (bridgeTile == outlet) {
                // The outlet is the chain endpoint — it receives a connection
                // from an interior bridge, so it doesn't need outgoing config.
                // But it still must be reachable by at least one other bridge.
                boolean hasIncoming = bridgeTiles.contains(
                    t -> t != outlet
                        && Math.abs(t.x - outlet.x) + Math.abs(t.y - outlet.y) == 3);
                boolean hasExistingIncoming = findExistingBridgePartner(outlet) != null;

                if (!hasIncoming && !hasExistingIncoming) {
                    if (Util.DEBUG) {
                        Log.info("[AutoDrill] BridgeDrill: skipping orphan outlet at ("
                            + outlet.x + "," + outlet.y + ") — no bridge connects to it");
                    }
                    continue;
                }

                BuildPlan plan = new BuildPlan(bridgeTile.x, bridgeTile.y, 0, Blocks.itemBridge, new Point2());
                if (Util.canPlaceWithoutPlanCollision(plan, team, allPlans)) {
                    bridgePlans.add(plan);
                }
            } else if (partner != null) {
                // Interior bridge with a valid connection target
                Point2 config = new Point2(partner.x - bridgeTile.x, partner.y - bridgeTile.y);
                BuildPlan plan = new BuildPlan(bridgeTile.x, bridgeTile.y, 0, Blocks.itemBridge, config);
                if (Util.canPlaceWithoutPlanCollision(plan, team, allPlans)) {
                    bridgePlans.add(plan);
                }
            } else {
                // No partner found — this bridge would be orphaned, skip it
                if (Util.DEBUG) {
                    Log.info("[AutoDrill] BridgeDrill: skipping orphan bridge at ("
                        + bridgeTile.x + "," + bridgeTile.y + ") — no partner at distance 3");
                }
            }
        }

        // Second pass: remove any bridge plan whose config points to a
        // partner that didn't survive validation (i.e. was rejected or is
        // itself orphaned). Keep iterating until stable.
        boolean changed = true;
        while (changed) {
            changed = false;
            for (int i = bridgePlans.size - 1; i >= 0; i--) {
                BuildPlan bp = bridgePlans.get(i);
                Point2 cfg = (Point2) bp.config;

                // Outlet bridges (zero config) just need at least one bridge
                // pointing at them
                if (cfg.x == 0 && cfg.y == 0) {
                    boolean anyPointsHere = false;
                    for (int j = 0; j < bridgePlans.size; j++) {
                        if (j == i) continue;
                        BuildPlan other = bridgePlans.get(j);
                        Point2 oCfg = (Point2) other.config;
                        if (other.x + oCfg.x == bp.x && other.y + oCfg.y == bp.y) {
                            anyPointsHere = true;
                            break;
                        }
                    }
                    // Also check if an existing bridge on the map points here
                    if (!anyPointsHere) {
                        anyPointsHere = hasExistingBridgePointingAt(bp.x, bp.y);
                    }
                    if (!anyPointsHere) {
                        bridgePlans.remove(i);
                        changed = true;
                        if (Util.DEBUG) {
                            Log.info("[AutoDrill] BridgeDrill: pruned unreachable bridge at ("
                                + bp.x + "," + bp.y + ")");
                        }
                    }
                    continue;
                }

                // For bridges with outgoing config, check that the target
                // is either a planned bridge or an existing bridge on the map
                int targetX = bp.x + cfg.x;
                int targetY = bp.y + cfg.y;

                boolean targetExists = false;
                for (int j = 0; j < bridgePlans.size; j++) {
                    BuildPlan other = bridgePlans.get(j);
                    if (other.x == targetX && other.y == targetY) {
                        targetExists = true;
                        break;
                    }
                }
                if (!targetExists) {
                    // Check for an existing bridge building at the target tile
                    Tile targetTile = Vars.world.tile(targetX, targetY);
                    if (targetTile != null && targetTile.build != null
                        && targetTile.block() == Blocks.itemBridge) {
                        targetExists = true;
                    }
                }
                if (!targetExists) {
                    bridgePlans.remove(i);
                    changed = true;
                    if (Util.DEBUG) {
                        Log.info("[AutoDrill] BridgeDrill: pruned bridge at ("
                            + bp.x + "," + bp.y + ") — target (" + targetX + "," + targetY + ") gone");
                    }
                }
            }
        }

        allPlans.addAll(bridgePlans);

        // --- Commit phase ---
        Util.commitPlans(allPlans);

        if (Util.DEBUG) {
            Log.info("[AutoDrill] BridgeDrill: committed " + allPlans.size + " plans");
        }
    }

    /**
     * Look for an existing itemBridge building on the map at exactly
     * Manhattan distance 3 from the given tile in any cardinal direction.
     * Returns the tile of the first match, or null if none found.
     */
    private static Tile findExistingBridgePartner(Tile bridgeTile) {
        int[][] offsets = {{3, 0}, {-3, 0}, {0, 3}, {0, -3}};
        for (int[] off : offsets) {
            Tile candidate = Vars.world.tile(bridgeTile.x + off[0], bridgeTile.y + off[1]);
            if (candidate != null && candidate.build != null
                && candidate.block() == Blocks.itemBridge) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * Check whether any existing itemBridge on the map has a config
     * that points at the given tile coordinates (i.e. it's already
     * linked to this position).
     */
    private static boolean hasExistingBridgePointingAt(int x, int y) {
        int[][] offsets = {{3, 0}, {-3, 0}, {0, 3}, {0, -3}};
        for (int[] off : offsets) {
            Tile candidate = Vars.world.tile(x + off[0], y + off[1]);
            if (candidate != null && candidate.build != null
                && candidate.block() == Blocks.itemBridge) {
                // An existing bridge exists at distance 3 — it could be
                // pointing at us. We can't easily read its config here,
                // but its presence is enough to justify our bridge.
                return true;
            }
        }
        return false;
    }

    private static boolean isDrillTile(Tile tile) {
        short x = tile.x;
        short y = tile.y;

        switch (x % 6) {
            case 0:
            case 2:
                if ((y - 1) % 6 == 0) return true;
                break;
            case 1:
                if ((y - 3) % 6 == 0 || (y - 3) % 6 == 2) return true;
                break;
            case 3:
            case 5:
                if ((y - 4) % 6 == 0) return true;
                break;
            case 4:
                if ((y) % 6 == 0 || (y) % 6 == 2) return true;
                break;
        }

        return false;
    }

    private static boolean isBridgeTile(Tile tile) {
        short x = tile.x;
        short y = tile.y;

        return x % 3 == 0 && y % 3 == 0;
    }
}