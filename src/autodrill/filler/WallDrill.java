package autodrill.filler;

import arc.Core;
import arc.func.Floatf;
import arc.math.geom.Point2;
import arc.struct.Queue;
import arc.struct.Seq;
import mindustry.Vars;
import mindustry.content.Blocks;
import mindustry.entities.units.BuildPlan;
import mindustry.type.Item;
import mindustry.world.Block;
import mindustry.world.Tile;
import mindustry.world.blocks.production.BeamDrill;

import java.util.Comparator;

import static arc.Core.bundle;

public class WallDrill {
    public static void fill(Tile tile, BeamDrill drill, Direction direction) {
        Seq<Tile> tiles = getConnectedWallTiles(tile, direction);

        Seq<Tile> boreTiles = new Seq<>();
        Seq<Integer> occupiedSecondaryAxis = new Seq<>();

        Direction directionOpposite = Direction.getOpposite(direction);
        Point2 offset = getDirectionOffset(direction, drill);
        Point2 offsetOpposite = getDirectionOffset(directionOpposite, drill);
        for (Tile tile1 : tiles) {
            for (int i = 0; i < drill.range; i++) {
                Tile boreTile = tile1.nearby((i + 1) * -direction.p.x + offset.x, (i + 1) * -direction.p.y + offset.y);
                if (boreTile == null) continue;

                BuildPlan buildPlan = new BuildPlan(boreTile.x, boreTile.y, direction.r, drill);
                if (buildPlan.placeable(Vars.player.team())) {
                    int sa = direction.secondaryAxis(new Point2(boreTile.x, boreTile.y));

                    boolean occupied = false;
                    for (int j = -(drill.size - 1) / 2; j <= drill.size / 2; j++) {
                        if (occupiedSecondaryAxis.contains(sa + j)) {
                            occupied = true;
                            break;
                        }
                    }
                    if (occupied) continue;

                    for (int j = -(drill.size - 1) / 2; j <= drill.size / 2; j++) {
                        occupiedSecondaryAxis.add(sa + j);
                    }

                    boreTiles.add(boreTile);
                    break;
                }
            }
        }
        if (boreTiles.isEmpty()) return;

        Seq<Tile> ductTiles = new Seq<>();
        for (Tile boreTile : boreTiles) {
            for (int i = -(drill.size - 1) / 2; i <= drill.size / 2; i++) {
                Tile ductTile = boreTile.nearby(new Point2(
                        -offsetOpposite.x + directionOpposite.p.x + (i * Math.abs(direction.p.y)),
                        -offsetOpposite.y + directionOpposite.p.y + (i * Math.abs(direction.p.x))));
                if (ductTile == null) continue;

                ductTiles.add(ductTile);
            }
        }
        if (ductTiles.isEmpty()) return;

        Tile outerMostDuctTile = ductTiles.select(t -> boreTiles.find(bt -> direction.secondaryAxis(new Point2(bt.x, bt.y)) == direction.secondaryAxis(new Point2(t.x, t.y))) == null).max(t -> -direction.primaryAxis(new Point2(t.x, t.y)));
        if (outerMostDuctTile == null) return;
        ductTiles.sort(t -> t.dst2(outerMostDuctTile));
        Seq<Tile> connectingTiles = new Seq<>();
        connectingTiles.add(outerMostDuctTile);
        for (Tile ductTile : ductTiles) {
            if (connectingTiles.contains(ductTile)) continue;

            Tile closestDuctTile = connectingTiles.min(t -> t.dst2(ductTile));
            if (closestDuctTile == null) continue;

            Point2 currentPoint = new Point2(ductTile.x, ductTile.y);
            Point2 goal = new Point2(closestDuctTile.x, closestDuctTile.y);
            int paGoal = direction.primaryAxis(goal);
            int saGoal = direction.secondaryAxis(goal);

            while (currentPoint.x != closestDuctTile.x || currentPoint.y != closestDuctTile.y) {
                int pa = direction.primaryAxis(currentPoint);
                int sa = direction.secondaryAxis(currentPoint);

                Tile currentTile = Vars.world.tile(currentPoint.x, currentPoint.y);
                if (currentTile != null && !connectingTiles.contains(currentTile)) connectingTiles.add(currentTile);

                if ((pa < paGoal && sa == saGoal) || pa > paGoal) {
                    if (Math.abs(pa) < Math.abs(paGoal))
                        currentPoint.add(Math.abs(direction.p.x), Math.abs(direction.p.y));
                    else currentPoint.add(-Math.abs(direction.p.x), -Math.abs(direction.p.y));
                } else {
                    if (Math.abs(sa) < Math.abs(saGoal))
                        currentPoint.add(Math.abs(direction.p.y), Math.abs(direction.p.x));
                    else currentPoint.add(-Math.abs(direction.p.y), -Math.abs(direction.p.x));
                }
            }
        }

        connectingTiles.sort((Floatf<Tile>) outerMostDuctTile::dst);
        Seq<Tile> visitedTiles = new Seq<>();
        visitedTiles.add(outerMostDuctTile);
        while (!connectingTiles.isEmpty()) {
            Tile tile1 = null, tile2 = null;

            for (Tile connectingTile : connectingTiles) {
                Tile adjacent = visitedTiles.find(t -> connectingTile.relativeTo(t) != -1);
                if (adjacent != null) {
                    tile1 = adjacent;
                    tile2 = connectingTile;
                    visitedTiles.add(connectingTile);
                    connectingTiles.remove(connectingTile);
                    break;
                }
            }
            if (tile1 == null || tile2 == null) continue;

            if (tile2.equals(outerMostDuctTile)) {
                BuildPlan buildPlan = new BuildPlan(tile2.x, tile2.y, directionOpposite.r, Blocks.duct);
                Vars.player.unit().addBuild(buildPlan);
                buildPlan = new BuildPlan(tile2.x + directionOpposite.p.x, tile2.y + directionOpposite.p.y, directionOpposite.r, Blocks.duct);
                Vars.player.unit().addBuild(buildPlan);
            } else {
                BuildPlan buildPlan = new BuildPlan(tile2.x, tile2.y, tile2.relativeTo(tile1), Blocks.duct);
                Vars.player.unit().addBuild(buildPlan);
            }
        }

        for (Tile ductTile : connectingTiles) {
            float ductTileIndex = connectingTiles.indexOf(ductTile);

            Tile neighbor = connectingTiles.find(t -> connectingTiles.indexOf(t) < ductTileIndex && t.relativeTo(ductTile) != -1);
            if (neighbor == null) continue;

            BuildPlan buildPlan = new BuildPlan(ductTile.x, ductTile.y, ductTile.relativeTo(neighbor), Blocks.duct);
            Vars.player.unit().addBuild(buildPlan);
        }

        Tile outerMost = boreTiles.max(t -> -direction.primaryAxis(new Point2(t.x, t.y)));
        for (Tile boreTile : boreTiles) {
            Tile beamNodeTile = Vars.world.tile(
                    Math.abs(direction.p.x) * outerMost.x + Math.abs(direction.p.y) * boreTile.x - offsetOpposite.x + directionOpposite.p.x * 2,
                    Math.abs(direction.p.y) * outerMost.y + Math.abs(direction.p.x) * boreTile.y - offsetOpposite.y + directionOpposite.p.y * 2);
            if (beamNodeTile == null) continue;

            BuildPlan buildPlan = new BuildPlan(beamNodeTile.x, beamNodeTile.y, 0, Blocks.beamNode);
            Vars.player.unit().addBuild(buildPlan);
            while (beamNodeTile.dst(boreTile) > 10 * Vars.tilesize) {
                beamNodeTile = beamNodeTile.nearby(direction.p.x * 5, direction.p.y * 5);
                if (beamNodeTile == null) break;

                buildPlan = new BuildPlan(beamNodeTile.x, beamNodeTile.y, 0, Blocks.beamNode);
                Vars.player.unit().addBuild(buildPlan);
            }
        }

        for (Tile boreTile : boreTiles) {
            BuildPlan buildPlan = new BuildPlan(boreTile.x, boreTile.y, direction.r, drill);
            Vars.player.unit().addBuild(buildPlan);
        }
    }

    private static Seq<Tile> getConnectedWallTiles(Tile tile, Direction direction) {
        Queue<Tile> queue = new Queue<>();
        Seq<Tile> tiles = new Seq<>();
        Seq<Tile> visited = new Seq<>();

        queue.addLast(tile);

        Item sourceItem = tile.wallDrop();

        int maxTiles = (int) (Core.settings.getInt(bundle.get("auto-drill.settings.max-tiles")) * 0.25f);

        while (!queue.isEmpty() && tiles.size < maxTiles) {
            Tile currentTile = queue.removeFirst();

            if (visited.contains(currentTile)) continue;

            if (currentTile.wallDrop() == sourceItem) {
                for (int x = -2; x <= 2; x++) {
                    for (int y = -2; y <= 2; y++) {
                        if (!(x == 0 && y == 0)) {
                            Tile neighbor = currentTile.nearby(x, y);
                            if (neighbor == null) continue;

                            Tile nearby = neighbor.nearby(new Point2(-direction.p.x, -direction.p.y));
                            if (!visited.contains(neighbor) && nearby != null && !nearby.solid()) {
                                queue.addLast(neighbor);
                            }
                        }
                    }
                }
                tiles.add(currentTile);
            }
            visited.add(currentTile);
        }

        Seq<Tile> tilesCopy = tiles.copy();
        tiles.retainAll(t1 -> {
            Point2 pT1 = Util.tileToPoint2(t1);
            int paT1 = direction.primaryAxis(pT1);
            int saT1 = direction.secondaryAxis(pT1);

            return !tilesCopy.contains(t2 -> {
                Point2 pT2 = Util.tileToPoint2(t2);

                return t2 != t1 && direction.secondaryAxis(pT2) == saT1 && direction.primaryAxis(pT2) < paT1;
            });
        });

        tiles.sort(t -> direction.secondaryAxis(Util.tileToPoint2(t)));

        return tiles;
    }

    private static Point2 getDirectionOffset(Direction direction, Block block) {
        int offset1 = (block.size - 1) / 2;
        int offset2 = block.size / 2;

        switch (direction) {
            case RIGHT -> {
                return new Point2(-offset2, 0);
            }
            case UP -> {
                return new Point2(0, -offset2);
            }
            case LEFT -> {
                return new Point2(offset1, 0);
            }
            default -> {
                return new Point2(0, offset1);
            }
        }
    }
}
