package autodrill.filler;

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

public class BridgeDrill {
    public static void fill(Tile tile, Drill drill, Direction direction) {
        if (drill.size != 2) throw new InputMismatchException("Drill must have a size of 2");

        Seq<Tile> tiles = Util.getConnectedTiles(tile);
        Util.expandArea(tiles, drill.size / 2);
        placeDrillsAndBridges(tile, tiles, drill, direction);
    }

    private static void placeDrillsAndBridges(Tile source, Seq<Tile> tiles, Drill drill, Direction direction) {
        Point2 directionConfig = getDirectionConfig(direction);

        Seq<Tile> drillTiles = tiles.copy().filter(BridgeDrill::isDrillTile);
        Seq<Tile> bridgeTiles = tiles.copy().filter(BridgeDrill::isBridgeTile);

        drillTiles.filter(t -> {
            ObjectIntMap.Entry<Item> itemAndCount = Util.countOre(t, drill);

            if (itemAndCount == null || itemAndCount.key != source.drop()) {
                return false;
            }

            Seq<Tile> neighbors = Util.getNearbyTiles(t.x, t.y, drill);
            neighbors.filter(BridgeDrill::isBridgeTile);

            for (Tile neighbor : neighbors) {
                if (bridgeTiles.contains(neighbor)) return true;
            }

            neighbors.filter(n -> {
                BuildPlan buildPlan = new BuildPlan(n.x, n.y, 0, Blocks.itemBridge);
                return buildPlan.placeable(Vars.player.team());
            });

            if (!neighbors.isEmpty()) {
                bridgeTiles.add(neighbors);
                return true;
            }

            return false;
        });

        Point2 directionVector = getDirectionVector(direction);
        Tile outerMost = bridgeTiles.max((t) -> directionVector.x == 0 ? t.y * directionVector.y : t.x * directionVector.x);
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

    private static Point2 getDirectionConfig(Direction direction) {
        return switch (direction) {
            case RIGHT -> new Point2(3, 0);
            case DOWN -> new Point2(0, -3);
            case LEFT -> new Point2(-3, 0);
            case UP -> new Point2(0, 3);
        };
    }

    private static Point2 getDirectionVector(Direction direction) {
        return switch (direction) {
            case RIGHT -> new Point2(1, 0);
            case DOWN -> new Point2(0, -1);
            case LEFT -> new Point2(-1, 0);
            case UP -> new Point2(0, 1);
        };
    }
}
