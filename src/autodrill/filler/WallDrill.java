package autodrill.filler;

import arc.Core;
import arc.math.geom.Point2;
import arc.math.geom.Rect;
import arc.struct.ObjectMap;
import arc.struct.Queue;
import arc.struct.Seq;
import arc.util.Timer;
import mindustry.Vars;
import mindustry.content.Blocks;
import mindustry.content.Fx;
import mindustry.entities.units.BuildPlan;
import mindustry.gen.Call;
import mindustry.type.Item;
import mindustry.world.Block;
import mindustry.world.Tile;
import mindustry.world.blocks.production.BeamDrill;

import static arc.Core.bundle;

public class WallDrill {
    public static void fill(Tile tile, BeamDrill drill, Direction direction) {
        Seq<Tile> tiles = getConnectedWallTiles(tile, direction);

        Seq<Tile> boreTiles = new Seq<>();
        Seq<Rect> boreRects = new Seq<>();

        for (Tile tile1 : tiles) {
            Point2 offset = getDirectionOffset(direction, drill);

            /*
            int o = -(drill.size - 1) / 2;
            for(int dx = 0; dx < drill.size; dx++) {
                for (int dy = 0; dy < drill.size; dy++) {
                    int wx = dx + o + tile.x, wy = dy + o + tile.y;

                    Tile check = world.tile(wx, wy);
                }
            }
            */

            Tile boreTile = tile1.nearby(-direction.p.x + offset.x, -direction.p.y + offset.y);

            Rect boreRect = Util.getBlockRect(boreTile, drill);
            BuildPlan buildPlan = new BuildPlan(boreTile.x, boreTile.y, 0, Blocks.berylliumWallLarge);

            if (buildPlan.placeable(Vars.player.team()) && !boreRects.contains(r -> r.overlaps(boreRect))) {
                boreTiles.add(boreTile);
                boreRects.add(boreRect);
            }
        }

        int i = 0;
        for (Tile tile1 : boreTiles) {
            Timer.schedule(() -> {
                Fx.placeBlock.at(tile1);
            }, i++ * 0.5f);
        }

        i = 0;
        for (Tile boreTile : boreTiles) {
            Timer.schedule(() -> {
                Call.setTile(boreTile, drill, Vars.player.team(), direction.r);
                Fx.placeBlock.at(boreTile);
            }, i++ * 0.5f);
        }
    }

    private static Seq<Tile> getConnectedWallTiles(Tile tile, Direction direction) {
        Queue<Tile> queue = new Queue<>();
        Seq<Tile> tiles = new Seq<>();
        Seq<Tile> visited = new Seq<>();

        queue.addLast(tile);

        Item sourceItem = tile.wallDrop();

        int maxTiles = Core.settings.getInt(bundle.get("auto-drill.settings.max-tiles"));

        while (!queue.isEmpty() && tiles.size < maxTiles) {
            Tile currentTile = queue.removeFirst();

            if (visited.contains(currentTile)) continue;

            if (currentTile.wallDrop() == sourceItem) {
                for (int x = -2; x <= 2; x++) {
                    for (int y = -2; y <= 2; y++) {
                        if (!(x == 0 && y == 0)) {
                            Tile neighbor = currentTile.nearby(x, y);

                            if (!visited.contains(neighbor) && !neighbor.nearby(new Point2(-direction.p.x, -direction.p.y)).solid()) {
                                queue.addLast(neighbor);
                            }
                        }
                    }
                }
                tiles.add(currentTile);
            }
            visited.add(currentTile);
        }

        tiles.filter(t1 -> {
            Point2 pT1 = Util.tileToPoint2(t1);
            int paT1 = direction.primaryAxis(pT1);
            int saT1 = direction.primaryAxis(pT1);

            return !tiles.contains(t2 -> {
                Point2 pT2 = Util.tileToPoint2(t2);

                return t2 != t1 && direction.secondaryAxis(pT2) == saT1 && direction.primaryAxis(pT2) > paT1;
            });
        });

        tiles.sort(t -> direction.secondaryAxis(Util.tileToPoint2(t)));

        return tiles;
    }

    private static Point2 getDirectionOffset(Direction direction, Block block) {
        int offset = block.size / 2;

        switch (direction) {
            case RIGHT -> {
                return new Point2(-offset, 0);
            }
            case UP -> {
                return new Point2(0, -offset);
            }
            default -> {
                return new Point2(0, 0);
            }
        }
    }
}
