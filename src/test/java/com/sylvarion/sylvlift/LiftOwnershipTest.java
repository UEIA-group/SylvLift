package com.sylvarion.sylvlift;

import org.bukkit.Location;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class LiftOwnershipTest {
    @Test
    void ownershipCleanupAcceptsImmutableDiscoveryResults() throws Exception {
        Object block = platformBlock();
        List<?> discovered = List.of(block).stream().toList();
        List<?> owned = ownedBlocks(discovered);

        assertDoesNotThrow(() -> owned.removeIf(candidate -> candidate == block));
        assertTrue(owned.isEmpty());
        assertEquals(1, discovered.size());
    }

    @Test
    void ownershipCleanupDoesNotModifyAnotherLiftsInput() throws Exception {
        List<Object> shared = new ArrayList<>(List.of(platformBlock()));
        List<?> firstLift = ownedBlocks(shared);
        List<?> secondLift = ownedBlocks(shared);

        firstLift.clear();

        assertEquals(1, secondLift.size());
        assertEquals(1, shared.size());
    }

    private Object platformBlock() throws Exception {
        Class<?> type = Class.forName(SylvLiftPlugin.class.getName() + "$PlatformBlock");
        var constructor = type.getDeclaredConstructor(int.class, int.class, int.class, String.class);
        constructor.setAccessible(true);
        return constructor.newInstance(0, 0, 0, "minecraft:stone");
    }

    private List<?> ownedBlocks(List<?> discovered) throws Exception {
        Class<?> type = Class.forName(SylvLiftPlugin.class.getName() + "$Lift");
        var constructor = type.getDeclaredConstructor(String.class, UUID.class, Location.class, List.class, String.class);
        constructor.setAccessible(true);
        Object lift = constructor.newInstance("test", UUID.randomUUID(), new Location(null, 0, 63, 0), discovered, "minecraft:oak_wall_sign");
        var field = type.getDeclaredField("blocks");
        field.setAccessible(true);
        return (List<?>) field.get(lift);
    }
}
