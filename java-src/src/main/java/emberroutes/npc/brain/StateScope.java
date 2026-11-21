package emberroutes.npc.brain;

import java.util.ArrayList;
import java.util.List;

/**
 * RAII 层级状态资源作用域（对标 UE5 StateTree 节点生命周期资源锁绑定）：
 * <ul>
 *   <li>节点 {@code onEnter} 时通过 {@link #defer(Runnable)} 注册清理句柄（{@code SmartSlotLease}、{@code BaseAction} 动画、令牌）；</li>
 *   <li>节点退出或被更高优分支/模式切换抢占时，{@link #close()} 按 LIFO 逆序安全释放全部句柄；</li>
 *   <li>支持 {@link #transferTo(StateScope)} 将单槽预锁零拷贝移交至接力作用域。</li>
 * </ul>
 */
public final class StateScope {
    private final List<Runnable> cleanups = new ArrayList<>(4);
    private boolean closed;
    private boolean closing;

    public boolean isClosed() {
        return closed;
    }

    public int size() {
        return cleanups.size();
    }

    /** 注册逆序清理动作；若作用域已关闭则立刻原地安全执行一次防泄漏。 */
    public void defer(Runnable cleanup) {
        if (cleanup == null) {
            return;
        }
        if (closed) {
            runSafely(cleanup);
            return;
        }
        cleanups.add(cleanup);
    }

    /** 按后进先出（LIFO）逆序执行全部未释放清理句柄，单个异常绝不阻断其余句柄释放。 */
    public void close() {
        if (closed || closing) {
            return;
        }
        closing = true;
        try {
            for (int i = cleanups.size() - 1; i >= 0; i--) {
                runSafely(cleanups.get(i));
            }
            cleanups.clear();
        } finally {
            closed = true;
            closing = false;
        }
    }

    /**
     * 将当前全部待释放清理句柄零拷贝转移至目标作用域并关闭自身（不执行原清理动作）；
     * 若目标无效或已关闭，则立即释放自身防止资源泄漏。
     */
    public void transferTo(StateScope target) {
        if (target == this || closed) {
            return;
        }
        if (target == null || target.isClosed()) {
            close();
            return;
        }
        if (!cleanups.isEmpty()) {
            for (int i = 0, n = cleanups.size(); i < n; i++) {
                target.defer(cleanups.get(i));
            }
            cleanups.clear();
        }
        closed = true;
    }

    private static void runSafely(Runnable cleanup) {
        try {
            cleanup.run();
        } catch (Throwable ignored) {
        }
    }
}
