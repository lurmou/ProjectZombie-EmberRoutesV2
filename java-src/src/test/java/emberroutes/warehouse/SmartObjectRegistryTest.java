package emberroutes.warehouse;

import emberroutes.warehouse.SmartSlot.LeaseStatus;
import emberroutes.warehouse.SmartSlot.SlotKind;
import emberroutes.warehouse.SmartSlot.SmartSlotLease;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SmartObjectRegistryTest {
    @BeforeEach
    void setUp() {
        SmartObjectRegistry.reset();
    }

    @AfterEach
    void tearDown() {
        SmartObjectRegistry.reset();
    }

    @Test
    void multiTileBedRequiresAllSubTilesToActivateAndInvalidatesOnAnyUnload() {
        // 2x2 double bed straddling chunk (0,1) [x=7] and chunk (1,1) [x=8], origin (7, 10, 0)
        SmartObjectRegistry.registerTile("s1", SlotKind.BED, 7, 10, 0, 0, 0, 2, 2);
        assertEquals(0, SmartObjectRegistry.getActiveCount("s1", SlotKind.BED));

        SmartObjectRegistry.registerTile("s1", SlotKind.BED, 7, 11, 0, 0, 1, 2, 2);
        SmartObjectRegistry.registerTile("s1", SlotKind.BED, 8, 10, 0, 1, 0, 2, 2);
        assertEquals(0, SmartObjectRegistry.getActiveCount("s1", SlotKind.BED));

        // 4th sub-tile loads -> single origin slot "7:10:0#BED" activates
        SmartSlot slot = SmartObjectRegistry.registerTile("s1", SlotKind.BED, 8, 11, 0, 1, 1, 2, 2);
        assertNotNull(slot);
        assertEquals("7:10:0#BED", slot.getSlotId());
        assertTrue(slot.isActive());
        assertEquals(1, SmartObjectRegistry.getActiveCount("s1", SlotKind.BED));

        SmartSlotLease lease = SmartObjectRegistry.acquireNearest(
                "s1", SlotKind.BED, "npc_1", 6.5F, 10.5F, 0, 10.0, 30.0, null);
        assertNotNull(lease);
        assertTrue(lease.isValid());
        assertEquals(LeaseStatus.ACTIVE, lease.getStatus());

        // Unloading ONLY non-origin sub-tile (8, 10, 0) deactivates whole bed and invalidates lease
        SmartObjectRegistry.unregisterSquare("s1", 8, 10, 0);
        assertFalse(slot.isActive());
        assertEquals(0, SmartObjectRegistry.getActiveCount("s1", SlotKind.BED));
        assertFalse(lease.isValid());
        assertEquals(LeaseStatus.INVALIDATED, lease.getStatus());
    }

    @Test
    void seatAcquisitionRespectsPhysicalStandUpBarrierAndTtlRecovery() {
        SmartObjectRegistry.registerTile("s1", SlotKind.SEAT, 10, 10, 0, 0, 0, 1, 1);
        SmartObjectRegistry.registerTile("s1", SlotKind.SEAT, 25, 25, 0, 0, 0, 1, 1);

        // NPC 1 claims nearest seat (10, 10)
        SmartSlotLease lease1 = SmartObjectRegistry.acquireNearest(
                "s1", SlotKind.SEAT, "npc_1", 11.0F, 10.0F, 0, 5.0, 30.0, null);
        assertNotNull(lease1);
        assertEquals("10:10:0#SEAT", lease1.getSlotId());

        // Logical lease released upon StateScope.close(), BUT physical STANDING_UP lock still active!
        SmartObjectRegistry.releaseLease(lease1);
        assertEquals(LeaseStatus.RELEASED, lease1.getStatus());

        AtomicBoolean npc1StillStandingUpOn10x10 = new AtomicBoolean(true);
        SmartSlotLease lease2 = SmartObjectRegistry.acquireNearest(
                "s1", SlotKind.SEAT, "npc_2", 11.0F, 10.0F, 0, 5.5, 30.0,
                slot -> !(npc1StillStandingUpOn10x10.get() && slot.originX == 10 && slot.originY == 10));
        assertNotNull(lease2);
        assertEquals("25:25:0#SEAT", lease2.getSlotId(),
                "Physical stand-up lock must prevent npc_2 from claiming 10:10 until npc_1 reaches STANDING");

        // Once npc_1 finishes standing up, 10:10 becomes acquirable again
        npc1StillStandingUpOn10x10.set(false);
        assertTrue(SmartObjectRegistry.hasAvailableSlot(
                "s1", SlotKind.SEAT, "npc_3", 6.0, slot -> slot.originX == 10));

        // TTL expiry self-heals a leaked 30s lease without manual unlock
        SmartSlotLease tempLease = SmartObjectRegistry.acquireNearest(
                "s1", SlotKind.SEAT, "npc_3", 10.0F, 10.0F, 0, 10.0, 30.0, null);
        assertNotNull(tempLease);
        assertNull(SmartObjectRegistry.acquireNearest(
                "s1", SlotKind.SEAT, "npc_4", 10.0F, 10.0F, 0, 20.0, 30.0,
                slot -> slot.originX == 10));
        SmartSlotLease recovered = SmartObjectRegistry.acquireNearest(
                "s1", SlotKind.SEAT, "npc_4", 10.0F, 10.0F, 0, 41.0, 30.0,
                slot -> slot.originX == 10);
        assertNotNull(recovered, "Expired TTL lease must auto-recover for another NPC");
        assertEquals("npc_4", recovered.getSlot().getOccupiedByNpcId());
    }

    @Test
    void trimOutsideRectsDeregistersSeatsInTrimmedTerritory() {
        SmartObjectRegistry.registerTile("s1", SlotKind.SEAT, 5, 5, 0, 0, 0, 1, 1);
        SmartObjectRegistry.registerTile("s1", SlotKind.SEAT, 15, 5, 0, 0, 0, 1, 1);
        assertEquals(2, SmartObjectRegistry.getActiveCount("s1", SlotKind.SEAT));

        SmartObjectRegistry.trimOutsideRects("s1", List.of(new int[]{0, 0, 10, 10}));
        assertEquals(1, SmartObjectRegistry.getActiveCount("s1", SlotKind.SEAT));
        assertNotNull(SmartObjectRegistry.getSlot("s1", "5:5:0#SEAT"));
        assertNull(SmartObjectRegistry.getSlot("s1", "15:5:0#SEAT"));
    }
}
