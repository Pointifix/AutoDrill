package autodrill.filler;

import arc.Core;
import arc.math.geom.Rect;
import arc.struct.ObjectIntMap;
import arc.struct.ObjectMap;
import arc.struct.Seq;
import mindustry.Vars;
import mindustry.content.Blocks;
import mindustry.entities.units.BuildPlan;
import mindustry.gen.Call;
import mindustry.type.Item;
import mindustry.world.Build;
import mindustry.world.Tile;
import mindustry.world.blocks.environment.Floor;
import mindustry.world.blocks.production.Drill;

import static arc.Core.bundle;

public class OptimizationDrill {
    public static void fill(Tile tile, Drill drill) {
        fill(tile, drill, true);
    }

    public static void fill(Tile tile, Drill drill, boolean waterExtractorsAndPowerNodes) {
        int maxTiles = Core.settings.getInt((drill == Blocks.mechanicalDrill ? "laser" : "airblast") + "-drill-max-tiles");

        Seq<Tile> tiles = Util.getConnectedTiles(tile, maxTiles);
        Util.expandArea(tiles, drill.size / 2);

        int minOresPerDrill = Core.settings.getInt((drill == Blocks.blastDrill ? "airblast" : (drill == Blocks.laserDrill ? "laser" : (drill == Blocks.pneumaticDrill ? "pneumatic" : "mechanical"))) + "-drill-min-ores");

        Floor floor = tile.overlay() != Blocks.air ? tile.overlay() : tile.floor();

        ObjectMap<Tile, ObjectIntMap.Entry<Item>> tilesItemAndCount = new ObjectMap<>();
        for (Tile t : tiles) {
            tilesItemAndCount.put(t, Util.countOre(t, drill));
        }

        tiles.retainAll(t -> {
            ObjectIntMap.Entry<Item> itemAndCount = tilesItemAndCount.get(t);
            return itemAndCount != null && itemAndCount.key == floor.itemDrop && itemAndCount.value >= minOresPerDrill;
        }).sort(t -> {
            ObjectIntMap.Entry<Item> itemAndCount = tilesItemAndCount.get(t);
            return itemAndCount == null ? Integer.MIN_VALUE : -itemAndCount.value;
        });

        Seq<Tile> selection = new Seq<>();

        int maxTries = Core.settings.getInt(bundle.get("auto-drill.settings.optimization-quality")) * 1000;

        recursiveMaxSearch(tiles, drill, tilesItemAndCount, selection, new Seq<>(), 0, new Seq<>(), maxTries, 0);

        if (waterExtractorsAndPowerNodes && Core.settings.getBool(bundle.get("auto-drill.settings.place-water-extractor-and-power-nodes")))
            placeWaterExtractorsAndPowerNodes(selection, drill);

        for (Tile t : selection) {
            BuildPlan buildPlan = new BuildPlan(t.x, t.y, 0, drill);
            Vars.player.unit().addBuild(buildPlan);
        }
    }

    private static int recursiveMaxSearch(Seq<Tile> tiles, Drill drill, ObjectMap<Tile, ObjectIntMap.Entry<Item>> tilesItemAndCount, Seq<Tile> selection, Seq<Rect> rects, int sum, Seq<Integer> triesPerLevel, final int maxTries, final int level) {
        int max = sum;
        Seq<Tile> maxSelection = selection.copy();

        if (triesPerLevel.size < level + 1) {
            triesPerLevel.setSize(level + 1);
            triesPerLevel.set(level, 0);
        }

        for (Tile tile : tiles) {
            Rect rect = Util.getBlockRect(tile, drill);

            if ((rects.isEmpty() || rects.find(r -> r.overlaps(rect)) == null) && Build.validPlace(drill, Vars.player.team(), tile.x, tile.y, 0)) {
                int newSum = sum + tilesItemAndCount.get(tile).value;

                Seq<Tile> newSelection = selection.copy().add(tile);
                Seq<Rect> newRects = rects.copy().add(rect);

                int newMax = recursiveMaxSearch(tiles, drill, tilesItemAndCount, newSelection, newRects, newSum, triesPerLevel, maxTries, level + 1);

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

    private static void placeWaterExtractorsAndPowerNodes(Seq<Tile> selection, Drill drill) {
        Seq<Rect> rects = new Seq<>();
        for (Tile t : selection) {
            rects.add(Util.getBlockRect(t, drill));
        }

        Seq<Tile> waterExtractorTiles = new Seq<>();
        Seq<Tile> powerNodeTiles = new Seq<>();

        for (Tile t : selection) {
            Seq<Tile> nearby = Util.getNearbyTiles(t.x, t.y, drill.size, Blocks.waterExtractor.size);

            for (Tile n : nearby) {
                Rect waterExtractorRect = Util.getBlockRect(n, Blocks.waterExtractor);
                BuildPlan buildPlan = new BuildPlan(n.x, n.y, 0, Blocks.waterExtractor);

                if (buildPlan.placeable(Vars.player.team()) && rects.find(r -> r.overlaps(waterExtractorRect)) == null) {
                    waterExtractorTiles.add(n);
                    rects.add(waterExtractorRect);
                    break;
                }
            }
        }

        for (Tile t : selection) {
            Seq<Tile> nearby = Util.getNearbyTiles(t.x, t.y, drill.size, Blocks.powerNode.size);

            for (Tile n : nearby) {
                Rect powerNodeRect = Util.getBlockRect(n, Blocks.powerNode);
                BuildPlan buildPlan = new BuildPlan(n.x, n.y, 0, Blocks.powerNode);

                if (buildPlan.placeable(Vars.player.team()) && rects.find(r -> r.overlaps(powerNodeRect)) == null) {
                    powerNodeTiles.add(n);
                    rects.add(powerNodeRect);
                    break;
                }
            }
        }

        for (Tile waterExtractorTile : waterExtractorTiles) {
            BuildPlan buildPlan = new BuildPlan(waterExtractorTile.x, waterExtractorTile.y, 0, Blocks.waterExtractor);
            Vars.player.unit().addBuild(buildPlan);
        }

        for (Tile powerNodeTile : powerNodeTiles) {
            BuildPlan buildPlan = new BuildPlan(powerNodeTile.x, powerNodeTile.y, 0, Blocks.powerNode);
            Vars.player.unit().addBuild(buildPlan);
        }
    }
}
