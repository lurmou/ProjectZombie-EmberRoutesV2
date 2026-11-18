package emberroutes;

import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongConsumer;
import zombie.characters.IsoGameCharacter;
import zombie.inventory.ItemContainer;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoObject;
import zombie.iso.objects.IsoDeadBody;

/**
 * 跨线程安全的无锁世界容器脏格队列。
 *
 * <p>区块流式加载时后台线程可能触发 {@code ItemContainer.load}，
 * 因此 {@code @Patch} 回调内严禁触碰非线程安全的 {@code KahluaTable}，
 * 只将打包后的 {@code (x, y, z)} 坐标写入并发集合，统一由主线程 {@code flushDirty} 排空。
 */
public final class ContainerDirtyQueue {
    private static final Set<Long> DIRTY_SQUARES = ConcurrentHashMap.newKeySet();
    private static final AtomicLong TOTAL_EVENTS = new AtomicLong(0);

    private ContainerDirtyQueue() {}

    /** 将有符号 24位 X、24位 Y、8位 Z 打包为单一 long。 */
    public static long packCoord(int x, int y, int z) {
        long lx = ((long) x) & 0xFFFFFFL;
        long ly = ((long) y) & 0xFFFFFFL;
        long lz = ((long) z) & 0xFFL;
        return (lz << 48) | (lx << 24) | ly;
    }

    public static int unpackX(long packed) {
        int raw = (int) ((packed >>> 24) & 0xFFFFFFL);
        return (raw << 8) >> 8;
    }

    public static int unpackY(long packed) {
        int raw = (int) (packed & 0xFFFFFFL);
        return (raw << 8) >> 8;
    }

    public static int unpackZ(long packed) {
        return (byte) ((packed >>> 48) & 0xFFL);
    }

    /** 由 @Patch 在 ItemContainer 底层增删出口调用。过滤人物背包、车载容器与尸体，仅记录世界方块容器。 */
    public static void markContainer(ItemContainer container) {
        if (container == null || container.getCharacter() != null || container.isVehiclePart()) {
            return;
        }
        IsoObject parent = container.getParent();
        if (parent instanceof IsoGameCharacter || parent instanceof IsoDeadBody) {
            return;
        }
        IsoGridSquare sq = container.getSquare();
        if (sq == null && parent != null) {
            sq = parent.getSquare();
        }
        if (sq != null) {
            DIRTY_SQUARES.add(packCoord(sq.getX(), sq.getY(), sq.getZ()));
            TOTAL_EVENTS.incrementAndGet();
        }
    }

    /** 由 @Patch 在方块增删家具/木箱时调用。 */
    public static void markSquareIfHasContainer(IsoGridSquare sq, IsoObject obj) {
        if (sq == null || obj == null || obj instanceof IsoGameCharacter || obj instanceof IsoDeadBody) {
            return;
        }
        if (obj.getContainerCount() > 0 || obj.getContainer() != null) {
            DIRTY_SQUARES.add(packCoord(sq.getX(), sq.getY(), sq.getZ()));
            TOTAL_EVENTS.incrementAndGet();
        }
    }

    /** 由方块整格清理入口或单箱权限变更显式唤醒（AE2 alertDevice 语义）。 */
    public static void markSquare(IsoGridSquare sq) {
        if (sq != null) {
            DIRTY_SQUARES.add(packCoord(sq.getX(), sq.getY(), sq.getZ()));
            TOTAL_EVENTS.incrementAndGet();
        }
    }

    public static void markSquareCoord(int x, int y, int z) {
        DIRTY_SQUARES.add(packCoord(x, y, z));
        TOTAL_EVENTS.incrementAndGet();
    }

    public static boolean hasDirty() {
        return !DIRTY_SQUARES.isEmpty();
    }

    public static long getTotalEvents() {
        return TOTAL_EVENTS.get();
    }

    public static void reset() {
        DIRTY_SQUARES.clear();
        TOTAL_EVENTS.set(0);
    }

    /** 主线程零对象分配排空脏格。 */
    public static int drain(LongConsumer consumer) {
        if (DIRTY_SQUARES.isEmpty()) {
            return 0;
        }
        int drained = 0;
        Iterator<Long> it = DIRTY_SQUARES.iterator();
        while (it.hasNext()) {
            Long packed = it.next();
            it.remove();
            if (packed != null) {
                drained++;
                consumer.accept(packed.longValue());
            }
        }
        return drained;
    }
}
