package alternative_bots_1;

import battlecode.common.Clock;
import battlecode.common.Direction;
import battlecode.common.GameActionException;
import battlecode.common.MapInfo;
import battlecode.common.MapLocation;
import battlecode.common.Message;
import battlecode.common.PaintType;
import battlecode.common.RobotController;
import battlecode.common.RobotInfo;
import battlecode.common.UnitType;

public class RobotPlayer {
    static int spentTurn = 0;
    static MapLocation exploreTarget = null;
    static MapLocation currentTarget = null;
    static MapLocation SpawnTowerLoc = null;

    static int lastReportedRound = -99;
    static final int reportCooldown = 50;
    static final int msgEnemyTile = 1;
    static final int msgTellMopper = 2;
    static final int msgTellWeakTow = 3;
    static final int msgTellSplasher = 4;

    static final int percantageWeakTower = 40;
    static MapLocation lastSeenEnemyTile = null;
    static int lastTowerReportRound = -99;
    static final int towerReportCooldown = 30;

    static MapLocation mopperTarget = null;

    static MapLocation splasherTowerTarget = null;
    static boolean isTowerDown = false;
    static int patternCleanRound = -1;

    static int enemyTileReportCount = 0;
    static int weakTowerReportCount = 0;
    static int lastSplasherSpawnRound = -999;
    static MapLocation lastDispatchedLocation = null;
    static final int splasherCooldown = 50;

    static UnitType chosenTowerType = null;
    static MapLocation chosenRuinLoc = null;

    static int stepTaken = 0;

    static final Direction[] directions = {
        Direction.NORTH,
        Direction.NORTHEAST,
        Direction.EAST,
        Direction.SOUTHEAST,
        Direction.SOUTH,
        Direction.SOUTHWEST,
        Direction.WEST,
        Direction.NORTHWEST,
    };

    static Direction[] fourDirections = {
        Direction.NORTH,
        Direction.EAST,
        Direction.SOUTH,
        Direction.WEST,
    };

    @SuppressWarnings("unused")
    public static void run(RobotController rc) throws GameActionException {
        while (true) {
            try {
                switch (rc.getType()) {
                    case SOLDIER:
                        runSoldier(rc);
                        break;
                    case MOPPER:
                        runMopper(rc);
                        break;
                    case SPLASHER:
                        runSplasher(rc);
                        break;
                    default:
                        runTower(rc);
                        break;
                }
            } catch (GameActionException e) {
                e.printStackTrace();
            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                Clock.yield();
            }
        }
    }

    public static void runTower(RobotController rc) throws GameActionException {
        RobotInfo[] enemies = rc.senseNearbyRobots(-1, rc.getTeam().opponent());
        for (RobotInfo enemy : enemies) {
            if (rc.canAttack(enemy.location)) {
                rc.attack(enemy.location);
                break;
            }
        }

        RobotInfo[] allies = rc.senseNearbyRobots(-1, rc.getTeam());
        for (RobotInfo ally : allies) {
            if (isAllyTower(ally.getType())) {
                if (rc.canUpgradeTower(ally.location)) {
                    rc.upgradeTower(ally.location);
                    break;
                }
            }
        }

        collectAndForward(rc);

        UnitType toSpawn = decideWhatToSpawn(rc);

        if (toSpawn != null) {
            for (Direction dir : directions) {
                MapLocation spawnLoc = rc.getLocation().add(dir);
                if (rc.canBuildRobot(toSpawn, spawnLoc)) {
                    rc.buildRobot(toSpawn, spawnLoc);
                    break;
                }
            }
        }
    }

    public static void runSoldier(RobotController rc) throws GameActionException {
        MapLocation myLoc = rc.getLocation();

        MapInfo[] visible = rc.senseNearbyMapInfos();
        RobotInfo[] enemies = rc.senseNearbyRobots(-1, rc.getTeam().opponent());
        RobotInfo[] allies = rc.senseNearbyRobots(-1, rc.getTeam());

        updtEnemyTile(rc, visible);
        reportWeakTower(rc, enemies, allies);

        if (SpawnTowerLoc == null) {
            int bestDist = Integer.MAX_VALUE;
            for (RobotInfo ally : allies) {
                if (ally.getType().isTowerType()) {
                    int d = myLoc.distanceSquaredTo(ally.location);
                    if (d < bestDist) {
                        bestDist = d;
                        SpawnTowerLoc = ally.location;
                    }
                }
            }
        }

        int totalSoldiers = 0;
        for (RobotInfo ally : allies) {
            if (ally.getType() == UnitType.SOLDIER) totalSoldiers++;
        }

        if (rc.getPaint() < 15) {
            if (SpawnTowerLoc != null) {
                moveToward(rc, SpawnTowerLoc);
            }
            rc.setIndicatorString("EMERGENCY paint=" + rc.getPaint());
            return;
        }

        if (rc.getPaint() < 50) {
            tryReportTower(rc, allies);
            boolean refueled = tryRefuel(rc);
            if (!refueled) {
                if (SpawnTowerLoc != null && myLoc.distanceSquaredTo(SpawnTowerLoc) > 4) {
                    moveToward(rc, SpawnTowerLoc);
                } else {
                    SpawnTowerLoc = null;
                    exploreOutward(rc);
                }
            }
            rc.setIndicatorString("REFUELING paint=" + rc.getPaint());
            return;
        }

        for (RobotInfo enemy : enemies) {
            if (enemy.getType().isTowerType()) {
                if (totalSoldiers >= 3) {
                    if (rc.canAttack(enemy.location)) {
                        rc.attack(enemy.location);
                    } else {
                        moveToward(rc, enemy.location);
                    }
                    return;
                }
            }
        }

        MapLocation ruin = null;
        if (rc.getRoundNum() > 50) {
            ruin = findRuinForBuilder(rc, visible);
        }
        if (ruin != null) {
            handleRuinBuilding(rc, ruin);
            rc.setIndicatorString("BUILDING tower at " + ruin);
            return;
        }

        int currHp = rc.getHealth();
        boolean canRisk = currHp > 40;
        boolean inDanger = false;
        MapLocation threatLocation = null;

        for (RobotInfo enemy : enemies) {
            UnitType t = enemy.getType();
            if (t == UnitType.MOPPER || t.isTowerType()) {
                if (!canRisk) {
                    inDanger = true;
                    threatLocation = enemy.location;
                    break;
                }
            }
        }

        if (inDanger && threatLocation != null) {
            Direction toward = myLoc.directionTo(threatLocation);
            Direction away = toward.opposite();

            if (rc.canMove(away)) {
                rc.move(away);
            } else if (SpawnTowerLoc != null) {
                moveToward(rc, SpawnTowerLoc);
            }

            MapInfo myTile = rc.senseMapInfo(rc.getLocation());
            if (!myTile.getPaint().isAlly() && rc.canAttack(rc.getLocation())) {
                rc.attack(rc.getLocation());
            }

            rc.setIndicatorString("RETREATING at HP=" + currHp);
            return;
        }

        for (MapInfo tile : visible) {
            if (!tile.isPassable()) continue;
            if (tile.getPaint().isEnemy()) {
                int dist = myLoc.distanceSquaredTo(tile.getMapLocation());
                if (dist <= 9 && rc.canAttack(tile.getMapLocation())) {
                    break;
                }
                if (dist <= 16) {
                    currentTarget = tile.getMapLocation();
                    spentTurn = 0;
                }
            }
        }

        if (currentTarget != null) {
            spentTurn++;

            if (myLoc.distanceSquaredTo(currentTarget) <= 2) {
                currentTarget = null;
                spentTurn = 0;
            } else if (rc.canSenseLocation(currentTarget)) {
                MapInfo info = rc.senseMapInfo(currentTarget);
                if (info.getPaint().isAlly()) {
                    currentTarget = null;
                    spentTurn = 0;
                }
            } else if (spentTurn > 20) {
                currentTarget = null;
                spentTurn = 0;
            }
        }

        if (currentTarget == null) {
            int bestDist = Integer.MAX_VALUE;

            for (MapInfo tile : visible) {
                if (!tile.isPassable()) continue;
                if (tile.getPaint() != PaintType.EMPTY) continue;

                MapLocation loc = tile.getMapLocation();
                int dist = myLoc.distanceSquaredTo(loc);

                if (dist < bestDist) {
                    bestDist = dist;
                    currentTarget = loc;
                    spentTurn = 0;
                }
            }
        }
        if (currentTarget != null) {
            if (rc.canAttack(currentTarget)) {
                rc.attack(currentTarget);
                currentTarget = null;
                spentTurn = 0;
            } else {
                moveToward(rc, currentTarget);
            }
        } else {
            exploreOutward(rc);
        }

        MapInfo myTile = rc.senseMapInfo(rc.getLocation());
        if (!myTile.getPaint().isAlly() && rc.canAttack(rc.getLocation())) {
            rc.attack(rc.getLocation());
        }

        tryReportTower(rc, allies);
    }

    public static void runMopper(RobotController rc) throws GameActionException {
        RobotInfo[] enemies = rc.senseNearbyRobots(-1, rc.getTeam().opponent());
        RobotInfo[] allies = rc.senseNearbyRobots(-1, rc.getTeam());
        MapInfo[] visible = rc.senseNearbyMapInfos();

        Message[] messages = rc.readMessages(-1);
        for (Message m : messages) {
            int[] decoded = decodeMsg(m.getBytes());
            if (decoded[0] == msgTellMopper) {
                MapLocation dispatchTarget = new MapLocation(decoded[1], decoded[2]);
                if (mopperTarget == null
                    || rc.getLocation().distanceSquaredTo(dispatchTarget)
                       < rc.getLocation().distanceSquaredTo(mopperTarget)) {
                    mopperTarget = dispatchTarget;
                }
            }
        }

        if (rc.getPaint() < 20) {
            boolean refueled = tryRefuel(rc);
            if (!refueled) {
                exploreOutward(rc);
            }
            return;
        }

        if (enemies.length > 0) {
            Direction bestSwing = getBestSwingDirection(rc, enemies);
            if (bestSwing != null && rc.canMopSwing(bestSwing)) {
                rc.mopSwing(bestSwing);
            }
        }

        if (rc.getPaint() > 50) {
            for (RobotInfo ally : allies) {
                if (ally.getType() == UnitType.SOLDIER && ally.paintAmount < 30) {
                    if (rc.canTransferPaint(ally.location, 30)) {
                        rc.transferPaint(ally.location, 30);
                        return;
                    }
                }
            }
        }

        MapLocation nearestEnemyTile = null;
        int bestDist = Integer.MAX_VALUE;
        for (MapInfo tile : visible) {
            if (tile.getPaint().isEnemy()) {
                int d = rc.getLocation().distanceSquaredTo(tile.getMapLocation());
                if (d < bestDist) {
                    bestDist = d;
                    nearestEnemyTile = tile.getMapLocation();
                }
            }
        }

        if (nearestEnemyTile != null) {
            if (rc.canAttack(nearestEnemyTile)) rc.attack(nearestEnemyTile);
            else moveToward(rc, nearestEnemyTile);
            mopperTarget = null;
        } else if (mopperTarget != null) {
            moveToward(rc, mopperTarget);
            if (rc.getLocation().distanceSquaredTo(mopperTarget) < 4) mopperTarget = null;
        } else {
            exploreOutward(rc);
        }
    }

    public static void runSplasher(RobotController rc) throws GameActionException {
        MapLocation myLoc = rc.getLocation();
        RobotInfo[] enemies = rc.senseNearbyRobots(-1, rc.getTeam().opponent());
        MapInfo[] visible = rc.senseNearbyMapInfos();

        if (rc.getPaint() < 80) {
            boolean refueled = tryRefuel(rc);
            if (!refueled) exploreOutward(rc);
            return;
        }

        Message[] messages = rc.readMessages(-1);
        for (Message m : messages) {
            int[] decoded = decodeMsg(m.getBytes());
            if (decoded[0] == msgTellSplasher) {
                MapLocation newTarget = new MapLocation(decoded[1], decoded[2]);
                if (splasherTowerTarget == null
                    || myLoc.distanceSquaredTo(newTarget) < myLoc.distanceSquaredTo(splasherTowerTarget)) {
                    splasherTowerTarget = newTarget;
                    isTowerDown = false;
                }
            }
        }

        if (splasherTowerTarget != null && !isTowerDown) {
            boolean towerStillExists = false;
            for (RobotInfo enemy : enemies) {
                if (enemy.location.equals(splasherTowerTarget) && enemy.getType().isTowerType()) {
                    towerStillExists = true;
                    break;
                }
            }

            if (!towerStillExists && rc.canSenseLocation(splasherTowerTarget)) {
                isTowerDown = true;
                patternCleanRound = rc.getRoundNum();
            }

            if (!isTowerDown) {
                if (rc.canAttack(splasherTowerTarget)) {
                    rc.attack(splasherTowerTarget);
                } else {
                    moveToward(rc, splasherTowerTarget);
                }
                return;
            }
        }

        if (isTowerDown && splasherTowerTarget != null) {
            MapLocation bestClean = null;
            int bestCov = 0;

            for (MapInfo tile : visible) {
                if (!tile.isPassable()) continue;
                MapLocation loc = tile.getMapLocation();

                if (loc.distanceSquaredTo(splasherTowerTarget) > 16) continue;
                if (myLoc.distanceSquaredTo(loc) > 4) continue;

                int covering = 0;
                for (MapInfo nearby : visible) {
                    if (nearby.getMapLocation().distanceSquaredTo(loc) <= 2) {
                        if (nearby.getPaint().isEnemy()) covering += 3;
                        else if (nearby.getPaint() == PaintType.EMPTY) covering += 1;
                    }
                }

                if (covering > bestCov) {
                    bestCov = covering;
                    bestClean = loc;
                }
            }

            if (bestClean != null && bestCov > 0) {
                if (rc.canAttack(bestClean)) {
                    rc.attack(bestClean);
                } else {
                    moveToward(rc, splasherTowerTarget);
                }
                rc.setIndicatorString("SPLASHER cleaning pattern coverage=" + bestCov);
                return;
            }

            if (rc.getRoundNum() - patternCleanRound > 20) {
                splasherTowerTarget = null;
                isTowerDown = false;
            }
            return;
        }

        MapLocation nearestEnemy = null;
        int bestDist = Integer.MAX_VALUE;
        for (MapInfo tile : visible) {
            if (tile.getPaint().isEnemy()) {
                int d = myLoc.distanceSquaredTo(tile.getMapLocation());
                if (d < bestDist) {
                    bestDist = d;
                    nearestEnemy = tile.getMapLocation();
                }
            }
        }

        if (nearestEnemy != null) {
            if (rc.canAttack(nearestEnemy)) rc.attack(nearestEnemy);
            else moveToward(rc, nearestEnemy);
        } else {
            exploreOutward(rc);
        }
    }

    public static void moveToward(RobotController rc, MapLocation target) throws GameActionException {
        Direction dir = rc.getLocation().directionTo(target);

        if (rc.canMove(dir)) { rc.move(dir); return; }

        Direction left = dir.rotateLeft();
        Direction right = dir.rotateRight();

        if (rc.canMove(left)) { rc.move(left); return; }
        if (rc.canMove(right)) { rc.move(right); return; }

        Direction left2 = left.rotateLeft();
        Direction right2 = right.rotateRight();

        if (rc.canMove(left2)) { rc.move(left2); return; }
        if (rc.canMove(right2)) { rc.move(right2); return; }

        Direction left3 = left2.rotateLeft();
        Direction right3 = right2.rotateRight();

        if (rc.canMove(left3)) { rc.move(left3); return; }
        if (rc.canMove(right3)) { rc.move(right3); return; }

        Direction opposite = dir.opposite();
        if (rc.canMove(opposite)) { rc.move(opposite); }
    }

    public static boolean tryRefuel(RobotController rc) throws GameActionException {
        MapLocation myLoc = rc.getLocation();
        RobotInfo[] allies = rc.senseNearbyRobots(-1, rc.getTeam());

        MapLocation bestTower = null;
        int bestScore = -1;

        for (RobotInfo ally : allies) {
            UnitType t = ally.getType();
            boolean isPaintTower = t == UnitType.LEVEL_ONE_PAINT_TOWER || t == UnitType.LEVEL_TWO_PAINT_TOWER || t == UnitType.LEVEL_THREE_PAINT_TOWER;

            if (!isPaintTower) continue;
            if (ally.paintAmount <= 50) continue;

            int dist = myLoc.distanceSquaredTo(ally.location);
            int score = ally.paintAmount / Math.max(1, dist);

            if (score > bestScore) {
                bestScore = score;
                bestTower = ally.location;
            }
        }

        if (bestTower == null) return false;

        int paintNeeded = 100 - rc.getPaint();
        if (rc.canTransferPaint(bestTower, -paintNeeded)) {
            rc.transferPaint(bestTower, -paintNeeded);
        } else {
            moveToward(rc, bestTower);
        }

        return true;
    }

    public static void exploreOutward(RobotController rc) throws GameActionException {
        MapLocation myLoc = rc.getLocation();

        if (exploreTarget == null || myLoc.distanceSquaredTo(exploreTarget) < 8) {
            int mapW = rc.getMapWidth();
            int mapH = rc.getMapHeight();

            int cx = mapW/2;
            int cy = mapH/2;

            MapLocation[] candidates = new MapLocation[] {
                new MapLocation(cx+mapW/4, cy+mapH/4),
                new MapLocation(cx-mapW/4, cy+mapH/4),
                new MapLocation(cx-mapW/4, cy-mapH/4),
                new MapLocation(cx+mapW/4, cy-mapH/4),
                new MapLocation(cx,cy+mapH/3),
                new MapLocation(cx,cy-mapH/3),
                new MapLocation(cx+mapW/3, cy),
                new MapLocation(cx-mapW/3, cy),
            };

            stepTaken = stepTaken % candidates.length;

            for (int i = 0; i < candidates.length; i++) {
                MapLocation cand = candidates[stepTaken % candidates.length];
                stepTaken++;

                boolean farEnough = SpawnTowerLoc == null || cand.distanceSquaredTo(SpawnTowerLoc) > 100;
                boolean notCurrentPos = myLoc.distanceSquaredTo(cand) >= 8;

                if (farEnough && notCurrentPos) {
                    exploreTarget = cand;
                    break;
                }
            }

            if (exploreTarget == null || myLoc.distanceSquaredTo(exploreTarget) < 8) {
                if (SpawnTowerLoc != null) {
                    int oppositeX = Math.min(mapW - 1, Math.max(0, mapW - SpawnTowerLoc.x));
                    int oppositeY = Math.min(mapH - 1, Math.max(0, mapH - SpawnTowerLoc.y));
                    exploreTarget = new MapLocation(oppositeX, oppositeY);
                } else {
                    exploreTarget = new MapLocation(cx, cy);
                }
            }
        }

        moveToward(rc, exploreTarget);
    }

    static boolean isAllyTower(UnitType twr) {
        return twr == UnitType.LEVEL_ONE_DEFENSE_TOWER || twr == UnitType.LEVEL_TWO_DEFENSE_TOWER || twr == UnitType.LEVEL_THREE_DEFENSE_TOWER || twr == UnitType.LEVEL_ONE_MONEY_TOWER || twr == UnitType.LEVEL_TWO_MONEY_TOWER || twr == UnitType.LEVEL_THREE_MONEY_TOWER || twr == UnitType.LEVEL_ONE_PAINT_TOWER || twr == UnitType.LEVEL_TWO_PAINT_TOWER || twr == UnitType.LEVEL_THREE_PAINT_TOWER;
    }

    public static MapLocation findRuinForBuilder(RobotController rc, MapInfo[] visible) throws GameActionException {
        MapLocation myLoc = rc.getLocation();
        MapLocation bestRuin = null;
        int bestDist = Integer.MAX_VALUE;

        for (MapInfo tile : visible) {
            if (!tile.hasRuin()) continue;
            MapLocation ruinLoc = tile.getMapLocation();

            if (rc.senseRobotAtLocation(ruinLoc) != null) continue;
            int myDist = myLoc.distanceSquaredTo(ruinLoc);
            int closestSoldier = 0;
            RobotInfo[] allies = rc.senseNearbyRobots(-1, rc.getTeam());
            for (RobotInfo ally : allies) {
                if (ally.getType() == UnitType.SOLDIER) {
                    if (ally.location.distanceSquaredTo(ruinLoc) < myDist) {
                        closestSoldier++;
                    }
                }
            }
            if (closestSoldier < 2 && myDist < bestDist) {
                bestDist = myDist;
                bestRuin = ruinLoc;
            }
        }
        return bestRuin;
    }

    public static UnitType chooseTowerType(RobotController rc) throws GameActionException {
        RobotInfo[] allies = rc.senseNearbyRobots(-1, rc.getTeam());

        int paintTowers = 0;
        int moneyTowers = 0;
        int defenseTowers = 0;

        for (RobotInfo ally : allies) {
            UnitType t = ally.getType();
            if (t == UnitType.LEVEL_ONE_PAINT_TOWER || t == UnitType.LEVEL_TWO_PAINT_TOWER || t == UnitType.LEVEL_THREE_PAINT_TOWER) {
                paintTowers++;
            } else if (t == UnitType.LEVEL_ONE_MONEY_TOWER || t == UnitType.LEVEL_TWO_MONEY_TOWER || t == UnitType.LEVEL_THREE_MONEY_TOWER) {
                moneyTowers++;
            } else if (t == UnitType.LEVEL_ONE_DEFENSE_TOWER || t == UnitType.LEVEL_TWO_DEFENSE_TOWER || t == UnitType.LEVEL_THREE_DEFENSE_TOWER) {
                defenseTowers++;
            }
        }

        if (paintTowers < 2) {
            return UnitType.LEVEL_ONE_PAINT_TOWER;
        } else if (moneyTowers < 2) {
            return UnitType.LEVEL_ONE_MONEY_TOWER;
        } else if (defenseTowers < 1) {
            return UnitType.LEVEL_ONE_DEFENSE_TOWER;
        } else if (paintTowers <= moneyTowers) {
            return UnitType.LEVEL_ONE_PAINT_TOWER;
        } else {
            return UnitType.LEVEL_ONE_MONEY_TOWER;
        }
    }

    public static void handleRuinBuilding(RobotController rc, MapLocation ruinLoc) throws GameActionException {
        if (!ruinLoc.equals(chosenRuinLoc)) {
            chosenTowerType = chooseTowerType(rc);
            chosenRuinLoc = ruinLoc;
        }

        UnitType towerType = chosenTowerType;

        moveToward(rc, ruinLoc);

        if (rc.canMarkTowerPattern(towerType, ruinLoc)) {
            rc.markTowerPattern(towerType, ruinLoc);
        }

        for (MapInfo tile : rc.senseNearbyMapInfos(ruinLoc, 8)) {
            boolean needsPainting = tile.getMark() != PaintType.EMPTY && tile.getMark() != tile.getPaint();
            if (needsPainting) {
                boolean useSecondary = tile.getMark() == PaintType.ALLY_SECONDARY;
                if (rc.canAttack(tile.getMapLocation())) {
                    rc.attack(tile.getMapLocation(), useSecondary);
                    break;
                }
            }
        }

        if (rc.canCompleteTowerPattern(towerType, ruinLoc)) {
            rc.completeTowerPattern(towerType, ruinLoc);
            rc.setTimelineMarker("Built " + towerType, 0, 255, 0);
            chosenRuinLoc = null;
            chosenTowerType = null;
        }

        if (rc.canUpgradeTower(ruinLoc)) {
            rc.upgradeTower(ruinLoc);
        }
    }

    public static Direction getBestSwingDirection(RobotController rc, RobotInfo[] enemies) {
        Direction best = null;
        int bestCount = 0;

        for (Direction d : fourDirections) {
            int count = 0;
            MapLocation step1 = rc.getLocation().add(d);
            MapLocation step2 = step1.add(d);

            for (RobotInfo e : enemies) {
                if (e.location.equals(step1) || e.location.equals(step2)) count++;
            }

            if (count > bestCount) {
                bestCount = count;
                best = d;
            }
        }

        return bestCount > 0 ? best : null;
    }

    static int encodeMsg(int type, int x, int y) {
        return (type << 12) | (x << 6) | y;
    }

    static int[] decodeMsg(int raw) {
        int type = (raw >> 12) & 0xF;
        int x = (raw >> 6) & 0x3F;
        int y = raw & 0x3F;
        return new int[]{type, x, y};
    }

    public static void updtEnemyTile(RobotController rc, MapInfo[] visible) throws GameActionException {
        MapLocation myLoc = rc.getLocation();
        MapLocation nearest = null;
        int bestDist = Integer.MAX_VALUE;

        for (MapInfo tile : visible) {
            if (tile.getPaint().isEnemy()) {
                int d = myLoc.distanceSquaredTo(tile.getMapLocation());
                if (d < bestDist) {
                    bestDist = d;
                    nearest = tile.getMapLocation();
                }
            }
        }

        if (nearest == null) return;

        if (lastSeenEnemyTile == null) {
            lastSeenEnemyTile = nearest;
            return;
        }

        boolean newIsCloser = bestDist < myLoc.distanceSquaredTo(lastSeenEnemyTile);
        boolean oldTileNowClean = rc.canSenseLocation(lastSeenEnemyTile) && !rc.senseMapInfo(lastSeenEnemyTile).getPaint().isEnemy();

        if (newIsCloser || oldTileNowClean) {
            lastSeenEnemyTile = nearest;
        }
    }

    public static void tryReportTower(RobotController rc, RobotInfo[] allies) throws GameActionException {
        if (lastSeenEnemyTile == null) return;
        if (rc.getRoundNum() - lastReportedRound < reportCooldown) return;

        MapLocation myLoc = rc.getLocation();
        boolean isLowPaint = rc.getPaint() < 50;

        MapLocation towerLoc = null;

        if (SpawnTowerLoc != null) {
            for (RobotInfo ally : allies) {
                if (ally.location.equals(SpawnTowerLoc)) {
                    towerLoc = SpawnTowerLoc;
                    break;
                }
            }
        }

        if (towerLoc == null) {
            int bestDist = Integer.MAX_VALUE;
            for (RobotInfo ally : allies) {
                if (isAllyTower(ally.getType())) {
                    int d = myLoc.distanceSquaredTo(ally.location);
                    if (d < bestDist) {
                        bestDist = d;
                        towerLoc = ally.location;
                    }
                }
            }
        }

        if (towerLoc == null) return;

        boolean isTowerInRange = myLoc.distanceSquaredTo(towerLoc) < 20;

        if (!isLowPaint && !isTowerInRange) return;

        int msg = encodeMsg(1, lastSeenEnemyTile.x, lastSeenEnemyTile.y);
        if (rc.canSendMessage(towerLoc, msg)) {
            rc.sendMessage(towerLoc, msg);
            lastReportedRound = rc.getRoundNum();
            lastSeenEnemyTile = null;
        }
    }

    public static void reportWeakTower(RobotController rc, RobotInfo[] enemies, RobotInfo[] allies) throws GameActionException {
        if (rc.getRoundNum() - lastTowerReportRound < towerReportCooldown) return;
        for (RobotInfo enemy : enemies) {
            if (!enemy.getType().isTowerType()) continue;
            int maxHp = enemy.getType().health;
            int anggapLemah = (maxHp * percantageWeakTower) / 100;

            if (enemy.health > anggapLemah) continue;

            MapLocation towerLoc = null;
            if (SpawnTowerLoc != null) {
                for (RobotInfo ally : allies) {
                    if (ally.location.equals(SpawnTowerLoc)) {
                        towerLoc = SpawnTowerLoc;
                        break;
                    }
                }
            }

            if (towerLoc == null) {
                int bestDist = Integer.MAX_VALUE;
                MapLocation myLoc = rc.getLocation();
                for (RobotInfo ally : allies) {
                    if (isAllyTower(ally.getType())) {
                        int d = myLoc.distanceSquaredTo(ally.location);
                        if (d < bestDist) { bestDist = d; towerLoc = ally.location; }
                    }
                }
            }
            if (towerLoc == null) return;

            int msg = encodeMsg(msgTellWeakTow, enemy.location.x, enemy.location.y);
            if (rc.canSendMessage(towerLoc, msg)) {
                rc.sendMessage(towerLoc, msg);
                lastTowerReportRound = rc.getRoundNum();
            }
            return;
        }
    }

    public static void collectAndForward(RobotController rc) throws GameActionException {
        Message[] messages = rc.readMessages(-1);
        RobotInfo[] allies = rc.senseNearbyRobots(-1, rc.getTeam());

        for (Message msg : messages) {
            int[] decoded = decodeMsg(msg.getBytes());
            int type = decoded[0];
            int x = decoded[1];
            int y = decoded[2];

            if (type == msgEnemyTile) {
                MapLocation reported = new MapLocation(x, y);
                if (lastDispatchedLocation != null && reported.equals(lastDispatchedLocation)) continue;
                enemyTileReportCount++;

                int dispatch = encodeMsg(msgTellMopper, x, y);
                int sent = 0;

                for (RobotInfo ally : allies) {
                    if (ally.getType() == UnitType.MOPPER && sent < 3) {
                        if (rc.canSendMessage(ally.location, dispatch)) {
                            rc.sendMessage(ally.location, dispatch);
                            sent++;
                        }
                    }
                }
                lastDispatchedLocation = reported;
            }

            if (type == msgTellWeakTow) {
                weakTowerReportCount++;
                int dispatch = encodeMsg(msgTellSplasher, x, y);
                int sent = 0;
                for (RobotInfo ally : allies) {
                    if (ally.getType() == UnitType.SPLASHER && sent < 2) {
                        if (rc.canSendMessage(ally.location, dispatch)) {
                            rc.sendMessage(ally.location, dispatch);
                            sent++;
                        }
                    }
                }
            }
        }
    }

    public static UnitType decideWhatToSpawn(RobotController rc) throws GameActionException {
        int round = rc.getRoundNum();
        int paint = rc.getPaint();
        int chips = rc.getChips();

        boolean canAffordSoldier = paint >= 200 && chips >= 250;
        boolean canAffordMopper = paint >= 100 && chips >= 300;
        boolean canAffordSplasher = paint >= 300 && chips >= 400;

        RobotInfo[] myTeam = rc.senseNearbyRobots(-1, rc.getTeam());
        int soldierCount = 0;
        int mopperCount = 0;
        int splasherCount = 0;
        for (RobotInfo member : myTeam) {
            switch (member.getType()) {
                case SOLDIER: soldierCount++; break;
                case MOPPER: mopperCount++; break;
                case SPLASHER: splasherCount++; break;
                default: break;
            }
        }

        if (soldierCount == 0) {
            if (canAffordSoldier) return UnitType.SOLDIER;
            return null;
        }

        if (round < 250 && soldierCount < 10) {
            if (canAffordSoldier) return UnitType.SOLDIER;
            return null;
        }

        boolean splasherCooldownOk = (round - lastSplasherSpawnRound) >= splasherCooldown;
        boolean hasEnemyIntel = enemyTileReportCount > 0 || weakTowerReportCount > 0;

        if (canAffordSplasher && splasherCooldownOk && splasherCount < 2 && hasEnemyIntel) {
            lastSplasherSpawnRound = round;
            return UnitType.SPLASHER;
        }

        if (canAffordSplasher && splasherCooldownOk && splasherCount < 1 && paint >= 350) {
            lastSplasherSpawnRound = round;
            return UnitType.SPLASHER;
        }

        boolean noMopperButNeed = mopperCount == 0 && enemyTileReportCount > 0;
        boolean tooMuchSoilder = soldierCount >= 3 && (double) soldierCount / Math.max(1, mopperCount) > 3.0;

        if (canAffordMopper && (noMopperButNeed || tooMuchSoilder)) {
            return UnitType.MOPPER;
        }

        if (canAffordSoldier) return UnitType.SOLDIER;

        return null;
    }
}