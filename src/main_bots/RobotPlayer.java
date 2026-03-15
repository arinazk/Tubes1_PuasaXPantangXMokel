package main-bots;

import battlecode.common.*;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.ArrayList;

import org.apache.lucene.document.MapFieldSelector;
import org.hibernate.dialect.lock.LockingStrategy;


/**
 * RobotPlayer is the class that describes your main robot strategy.
 * The run() method inside this class is like your main function: this is what we'll call once your robot
 * is created!
 */
public class RobotPlayer {
    /**
     * We will use this variable to count the number of turns this robot has been alive.
     * You can use static variables like this to save any information you want. Keep in mind that even though
     * these variables are static, in Battlecode they aren't actually shared between your robots.
     */
    static int turnCount = 0;

    /**
     * A random number generator.
     * We will use this RNG to make some random moves. The Random class is provided by the java.util.Random
     * import at the top of this file. Here, we *seed* the RNG with a constant number (6147); this makes sure
     * we get the same sequence of numbers every time this code is run. This is very useful for debugging!
     */
    static final Random rng = new Random(6147);

    /** Array containing all the possible movement directions. */
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

    
    /**
     * run() is the method that is called when a robot is instantiated in the Battlecode world.
     * It is like the main function for your robot. If this method returns, the robot dies!
    *
    * @param rc  The RobotController object. You use it to perform actions from this robot, and to get
    *            information on its current status. Essentially your portal to interacting with the world.
    **/
    @SuppressWarnings("unused")
    public static void run(RobotController rc) throws GameActionException {
        System.out.println("I'm alive");
        
        while (true) {
            turnCount += 1;
            try {
                switch (rc.getType()){
                    case SOLDIER: runSoldier(rc); break; 
                    case MOPPER: runMopper(rc); break;
                    case SPLASHER: runSplasher(rc); break;
                    default: runTower(rc); break;
                    }
                }
             catch (GameActionException e) {
                System.out.println("GameActionException");
                e.printStackTrace();
                
            } catch (Exception e) {
                System.out.println("Exception");
                e.printStackTrace();
                
            } finally {
                Clock.yield();
            }
        }
    }
    
    /**
     * Run a single turn for towers.
     * This code is wrapped inside the infinite loop in run(), so it is called once per turn.
    */
    private static int botSpawnDir = 0;
    private static int spawnedBot = 0;
    private static int towerRoundAge = 0;
    private static int messageStreak = 0;
    private static MapLocation enemyLoc = null;
    public static void runTower(RobotController rc) throws GameActionException{  
        towerRoundAge++;

        boolean mustSpawnMopper = false;
        boolean mustSpawnSplasher = false;
        Message[] messages = rc.readMessages(rc.getRoundNum() - 1);
        for (Message m : messages) {
            System.out.println("TOWER RECEIVES MESSAGE! " + m.getBytes());
            if(enemyLoc != null) messageStreak++;
            if(messageStreak == 3){ 
                mustSpawnMopper = true;
                messageStreak = 0;
            }
            enemyLoc = new MapLocation(m.getBytes() >> 8, m.getBytes() & 0xFF);
        }

        MapInfo[] nearbyTiles = rc.senseNearbyMapInfos();
        boolean[][] blocked = blockedByWall(rc, nearbyTiles, rc.getLocation());
        int enemyTile = 0;
        for(MapInfo tile : nearbyTiles){
            if(tile.getPaint().isEnemy() && !blocked[tile.getMapLocation().y + 4 - rc.getLocation().y][tile.getMapLocation().x + 4 - rc.getLocation().x]){
                enemyLoc = tile.getMapLocation();
                mustSpawnMopper = true;
                enemyTile++;
                if(enemyTile > 12) mustSpawnSplasher = true;
            }
        }

        RobotInfo[] nearbyBots = rc.senseNearbyRobots();
        MapLocation nearestEnemyBot = null;
        int enemyCount = 0;
        for(RobotInfo bots : nearbyBots){
            if(bots.getTeam() != rc.getTeam()){
                enemyCount++;
                if(nearestEnemyBot == null) nearestEnemyBot = bots.getLocation();
                else if(rc.getLocation().distanceSquaredTo(bots.getLocation()) < rc.getLocation().distanceSquaredTo(nearestEnemyBot)){
                    nearestEnemyBot = bots.getLocation();
                }
            }
        }
        Direction dir = directions[(botSpawnDir++) % 8];
        MapLocation nextLoc = rc.getLocation().add(dir);
        int towerCount = rc.getNumberTowers();
        boolean spawnBot = true;
        if(towerCount > 6){
            int turnOffset = rc.getID() % towerCount;
            
            if(rc.getRoundNum() % towerCount != turnOffset) spawnBot = false;
        }
        if(spawnBot){
            int robotType = 0;
            if((spawnedBot % 5 == 0 && spawnedBot != 0) || mustSpawnMopper) robotType = 1;
            if(mustSpawnSplasher) robotType = 2;
            if((spawnedBot % 15 == 0 && spawnedBot != 0) && rc.getRoundNum() > 1500) robotType = 2;
            if (robotType == 0 && rc.canBuildRobot(UnitType.SOLDIER, nextLoc)){
                rc.buildRobot(UnitType.SOLDIER, nextLoc);
                System.out.println("BUILT A SOLDIER at " + dir);
                spawnedBot++;
            }
            else if (robotType == 1 && rc.canBuildRobot(UnitType.MOPPER, nextLoc)){
                rc.buildRobot(UnitType.MOPPER, nextLoc);
                System.out.println("BUILT A MOPPER");
                spawnedBot++;
                if(rc.canSendMessage(nextLoc) && enemyLoc != null){
                    rc.sendMessage(nextLoc, (enemyLoc.x << 8) + (enemyLoc.y & 0xFF));
                    messageStreak = 0;
                    enemyLoc = null;
                }
            }
            else if (robotType == 2 && rc.canBuildRobot(UnitType.SPLASHER, nextLoc)){
                rc.buildRobot(UnitType.SPLASHER, nextLoc);
                System.out.println("BUILT A SPLASHER");
            }
        }
        if(nearestEnemyBot != null && rc.isActionReady()){
            if(enemyCount > 1) rc.attack(null);
            if(rc.canAttack(nearestEnemyBot) && enemyCount < 3) rc.attack(nearestEnemyBot);
        }
        if(towerRoundAge % 500 == 0){
            if(rc.canUpgradeTower(rc.getLocation())){
                rc.upgradeTower(rc.getLocation());
                rc.setTimelineMarker("TOWER UPGRADED", 0, 0, 255);
                System.out.println("TOWER ID " + rc.getID() + "UPGRADED!");
            }
        }
    }
    
    
    /**
     * Run a single turn for a Soldier.
     * This code is wrapped inside the infinite loop in run(), so it is called once per turn.
    */
    private static MapLocation[] endPoint = new MapLocation[4];
    private static int corner;
    private static MapLocation enemyTileLoc = null;
    private static MapLocation nearestTower = null;
    private static int botState = 0;
    public static void runSoldier(RobotController rc) throws GameActionException{
        // MapInfo[] nearbyTiles = rc.senseNearbyMapInfos();
        // RobotInfo[] nearbyBots = rc.senseNearbyRobots();
        // Message[] messages = rc.readMessages(-1);
        // int message;
        // if(messages.length == 0) message = 0; else message = messages[0].getBytes();
        
        // if(paintTile != null && paintTile.getPaint().isAlly()) paintTile = null;
        // if(goTo == null) goTo = rc.getLocation().directionTo(new MapLocation(rc.getMapWidth()/2, rc.getMapHeight()/2))

        MapLocation currentLoc = rc.getLocation();
        MapInfo[] surroundTile = rc.senseNearbyMapInfos();
        MapInfo curRuin = null;
        if(endPoint[0] == null){
            initEndPoint(rc, currentLoc);
        }
        
        boolean hasAttacked = false;
        if(rc.isActionReady()){
            for (MapInfo tile : surroundTile){
                MapLocation tileLoc = tile.getMapLocation();
                if (tile.getMark() != tile.getPaint() && tile.getMark() != PaintType.EMPTY && rc.canAttack(tileLoc)){
                    boolean useSecondaryColor = tile.getMark() == PaintType.ALLY_SECONDARY;
                    rc.attack(tileLoc, useSecondaryColor);
                    hasAttacked = true;
                    break;
                }
                if (tile.getPaint() == PaintType.EMPTY && rc.canAttack(tileLoc) && tileLoc.isWithinDistanceSquared(currentLoc, 2)){ 
                    rc.attack(tileLoc);
                    hasAttacked = true;
                    break;
                }
            }
        }
        if(rc.isMovementReady()){
            MapLocation destination = null;
            int minDistance = Integer.MAX_VALUE;
            boolean[][] blocked = blockedByWall(rc,surroundTile,currentLoc);
            for (MapInfo tile : surroundTile){
                MapLocation tileLoc = tile.getMapLocation();
                if(rc.canSenseRobotAtLocation(tileLoc)){
                    RobotInfo bot = rc.senseRobotAtLocation(tileLoc);
                    if(bot.getTeam() != rc.getTeam() && rc.isActionReady() && rc.canAttack(tileLoc) && !hasAttacked){
                        rc.attack(tileLoc);
                    }
                    if(bot.getType().isTowerType() && bot.getTeam() == rc.getTeam()){
                        nearestTower = tile.getMapLocation();
                        continue;
                    }
                }
                if(tile.hasRuin()){
                    curRuin = tile;
                    continue;
                } 
                if(tile.isWall()) continue;
                if(tile.getPaint().isEnemy()){
                    enemyTileLoc = tileLoc;
                    if(destination == null || (currentLoc.distanceSquaredTo(destination) > currentLoc.distanceSquaredTo(enemyTileLoc))) if(!blocked[tileLoc.y + 4 - currentLoc.y][tileLoc.x + 4 - currentLoc.x]) botState = 1;
                }
                if(tile.getPaint() == PaintType.EMPTY && tile.isPassable() && rc.canMove(currentLoc.directionTo(tileLoc)) && !blocked[tileLoc.y + 4 - currentLoc.y][tileLoc.x + 4 - currentLoc.x]){
                    if(tile.getMark() != PaintType.EMPTY){
                        destination = tileLoc;
                        break;
                    }
                    int dist = currentLoc.distanceSquaredTo(tileLoc);
                    if(dist < minDistance){
                        minDistance = dist;
                        destination = tile.getMapLocation();
                    }
                }
            }

            if(curRuin != null){
                MapLocation targetLoc = curRuin.getMapLocation();
                if(currentLoc.distanceSquaredTo(targetLoc) <= 4){
                    markAndBuildTower(rc, currentLoc, targetLoc);
                }
                else destination = targetLoc;
            }
            
            if(botState == 0) {
                robotMove(rc, currentLoc, destination);
            }
            else if(botState == 1){
                int message = (enemyTileLoc.x << 8) + (enemyTileLoc.y & 0xFF);
                boolean sendSuccess = sendMessageToTower(rc, message);
                if(sendSuccess){ 
                    botState = 0;
                    robotMove(rc, currentLoc, destination);
                }
                else robotMove(rc, currentLoc, nearestTower);
            }
        }
    }
    
    
    /**
     * Run a single turn for a Mopper.
     * This code is wrapped inside the infinite loop in run(), so it is called once per turn.
    */
   
    public static void runMopper(RobotController rc) throws GameActionException{
        MapLocation currentLoc = rc.getLocation();
        if(endPoint[0] == null) initEndPoint(rc, currentLoc);
        Message[] messages = rc.readMessages(-1);
        RobotInfo[] nearbyBots = rc.senseNearbyRobots();
        MapInfo[] nearbyTiles = rc.senseNearbyMapInfos();
        
        for(Message m : messages){
            enemyTileLoc = new MapLocation(m.getBytes() >> 8, m.getBytes() & 0xFF);
            System.out.println("MOPPER RECEIVES MESSAGE! " + m.getBytes());
        }

        if(enemyTileLoc != null && currentLoc.isWithinDistanceSquared(enemyTileLoc, 20)) enemyTileLoc = null;
        
        boolean hasAttacked = false;
        boolean enemyTileNearby = false;
        boolean[][] blocked = blockedByWall(rc, nearbyTiles, currentLoc);
        for(MapInfo tile : nearbyTiles){
            MapLocation tileLoc = tile.getMapLocation();
            if (tile.getPaint().isEnemy() && rc.isActionReady() && tileLoc.isWithinDistanceSquared(currentLoc, 2) && !hasAttacked){ 
                if(rc.canAttack(tileLoc)){ rc.attack(tileLoc); hasAttacked = true; }
                else enemyTileNearby = true;
            }
            if(enemyTileLoc == null && tile.getPaint().isEnemy() && !tileLoc.isWithinDistanceSquared(currentLoc, 2) && !blocked[tileLoc.y + 4 - currentLoc.y][tileLoc.x + 4 - currentLoc.x]){
                enemyTileLoc = tileLoc;
            }
        }
        if(rc.isMovementReady() && !enemyTileNearby) robotMove(rc, currentLoc, enemyTileLoc);
        
        int lowestPaint = Integer.MAX_VALUE;
        MapLocation lowestPaintBot = null;
        for(RobotInfo bots : nearbyBots){
            MapLocation botLoc = bots.getLocation();
            if(bots.getTeam().equals(rc.getTeam()) && botLoc.isWithinDistanceSquared(currentLoc, 2)){
                if(bots.getPaintAmount() < lowestPaint){
                    lowestPaint = bots.getPaintAmount();
                    lowestPaintBot = botLoc;
                }
            }
            else if(bots.getTeam().equals(rc.getTeam().opponent()) && bots.getType().isRobotType() && botLoc.isWithinDistanceSquared(currentLoc, 2)){
                Direction dir = currentLoc.directionTo(botLoc);
                if(rc.isActionReady() && rc.canMopSwing(dir)){
                    rc.mopSwing(dir);
                    break;
                }
            }
        }
        if(lowestPaintBot != null && rc.canTransferPaint(lowestPaintBot, 10) && rc.isActionReady()){
            rc.transferPaint(lowestPaintBot, 10);
        }
    }

    public static void runSplasher(RobotController rc) throws GameActionException{
        MapLocation currentLoc = rc.getLocation();
        if(endPoint[0] == null) initEndPoint(rc, currentLoc);
        MapInfo[] nearbyTiles = rc.senseNearbyMapInfos();
        boolean[][] blocked = blockedByWall(rc, nearbyTiles, currentLoc);
        int enemyTileCount = 0;
        for(MapInfo tile : nearbyTiles){
            MapLocation tileLoc = tile.getMapLocation();
            if (tile.getPaint().isEnemy() && rc.isActionReady() && tileLoc.isWithinDistanceSquared(currentLoc, 2)){ 
                enemyTileCount++;
            }
            if(enemyTileLoc == null && tile.getPaint().isEnemy() && !tileLoc.isWithinDistanceSquared(currentLoc, 2) && !blocked[tileLoc.y + 4 - currentLoc.y][tileLoc.x + 4 - currentLoc.x]){
                enemyTileLoc = tileLoc;
            }
            if(rc.canSenseRobotAtLocation(tileLoc)){
                RobotInfo bot = rc.senseRobotAtLocation(tileLoc);
                if(bot.getType().isTowerType() && bot.getTeam() == rc.getTeam()){
                    nearestTower = tile.getMapLocation();
                    continue;
                }
            }
        }
        if(enemyTileCount > 6 && rc.canAttack(currentLoc)) rc.attack(currentLoc);
        if(rc.isMovementReady()) robotMove(rc, currentLoc, enemyTileLoc);
    }

    public static void initEndPoint(RobotController rc, MapLocation currentLoc){
        corner = rc.getID() % 2;
        if(rc.getMapHeight() > 2 * rc.getMapWidth()){
            endPoint[0] = new MapLocation(currentLoc.x, rc.getMapHeight() - 1 - currentLoc.y);
            endPoint[1] = new MapLocation(currentLoc.x, rc.getMapHeight() - 1 - currentLoc.y);
        }
        else if(rc.getMapWidth() > 2 * rc.getMapHeight()){
            endPoint[0] = new MapLocation(rc.getMapWidth() - 1 - currentLoc.x, currentLoc.y);
            endPoint[1] = new MapLocation(rc.getMapWidth() - 1 - currentLoc.x, currentLoc.y);
        } else {
            endPoint[0] = new MapLocation(rc.getMapWidth() - 1 - currentLoc.x, currentLoc.y);
            endPoint[1] = new MapLocation(currentLoc.x, rc.getMapHeight() - 1 - currentLoc.y);
        }
        endPoint[2] = new MapLocation(rc.getMapWidth() / 2, rc.getMapHeight() / 2);
        endPoint[3] = new MapLocation(rc.getMapWidth() - 1 - currentLoc.x, rc.getMapHeight() - 1 - currentLoc.y);
    }

    private static UnitType buildWhat = null;
    private static final UnitType[] buildBrute = {UnitType.LEVEL_ONE_MONEY_TOWER, UnitType.LEVEL_ONE_PAINT_TOWER, UnitType.LEVEL_ONE_DEFENSE_TOWER};
    public static void markAndBuildTower(RobotController rc, MapLocation currentLoc, MapLocation targetLoc) throws GameActionException{
        Direction dir = currentLoc.directionTo(targetLoc);
        MapLocation shouldBeMarked = targetLoc.subtract(dir);
        if(buildWhat == null) buildWhat = nextTowerType(rc, targetLoc);
        if (rc.senseMapInfo(shouldBeMarked).getMark() == PaintType.EMPTY && rc.canMarkTowerPattern(buildWhat, targetLoc)){
            rc.markTowerPattern(buildWhat, targetLoc);
            System.out.println("MARK RUINS AT " + targetLoc);
        }
        for(UnitType towerType : buildBrute){
            if (rc.canCompleteTowerPattern(towerType, targetLoc)){
                rc.completeTowerPattern(towerType, targetLoc);
                rc.setTimelineMarker("TOWER BUILT", 0, 255, 0);
                System.out.println("BUILT A TOWER AT " + targetLoc + "!");
            }
        }
    }

    public static UnitType nextTowerType(RobotController rc, MapLocation ruinLocation){
        MapLocation midPoint = endPoint[2];
        int relativeDistance;
        if(rc.getMapHeight() > rc.getMapWidth()) relativeDistance = rc.getMapHeight() / 2;
        else relativeDistance = rc.getMapWidth() / 2;
        if(ruinLocation.distanceSquaredTo(midPoint) < relativeDistance && rc.getNumberTowers() > 5)
            return UnitType.LEVEL_ONE_DEFENSE_TOWER;
        return rc.getNumberTowers() % 2 == 0 ? UnitType.LEVEL_ONE_MONEY_TOWER : UnitType.LEVEL_ONE_PAINT_TOWER;
    }

    public static void robotMove(RobotController rc, MapLocation currentLoc, MapLocation destination) throws GameActionException{
        Direction goTo = null;
        if(destination != null){ 
            goTo = currentLoc.directionTo(destination);
            System.out.println("ROBOT ID " + rc.getID() + " MOVE TO (" + destination.x + ", " + destination.y + ")");
        }
        if(destination == null){
            if(currentLoc.distanceSquaredTo(endPoint[corner]) <= 20){
                if(corner == 2) corner = 3;
                else corner = 2;
            }
            goTo = currentLoc.directionTo(endPoint[corner]);
        }
        if(goTo != null && goTo != Direction.CENTER){
            for(int i = 0; i < 8 && !rc.canMove(goTo); i++){
                goTo = goTo.rotateRight();
            }
            if (rc.canMove(goTo)) {
                rc.move(goTo);
            }
        }
    }

    public static boolean[][] blockedByWall(RobotController rc, MapInfo[] nearbyTiles, MapLocation currentLoc){
        boolean[][] isWall = new boolean[9][9];
        boolean[][] blocked = new boolean[9][9];
        for(int i = 0; i < 9; i++) Arrays.fill(isWall[i], true);
        int lowestX = currentLoc.x - 4;
        int lowestY = currentLoc.y - 4;
        boolean[] verWall = new boolean[9];
        boolean[] horWall = new boolean[9];
        for(MapInfo tile : nearbyTiles){
            MapLocation tileLoc = tile.getMapLocation();
            if(!tile.isWall()){
                isWall[tileLoc.y - lowestY][tileLoc.x - lowestX] = false;
            }
        }
        for(int i = 0; i < 9; i++){
            int j, end;
            if(i == 0 || i == 8){
                j = 2;
                end = 7;
            }
            else if(i == 1 || i == 7){
                j = 1;
                end = 8;
            }
            else{
                j = 0;
                end = 9;
            }
            boolean possibleWall = true;
            while(j < end && possibleWall){
                if(!isWall[j][i]) possibleWall = false;
                j++;
            }
            if(possibleWall){
                verWall[i] = true;
                for(int k = 0; k < 9; k++){
                    if(i > 4){
                        if(i < 8) blocked[k][8] = true;
                        if(i < 7) blocked[k][7] = true;
                        if(i < 6) blocked[k][6] = true;
                    }
                    if(i < 4){
                        if(i > 0) blocked[k][0] = true;
                        if(i > 1) blocked[k][1] = true;
                        if(i > 2) blocked[k][2] = true;
                    }
                }
            }
        }
        for(int i = 0; i < 9; i++){
            int j, end;
            if(i == 0 || i == 8){
                j = 2;
                end = 7;
            }
            else if(i == 1 || i == 7){
                j = 1;
                end = 8;
            }
            else{
                j = 0;
                end = 9;
            }
            boolean possibleWall = true;
            while(j < end && possibleWall){
                if(!isWall[i][j]) possibleWall = false;
                j++;
            }
            if(possibleWall){
                horWall[i] = true;
                if(i < 4){
                    if(i > 0) Arrays.fill(blocked[0], true);
                    if(i > 1) Arrays.fill(blocked[1], true);
                    if(i > 2) Arrays.fill(blocked[2], true);
                }
                if(i > 4){
                    if(i < 8) Arrays.fill(blocked[8], true);
                    if(i < 7) Arrays.fill(blocked[7], true);
                    if(i < 6) Arrays.fill(blocked[6], true);
                }
            }
        }
        return blocked;
    }

    public static boolean sendMessageToTower(RobotController rc, int message) throws GameActionException{
        RobotInfo[] nearbyBots = rc.senseNearbyRobots(-1, rc.getTeam());
        boolean sent = false;
        for(RobotInfo bots : nearbyBots){
            MapLocation botLoc = bots.getLocation();
            if(bots.getType().isTowerType() && bots.getTeam().equals(rc.getTeam())){
                if(rc.canSendMessage(botLoc, message)){ 
                    rc.sendMessage(botLoc, message);
                    sent = true;
                }
            }
        } return sent;
    }
}
