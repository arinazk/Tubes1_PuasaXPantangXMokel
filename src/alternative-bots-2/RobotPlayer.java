package alternative-bots-2;

import battlecode.common.*;
import java.util.Random;

public class RobotPlayer {
    static final int MAX_CLAIMS = 20;
    static final int CLAIM_TIMEOUT = 25;
    static final MapLocation[] claimLoc = new MapLocation[MAX_CLAIMS];
    static final int[] claimOwner = new int[MAX_CLAIMS];
    static final int[] claimRound = new int[MAX_CLAIMS];
    static final UnitType[] claimType = new UnitType[MAX_CLAIMS];
    static int claimCount = 0;

    static MapLocation robotAssignedRuin = null;
    static MapLocation robotPrevLocation = null;
    static MapLocation robotExploreTarget = null;
    static Direction robotLastMove = null;
    static boolean robotBuildingLocked = false;
    static int robotNoProgressTurns = 0;
    static int robotExploreRefreshRound = -999;

    static final int MAX_VISITED = 12;
    static final int EXPLORE_TTL = 40;
    static final MapLocation[] robotVisited = new MapLocation[MAX_VISITED];
    static int robotVisitedCount = 0;

    static boolean robotSymInit = false;
    static MapLocation[] robotSymTgts = new MapLocation[3];
    static int robotSymCount = 0;
    static int robotSymIdx = -1;

    static final int PAINT_REFILL = 25;
    static final int PAINT_BUILD_COMMIT = 70;
    static final int PAINT_BUILD_LOCKED = 40;
    static final int PAINT_DONATE_RESERVE = 40;
    static final int PAINT_EXPLORE_PAINT = 40;

    static final Direction[] dirs = {
            Direction.NORTH, Direction.NORTHEAST, Direction.EAST, Direction.SOUTHEAST,
            Direction.SOUTH, Direction.SOUTHWEST, Direction.WEST, Direction.NORTHWEST
    };

    static void initSym(RobotController rc) throws GameActionException {
        if (robotSymInit)
            return;
        int w = rc.getMapWidth(), h = rc.getMapHeight();
        MapLocation[] tmp = new MapLocation[9];
        int tc = 0;
        for (RobotInfo a : rc.senseNearbyRobots(-1, rc.getTeam())) {
            if (!a.getType().isTowerType())
                continue;
            MapLocation[] candidates = {
                    new MapLocation(w - 1 - a.location.x, h - 1 - a.location.y),
                    new MapLocation(w - 1 - a.location.x, a.location.y),
                    new MapLocation(a.location.x, h - 1 - a.location.y)
            };
            for (MapLocation m : candidates) {
                boolean dup = false;
                for (int i = 0; i < tc; i++)
                    if (tmp[i].equals(m)) {
                        dup = true;
                        break;
                    }
                if (!dup && tc < 9)
                    tmp[tc++] = m;
            }
        }
        for (int i = 0; i < tc && robotSymCount < 3; i++)
            robotSymTgts[robotSymCount++] = tmp[i];
        robotSymInit = true;
    }

    static MapLocation getSymTarget(RobotController rc) throws GameActionException {
        initSym(rc);
        if (robotSymCount == 0)
            return null;
        if (robotSymIdx < 0)
            robotSymIdx = rc.getID() % robotSymCount;
        return robotSymTgts[robotSymIdx];
    }

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
            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                Clock.yield();
            }
        }
    }

    static void runTower(RobotController rc) throws GameActionException {
        RobotInfo[] enemies = rc.senseNearbyRobots(-1, rc.getTeam().opponent());
        RobotInfo weakest = null;
        int lowestHp = Integer.MAX_VALUE;
        for (RobotInfo r : enemies)
            if (r.health < lowestHp) {
                lowestHp = r.health;
                weakest = r;
            }
        if (weakest != null && rc.canAttack(weakest.location))
            rc.attack(weakest.location);

        UnitType myType = rc.getType();
        if (enemies.length > 0)
            return;

        if (myType == UnitType.LEVEL_ONE_MONEY_TOWER) {

            if (rc.getChips() >= 2500 &&
                    rc.getNumberTowers() >= 2 &&
                    rc.canUpgradeTower(rc.getLocation())) {

                rc.upgradeTower(rc.getLocation());
            }

        } else if (myType == UnitType.LEVEL_TWO_MONEY_TOWER) {

            if (rc.getChips() >= 5000 &&
                    rc.getNumberTowers() >= 2 &&
                    rc.canUpgradeTower(rc.getLocation())) {

                rc.upgradeTower(rc.getLocation());
            }
        }

        if (!rc.isActionReady())
            return;

        MapInfo[] infos = rc.senseNearbyMapInfos();
        UnitType spawn = chooseSpawn(rc, enemies, infos);
        for (Direction d : dirs) {
            MapLocation loc = rc.getLocation().add(d);
            if (rc.canBuildRobot(spawn, loc)) {
                rc.buildRobot(spawn, loc);
                return;
            }
        }
    }

    static UnitType chooseSpawn(RobotController rc, RobotInfo[] enemies, MapInfo[] infos)
            throws GameActionException {
        for (RobotInfo e : enemies)
            if (e.getType() == UnitType.MOPPER)
                return UnitType.MOPPER;

        if (enemies.length > 0 || ruinWithEnemyPaint(infos) != null)
            return UnitType.MOPPER;

        if (rc.getRoundNum() < 80)
            return UnitType.SOLDIER;

        int enemyPaintTiles = 0;
        for (MapInfo i : infos)
            if (i.getPaint().isEnemy())
                enemyPaintTiles++;
        if (enemyPaintTiles >= 4)
            return UnitType.MOPPER;
        RobotInfo[] allies = rc.senseNearbyRobots(-1, rc.getTeam());
        int soldiers = 0, moppers = 0;
        for (RobotInfo a : allies) {
            if (a.getType() == UnitType.SOLDIER)
                soldiers++;
            if (a.getType() == UnitType.MOPPER)
                moppers++;
        }
        if (moppers == 0 && soldiers >= 3)
            return UnitType.MOPPER;
        if (moppers == 1 && soldiers >= 6)
            return UnitType.MOPPER;

        if (rc.getNumberTowers() >= 4 && rc.getRoundNum() > 150)
            return UnitType.SPLASHER;

        return UnitType.SOLDIER;
    }

    static void runSoldier(RobotController rc) throws GameActionException {
        if (robotBuildingLocked && robotAssignedRuin != null) {
            if (rc.getPaint() < PAINT_BUILD_LOCKED) {
                robotBuildingLocked = false;
                refill(rc);
                return;
            }
            buildTower(rc, robotAssignedRuin);
            return;
        }
        if (rc.getPaint() < PAINT_REFILL) {
            robotAssignedRuin = null;
            robotBuildingLocked = false;
            refill(rc);
            return;
        }
        paintUnder(rc);

        MapInfo[] infos = rc.senseNearbyMapInfos();
        RobotInfo[] enemies = rc.senseNearbyRobots(-1, rc.getTeam().opponent());
        RobotInfo[] allies = rc.senseNearbyRobots(-1, rc.getTeam());

        RobotInfo nearestEnemyTower = nearestEnemy(rc, enemies, true);
        if (rc.getRoundNum() <= 60 && nearestEnemyTower == null) {
            MapLocation sym = getSymTarget(rc);
            if (sym != null && rc.getLocation().distanceSquaredTo(sym) > 9) {
                move(rc, sym, false);
                if (rc.isActionReady() && Clock.getBytecodesLeft() > 3000) {
                    MapLocation pt = bestPaintTile(rc, infos);
                    if (pt != null && rc.canAttack(pt))
                        rc.attack(pt);
                }
                return;
            }
        }
        MapLocation enemyRuin = findEnemyRuin(rc, infos);
        if (enemyRuin != null) {
            if (rc.getLocation().distanceSquaredTo(enemyRuin) > 8) {
                move(rc, enemyRuin, false);
                return;
            }
            for (Direction d : dirs) {
                MapLocation loc = enemyRuin.add(d);
                if (!rc.canSenseLocation(loc) || !rc.canAttack(loc))
                    continue;
                MapInfo inf = rc.senseMapInfo(loc);
                if (!inf.isPassable() || inf.getPaint().isAlly())
                    continue;
                rc.attack(loc);
                return;
            }
            explore(rc, infos);
            return;
        }

        if (robotAssignedRuin != null) {
            if (!rc.canSenseLocation(robotAssignedRuin)) {
                move(rc, robotAssignedRuin, false);
                return;
            }
            if (rc.senseRobotAtLocation(robotAssignedRuin) != null)
                releaseRuin(rc, robotAssignedRuin);
            else {
                if (rc.getPaint() >= PAINT_BUILD_COMMIT)
                    robotBuildingLocked = true;
                buildTower(rc, robotAssignedRuin);
                return;
            }
        }
        MapLocation newRuin = findNearbyRuin(rc, infos, allies);
        if (newRuin != null) {
            if (rc.getPaint() >= PAINT_BUILD_COMMIT)
                robotBuildingLocked = true;
            buildTower(rc, newRuin);
            return;
        }

        if (nearestEnemyTower != null) {
            MapLocation towerLoc = nearestEnemyTower.location;
            if ((rc.getRoundNum() & 1) == 0) {
                if (rc.canAttack(towerLoc))
                    rc.attack(towerLoc);
                else
                    move(rc, towerLoc, false);
            } else {
                boolean adjacent = rc.getLocation().distanceSquaredTo(towerLoc) <= 4;
                move(rc, towerLoc, adjacent);
            }
            return;
        }

        RobotInfo nearestFoe = nearestEnemy(rc, enemies, false);
        if (nearestFoe != null) {
            if (rc.canAttack(nearestFoe.location))
                rc.attack(nearestFoe.location);
            else if (rc.getPaint() >= PAINT_EXPLORE_PAINT)
                move(rc, nearestFoe.location, false);
            else
                move(rc, nearestFoe.location, true);
            return;
        }

        explore(rc, infos);
    }

    static void paintUnder(RobotController rc) throws GameActionException {
        if (!rc.isActionReady())
            return;
        MapLocation loc = rc.getLocation();
        MapInfo info = rc.senseMapInfo(loc);
        if (robotAssignedRuin != null && loc.distanceSquaredTo(robotAssignedRuin) <= 8) {
            PaintType mark = info.getMark();
            if (mark != PaintType.EMPTY && info.getPaint() != mark && rc.canAttack(loc))
                rc.attack(loc, mark == PaintType.ALLY_SECONDARY);
            return;
        }
        if (info.getPaint().isEnemy()) {
            if (rc.canAttack(loc))
                rc.attack(loc);
            return;
        }
        if (!info.getPaint().isAlly() && rc.getPaint() >= PAINT_EXPLORE_PAINT && rc.canAttack(loc))
            rc.attack(loc);
    }

    static void runMopper(RobotController rc) throws GameActionException {
        if (rc.getPaint() < PAINT_REFILL) {
            refill(rc);
            return;
        }

        RobotInfo[] enemies = rc.senseNearbyRobots(-1, rc.getTeam().opponent());
        RobotInfo[] allies = rc.senseNearbyRobots(-1, rc.getTeam());
        MapInfo[] infos = rc.senseNearbyMapInfos();
        if (rc.isActionReady()) {
            for (RobotInfo a : allies) {
                if (a.getType() != UnitType.SOLDIER)
                    continue;
                if (a.paintAmount >= PAINT_REFILL)
                    continue;
                int give = Math.min(30, rc.getPaint() - PAINT_DONATE_RESERVE);
                if (give > 0 && rc.canTransferPaint(a.location, give)) {
                    rc.transferPaint(a.location, give);
                    break;
                }
            }
        }

        RobotInfo rushTarget = antiRushTarget(rc, enemies, allies);
        if (rushTarget != null) {
            if (rc.canAttack(rushTarget.location))
                rc.attack(rushTarget.location);
            else
                move(rc, rushTarget.location, false);
            return;
        }
        for (MapLocation ruin : new MapLocation[] { robotAssignedRuin, ruinWithEnemyPaint(infos) }) {
            if (ruin == null)
                continue;
            MapLocation dirty = enemyPaintNear(infos, rc, ruin);
            if (dirty != null) {
                if (rc.canAttack(dirty))
                    rc.attack(dirty);
                else
                    move(rc, dirty, false);
                return;
            }
        }

        RobotInfo lowestPaintEnemy = null;
        int lowestPaint = Integer.MAX_VALUE;
        for (RobotInfo e : enemies)
            if (e.paintAmount < lowestPaint) {
                lowestPaint = e.paintAmount;
                lowestPaintEnemy = e;
            }
        if (lowestPaintEnemy != null) {
            if (rc.canAttack(lowestPaintEnemy.location))
                rc.attack(lowestPaintEnemy.location);
            else
                move(rc, lowestPaintEnemy.location, false);
            return;
        }

        MapLocation nearestDirt = null;
        int nearestDist = Integer.MAX_VALUE;
        for (MapInfo i : infos) {
            if (!i.getPaint().isEnemy())
                continue;
            int d = rc.getLocation().distanceSquaredTo(i.getMapLocation());
            if (d < nearestDist) {
                nearestDist = d;
                nearestDirt = i.getMapLocation();
            }
        }
        if (nearestDirt != null) {
            if (rc.canAttack(nearestDirt))
                rc.attack(nearestDirt);
            else
                move(rc, nearestDirt, false);
            return;
        }

        explore(rc, infos);
    }

    static RobotInfo antiRushTarget(RobotController rc, RobotInfo[] enemies, RobotInfo[] allies) {
        RobotInfo best = null;
        int bestDist = Integer.MAX_VALUE;
        for (RobotInfo e : enemies) {
            if (e.getType() != UnitType.MOPPER && e.getType() != UnitType.SOLDIER)
                continue;
            if (e.getType() == UnitType.SOLDIER) {
                boolean nearOurTower = false;
                for (RobotInfo a : allies) {
                    if (!a.getType().isTowerType())
                        continue;
                    if (e.location.distanceSquaredTo(a.location) <= 20) {
                        nearOurTower = true;
                        break;
                    }
                }
                if (!nearOurTower)
                    continue;
            }
            int d = rc.getLocation().distanceSquaredTo(e.location);
            if (d < bestDist) {
                bestDist = d;
                best = e;
            }
        }
        return best;
    }

    static MapLocation enemyPaintNear(MapInfo[] infos, RobotController rc, MapLocation ruin) {
        MapLocation best = null;
        int bestDist = Integer.MAX_VALUE;
        for (MapInfo i : infos) {
            if (i.getMapLocation().distanceSquaredTo(ruin) > 8 || !i.getPaint().isEnemy())
                continue;
            int d = rc.getLocation().distanceSquaredTo(i.getMapLocation());
            if (d < bestDist) {
                bestDist = d;
                best = i.getMapLocation();
            }
        }
        return best;
    }

    static void runSplasher(RobotController rc) throws GameActionException {
        if (rc.getPaint() < PAINT_REFILL) {
            refill(rc);
            return;
        }

        MapInfo[] infos = rc.senseNearbyMapInfos();

        if (Clock.getBytecodesLeft() > 5000) {
            MapLocation pb = bestPatternBreak(infos, rc);
            if (pb != null && rc.canAttack(pb)) {
                rc.attack(pb);
                return;
            }
        }

        if (Clock.getBytecodesLeft() > 4000) {
            MapLocation best = bestSplash(infos, rc);
            if (best != null && rc.canAttack(best)) {
                rc.attack(best);
                return;
            }
        }

        if (rc.senseMapInfo(rc.getLocation()).getPaint().isEnemy()) {
            move(rc, rc.getLocation(), true);
            return;
        }

        explore(rc, infos);
    }

    static MapLocation bestPatternBreak(MapInfo[] infos, RobotController rc) {
        MapLocation best = null;
        int bestCount = 0;
        for (MapInfo i : infos) {
            MapLocation center = i.getMapLocation();
            if (!rc.canAttack(center))
                continue;
            int count = 0;
            for (MapInfo m : infos) {
                int dx = center.x - m.getMapLocation().x;
                int dy = center.y - m.getMapLocation().y;
                if (dx < -1 || dx > 1 || dy < -1 || dy > 1)
                    continue;
                if (m.getMark() != PaintType.EMPTY && m.getPaint().isEnemy())
                    count += 2;
                else if (m.getPaint().isEnemy())
                    count++;
            }
            if (count > bestCount) {
                bestCount = count;
                best = center;
            }
        }
        return best;
    }

    static MapLocation bestSplash(MapInfo[] infos, RobotController rc) {
        MapLocation best = null;
        int bestCount = 0;
        for (MapInfo i : infos) {
            MapLocation center = i.getMapLocation();
            if (!rc.canAttack(center))
                continue;
            int count = 0;
            for (MapInfo m : infos) {
                int dx = center.x - m.getMapLocation().x;
                int dy = center.y - m.getMapLocation().y;
                if (dx < -1 || dx > 1 || dy < -1 || dy > 1)
                    continue;
                if (m.getPaint().isEnemy())
                    count++;
            }
            if (count > bestCount) {
                bestCount = count;
                best = center;
            }
        }
        return best;
    }

    static void move(RobotController rc, MapLocation tgt, boolean moveAway)
            throws GameActionException {
        if (tgt == null || !rc.isMovementReady())
            return;
        MapLocation cur = rc.getLocation();

        Direction best = null;
        Direction bestFallback = null;
        int bestDelta = Integer.MIN_VALUE;
        int bestDeltaFB = Integer.MIN_VALUE;

        for (Direction d : dirs) {
            if (!rc.canMove(d))
                continue;
            MapLocation next = cur.add(d);
            if (rc.canSenseLocation(next) && !rc.senseMapInfo(next).isPassable())
                continue;
            int distNow = cur.distanceSquaredTo(tgt);
            int distNext = next.distanceSquaredTo(tgt);
            int delta = moveAway ? (distNext - distNow) : (distNow - distNext);

            if (rc.canSenseLocation(next)) {
                PaintType pt = rc.senseMapInfo(next).getPaint();
                if (pt.isAlly())
                    delta++;
                if (pt.isEnemy())
                    delta -= 2;
            }

            if (delta > bestDeltaFB) {
                bestDeltaFB = delta;
                bestFallback = d;
            }

            if (robotPrevLocation != null && next.equals(robotPrevLocation))
                continue;
            if (robotLastMove != null && d == robotLastMove.opposite())
                continue;

            if (delta > bestDelta) {
                bestDelta = delta;
                best = d;
            }
        }
        if (best == null)
            best = bestFallback;

        MapLocation before = rc.getLocation();
        if (best != null && rc.canMove(best)) {
            robotPrevLocation = cur;
            rc.move(best);
            robotLastMove = best;
        }
        robotNoProgressTurns = before.equals(rc.getLocation()) ? robotNoProgressTurns + 1 : 0;
    }

    static void explore(RobotController rc, MapInfo[] infos) throws GameActionException {
        if (robotNoProgressTurns >= 3) {
            robotExploreTarget = null;
            robotExploreRefreshRound = -999;
            robotLastMove = null;
            robotPrevLocation = null;
            robotNoProgressTurns = 0;
        }

        MapLocation cur = rc.getLocation();
        boolean expired = rc.getRoundNum() - robotExploreRefreshRound > EXPLORE_TTL;
        boolean arrived = robotExploreTarget != null
                && cur.distanceSquaredTo(robotExploreTarget) <= 4;

        if (robotExploreTarget == null || expired || arrived) {
            MapLocation sym = (rc.getRoundNum() <= 80) ? getSymTarget(rc) : null;
            robotExploreTarget = (sym != null && cur.distanceSquaredTo(sym) > 16)
                    ? sym
                    : pickExploreTarget(rc);
            robotExploreRefreshRound = rc.getRoundNum();
        }

        move(rc, robotExploreTarget, false);

        if (rc.isActionReady() && Clock.getBytecodesLeft() > 3000) {
            MapLocation pt = bestPaintTile(rc, infos);
            if (pt != null && rc.canAttack(pt))
                rc.attack(pt);
        }
    }

    static MapLocation pickExploreTarget(RobotController rc) {
        int w = rc.getMapWidth(), h = rc.getMapHeight(), id = rc.getID();
        MapLocation[] waypoints = {
                new MapLocation(2, 2), new MapLocation(w - 3, 2),
                new MapLocation(2, h - 3), new MapLocation(w - 3, h - 3),
                new MapLocation(w / 2, 2), new MapLocation(w / 2, h - 3),
                new MapLocation(2, h / 2), new MapLocation(w - 3, h / 2),
                new MapLocation(w / 3, h / 3), new MapLocation(2 * w / 3, h / 3),
                new MapLocation(w / 3, 2 * h / 3), new MapLocation(2 * w / 3, 2 * h / 3)
        };

        if (robotExploreTarget != null
                && rc.getLocation().distanceSquaredTo(robotExploreTarget) <= 9)
            visitedAdd(robotExploreTarget);
        if (robotVisitedCount >= waypoints.length - 1)
            robotVisitedCount = 0;

        MapLocation bestInQuadrant = null, bestAny = null;
        int distInQuadrant = Integer.MAX_VALUE, distAny = Integer.MAX_VALUE;
        for (int i = 0; i < waypoints.length; i++) {
            if (visitedHas(waypoints[i]))
                continue;
            if (waypoints[i].equals(robotExploreTarget))
                continue;
            int dist = rc.getLocation().distanceSquaredTo(waypoints[i]);
            if (dist < distAny) {
                distAny = dist;
                bestAny = waypoints[i];
            }
            if ((i & 3) == (id & 3) && dist < distInQuadrant) {
                distInQuadrant = dist;
                bestInQuadrant = waypoints[i];
            }
        }
        if (bestInQuadrant != null)
            return bestInQuadrant;
        return bestAny != null ? bestAny : waypoints[id % waypoints.length];
    }

    static boolean visitedHas(MapLocation loc) {
        if (loc == null)
            return false;
        for (int i = 0; i < robotVisitedCount; i++)
            if (robotVisited[i].equals(loc))
                return true;
        return false;
    }

    static void visitedAdd(MapLocation loc) {
        if (loc == null || visitedHas(loc))
            return;
        if (robotVisitedCount < MAX_VISITED)
            robotVisited[robotVisitedCount++] = loc;
    }

    static MapLocation bestPaintTile(RobotController rc, MapInfo[] infos) {
        MapLocation bestEnemy = null, bestEmpty = null;
        int bestEnemyD = Integer.MAX_VALUE, bestEmptyD = Integer.MAX_VALUE;
        for (MapInfo i : infos) {
            MapLocation loc = i.getMapLocation();
            if (!rc.canAttack(loc) || !i.isPassable())
                continue;
            if (robotAssignedRuin != null && loc.distanceSquaredTo(robotAssignedRuin) <= 8)
                continue;
            int d = rc.getLocation().distanceSquaredTo(loc);
            if (i.getPaint().isEnemy() && d < bestEnemyD) {
                bestEnemyD = d;
                bestEnemy = loc;
            } else if (!i.getPaint().isAlly() && d < bestEmptyD) {
                bestEmptyD = d;
                bestEmpty = loc;
            }
        }
        return bestEnemy != null ? bestEnemy : bestEmpty;
    }

    static void refill(RobotController rc) throws GameActionException {
        MapLocation tower = null;
        int bestDist = Integer.MAX_VALUE;
        for (RobotInfo a : rc.senseNearbyRobots(-1, rc.getTeam())) {
            if (!a.getType().isTowerType())
                continue;
            boolean isPaintTower = a.getType() == UnitType.LEVEL_ONE_PAINT_TOWER
                    || a.getType() == UnitType.LEVEL_TWO_PAINT_TOWER
                    || a.getType() == UnitType.LEVEL_THREE_PAINT_TOWER;
            int d = rc.getLocation().distanceSquaredTo(a.location);
            if (isPaintTower)
                d /= 2;
            if (d < bestDist) {
                bestDist = d;
                tower = a.location;
            }
        }
        if (tower == null) {
            explore(rc, rc.senseNearbyMapInfos());
            return;
        }
        if (rc.getLocation().distanceSquaredTo(tower) > 2) {
            move(rc, tower, false);
            return;
        }
        int need = rc.getType().paintCapacity - rc.getPaint();
        if (need > 0 && rc.canTransferPaint(tower, -need))
            rc.transferPaint(tower, -need);
        else if (rc.isMovementReady())
            move(rc, tower, false);
    }

    static void buildTower(RobotController rc, MapLocation ruin) throws GameActionException {
        if (ruin == null)
            return;
        if (rc.getPaint() < PAINT_BUILD_LOCKED) {
            robotBuildingLocked = false;
            refill(rc);
            return;
        }

        int idx = findClaimIdx(ruin);
        UnitType towerType;
        if (idx >= 0 && claimType[idx] != null) {
            towerType = claimType[idx];
        } else {
            towerType = chooseTowerType(rc, ruin);
            if (idx >= 0)
                claimType[idx] = towerType;
        }

        if (rc.getLocation().distanceSquaredTo(ruin) > 2) {
            move(rc, ruin, false);
            return;
        }
        if (rc.senseRobotAtLocation(ruin) != null) {
            releaseRuin(rc, ruin);
            return;
        }

        if (rc.canCompleteTowerPattern(towerType, ruin)) {
            rc.completeTowerPattern(towerType, ruin);
            releaseRuin(rc, ruin);
            return;
        }

        boolean alreadyMarked = false;
        for (MapInfo i : rc.senseNearbyMapInfos()) {
            MapLocation l = i.getMapLocation();
            if (!l.equals(ruin) && l.distanceSquaredTo(ruin) <= 8 && i.getMark() != PaintType.EMPTY) {
                alreadyMarked = true;
                break;
            }
        }
        if (!alreadyMarked) {
            if (rc.canMarkTowerPattern(towerType, ruin)) {
                rc.markTowerPattern(towerType, ruin);
                if (rc.isActionReady() && Clock.getBytecodesLeft() > 2000)
                    paintTiles(rc, ruin, towerType);
            }
            return;
        }
        if (rc.isActionReady() && Clock.getBytecodesLeft() > 2000)
            paintTiles(rc, ruin, towerType);
    }

    static void paintTiles(RobotController rc, MapLocation ruin, UnitType towerType)
            throws GameActionException {
        if (!rc.isActionReady()) {
            if (rc.getLocation().distanceSquaredTo(ruin) > 2)
                move(rc, ruin, false);
            return;
        }
        MapLocation toFill = null;
        int bestDist = Integer.MAX_VALUE;
        boolean useSecondary = false;
        for (MapInfo i : rc.senseNearbyMapInfos()) {
            MapLocation l = i.getMapLocation();
            if (l.distanceSquaredTo(ruin) > 8 || l.equals(ruin) || !i.isPassable())
                continue;
            PaintType mark = i.getMark(), paint = i.getPaint();
            if (mark == PaintType.EMPTY || paint == mark)
                continue;
            int d = rc.getLocation().distanceSquaredTo(l);
            if (d < bestDist) {
                bestDist = d;
                toFill = l;
                useSecondary = (mark == PaintType.ALLY_SECONDARY);
            }
        }
        if (toFill == null) {
            if (rc.canCompleteTowerPattern(towerType, ruin)) {
                rc.completeTowerPattern(towerType, ruin);
                releaseRuin(rc, ruin);
            } else if (rc.getLocation().distanceSquaredTo(ruin) > 2)
                move(rc, ruin, false);
            else
                releaseRuin(rc, ruin);
            return;
        }
        if (rc.canAttack(toFill))
            rc.attack(toFill, useSecondary);
        else
            move(rc, toFill, false);
    }

    static UnitType chooseTowerType(RobotController rc, MapLocation ruin) throws GameActionException {
        if (rc.getRoundNum() < 120)
            return UnitType.LEVEL_ONE_MONEY_TOWER;

        int paintTowers = 0, moneyTowers = 0;
        for (RobotInfo a : rc.senseNearbyRobots(-1, rc.getTeam())) {
            UnitType t = a.getType();
            if (t == UnitType.LEVEL_ONE_PAINT_TOWER || t == UnitType.LEVEL_TWO_PAINT_TOWER
                    || t == UnitType.LEVEL_THREE_PAINT_TOWER)
                paintTowers++;
            if (t == UnitType.LEVEL_ONE_MONEY_TOWER || t == UnitType.LEVEL_TWO_MONEY_TOWER
                    || t == UnitType.LEVEL_THREE_MONEY_TOWER)
                moneyTowers++;
        }
        if (paintTowers == 0)
            return UnitType.LEVEL_ONE_PAINT_TOWER;

        int wallCount = 0;
        for (Direction d : new Direction[] { Direction.NORTH, Direction.EAST,
                Direction.SOUTH, Direction.WEST }) {
            MapLocation n = ruin.add(d);
            if (!rc.canSenseLocation(n) || !rc.senseMapInfo(n).isPassable())
                wallCount++;
        }
        if (wallCount >= 2)
            return UnitType.LEVEL_ONE_DEFENSE_TOWER;

        return moneyTowers > paintTowers ? UnitType.LEVEL_ONE_PAINT_TOWER
                : UnitType.LEVEL_ONE_MONEY_TOWER;
    }

    static int findClaimIdx(MapLocation r) {
        for (int i = 0; i < claimCount; i++)
            if (claimLoc[i] != null && claimLoc[i].equals(r))
                return i;
        return -1;
    }

    static void cleanClaims(RobotController rc) {
        int now = rc.getRoundNum();
        for (int i = 0; i < claimCount; i++) {
            if (claimLoc[i] == null || now - claimRound[i] <= CLAIM_TIMEOUT)
                continue;
            claimLoc[i] = claimLoc[--claimCount];
            claimOwner[i] = claimOwner[claimCount];
            claimRound[i] = claimRound[claimCount];
            claimType[i] = claimType[claimCount];
            claimLoc[claimCount] = null;
            i--;
        }
    }

    static boolean canClaim(RobotController rc, MapLocation r) {
        int i = findClaimIdx(r);
        return i < 0 || rc.getRoundNum() - claimRound[i] > CLAIM_TIMEOUT
                || claimOwner[i] == rc.getID();
    }

    static void claimRuin(RobotController rc, MapLocation r) {
        int i = findClaimIdx(r);
        if (i < 0) {
            if (claimCount >= MAX_CLAIMS)
                return;
            i = claimCount++;
        }
        claimLoc[i] = r;
        claimOwner[i] = rc.getID();
        claimRound[i] = rc.getRoundNum();
        robotAssignedRuin = r;
    }

    static void releaseRuin(RobotController rc, MapLocation r) {
        if (r == null)
            return;
        int i = findClaimIdx(r);
        if (i >= 0 && claimOwner[i] == rc.getID()) {
            claimLoc[i] = claimLoc[--claimCount];
            claimOwner[i] = claimOwner[claimCount];
            claimRound[i] = claimRound[claimCount];
            claimType[i] = claimType[claimCount];
            claimLoc[claimCount] = null;
        }
        robotAssignedRuin = null;
        robotBuildingLocked = false;
    }

    static MapLocation ruinWithEnemyPaint(MapInfo[] infos) {
        for (MapInfo i : infos) {
            if (!i.hasRuin() || findClaimIdx(i.getMapLocation()) < 0)
                continue;
            for (MapInfo j : infos)
                if (j.getMapLocation().distanceSquaredTo(i.getMapLocation()) <= 8
                        && j.getPaint().isEnemy())
                    return i.getMapLocation();
        }
        return null;
    }

    static MapLocation findNearbyRuin(RobotController rc, MapInfo[] infos, RobotInfo[] allies)
            throws GameActionException {
        cleanClaims(rc);
        MapLocation best = null;
        int closestDist = Integer.MAX_VALUE;
        for (MapInfo info : infos) {
            if (!info.hasRuin())
                continue;
            MapLocation ruin = info.getMapLocation();
            if (!canClaim(rc, ruin) || rc.senseRobotAtLocation(ruin) != null)
                continue;
            int myDist = rc.getLocation().distanceSquaredTo(ruin);
            boolean closerAllyExists = false;
            for (RobotInfo a : allies)
                if (a.getType() == UnitType.SOLDIER && a.location.distanceSquaredTo(ruin) < myDist) {
                    closerAllyExists = true;
                    break;
                }
            if (closerAllyExists)
                continue;
            if (myDist < closestDist) {
                closestDist = myDist;
                best = ruin;
            }
        }
        if (best != null)
            claimRuin(rc, best);
        return best;
    }

    static MapLocation findEnemyRuin(RobotController rc, MapInfo[] infos)
            throws GameActionException {
        MapLocation best = null;
        int mostProgress = 0;
        for (MapInfo info : infos) {
            if (!info.hasRuin())
                continue;
            MapLocation ruin = info.getMapLocation();
            if (rc.senseRobotAtLocation(ruin) != null)
                continue;
            if (robotAssignedRuin != null && robotAssignedRuin.equals(ruin))
                continue;
            int enemyPaint = 0, marks = 0, allyPaint = 0;
            for (MapInfo j : infos) {
                MapLocation l = j.getMapLocation();
                if (l.equals(ruin) || l.distanceSquaredTo(ruin) > 8)
                    continue;
                if (j.getPaint().isEnemy())
                    enemyPaint++;
                if (j.getMark() != PaintType.EMPTY)
                    marks++;
                if (j.getPaint().isAlly())
                    allyPaint++;
            }
            int progress = marks * 2 + enemyPaint - allyPaint;
            if (progress >= 1 && progress > mostProgress) {
                mostProgress = progress;
                best = ruin;
            }
        }
        return best;
    }

    static RobotInfo nearestEnemy(RobotController rc, RobotInfo[] enemies, boolean wantTower) {
        RobotInfo best = null;
        int bestDist = Integer.MAX_VALUE;
        for (RobotInfo e : enemies) {
            if (e.getType().isTowerType() != wantTower)
                continue;
            int d = rc.getLocation().distanceSquaredTo(e.location);
            if (d < bestDist) {
                bestDist = d;
                best = e;
            }
        }
        return best;
    }
}