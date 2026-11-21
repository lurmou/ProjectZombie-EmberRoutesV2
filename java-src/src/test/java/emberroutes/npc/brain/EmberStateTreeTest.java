package emberroutes.npc.brain;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class EmberStateTreeTest {
    private static final class TestNode extends StateNode {
        private final List<String> log;
        private final Predicate<BrainContext> enterCond;
        private final Predicate<BrainContext> continueCond;
        private final List<String> scopeCleanups;
        private StateScope lastScope;

        TestNode(
                String id,
                StateNode[] children,
                StateEvaluator[] evals,
                double minDwell,
                boolean preemptive,
                List<String> log,
                Predicate<BrainContext> enterCond,
                Predicate<BrainContext> continueCond,
                List<String> scopeCleanups) {
            super(id, children, evals, minDwell, preemptive);
            this.log = log;
            this.enterCond = enterCond;
            this.continueCond = continueCond;
            this.scopeCleanups = scopeCleanups;
        }

        @Override
        public boolean canEnter(BrainContext ctx) {
            return enterCond == null || enterCond.test(ctx);
        }

        @Override
        public boolean canContinue(BrainContext ctx) {
            return continueCond != null ? continueCond.test(ctx) : canEnter(ctx);
        }

        @Override
        public void onEnter(BrainContext ctx, StateScope scope) {
            this.lastScope = scope;
            log.add("enter:" + getId());
            if (scopeCleanups != null) {
                for (String tag : scopeCleanups) {
                    scope.defer(() -> log.add("cleanup:" + getId() + ":" + tag));
                }
            }
        }

        @Override
        public void onTick(BrainContext ctx, StateScope scope, double dtSim) {
            log.add("tick:" + getId());
        }

        @Override
        public void onExit(BrainContext ctx) {
            log.add("exit:" + getId());
        }
    }

    @Test
    void threePhaseTickOrderLcaDiffAndLifoScopeCleanup() {
        List<String> log = new ArrayList<>();
        AtomicBoolean haulActive = new AtomicBoolean(true);
        AtomicBoolean craftActive = new AtomicBoolean(false);
        AtomicBoolean surviveActive = new AtomicBoolean(false);

        TestNode haul = new TestNode("HAUL", null, null, 0.0, false, log,
                c -> haulActive.get(), null, List.of("slotA", "ticketA"));
        TestNode craft = new TestNode("CRAFT", null, null, 0.0, false, log,
                c -> craftActive.get(), null, List.of("benchB"));
        TestNode work = new TestNode("WORK", new StateNode[]{haul, craft}, null, 0.0, false, log,
                c -> haulActive.get() || craftActive.get(), null, List.of("workParentLock"));
        TestNode eat = new TestNode("EAT", null, null, 0.0, true, log,
                c -> surviveActive.get(), null, List.of("eatAction"));

        StateEvaluator rootEval = c -> log.add("eval:ROOT");
        TestNode root = new TestNode("ROOT", new StateNode[]{eat, work}, new StateEvaluator[]{rootEval},
                0.0, false, log, null, null, List.of("rootLease"));

        StateTreeRunner runner = new StateTreeRunner(root);
        BrainContext ctx = new BrainContext("npc_1");
        ctx.dtSim = 0.1;

        // Tick 1: enters ROOT -> WORK -> HAUL cleanly in Phase 1 -> 2 -> 3 order
        ctx.simTime = 1.0;
        runner.tick(ctx);
        assertEquals("ROOT/WORK/HAUL", runner.getActivePathString());
        assertEquals(List.of(
                "eval:ROOT",
                "enter:ROOT", "enter:WORK", "enter:HAUL",
                "tick:ROOT", "tick:WORK", "tick:HAUL"), log);

        // Tick 2: sibling switch HAUL -> CRAFT preserves ROOT and WORK scopes alive, releasing HAUL LIFO
        log.clear();
        haulActive.set(false);
        craftActive.set(true);
        ctx.simTime = 1.1;
        runner.tick(ctx);
        assertEquals("ROOT/WORK/CRAFT", runner.getActivePathString());
        assertFalse(work.lastScope.isClosed(), "LCA parent WORK scope must remain open across sibling switch");
        assertTrue(haul.lastScope.isClosed(), "Exited HAUL scope must be closed");
        assertEquals(List.of(
                "eval:ROOT",
                "exit:HAUL", "cleanup:HAUL:ticketA", "cleanup:HAUL:slotA",
                "enter:CRAFT",
                "tick:ROOT", "tick:WORK", "tick:CRAFT"), log);

        // Tick 3: higher-priority EAT closes CRAFT then WORK (LCA = ROOT)
        log.clear();
        surviveActive.set(true);
        ctx.simTime = 1.2;
        runner.tick(ctx);
        assertEquals("ROOT/EAT", runner.getActivePathString());
        assertTrue(craft.lastScope.isClosed());
        assertTrue(work.lastScope.isClosed());
        assertFalse(root.lastScope.isClosed());
        assertEquals(List.of(
                "eval:ROOT",
                "exit:CRAFT", "cleanup:CRAFT:benchB",
                "exit:WORK", "cleanup:WORK:workParentLock",
                "enter:EAT",
                "tick:ROOT", "tick:EAT"), log);

        // Shutdown cleanly closes EAT then ROOT in LIFO order
        log.clear();
        runner.shutdown(ctx);
        assertEquals(0, runner.getActiveDepth());
        assertEquals(List.of("exit:EAT", "cleanup:EAT:eatAction", "exit:ROOT", "cleanup:ROOT:rootLease"), log);
    }

    @Test
    void minDwellDebouncesLowPrioritySwitchWhilePreemptiveOverridesAtZeroDelay() {
        List<String> log = new ArrayList<>();
        AtomicBoolean threat = new AtomicBoolean(false);
        AtomicBoolean workReady = new AtomicBoolean(false);
        AtomicBoolean seatValid = new AtomicBoolean(true);

        TestNode threaten = new TestNode("THREAT_EVADE", null, null, 0.0, true, log,
                c -> threat.get(), null, List.of("evadeMove"));
        TestNode work = new TestNode("WORK", null, null, 0.0, false, log,
                c -> workReady.get(), null, null);
        TestNode seatRest = new TestNode("SEAT_REST", null, null, 12.0, false, log,
                c -> seatValid.get(), c -> seatValid.get(), List.of("seatLease"));
        TestNode idle = new TestNode("IDLE", null, null, 0.0, false, log, c -> true, null, null);
        TestNode root = new TestNode("ROOT", new StateNode[]{threaten, work, seatRest, idle}, null,
                0.0, false, log, null, null, null);

        StateTreeRunner runner = new StateTreeRunner(root);
        BrainContext ctx = new BrainContext("npc_2");

        ctx.simTime = 10.0;
        runner.tick(ctx);
        assertEquals("ROOT/SEAT_REST", runner.getActivePathString());

        // After 5s (< 12s minDwell), non-preemptive WORK is debounced while SEAT_REST stays valid
        workReady.set(true);
        ctx.simTime = 15.0;
        runner.tick(ctx);
        assertEquals("ROOT/SEAT_REST", runner.getActivePathString(),
                "Non-preemptive switch must respect minDwellSimSeconds debounce");

        // After 12s (>= 12s minDwell), WORK takes over
        ctx.simTime = 22.0;
        runner.tick(ctx);
        assertEquals("ROOT/WORK", runner.getActivePathString());

        // Return to SEAT_REST, then test 0-delay preemption by THREAT_EVADE after only 0.05s
        workReady.set(false);
        ctx.simTime = 23.0;
        runner.tick(ctx);
        assertEquals("ROOT/SEAT_REST", runner.getActivePathString());

        threat.set(true);
        ctx.simTime = 23.05;
        runner.tick(ctx);
        assertEquals("ROOT/THREAT_EVADE", runner.getActivePathString(),
                "Preemptive THREAT_EVADE must interrupt SEAT_REST at 0-frame delay");

        // Also test invalidation ignores minDwell
        threat.set(false);
        workReady.set(false);
        ctx.simTime = 24.0;
        runner.tick(ctx);
        assertEquals("ROOT/SEAT_REST", runner.getActivePathString());
        seatValid.set(false);
        ctx.simTime = 24.1;
        runner.tick(ctx);
        assertEquals("ROOT/IDLE", runner.getActivePathString(),
                "Invalidated node must exit immediately regardless of minDwellSimSeconds");
    }

    @Test
    void brainRouterShutdownAndChainedNextScopeTransferZeroLeak() {
        List<String> log = new ArrayList<>();
        TestNode settleLeaf = new TestNode("WORK", null, null, 0.0, false, log,
                c -> true, null, List.of("haulLease"));
        TestNode settleRoot = new TestNode("SETTLEMENT", new StateNode[]{settleLeaf}, null,
                0.0, false, log, null, null, null);
        TestNode squadLeaf = new TestNode("FOLLOW", null, null, 0.0, false, log,
                c -> true, null, List.of("followToken"));
        TestNode squadRoot = new TestNode("SQUAD", new StateNode[]{squadLeaf}, null,
                0.0, false, log, null, null, null);

        StateTreeRunner settleRunner = new StateTreeRunner(settleRoot);
        StateTreeRunner squadRunner = new StateTreeRunner(squadRoot);
        BrainRouter router = new BrainRouter(settleRunner, squadRunner);

        BrainContext ctx = new BrainContext("npc_3");
        ctx.simTime = 1.0;
        router.tick(ctx);
        assertEquals("SETTLEMENT/WORK", router.getActivePathString());

        // Mount lookahead pre-lock on chainedNextScope and verify transferTo moves it cleanly without early firing
        settleRunner.getChainedNextScope().defer(() -> log.add("cleanup:chainedRelay"));
        settleRunner.transferChainedScopeToActiveLeaf();
        assertFalse(log.contains("cleanup:chainedRelay"), "transferTo must NOT execute cleanup prematurely");

        // Switching mode SETTLEMENT -> SQUAD shuts down old tree & releases both haulLease and transferred chainedRelay
        assertTrue(router.setMode(BrainMode.SQUAD, ctx));
        assertTrue(log.contains("cleanup:WORK:haulLease"));
        assertTrue(log.contains("cleanup:chainedRelay"));

        router.tick(ctx);
        assertEquals("SQUAD/FOLLOW", router.getActivePathString());
        router.shutdown(ctx);
        assertTrue(log.contains("cleanup:FOLLOW:followToken"));
    }
}
