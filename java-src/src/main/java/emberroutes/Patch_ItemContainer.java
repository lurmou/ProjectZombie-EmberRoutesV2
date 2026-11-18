package emberroutes;

import me.zed_0xff.zombie_buddy.Patch;
import zombie.inventory.ItemContainer;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoObject;

/**
 * 字节码织入世界容器变更漏斗（对标 AE2 {@code alertDevice} 即时唤醒通道），替代原版从未触发的 Events.OnContainerUpdate。
 *
 * <p>必须直接位于根包 {@code emberroutes}，由 ZombieBuddy {@code Loader} 通过 {@code g_known_jars}
 * 一次性自动扫描织入。无 {@code @Patch.Argument} 且默认 {@code strictMatch = false} 时自动覆盖同名全部重载。
 */
public final class Patch_ItemContainer {
    private Patch_ItemContainer() {}

    @Patch(className = "zombie.inventory.ItemContainer", methodName = "AddItem")
    public static final class OnAddItem {
        @Patch.OnExit
        public static void exit(@Patch.This ItemContainer self) {
            ContainerDirtyQueue.markContainer(self);
        }
    }

    @Patch(className = "zombie.inventory.ItemContainer", methodName = "AddItemBlind")
    public static final class OnAddItemBlind {
        @Patch.OnExit
        public static void exit(@Patch.This ItemContainer self) {
            ContainerDirtyQueue.markContainer(self);
        }
    }

    @Patch(className = "zombie.inventory.ItemContainer", methodName = "Remove")
    public static final class OnRemove {
        @Patch.OnExit
        public static void exit(@Patch.This ItemContainer self) {
            ContainerDirtyQueue.markContainer(self);
        }
    }

    @Patch(className = "zombie.inventory.ItemContainer", methodName = "DoRemoveItem")
    public static final class OnDoRemoveItem {
        @Patch.OnExit
        public static void exit(@Patch.This ItemContainer self) {
            ContainerDirtyQueue.markContainer(self);
        }
    }

    @Patch(className = "zombie.inventory.ItemContainer", methodName = "RemoveAll")
    public static final class OnRemoveAll {
        @Patch.OnExit
        public static void exit(@Patch.This ItemContainer self) {
            ContainerDirtyQueue.markContainer(self);
        }
    }

    @Patch(className = "zombie.inventory.ItemContainer", methodName = "RemoveOneOf")
    public static final class OnRemoveOneOf {
        @Patch.OnExit
        public static void exit(@Patch.This ItemContainer self) {
            ContainerDirtyQueue.markContainer(self);
        }
    }

    @Patch(className = "zombie.inventory.ItemContainer", methodName = "clear")
    public static final class OnClear {
        @Patch.OnExit
        public static void exit(@Patch.This ItemContainer self) {
            ContainerDirtyQueue.markContainer(self);
        }
    }

    @Patch(className = "zombie.inventory.ItemContainer", methodName = "removeAllItems")
    public static final class OnRemoveAllItems {
        @Patch.OnExit
        public static void exit(@Patch.This ItemContainer self) {
            ContainerDirtyQueue.markContainer(self);
        }
    }

    @Patch(className = "zombie.inventory.ItemContainer", methodName = "emptyIt")
    public static final class OnEmptyIt {
        @Patch.OnExit
        public static void exit(@Patch.This ItemContainer self) {
            ContainerDirtyQueue.markContainer(self);
        }
    }

    @Patch(className = "zombie.inventory.ItemContainer", methodName = "setItems")
    public static final class OnSetItems {
        @Patch.OnExit
        public static void exit(@Patch.This ItemContainer self) {
            ContainerDirtyQueue.markContainer(self);
        }
    }

    @Patch(className = "zombie.inventory.ItemContainer", methodName = "setDirty")
    public static final class OnSetDirty {
        @Patch.OnExit
        public static void exit(@Patch.This ItemContainer self, @Patch.Argument(0) boolean dirty) {
            if (dirty) {
                ContainerDirtyQueue.markContainer(self);
            }
        }
    }

    @Patch(className = "zombie.inventory.ItemContainer", methodName = "load")
    public static final class OnLoad {
        @Patch.OnExit
        public static void exit(@Patch.This ItemContainer self) {
            ContainerDirtyQueue.markContainer(self);
        }
    }

    @Patch(className = "zombie.iso.IsoGridSquare", methodName = "AddSpecialObject")
    public static final class OnSquareAddSpecial {
        @Patch.OnExit
        public static void exit(@Patch.This IsoGridSquare sq, @Patch.Argument(0) IsoObject obj) {
            ContainerDirtyQueue.markSquareIfHasContainer(sq, obj);
        }
    }

    @Patch(className = "zombie.iso.IsoGridSquare", methodName = "AddTileObject")
    public static final class OnSquareAddTile {
        @Patch.OnExit
        public static void exit(@Patch.This IsoGridSquare sq, @Patch.Argument(0) IsoObject obj) {
            ContainerDirtyQueue.markSquareIfHasContainer(sq, obj);
        }
    }

    @Patch(className = "zombie.iso.IsoGridSquare", methodName = "RemoveTileObject")
    public static final class OnSquareRemoveTile {
        @Patch.OnEnter
        public static void enter(@Patch.This IsoGridSquare sq, @Patch.Argument(0) IsoObject obj) {
            ContainerDirtyQueue.markSquareIfHasContainer(sq, obj);
        }
    }

    @Patch(className = "zombie.iso.IsoGridSquare", methodName = "DeleteTileObject")
    public static final class OnSquareDeleteTile {
        @Patch.OnEnter
        public static void enter(@Patch.This IsoGridSquare sq, @Patch.Argument(0) IsoObject obj) {
            ContainerDirtyQueue.markSquareIfHasContainer(sq, obj);
        }
    }

    @Patch(className = "zombie.iso.IsoGridSquare", methodName = "RemoveTileObjectErosionNoRecalc")
    public static final class OnSquareRemoveErosionTile {
        @Patch.OnEnter
        public static void enter(@Patch.This IsoGridSquare sq, @Patch.Argument(0) IsoObject obj) {
            ContainerDirtyQueue.markSquareIfHasContainer(sq, obj);
        }
    }

    @Patch(className = "zombie.iso.IsoGridSquare", methodName = "ClearTileObjects")
    public static final class OnSquareClearTiles {
        @Patch.OnEnter
        public static void enter(@Patch.This IsoGridSquare sq) {
            ContainerDirtyQueue.markSquare(sq);
        }
    }

    @Patch(className = "zombie.iso.IsoGridSquare", methodName = "ClearTileObjectsExceptFloor")
    public static final class OnSquareClearTilesExceptFloor {
        @Patch.OnEnter
        public static void enter(@Patch.This IsoGridSquare sq) {
            ContainerDirtyQueue.markSquare(sq);
        }
    }
}
