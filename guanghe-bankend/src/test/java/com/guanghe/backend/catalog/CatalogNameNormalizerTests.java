package com.guanghe.backend.catalog;

import com.guanghe.backend.common.api.BusinessException;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CatalogNameNormalizerTests {

    private final CatalogNameNormalizer normalizer = new CatalogNameNormalizer();

    @Test
    void trimsDisplayValueAndUsesNfkcLowercaseForUniqueness() {
        AliasValue value = normalizer.requiredName("  ＡＢＣ  ", 100, "name");

        assertThat(value.value()).isEqualTo("ＡＢＣ");
        assertThat(value.normalized()).isEqualTo("abc");
    }

    @Test
    void aliasesRejectDuplicatesAfterWhitespaceCaseAndUnicodeNormalization() {
        assertThatThrownBy(() -> normalizer.aliases(List.of(" Product ", "ＰＲＯＤＵＣＴ"), 150, "aliases"))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).errorCode().name())
                .isEqualTo("VALIDATION_FAILED");
    }
}
