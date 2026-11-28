package emberroutes.npc.brain;

import emberroutes.npc.IsoNPC;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import zombie.characters.BodyDamage.Metabolics;
import zombie.inventory.InventoryItem;
import zombie.inventory.ItemContainer;
import zombie.inventory.types.Radio;
import zombie.scripting.objects.CharacterTrait;

/**
 * 原版 B42 对齐的自身主物品栏 ↔ 随身背包物品整理 TimedAction（1:1 复刻 {@code ISInventoryTransferAction} 自包支线）：
 * <ul>
 *   <li>挂载原版 {@code TransferItemOnSelf} 动作，隐藏手持武器并循环播放 {@code RummageInInventory}；</li>
 *   <li>按 {@code 120 × min(重量, 3.0) × clamp(目标容量率, 0.4, 1.0)} 与 {@code Dextrous / AllThumbs / 笨重手套} 逐件计时与转移；</li>
 *   <li>每件转移完成播放 {@code PutSound / StoreItemPlayerInventory} 并同步 {@code updateEquippedRadioFreq()}，期间持续施加 {@code Metabolics.LightWork}。</li>
 * </ul>
 */
public final class SelfInventoryTransferAction extends BaseAction {
    public record TransferEntry(InventoryItem item, ItemContainer src, ItemContainer dest) {}

    private final List<TransferEntry> entries;
    private double[] itemDurations;
    private int currentIdx;
    private double itemElapsedSimSec;
    private int movedCount;

    public SelfInventoryTransferAction(IsoNPC npc, List<TransferEntry> entries) {
        super(npc, 1.0, "TransferItemOnSelf");
        this.stopOnWalk = false;
        this.stopOnRun = true;
        this.stopOnAim = false;
        this.entries = entries != null ? new ArrayList<>(entries) : Collections.emptyList();
    }

    @Override
    protected void onStart(BrainContext ctx) {
        currentIdx = 0;
        itemElapsedSimSec = 0.0;
        movedCount = 0;

        IsoNPC targetNpc = this.npc != null ? this.npc : (ctx != null ? ctx.npc : null);
        if (targetNpc == null || targetNpc.isDead()) {
            itemDurations = new double[0];
            failOnStart();
            return;
        }
        if (entries.isEmpty()) {
            itemDurations = new double[0];
            forceComplete();
            return;
        }

        configureHandModels(null, null);

        ItemContainer firstSrc = entries.get(0) != null ? entries.get(0).src() : null;
        if (firstSrc != null) {
            String takeSound = firstSrc.getTakeSound();
            if (takeSound != null && !takeSound.isEmpty()) {
                playOneShotSound(targetNpc, takeSound);
            }
        }
        playLoopingActionSound(targetNpc, "RummageInInventory");

        int n = entries.size();
        this.itemDurations = new double[n];
        boolean dextrous = targetNpc.hasTrait(CharacterTrait.DEXTROUS);
        boolean clumsy = targetNpc.hasTrait(CharacterTrait.ALL_THUMBS) || targetNpc.isWearingAwkwardGloves();
        ItemContainer mainInv = targetNpc.getInventory();

        double totalSec = 0.0;
        for (int i = 0; i < n; i++) {
            TransferEntry e = entries.get(i);
            boolean fromMainInv = e != null && mainInv != null && e.src() == mainInv;
            double destCapRatio = 1.0;
            if (fromMainInv && e.dest() != null && e.dest().getMaxWeight() > 0.0F) {
                destCapRatio = e.dest().getCapacityWeight() / (double) e.dest().getMaxWeight();
            }
            double w = e != null && e.item() != null ? e.item().getActualWeight() : 0.1;
            double sec = VanillaActionTiming.computeInventoryTransferSeconds(
                    w, true, destCapRatio, dextrous, clumsy, false, fromMainInv);
            this.itemDurations[i] = sec;
            totalSec += sec;
        }
        setDurationSimSec(totalSec);
    }

    @Override
    protected void onTick(BrainContext ctx, double dtSim) {
        IsoNPC targetNpc = this.npc != null ? this.npc : (ctx != null ? ctx.npc : null);
        if (targetNpc == null || targetNpc.isDead()) {
            forceStop();
            return;
        }
        targetNpc.setMetabolicTarget(Metabolics.LightWork);
        if (itemDurations == null || currentIdx >= entries.size()) {
            return;
        }
        this.itemElapsedSimSec += Math.max(0.0, dtSim);
        while (currentIdx < entries.size() && this.itemElapsedSimSec >= itemDurations[currentIdx]) {
            this.itemElapsedSimSec -= itemDurations[currentIdx];
            transferSingleEntry(targetNpc, entries.get(currentIdx++));
        }
    }

    @Override
    protected boolean onComplete(BrainContext ctx) {
        IsoNPC npc = ctx != null ? ctx.npc : null;
        if (npc == null || npc.isDead() || npc.getInventory() == null) {
            return false;
        }
        while (currentIdx < entries.size()) {
            transferSingleEntry(npc, entries.get(currentIdx++));
        }
        return movedCount > 0 || entries.isEmpty();
    }

    private void transferSingleEntry(IsoNPC npc, TransferEntry e) {
        if (e == null || e.item() == null || e.src() == null || e.dest() == null || e.src() == e.dest()) {
            return;
        }
        InventoryItem item = e.item();
        ItemContainer src = e.src();
        ItemContainer dest = e.dest();
        if (!src.contains(item) || !dest.hasRoomFor(npc, item) || !dest.isItemAllowed(item)) {
            return;
        }
        if (npc.getPrimaryHandItem() == item) {
            npc.setPrimaryHandItem(null);
        }
        if (npc.getSecondaryHandItem() == item) {
            npc.setSecondaryHandItem(null);
        }
        src.DoRemoveItem(item);
        src.setDirty(true);
        src.setDrawDirty(true);
        dest.AddItem(item);
        dest.setDirty(true);
        dest.setDrawDirty(true);
        ItemContainer mainInv = npc.getInventory();
        if (mainInv != null && mainInv != src && mainInv != dest) {
            mainInv.setDirty(true);
            mainInv.setDrawDirty(true);
        }
        if (item instanceof Radio) {
            npc.updateEquippedRadioFreq();
        }
        movedCount++;

        String completeSound = dest.getPutSound();
        if ((completeSound == null || completeSound.isEmpty()) && dest == mainInv) {
            completeSound = "StoreItemPlayerInventory";
        }
        if (completeSound != null && !completeSound.isEmpty()) {
            playOneShotSound(npc, completeSound);
        }
    }
}
