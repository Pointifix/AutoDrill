package autodrill.filler;

import arc.Core;
import arc.math.geom.Point2;
import arc.struct.ObjectIntMap;
import arc.struct.Seq;
import mindustry.Vars;
import mindustry.content.Blocks;
import mindustry.entities.units.BuildPlan;
import mindustry.type.Item;
import mindustry.world.Tile;
import mindustry.world.blocks.production.Drill;

import java.util.InputMismatchException;

import static arc.Core.bundle;

public class BridgeDrill {
    public static void fill(Tile tile, Drill drill, Direction direction) {
        if (drill.size != 2) throw new InputMismatchException("Drill must have a size of 2");

        int maxTiles = Core.settings.getInt((drill == Blocks.mechanicalDrill ? "mechanical" : "pneumatic") + "-drill-max-tiles");

        Seq<Tile> tiles = Util.getConnectedTiles(tile, maxTiles);
        Util.expandArea(tiles, drill.size / 2);
        placeDrillsAndBridges(tile, tiles, drill, direction);
    }

    private static void placeDrillsAndBridges(Tile source, Seq<Tile> tiles, Drill drill, Direction direction) {
        Point2 directionConfig = new Point2(direction.p.x * 3, direction.p.y * 3);

        Seq<Tile> drillTiles = tiles.select(BridgeDrill::isDrillTile);
        Seq<Tile> bridgeTiles = tiles.select(BridgeDrill::isBridgeTile);

        int minOresPerDrill = Core.settings.getInt((drill == Blocks.blastDrill ? "airblast" : (drill == Blocks.laserDrill ? "laser" : (drill == Blocks.pneumaticDrill ? "pneumatic" : "mechanical"))) + "-drill-min-ores");

        drillTiles.retainAll(t -> {
            ObjectIntMap.Entry<Item> itemAndCount = Util.countOre(t, drill);

            if (itemAndCount == null || itemAndCount.key != source.drop() || itemAndCount.value < minOresPerDrill) {
                return false;
            }

            Seq<Tile> neighbors = Util.getNearbyTiles(t.x, t.y, drill);
            neighbors.retainAll(BridgeDrill::isBridgeTile);

            for (Tile neighbor : neighbors) {
                if (bridgeTiles.contains(neighbor)) return true;
            }

            neighbors.retainAll(n -> {
                BuildPlan buildPlan = new BuildPlan(n.x, n.y, 0, Blocks.itemBridge);
                return buildPlan.placeable(Vars.player.team());
            });

            if (!neighbors.isEmpty()) {
                bridgeTiles.add(neighbors);
                return true;
            }

            return false;
        });

        Tile outerMost = bridgeTiles.max((t) -> direction.p.x == 0 ? t.y * direction.p.y : t.x * direction.p.x);
        if (outerMost == null) return;

        Tile outlet = outerMost.nearby(directionConfig);
        bridgeTiles.add(outlet);

        bridgeTiles.sort(t -> t.dst2(outlet.worldx(), outlet.worldy()));

        for (Tile drillTile : drillTiles) {
            BuildPlan buildPlan = new BuildPlan(drillTile.x, drillTile.y, 0, drill);
            Vars.player.unit().addBuild(buildPlan);
        }

        for (Tile bridgeTile : bridgeTiles) {
            Tile neighbor = bridgeTiles.find(t -> Math.abs(t.x - bridgeTile.x) + Math.abs(t.y - bridgeTile.y) == 3);

            Point2 config = new Point2();
            if (bridgeTile != outlet && neighbor != null) {
                config = new Point2(neighbor.x - bridgeTile.x, neighbor.y - bridgeTile.y);
            }

            BuildPlan buildPlan = new BuildPlan(bridgeTile.x, bridgeTile.y, 0, Blocks.itemBridge, config);
            Vars.player.unit().addBuild(buildPlan);
        }
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
