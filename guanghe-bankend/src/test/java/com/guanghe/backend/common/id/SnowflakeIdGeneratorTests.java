package com.guanghe.backend.common.id;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import org.junit.jupiter.api.Test;

class SnowflakeIdGeneratorTests {

    @Test
    void shouldGeneratePositiveOrderedUniqueIds() {
        SnowflakeIdGenerator generator = new SnowflakeIdGenerator(new SnowflakeProperties(1, 2));
        var ids = new HashSet<Long>();
        long previous = 0;

        for (int index = 0; index < 10_000; index++) {
            long id = generator.nextId();
            assertThat(id).isPositive().isGreaterThan(previous);
            ids.add(id);
            previous = id;
        }

        assertThat(ids).hasSize(10_000);
    }
}

