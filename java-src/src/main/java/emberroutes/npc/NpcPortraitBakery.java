package emberroutes.npc;

import java.util.HashMap;
import java.util.Map;
import zombie.characters.WornItems.WornItem;
import zombie.characters.WornItems.WornItems;
import zombie.core.skinnedmodel.DeadBodyAtlas;
import zombie.core.skinnedmodel.visual.HumanVisual;
import zombie.core.skinnedmodel.visual.IHumanVisual;
import zombie.core.skinnedmodel.visual.ItemVisuals;
import zombie.core.textures.Texture;
import zombie.inventory.InventoryItem;
import zombie.iso.IsoDirections;

/**
 * 随从 3D 静态立绘离线烘焙器（复用原版 {@link DeadBodyAtlas} 渲染管线）：
 * <ul>
 *   <li>传入纯 {@link IHumanVisual} 轻量代理（严禁实现 {@code IAnimatable}），
 *       使 {@code DeadBodyAtlas.BodyParams#init} 跳过尸体骨架采样并在日光环境渲染站立姿态；</li>
 *   <li>结合外观与穿戴哈希按 {@code npcId} 缓存 {@link DeadBodyAtlas.BodyTexture}，
 *       无头预演或尚未就绪时安全降级返回 {@code null}。</li>
 * </ul>
 */
public final class NpcPortraitBakery {
    private static final class VisualProxy implements IHumanVisual {
        IsoNPC target;

        @Override
        public HumanVisual getHumanVisual() {
            return target != null ? target.getHumanVisual() : null;
        }

        @Override
        public void getItemVisuals(ItemVisuals itemVisuals) {
            itemVisuals.clear();
            if (target != null) {
                target.getItemVisuals(itemVisuals);
            }
        }

        @Override
        public boolean isFemale() {
            return target != null && target.isFemale();
        }

        @Override
        public boolean isZombie() {
            return false;
        }

        @Override
        public boolean isSkeleton() {
            return false;
        }
    }

    private static final class CachedPortrait {
        DeadBodyAtlas.BodyTexture bodyTexture;
        int visualFingerprint;
        long lastAttemptMs;
    }

    private static final long RETRY_INTERVAL_MS = 1200L;
    private static final VisualProxy PROXY = new VisualProxy();
    private static final Map<String, CachedPortrait> CACHE = new HashMap<>();
    private static DeadBodyAtlas.BodyParams sharedBodyParams;

    private NpcPortraitBakery() {}

    public static synchronized void invalidate(String npcId) {
        if (npcId != null) {
            CACHE.remove(npcId);
        }
    }

    public static synchronized void reset() {
        CACHE.clear();
        PROXY.target = null;
        sharedBodyParams = null;
    }

    public static synchronized Texture getPortraitTexture(String npcId) {
        if (npcId == null || npcId.isEmpty()) {
            return null;
        }
        CachedPortrait slot = CACHE.get(npcId);
        IsoNPC npc = NpcManager.getLiveNpc(npcId);
        if (npc == null || npc.isDead() || npc.getHumanVisual() == null) {
            return (slot != null && slot.bodyTexture != null) ? slot.bodyTexture.getTexture() : null;
        }

        int fp = computeVisualFingerprint(npc);
        long now = System.currentTimeMillis();
        boolean needsBake = slot == null || slot.visualFingerprint != fp
                || (slot.bodyTexture == null && now - slot.lastAttemptMs >= RETRY_INTERVAL_MS);

        if (needsBake) {
            if (slot == null) {
                slot = new CachedPortrait();
                CACHE.put(npcId, slot);
            }
            slot.visualFingerprint = fp;
            slot.lastAttemptMs = now;
            try {
                if (sharedBodyParams == null) {
                    sharedBodyParams = new DeadBodyAtlas.BodyParams();
                }
                PROXY.target = npc;
                sharedBodyParams.init(PROXY, IsoDirections.S, "player", "idle", 0.0F);
                if (DeadBodyAtlas.instance != null) {
                    slot.bodyTexture = DeadBodyAtlas.instance.getBodyTexture(sharedBodyParams);
                }
            } catch (Throwable ignored) {
                // 无头环境或 GL 尚未初始化时安全降级为 null，由 HUD 绘制人形剪影占位
            } finally {
                PROXY.target = null;
            }
        }
        return (slot != null && slot.bodyTexture != null) ? slot.bodyTexture.getTexture() : null;
    }

    private static int computeVisualFingerprint(IsoNPC npc) {
        int h = npc.isFemale() ? 31 : 17;
        HumanVisual hv = npc.getHumanVisual();
        if (hv != null) {
            String hair = hv.getHairModel();
            String beard = hv.getBeardModel();
            String skinTex = hv.getSkinTexture();
            h = 31 * h + (hair != null ? hair.hashCode() : 0);
            h = 31 * h + (beard != null ? beard.hashCode() : 0);
            h = 31 * h + (skinTex != null ? skinTex.hashCode() : 0);
        }
        WornItems worn = npc.getWornItems();
        if (worn != null) {
            int n = worn.size();
            h = 31 * h + n;
            for (int i = 0; i < n; i++) {
                WornItem w = worn.get(i);
                InventoryItem it = w != null ? w.getItem() : null;
                if (it != null) {
                    h = 31 * h + it.id;
                }
            }
        }
        return h;
    }
}
