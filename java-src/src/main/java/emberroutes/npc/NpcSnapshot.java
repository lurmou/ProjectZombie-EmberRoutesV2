package emberroutes.npc;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.BufferOverflowException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import zombie.characters.AttachedItems.AttachedItems;
import zombie.characters.AttachedItems.AttachedLocationGroup;
import zombie.characters.IsoGameCharacter;
import zombie.characters.SurvivorDesc;
import zombie.characters.WornItems.BodyLocationGroup;
import zombie.characters.WornItems.WornItems;
import zombie.inventory.InventoryItem;
import zombie.inventory.ItemContainer;
import zombie.inventory.types.WeaponType;
import zombie.iso.IsoWorld;
import zombie.scripting.objects.ItemBodyLocation;
import zombie.scripting.objects.ResourceLocation;
import zombie.vehicles.BaseVehicle;

/**
 * NPC 全量二进制原件快照（外观、描述、数值状态、身体损伤、经验特质、ModData、背包与嵌套容器、穿戴/挂载/主副手持索引）。
 *
 * <p>严格遵循原版 {@link ItemContainer#save(ByteBuffer, IsoGameCharacter)} 与
 * {@link ItemContainer#load(ByteBuffer, int)} 序列化，100% 保留每件物品原始 {@code InventoryItem.id}、
 * 嵌套背包树、腐烂度/耐久/剩余次数、流体与物品 {@code ModData}。
 */
public final class NpcSnapshot {
    public static final int FILE_MAGIC = 0x454D424E; // "EMBN"
    public static final int FILE_VERSION = 1;
    private static final int INITIAL_BUF_BYTES = 8192;
    private static final int MAX_BUF_BYTES = 32 * 1024 * 1024;

    @FunctionalInterface
    public interface BufferWriter {
        void write(ByteBuffer out) throws IOException;
    }

    /** 独立容器与装备索引快照载体（同时服务运行时快照与脱机契约校验）。 */
    public record InventorySnapshot(
            byte[] inventoryData,
            int primaryItemIndex,
            int secondaryItemIndex,
            String[] wornLocations,
            int[] wornItemIndexes,
            String[] attachedLocations,
            int[] attachedItemIndexes) {}

    public String npcId;
    public String displayName;
    public float x;
    public float y;
    public float z;
    public float dirAngleDeg;
    public int worldVersion;

    public byte[] survivorDescData;
    public byte[] humanVisualData;
    public byte[] statsData;
    public byte[] bodyDamageData;
    public byte[] xpData;
    public byte[] modDataData;

    public byte[] inventoryData;
    public int primaryItemIndex = -1;
    public int secondaryItemIndex = -1;
    public String[] wornLocations = new String[0];
    public int[] wornItemIndexes = new int[0];
    public String[] attachedLocations = new String[0];
    public int[] attachedItemIndexes = new int[0];

    public static int resolveWorldVersion() {
        int ver = IsoWorld.getWorldVersion();
        return ver > 0 ? ver : 249;
    }

    public static byte[] writeChunk(BufferWriter writer) throws IOException {
        int cap = INITIAL_BUF_BYTES;
        while (cap <= MAX_BUF_BYTES) {
            ByteBuffer bb = ByteBuffer.allocate(cap);
            try {
                writer.write(bb);
                int len = bb.position();
                byte[] out = new byte[len];
                bb.flip();
                bb.get(out);
                return out;
            } catch (BufferOverflowException overflow) {
                cap *= 2;
            }
        }
        throw new IOException("NpcSnapshot chunk exceeded 32MB limit");
    }

    public static InventorySnapshot captureInventory(
            ItemContainer container,
            IsoGameCharacter owner,
            InventoryItem primaryHand,
            InventoryItem secondaryHand,
            WornItems wornItems,
            AttachedItems attachedItems) throws IOException {
        if (container == null) {
            return new InventorySnapshot(null, -1, -1, new String[0], new int[0], new String[0], new int[0]);
        }
        ArrayList<InventoryItem> savedItems = new ArrayList<>();
        byte[] invBytes = writeChunk(bb -> {
            // CompressIdenticalItems.save 返回线程复用池列表，必须在同一线程下立刻深拷
            savedItems.clear();
            ArrayList<InventoryItem> pooled = container.save(bb, owner);
            if (pooled != null) {
                savedItems.addAll(pooled);
            }
        });

        int primaryIdx = primaryHand != null ? savedItems.indexOf(primaryHand) : -1;
        int secondaryIdx = secondaryHand != null ? savedItems.indexOf(secondaryHand) : -1;

        List<String> wLocs = new ArrayList<>();
        List<Integer> wIdxs = new ArrayList<>();
        if (wornItems != null) {
            wornItems.forEach(w -> {
                int idx = savedItems.indexOf(w.getItem());
                if (idx >= 0 && w.getLocation() != null) {
                    wLocs.add(w.getLocation().toString());
                    wIdxs.add(idx);
                }
            });
        }

        List<String> aLocs = new ArrayList<>();
        List<Integer> aIdxs = new ArrayList<>();
        if (attachedItems != null) {
            attachedItems.forEach(a -> {
                int idx = savedItems.indexOf(a.getItem());
                if (idx >= 0 && a.getLocation() != null) {
                    aLocs.add(a.getLocation());
                    aIdxs.add(idx);
                }
            });
        }

        return new InventorySnapshot(
                invBytes,
                primaryIdx,
                secondaryIdx,
                wLocs.toArray(new String[0]),
                toIntArray(wIdxs),
                aLocs.toArray(new String[0]),
                toIntArray(aIdxs)
        );
    }

    public static ArrayList<InventoryItem> restoreInventory(
            ItemContainer container,
            InventorySnapshot snap,
            int worldVersion,
            WornItems wornItems,
            AttachedItems attachedItems) throws IOException {
        if (container == null || snap == null || snap.inventoryData() == null) {
            return new ArrayList<>();
        }
        if (wornItems != null) {
            wornItems.clear();
        }
        if (attachedItems != null) {
            attachedItems.clear();
        }
        container.clear();

        ArrayList<InventoryItem> pooled = container.load(ByteBuffer.wrap(snap.inventoryData()), worldVersion);
        ArrayList<InventoryItem> loaded = pooled != null ? new ArrayList<>(pooled) : new ArrayList<>(container.getItems());

        if (wornItems != null && snap.wornLocations() != null && snap.wornItemIndexes() != null) {
            BodyLocationGroup group = wornItems.getBodyLocationGroup();
            int n = Math.min(snap.wornLocations().length, snap.wornItemIndexes().length);
            for (int i = 0; i < n; i++) {
                InventoryItem item = itemAt(loaded, snap.wornItemIndexes()[i]);
                String locStr = snap.wornLocations()[i];
                if (item == null || locStr == null) continue;
                try {
                    ItemBodyLocation loc = ItemBodyLocation.get(ResourceLocation.of(locStr));
                    if (loc != null && (group == null || group.getLocation(loc) != null)) {
                        wornItems.setItem(loc, item);
                    }
                } catch (Throwable ignored) {}
            }
        }

        if (attachedItems != null && snap.attachedLocations() != null && snap.attachedItemIndexes() != null) {
            AttachedLocationGroup group = attachedItems.getGroup();
            int n = Math.min(snap.attachedLocations().length, snap.attachedItemIndexes().length);
            for (int i = 0; i < n; i++) {
                InventoryItem item = itemAt(loaded, snap.attachedItemIndexes()[i]);
                String locStr = snap.attachedLocations()[i];
                if (item == null || locStr == null) continue;
                if (group == null || group.getLocation(locStr) != null) {
                    try {
                        attachedItems.setItem(locStr, item);
                    } catch (Throwable ignored) {}
                }
            }
        }

        return loaded;
    }

    public static NpcSnapshot captureFrom(IsoNPC npc) throws IOException {
        NpcSnapshot s = new NpcSnapshot();
        s.npcId = npc.getNpcId();
        s.displayName = npc.getDescriptor() != null ? npc.getDescriptor().getForename() : npc.getUsername();
        BaseVehicle seatedVeh = npc.getVehicle();
        if (seatedVeh != null) {
            // 热存盘时保持乘员留在车内，但快照坐标写为车门外安全站立点（读档复生绝不嵌进车身或高空坠落）
            org.joml.Vector3f safePos = emberroutes.npc.brain.VehicleCoRideHelper.computeSafeOutsidePoint(
                    seatedVeh, seatedVeh.getSeat(npc), npc);
            s.x = safePos.x;
            s.y = safePos.y;
            s.z = safePos.z;
        } else if (npc.getPosture().hasSafeStandPos()) {
            s.x = npc.getPosture().getSafeStandX();
            s.y = npc.getPosture().getSafeStandY();
            s.z = npc.getPosture().getSafeStandZ();
        } else {
            s.x = npc.getX();
            s.y = npc.getY();
            s.z = npc.getZ();
        }
        s.dirAngleDeg = npc.getDirectionAngle();
        s.worldVersion = resolveWorldVersion();

        if (npc.getDescriptor() != null) {
            s.survivorDescData = writeChunk(bb -> npc.getDescriptor().save(bb));
        }
        if (npc.getHumanVisual() != null) {
            s.humanVisualData = writeChunk(bb -> npc.getHumanVisual().save(bb));
        }
        if (npc.getStats() != null) {
            s.statsData = writeChunk(bb -> npc.getStats().save(bb));
        }
        if (npc.getBodyDamage() != null) {
            s.bodyDamageData = writeChunk(bb -> npc.getBodyDamage().save(bb));
        }
        if (npc.getXp() != null) {
            s.xpData = writeChunk(bb -> npc.getXp().save(bb));
        }
        if (npc.hasModData() && !npc.getModData().isEmpty()) {
            s.modDataData = writeChunk(bb -> npc.getModData().save(bb));
        }

        InventorySnapshot inv = captureInventory(
                npc.getInventory(),
                npc,
                npc.getPrimaryHandItem(),
                npc.getSecondaryHandItem(),
                npc.getWornItems(),
                npc.getAttachedItems()
        );
        s.applyInventorySnapshot(inv);
        return s;
    }

    public void restoreTo(IsoNPC npc) throws IOException {
        int ver = this.worldVersion > 0 ? this.worldVersion : resolveWorldVersion();

        if (this.survivorDescData != null) {
            SurvivorDesc desc = new SurvivorDesc(true);
            desc.load(ByteBuffer.wrap(this.survivorDescData), ver, npc);
            desc.setInstance(npc);
            npc.setDescriptor(desc);
            npc.setFemale(desc.isFemale());
            npc.setVoiceType(desc.getVoiceType());
            npc.setVoicePitch(desc.getVoicePitch());
            if (desc.getForename() != null && !desc.getForename().isEmpty()) {
                npc.setUsername(desc.getForename());
            }
        }

        if (this.humanVisualData != null && npc.getHumanVisual() != null) {
            npc.getHumanVisual().load(ByteBuffer.wrap(this.humanVisualData), ver);
            if (npc.getDescriptor() != null && npc.getDescriptor().getHumanVisual() != null) {
                npc.getDescriptor().getHumanVisual().copyFrom(npc.getHumanVisual());
            }
        }

        if (this.statsData != null && npc.getStats() != null) {
            npc.getStats().load(ByteBuffer.wrap(this.statsData), ver);
        }

        if (this.bodyDamageData != null && npc.getBodyDamage() != null) {
            npc.getBodyDamage().load(ByteBuffer.wrap(this.bodyDamageData), ver);
        }

        if (this.xpData != null && npc.getXp() != null) {
            npc.getXp().load(ByteBuffer.wrap(this.xpData), ver);
        }

        if (this.modDataData != null) {
            npc.getModData().wipe();
            npc.getModData().load(ByteBuffer.wrap(this.modDataData), ver);
        } else if (npc.hasModData()) {
            npc.getModData().wipe();
        }

        npc.setPrimaryHandItem(null);
        npc.setSecondaryHandItem(null);

        if (this.inventoryData != null && npc.getInventory() != null) {
            ArrayList<InventoryItem> loaded = restoreInventory(
                    npc.getInventory(),
                    toInventorySnapshot(),
                    ver,
                    npc.getWornItems(),
                    npc.getAttachedItems()
            );
            npc.setPrimaryHandItem(itemAt(loaded, this.primaryItemIndex));
            npc.setSecondaryHandItem(itemAt(loaded, this.secondaryItemIndex));
            try {
                npc.setVariable("Weapon", WeaponType.getWeaponType(npc).getType());
            } catch (Throwable ignored) {}
            try {
                npc.getInventory().setDrawDirty(true);
            } catch (Throwable ignored) {}
            try {
                npc.onWornItemsChanged();
            } catch (Throwable ignored) {}
        }

        npc.setX(this.x);
        npc.setY(this.y);
        npc.setZ(this.z);
        npc.setNextX(this.x);
        npc.setNextY(this.y);
        npc.setLastX(this.x);
        npc.setLastY(this.y);
        npc.setLastZ(this.z);
        npc.setDirectionAngle(this.dirAngleDeg);
        npc.InitSpriteParts(npc.getDescriptor());
        npc.resetModelNextFrame();
    }

    public InventorySnapshot toInventorySnapshot() {
        return new InventorySnapshot(
                inventoryData,
                primaryItemIndex,
                secondaryItemIndex,
                wornLocations,
                wornItemIndexes,
                attachedLocations,
                attachedItemIndexes
        );
    }

    public void applyInventorySnapshot(InventorySnapshot inv) {
        if (inv == null) return;
        this.inventoryData = inv.inventoryData();
        this.primaryItemIndex = inv.primaryItemIndex();
        this.secondaryItemIndex = inv.secondaryItemIndex();
        this.wornLocations = inv.wornLocations() != null ? inv.wornLocations() : new String[0];
        this.wornItemIndexes = inv.wornItemIndexes() != null ? inv.wornItemIndexes() : new int[0];
        this.attachedLocations = inv.attachedLocations() != null ? inv.attachedLocations() : new String[0];
        this.attachedItemIndexes = inv.attachedItemIndexes() != null ? inv.attachedItemIndexes() : new int[0];
    }

    public void writeTo(DataOutputStream out) throws IOException {
        writeString(out, npcId);
        writeString(out, displayName);
        out.writeFloat(x);
        out.writeFloat(y);
        out.writeFloat(z);
        out.writeFloat(dirAngleDeg);
        out.writeInt(worldVersion);

        writeBytes(out, survivorDescData);
        writeBytes(out, humanVisualData);
        writeBytes(out, statsData);
        writeBytes(out, bodyDamageData);
        writeBytes(out, xpData);
        writeBytes(out, modDataData);
        writeBytes(out, inventoryData);

        out.writeInt(primaryItemIndex);
        out.writeInt(secondaryItemIndex);

        int wLen = Math.min(wornLocations.length, wornItemIndexes.length);
        out.writeInt(wLen);
        for (int i = 0; i < wLen; i++) {
            writeString(out, wornLocations[i]);
            out.writeInt(wornItemIndexes[i]);
        }

        int aLen = Math.min(attachedLocations.length, attachedItemIndexes.length);
        out.writeInt(aLen);
        for (int i = 0; i < aLen; i++) {
            writeString(out, attachedLocations[i]);
            out.writeInt(attachedItemIndexes[i]);
        }
    }

    public static NpcSnapshot readFrom(DataInputStream in) throws IOException {
        NpcSnapshot s = new NpcSnapshot();
        s.npcId = readString(in);
        s.displayName = readString(in);
        s.x = in.readFloat();
        s.y = in.readFloat();
        s.z = in.readFloat();
        s.dirAngleDeg = in.readFloat();
        s.worldVersion = in.readInt();

        s.survivorDescData = readBytes(in);
        s.humanVisualData = readBytes(in);
        s.statsData = readBytes(in);
        s.bodyDamageData = readBytes(in);
        s.xpData = readBytes(in);
        s.modDataData = readBytes(in);
        s.inventoryData = readBytes(in);

        s.primaryItemIndex = in.readInt();
        s.secondaryItemIndex = in.readInt();

        int wLen = Math.max(0, in.readInt());
        s.wornLocations = new String[wLen];
        s.wornItemIndexes = new int[wLen];
        for (int i = 0; i < wLen; i++) {
            s.wornLocations[i] = readString(in);
            s.wornItemIndexes[i] = in.readInt();
        }

        int aLen = Math.max(0, in.readInt());
        s.attachedLocations = new String[aLen];
        s.attachedItemIndexes = new int[aLen];
        for (int i = 0; i < aLen; i++) {
            s.attachedLocations[i] = readString(in);
            s.attachedItemIndexes[i] = in.readInt();
        }
        return s;
    }

    public static InventoryItem itemAt(List<InventoryItem> list, int idx) {
        return list != null && idx >= 0 && idx < list.size() ? list.get(idx) : null;
    }

    private static int[] toIntArray(List<Integer> src) {
        int[] out = new int[src.size()];
        for (int i = 0; i < src.size(); i++) {
            out[i] = src.get(i);
        }
        return out;
    }

    private static void writeString(DataOutputStream out, String val) throws IOException {
        if (val == null) {
            out.writeInt(-1);
            return;
        }
        byte[] utf = val.getBytes(StandardCharsets.UTF_8);
        out.writeInt(utf.length);
        out.write(utf);
    }

    private static String readString(DataInputStream in) throws IOException {
        int len = in.readInt();
        if (len < 0) return null;
        byte[] utf = in.readNBytes(len);
        if (utf.length != len) {
            throw new IOException("Unexpected EOF reading UTF string");
        }
        return new String(utf, StandardCharsets.UTF_8);
    }

    private static void writeBytes(DataOutputStream out, byte[] data) throws IOException {
        if (data == null) {
            out.writeInt(-1);
            return;
        }
        out.writeInt(data.length);
        out.write(data);
    }

    private static byte[] readBytes(DataInputStream in) throws IOException {
        int len = in.readInt();
        if (len < 0) return null;
        byte[] data = in.readNBytes(len);
        if (data.length != len) {
            throw new IOException("Unexpected EOF reading binary chunk");
        }
        return data;
    }
}
