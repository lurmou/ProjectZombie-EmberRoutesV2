package emberroutes.npc.brain;

import emberroutes.npc.EmberTargetProjector;
import emberroutes.npc.IsoNPC;
import emberroutes.npc.TacticalRole;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import org.joml.Vector3f;
import zombie.characters.AttachedItems.AttachedItem;
import zombie.characters.AttachedItems.AttachedItems;
import zombie.characters.IsoGameCharacter;
import zombie.characters.IsoPlayer;
import zombie.characters.skills.PerkFactory;
import zombie.inventory.InventoryItem;
import zombie.inventory.types.HandWeapon;
import zombie.iso.IsoCell;
import zombie.iso.IsoGridSquare;
import zombie.iso.Vector2;
import zombie.scripting.objects.ItemTag;
import zombie.scripting.objects.VehicleScript;
import zombie.vehicles.BaseVehicle;
import zombie.vehicles.VehicleDoor;
import zombie.vehicles.VehiclePart;

/**
 * 载具协同纯原版几何、门锁、安全脱车与随从职能判定助手（100% 调原版 {@link BaseVehicle} Java 接口）。
 */
public final class VehicleCoRideHelper {
    public record ApproachTarget(float x, float y, int z, boolean useDoor2) {}

    private VehicleCoRideHelper() {}

    /** 计算车体包围圆近似半径（用于溢出警戒保持在车辆碰撞框之外）。 */
    public static float computeVehicleSafeRadius(BaseVehicle veh) {
        if (veh == null || veh.getScript() == null || veh.getScript().getExtents() == null) {
            return 2.40F;
        }
        Vector3f ext = veh.getScript().getExtents();
        float halfDiag = (float) Math.hypot(ext.x * 0.5F, ext.z * 0.5F);
        return Math.max(1.80F, halfDiag + 0.65F);
    }

    /** 校验两处坐标及车门状态决定最优登车门外接近点（优先使用原版 {@code areaPositionWorld4PlayerInteract}）。 */
    public static ApproachTarget resolveDoorApproachPoint(
            BaseVehicle veh, int seat, IsoNPC npc, IsoPlayer leader) {
        if (veh == null || seat < 0 || seat >= veh.getMaxPassengers()) {
            return null;
        }
        Vector3f out1 = getPositionWorld(veh, seat, "outside");
        Vector3f out2 = getPositionWorld(veh, seat, "outside2");
        boolean clear1 = out1 != null && !veh.isEnterBlocked(npc, seat)
                && canAccessOrUnlockDoor(veh, veh.getPassengerDoor(seat), npc, leader);
        boolean clear2 = out2 != null && !veh.isEnterBlocked2(npc, seat)
                && canAccessOrUnlockDoor(veh, veh.getPassengerDoor2(seat), npc, leader);

        boolean useDoor2 = false;
        Vector3f chosen = out1;
        if (clear2 && !clear1) {
            chosen = out2;
            useDoor2 = true;
        } else if (clear1 && clear2 && npc != null) {
            float d1 = distSq(npc.getX(), npc.getY(), out1.x, out1.y);
            float d2 = distSq(npc.getX(), npc.getY(), out2.x, out2.y);
            if (d2 < d1) {
                chosen = out2;
                useDoor2 = true;
            }
        } else if (!clear1 && chosen == null && out2 != null) {
            chosen = out2;
            useDoor2 = true;
        }
        if (chosen == null) {
            return null;
        }
        EmberTargetProjector.ProjectedPoint proj =
                EmberTargetProjector.projectGoal(npc, chosen.x, chosen.y, (int) Math.floor(veh.getZ()));
        if (proj != null) {
            return new ApproachTarget(proj.x(), proj.y(), proj.z(), useDoor2);
        }
        return new ApproachTarget(chosen.x, chosen.y, EmberTargetProjector.levelOf(veh.getZ()), useDoor2);
    }

    /** 1:1 复现 {@code BaseVehicle.java:6686-6694} 门外交互坐标求解。 */
    public static Vector3f getPositionWorld(BaseVehicle veh, int seat, String posId) {
        if (veh == null) {
            return null;
        }
        VehicleScript.Position pos = veh.getPassengerPosition(seat, posId);
        if (pos == null) {
            return null;
        }
        Vector3f out = new Vector3f();
        veh.getPassengerPositionWorldPos(pos, out);
        if (pos.area != null && veh.getScript() != null) {
            VehicleScript.Area area = veh.getScript().getAreaById(pos.area);
            if (area != null) {
                Vector2 areaPos = veh.areaPositionWorld4PlayerInteract(area);
                out.x = areaPos.x;
                out.y = areaPos.y;
            }
        }
        out.z = (float) Math.floor(veh.getZ());
        return out;
    }

    public static VehiclePart resolveDoorPart(BaseVehicle veh, int seat, boolean useDoor2) {
        if (veh == null) {
            return null;
        }
        VehiclePart p = useDoor2 ? veh.getPassengerDoor2(seat) : veh.getPassengerDoor(seat);
        if (p == null) {
            p = useDoor2 ? veh.getPassengerDoor(seat) : veh.getPassengerDoor2(seat);
        }
        return p;
    }

    public static boolean canAccessOrUnlockDoor(
            BaseVehicle veh, VehiclePart doorPart, IsoNPC npc, IsoPlayer leader) {
        if (veh == null) {
            return false;
        }
        if (doorPart == null || doorPart.getInventoryItem() == null) {
            return true; // 缺门/无门板可直接上车
        }
        VehicleDoor door = doorPart.getDoor();
        if (door == null || !door.isLocked()) {
            return true;
        }
        if (npc != null && veh.canUnlockDoor(doorPart, npc)) {
            return true;
        }
        return leader != null && veh.canUnlockDoor(doorPart, leader);
    }

    public static boolean isSeatDirectlyEnterable(
            BaseVehicle veh, int seat, IsoNPC npc, IsoPlayer leader) {
        if (veh == null || seat < 1 || seat >= veh.getMaxPassengers() || !veh.isSeatInstalled(seat)) {
            return false;
        }
        boolean door1 = veh.getPassengerPosition(seat, "outside") != null
                && !veh.isEnterBlocked(npc, seat)
                && canAccessOrUnlockDoor(veh, veh.getPassengerDoor(seat), npc, leader);
        if (door1) {
            return true;
        }
        return veh.getPassengerPosition(seat, "outside2") != null
                && !veh.isEnterBlocked2(npc, seat)
                && canAccessOrUnlockDoor(veh, veh.getPassengerDoor2(seat), npc, leader);
    }

    public static boolean unlockAndOpenDoor(
            BaseVehicle veh, VehiclePart doorPart, int seat, IsoNPC npc, IsoPlayer leader) {
        if (veh == null || doorPart == null || doorPart.getInventoryItem() == null) {
            return true;
        }
        VehicleDoor door = doorPart.getDoor();
        if (door == null) {
            return true;
        }
        if (door.isLocked()) {
            IsoGameCharacter keyHolder = (npc != null && (npc.getVehicle() == veh || veh.canUnlockDoor(doorPart, npc)))
                    ? npc : leader;
            if (keyHolder != null) {
                veh.toggleLockedDoor(doorPart, keyHolder, false);
                if (!door.isLocked()) {
                    IsoPlayer soundChr = keyHolder instanceof IsoPlayer p ? p : npc;
                    veh.playPartSound(doorPart, soundChr, "Unlock");
                }
            }
            if (door.isLocked()) {
                return false;
            }
        }
        if (!door.isOpen()) {
            veh.playPartAnim(doorPart, "Open");
            veh.playPartSound(doorPart, npc, "Open");
            door.setOpen(true);
            if (!veh.isPreviouslyEntered()) {
                veh.setPreviouslyEntered(true);
            }
        }
        return true;
    }

    public static void closeDoorIfOpen(BaseVehicle veh, VehiclePart doorPart, IsoNPC npc) {
        if (veh == null || doorPart == null || doorPart.getInventoryItem() == null) {
            return;
        }
        VehicleDoor door = doorPart.getDoor();
        if (door != null && door.isOpen()) {
            veh.playPartAnim(doorPart, "Close");
            veh.playPartSound(doorPart, npc, "Close");
            door.setOpen(false);
        }
    }

    /** 出口被堵时按原版 {@code ISVehicleMenu.getBestSwitchSeatExit} 查找可切换且未阻塞的空座。 */
    public static int findBestExitSeat(BaseVehicle veh, IsoNPC npc, int currentSeat) {
        if (veh == null || currentSeat < 0) {
            return -1;
        }
        if (!veh.isExitBlocked(npc, currentSeat)) {
            return currentSeat;
        }
        if (veh.getPassengerPosition(currentSeat, "outside2") != null && !veh.isExitBlocked2(currentSeat)) {
            return currentSeat;
        }
        int max = veh.getMaxPassengers();
        for (int s = 1; s < max; s++) {
            if (s == currentSeat || !veh.isSeatInstalled(s) || veh.isSeatOccupied(s)) {
                continue;
            }
            if (!veh.canSwitchSeat(currentSeat, s)) {
                continue;
            }
            if (!veh.isExitBlocked(npc, s)
                    || (veh.getPassengerPosition(s, "outside2") != null && !veh.isExitBlocked2(s))) {
                return s;
            }
        }
        return currentSeat;
    }

    /** 计算热存盘、phaseOut 与强制脱车共用的车门外安全可站立落点（永不穿进车体碰撞盒）。 */
    public static Vector3f computeSafeOutsidePoint(BaseVehicle veh, int seat, IsoNPC actor) {
        int vz = veh != null ? EmberTargetProjector.levelOf(veh.getZ()) : 0;
        if (veh != null && seat >= 0) {
            Vector3f p1 = getPositionWorld(veh, seat, "outside");
            if (p1 != null) {
                EmberTargetProjector.ProjectedPoint proj =
                        EmberTargetProjector.projectGoal(actor, p1.x, p1.y, vz);
                if (proj != null) {
                    return new Vector3f(proj.x(), proj.y(), proj.z());
                }
            }
            Vector3f p2 = getPositionWorld(veh, seat, "outside2");
            if (p2 != null) {
                EmberTargetProjector.ProjectedPoint proj =
                        EmberTargetProjector.projectGoal(actor, p2.x, p2.y, vz);
                if (proj != null) {
                    return new Vector3f(proj.x(), proj.y(), proj.z());
                }
            }
            int max = veh.getMaxPassengers();
            for (int s = 0; s < max; s++) {
                Vector3f ps = getPositionWorld(veh, s, "outside");
                if (ps != null && !veh.isExitBlocked(actor, s)) {
                    EmberTargetProjector.ProjectedPoint proj =
                            EmberTargetProjector.projectGoal(actor, ps.x, ps.y, vz);
                    if (proj != null) {
                        return new Vector3f(proj.x(), proj.y(), proj.z());
                    }
                }
            }
        }
        if (veh != null) {
            float safeR = computeVehicleSafeRadius(veh) + 0.50F;
            for (int i = 0; i < 12; i++) {
                double ang = (Math.PI * 2.0 * i) / 12.0;
                float cx = veh.getX() + (float) Math.cos(ang) * safeR;
                float cy = veh.getY() + (float) Math.sin(ang) * safeR;
                EmberTargetProjector.ProjectedPoint proj = EmberTargetProjector.projectGoal(actor, cx, cy, vz);
                if (proj != null && EmberTargetProjector.canStandClean(actor, proj.x(), proj.y(), proj.z())) {
                    return new Vector3f(proj.x(), proj.y(), proj.z());
                }
            }
            return new Vector3f(veh.getX() + safeR, veh.getY(), vz);
        }
        return actor != null ? new Vector3f(actor.getX(), actor.getY(), actor.getZ()) : new Vector3f();
    }

    /** 参照原版 {@code ISEnterVehicle:55-62} 上车前自动放下手持重物到地面，保持原件守恒。 */
    public static void dropHeavyHandItems(IsoNPC npc) {
        if (npc == null || npc.getCurrentSquare() == null || npc.getInventory() == null) {
            return;
        }
        dropIfHeavy(npc, npc.getPrimaryHandItem());
        dropIfHeavy(npc, npc.getSecondaryHandItem());
    }

    private static void dropIfHeavy(IsoNPC npc, InventoryItem item) {
        if (item == null || !item.hasTag(ItemTag.HEAVY_ITEM)) {
            return;
        }
        IsoGridSquare sq = npc.getCurrentSquare();
        if (sq == null) {
            return;
        }
        npc.getInventory().Remove(item);
        npc.removeFromHands(item);
        sq.AddWorldInventoryItem(item, 0.5F, 0.5F, 0.0F);
    }

    /**
     * 强制安全脱车（用于 {@code phaseOut / despawn / reset / OnDeath / 车辆被卸载 / StateScope shutdown}）：
     * 释放预约、退出 {@link BaseVehicle}、落地安全位置、清空 6 个车内动画变量、切回 {@code "player"} 动作组。
     */
    public static void forceDetachFromVehicle(IsoNPC npc, String reason) {
        if (npc == null) {
            return;
        }
        VehicleSeatRegistry.releaseForNpc(npc.getNpcId());
        BaseVehicle veh = npc.getVehicle();
        int seat = -1;
        if (veh != null) {
            seat = veh.getSeat(npc);
            Vector3f safePos = computeSafeOutsidePoint(veh, seat, npc);
            try {
                veh.exit(npc);
            } catch (Throwable ignored) {}
            if (npc.getVehicle() != null) {
                npc.setVehicle(null);
            }
            if (seat >= 0) {
                try {
                    veh.setCharacterPosition(npc, seat, "outside");
                } catch (Throwable ignored) {}
                try {
                    closeDoorIfOpen(veh, veh.getPassengerDoor(seat), npc);
                } catch (Throwable ignored) {}
                try {
                    veh.updateHasExtendOffsetForExitEnd(npc);
                } catch (Throwable ignored) {}
            }
            if (safePos != null && Float.isFinite(safePos.x) && Float.isFinite(safePos.y)) {
                npc.setX(safePos.x);
                npc.setY(safePos.y);
                npc.setZ(safePos.z);
                npc.setNextX(safePos.x);
                npc.setNextY(safePos.y);
            }
        }
        clearVehicleAnimVars(npc);
        try {
            npc.checkActionGroup();
        } catch (Throwable ignored) {}
        if (!npc.isDead()) {
            try {
                npc.PlayAnim("Idle");
            } catch (Throwable ignored) {}
            npc.setCollidable(true);
            try {
                npc.setCurrentSquareFromPosition();
                npc.setMovingSquareNow();
            } catch (Throwable ignored) {}
        }
    }

    public static void clearVehicleAnimVars(IsoGameCharacter chr) {
        if (chr == null) {
            return;
        }
        chr.clearVariable("bEnteringVehicle");
        chr.clearVariable("EnterAnimationFinished");
        chr.clearVariable("bExitingVehicle");
        chr.clearVariable("ExitAnimationFinished");
        chr.clearVariable("bSwitchingSeat");
        chr.clearVariable("SwitchSeatAnimationFinished");
    }

    /** 为手动「全体上车」在领袖尚未坐车时查找身旁 8 格内最近的停稳车辆。 */
    public static BaseVehicle findNearestBoardableVehicle(IsoPlayer leader, float maxDist) {
        if (leader == null || leader.getCell() == null) {
            return null;
        }
        IsoCell cell = leader.getCell();
        Set<BaseVehicle> vehicles = cell.getVehicles();
        if (vehicles == null || vehicles.isEmpty()) {
            return null;
        }
        int lz = EmberTargetProjector.levelOf(leader.getZ());
        float maxSq = maxDist * maxDist;
        BaseVehicle best = null;
        float bestSq = Float.POSITIVE_INFINITY;
        for (BaseVehicle v : vehicles) {
            if (v == null || v.isRemovedFromWorld() || v.getSquare() == null) {
                continue;
            }
            if (EmberTargetProjector.levelOf(v.getZ()) != lz || Math.abs(v.getCurrentSpeedKmHour()) > 1.5F) {
                continue;
            }
            float dSq = distSq(leader.getX(), leader.getY(), v.getX(), v.getY());
            if (dSq <= maxSq && dSq < bestSq) {
                bestSq = dSq;
                best = v;
            }
        }
        return best;
    }

    /** 自动推导随从的武器类别（用于 {@code SquadHUD} 图标与徽标）。 */
    public static String resolveWeaponType(IsoNPC npc) {
        if (npc == null) {
            return "UNARMED";
        }
        if (hasRangedWeapon(npc)) {
            return "FIREARM";
        }
        if (hasMeleeWeapon(npc)) {
            return "MELEE";
        }
        return "UNARMED";
    }

    /** 自动推导随从的战术职能（驱动四阵型 {@link TacticalRole} 匈牙利偏置）。 */
    public static TacticalRole resolveTacticalRole(IsoNPC npc) {
        if (npc == null) {
            return TacticalRole.SUPPORT;
        }
        if (hasRangedWeapon(npc)) {
            return TacticalRole.RANGED;
        }
        boolean hasMedAbility = false;
        try {
            int doc = npc.getPerkLevel(PerkFactory.Perks.Doctor);
            if (doc >= 2) {
                hasMedAbility = true;
            }
        } catch (Throwable ignored) {}
        boolean hasMelee = hasMeleeWeapon(npc);
        if (hasMedAbility && !hasMelee) {
            return TacticalRole.MEDIC;
        }
        if (hasMelee) {
            return TacticalRole.MELEE;
        }
        if (hasMedAbility || BandageSelfAction.findBestBandage(npc) != null) {
            return TacticalRole.MEDIC;
        }
        return TacticalRole.SUPPORT;
    }

    private static boolean hasRangedWeapon(IsoNPC npc) {
        return hasWeaponMatching(npc, item -> item instanceof HandWeapon w && w.isRanged());
    }

    private static boolean hasMeleeWeapon(IsoNPC npc) {
        return hasWeaponMatching(npc, VehicleCoRideHelper::isRealMeleeWeapon);
    }

    private static boolean hasWeaponMatching(IsoNPC npc, Predicate<InventoryItem> predicate) {
        if (predicate.test(npc.getPrimaryHandItem()) || predicate.test(npc.getSecondaryHandItem())) {
            return true;
        }
        AttachedItems att = npc.getAttachedItems();
        if (att != null) {
            for (int i = 0, n = att.size(); i < n; i++) {
                AttachedItem ai = att.get(i);
                if (ai != null && predicate.test(ai.getItem())) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean isRealMeleeWeapon(InventoryItem item) {
        if (!(item instanceof HandWeapon w) || w.isRanged()) {
            return false;
        }
        String ft = w.getFullType();
        return ft == null || !ft.endsWith(".BareHands");
    }

    public static float distSq(float x1, float y1, float x2, float y2) {
        float dx = x1 - x2;
        float dy = y1 - y2;
        return dx * dx + dy * dy;
    }
}
