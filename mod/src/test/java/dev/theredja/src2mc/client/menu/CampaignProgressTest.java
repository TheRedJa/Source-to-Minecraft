package dev.theredja.src2mc.client.menu;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

final class CampaignProgressTest {
    @Test void arrivingRaisesTheUnlockedChaptersAndSetsTheLastMap() {
        var start = new CampaignProgress.Progress("", 1);
        var next = start.arrived("INFRA_c3_m2_tunnel2", 3);
        assertEquals("infra_c3_m2_tunnel2", next.lastMap());
        assertEquals(3, next.unlocked());
    }

    @Test void arrivingInAnEarlierChapterNeverLowersTheNumber() {
        var next = new CampaignProgress.Progress("infra_c6_m1_sewer3", 6).arrived("infra_c2_m1_reserve1", 2);
        assertEquals("infra_c2_m1_reserve1", next.lastMap());
        assertEquals(6, next.unlocked());
    }

    @Test void aMapWithoutAChapterKeepsTheNumber() {
        assertEquals(4, new CampaignProgress.Progress("", 4).arrived("x", 0).unlocked());
    }
}
