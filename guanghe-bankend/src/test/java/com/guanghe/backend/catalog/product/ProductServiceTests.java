package com.guanghe.backend.catalog.product;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.guanghe.backend.auth.account.AccountRole;
import com.guanghe.backend.auth.account.AccountStatus;
import com.guanghe.backend.auth.security.AuthenticatedAccount;
import com.guanghe.backend.catalog.AliasValue;
import com.guanghe.backend.catalog.CatalogJsonCodec;
import com.guanghe.backend.catalog.CatalogNameNormalizer;
import com.guanghe.backend.catalog.space.BusinessSpaceRecord;
import com.guanghe.backend.catalog.space.BusinessSpaceService;
import com.guanghe.backend.common.api.BusinessException;
import com.guanghe.backend.common.api.ErrorCode;
import com.guanghe.backend.common.id.SnowflakeIdGenerator;
import com.guanghe.backend.common.id.SnowflakeProperties;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.beans.factory.ObjectProvider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProductServiceTests {

    @Mock
    private ProductMapper mapper;
    @Mock
    private BusinessSpaceService spaceService;
    @Mock
    private ProductIdentityAssetService identityAssets;
    private ProductService service;

    @BeforeEach
    void setUp() {
        @SuppressWarnings("unchecked")
        ObjectProvider<ProductIdentityAssetService> provider = org.mockito.Mockito.mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(identityAssets);
        service = new ProductService(
                mapper, spaceService,
                new SnowflakeIdGenerator(new SnowflakeProperties(2, 1)),
                new CatalogNameNormalizer(), new CatalogJsonCodec(new ObjectMapper()), provider
        );
    }

    @Test
    void userCreatesAndReadsProductInsideOwnedSpace() {
        when(spaceService.requireProjectSpace(101, 21)).thenReturn(space(21, 101));
        when(mapper.insert(any())).thenReturn(1);
        ProductDtos.CreateRequest request = new ProductDtos.CreateRequest(
                "  电饭煲 A1  ", " 家电 ", " A1 ", List.of(" 快速 "),
                Map.of(" 功率 ", " 1000W "), List.of(" 家庭 "), List.of(" 厨房 "),
                List.of(" 绝对化表述 "), null
        );

        ProductDtos.Response created = service.create(user(101), 21, request);
        ArgumentCaptor<ProductRecord> captured = ArgumentCaptor.forClass(ProductRecord.class);
        verify(mapper).insert(captured.capture());
        assertThat(captured.getValue().userId()).isEqualTo(101);
        assertThat(captured.getValue().spaceId()).isEqualTo(21);
        assertThat(captured.getValue().name()).isEqualTo("电饭煲 A1");
        assertThat(created.id()).containsOnlyDigits();
        assertThat(created.spaceId()).isEqualTo("21");
        assertThat(created.parameters()).containsEntry("功率", "1000W");

        ProductRecord persisted = captured.getValue();
        when(mapper.findByIdAndUserId(persisted.id(), 101)).thenReturn(Optional.of(persisted));
        when(mapper.findAliases(persisted.id(), 101, 21)).thenReturn(List.of());
        assertThat(service.get(user(101), persisted.id()).name()).isEqualTo("电饭煲 A1");
    }

    @Test
    void updateUsesVersionAndDeleteUsesUserScopedIdentity() {
        ProductRecord current = product(31, 101, 21, 4);
        when(mapper.findByIdAndUserId(31, 101)).thenReturn(Optional.of(current));
        when(mapper.update(any(), org.mockito.ArgumentMatchers.eq(4))).thenReturn(1);
        when(mapper.findAliases(31, 101, 21)).thenReturn(List.of());
        when(mapper.delete(31, 101)).thenReturn(1);
        ProductDtos.UpdateRequest request = new ProductDtos.UpdateRequest();
        request.setVersion(4);
        request.setName("  新名称  ");
        request.setCategory(null);

        ProductDtos.Response updated = service.update(user(101), 31, request);
        assertThat(updated.name()).isEqualTo("新名称");
        assertThat(updated.category()).isNull();
        assertThat(updated.version()).isEqualTo(5);

        service.delete(user(101), 31);
        verify(mapper).delete(31, 101);
    }

    @Test
    void aliasesAreAtomicallyReplacedAndIncrementProductVersion() {
        ProductRecord current = product(31, 101, 21, 4);
        when(mapper.findByIdAndUserId(31, 101)).thenReturn(Optional.of(current));
        when(mapper.incrementVersion(
                org.mockito.ArgumentMatchers.eq(31L), org.mockito.ArgumentMatchers.eq(101L),
                org.mockito.ArgumentMatchers.eq(21L), org.mockito.ArgumentMatchers.eq(4), any()
        )).thenReturn(1);

        ProductDtos.Response response = service.replaceAliases(
                user(101), 31, new ProductDtos.ReplaceAliasesRequest(4, List.of(" Zeta ", " alpha "))
        );

        assertThat(response.aliases()).containsExactly("alpha", "Zeta");
        assertThat(response.version()).isEqualTo(5);
        verify(mapper).deleteAliases(31, 101, 21);
        verify(mapper, org.mockito.Mockito.times(2))
                .insertAlias(anyLong(), org.mockito.ArgumentMatchers.eq(101L),
                        org.mockito.ArgumentMatchers.eq(21L), org.mockito.ArgumentMatchers.eq(31L),
                        any(AliasValue.class), any());
    }

    @Test
    void referencedProductDeleteBecomesSafeResourceInUseConflict() {
        when(mapper.findByIdAndUserId(31, 101)).thenReturn(Optional.of(product(31, 101, 21, 1)));
        doThrow(new DataIntegrityViolationException("fk_business_task_product internal detail"))
                .when(mapper).delete(31, 101);

        assertThatThrownBy(() -> service.delete(user(101), 31))
                .isInstanceOfSatisfying(BusinessException.class, error -> {
                    assertThat(error.errorCode()).isEqualTo(ErrorCode.RESOURCE_IN_USE);
                    assertThat(error.getMessage()).contains("任务、永久资产或审计记录")
                            .doesNotContain("fk_business_task_product");
                    assertThat(error.details()).containsEntry("reason", "PRODUCT_HAS_PROTECTED_REFERENCES");
                });
    }

    @Test
    void duplicateAliasesAfterNormalizationAndPersistedAliasConflictsAreRejected() {
        ProductRecord current = product(31, 101, 21, 4);
        when(mapper.findByIdAndUserId(31, 101)).thenReturn(Optional.of(current));

        assertCode(() -> service.replaceAliases(
                user(101), 31, new ProductDtos.ReplaceAliasesRequest(4, List.of("Alias", " alias "))
        ), ErrorCode.VALIDATION_FAILED);

        when(mapper.aliasExists(101, 21, "taken", 31)).thenReturn(true);
        assertCode(() -> service.replaceAliases(
                user(101), 31, new ProductDtos.ReplaceAliasesRequest(4, List.of("TAKEN"))
        ), ErrorCode.RESOURCE_ALIAS_CONFLICT);
    }

    @Test
    void staleVersionAndNormalizedNameConflictReturn409() {
        ProductRecord current = product(31, 101, 21, 4);
        when(mapper.findByIdAndUserId(31, 101)).thenReturn(Optional.of(current));
        ProductDtos.UpdateRequest stale = new ProductDtos.UpdateRequest();
        stale.setVersion(3);
        stale.setName("name");
        assertCode(() -> service.update(user(101), 31, stale), ErrorCode.RESOURCE_VERSION_CONFLICT);

        when(spaceService.requireProjectSpace(101, 21)).thenReturn(space(21, 101));
        when(mapper.nameExists(101, 21, "product", 0)).thenReturn(true);
        ProductDtos.CreateRequest duplicate = new ProductDtos.CreateRequest(
                "  PRODUCT  ", null, null, null, null, null, null, null, null
        );
        assertCode(() -> service.create(user(101), 21, duplicate), ErrorCode.RESOURCE_NAME_CONFLICT);
    }

    @Test
    void primaryAssetIsAcceptedOnlyAfterAssetModuleVerification() throws Exception {
        when(spaceService.requireProjectSpace(101, 21)).thenReturn(space(21, 101));
        when(mapper.insert(any())).thenReturn(1);
        when(identityAssets.validatePrimaryAsset(101, 21, 999)).thenReturn(999L);
        ProductDtos.CreateRequest request = new ProductDtos.CreateRequest(
                "product", null, null, null, null, null, null, null,
                new ObjectMapper().readTree("\"999\"")
        );

        ProductDtos.Response created = service.create(user(101), 21, request);

        assertThat(created.primaryAssetId()).isEqualTo("999");
        verify(identityAssets).validatePrimaryAsset(101, 21, 999);
        verify(identityAssets).replacePrimaryRelation(org.mockito.ArgumentMatchers.eq(101L), anyLong(),
                org.mockito.ArgumentMatchers.eq(21L), org.mockito.ArgumentMatchers.eq(999L), any());
    }

    @Test
    void productCannotUseAnotherUsersSpaceOrResource() {
        doThrow(new BusinessException(ErrorCode.RESOURCE_NOT_FOUND))
                .when(spaceService).requireProjectSpace(101, 99);
        ProductDtos.CreateRequest request = new ProductDtos.CreateRequest(
                "product", null, null, null, null, null, null, null, null
        );

        assertCode(() -> service.create(user(101), 99, request), ErrorCode.RESOURCE_NOT_FOUND);
        when(mapper.findByIdAndUserId(31, 101)).thenReturn(Optional.empty());
        ProductDtos.UpdateRequest update = new ProductDtos.UpdateRequest();
        update.setVersion(1);
        update.setName("new");
        assertCode(() -> service.get(user(101), 31), ErrorCode.RESOURCE_NOT_FOUND);
        assertCode(() -> service.update(user(101), 31, update), ErrorCode.RESOURCE_NOT_FOUND);
        assertCode(() -> service.delete(user(101), 31), ErrorCode.RESOURCE_NOT_FOUND);
        verify(mapper, never()).insert(any());
    }

    private ProductRecord product(long id, long userId, long spaceId, int version) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        return new ProductRecord(
                id, userId, spaceId, "Product", "product", "category", "model",
                "[]", "{}", "[]", "[]", "[]", null, version, now, now, 0
        );
    }

    private BusinessSpaceRecord space(long id, long userId) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        return new BusinessSpaceRecord(id, userId, "Space", "space", "Brand", "{}",
                null, 1, now, now, 0, 0);
    }

    private AuthenticatedAccount user(long id) {
        return new AuthenticatedAccount(id, "user", AccountRole.USER, 1,
                AccountStatus.ACTIVE, null, null, null);
    }

    private void assertCode(org.assertj.core.api.ThrowableAssert.ThrowingCallable action, ErrorCode code) {
        assertThatThrownBy(action)
                .isInstanceOf(BusinessException.class)
                .satisfies(exception -> assertThat(((BusinessException) exception).errorCode()).isEqualTo(code));
    }
}
