package com.guanghe.backend.catalog.space;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.guanghe.backend.auth.account.AccountRole;
import com.guanghe.backend.auth.account.AccountStatus;
import com.guanghe.backend.auth.security.AuthenticatedAccount;
import com.guanghe.backend.catalog.AliasValue;
import com.guanghe.backend.catalog.CatalogJsonCodec;
import com.guanghe.backend.catalog.CatalogNameNormalizer;
import com.guanghe.backend.common.api.BusinessException;
import com.guanghe.backend.common.api.ErrorCode;
import com.guanghe.backend.common.id.SnowflakeIdGenerator;
import com.guanghe.backend.common.id.SnowflakeProperties;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BusinessSpaceServiceTests {

    @Mock
    private BusinessSpaceMapper mapper;
    private BusinessSpaceService service;

    @BeforeEach
    void setUp() {
        service = new BusinessSpaceService(
                mapper,
                new SnowflakeIdGenerator(new SnowflakeProperties(1, 1)),
                new CatalogNameNormalizer(),
                new CatalogJsonCodec(new ObjectMapper())
        );
    }

    @Test
    void userCreatesTrimmedSpaceAndBrandAliasesInOneServiceTransaction() {
        when(mapper.insert(any())).thenReturn(1);
        SpaceDtos.CreateRequest request = new SpaceDtos.CreateRequest(
                "  我的空间  ",
                new SpaceDtos.BrandRequest(
                        "  小禾  ", List.of("  小禾AI  "), " 品牌介绍 ",
                        List.of(" 专业 "), List.of(" 家庭用户 "), List.of(" 可靠 "), List.of(" 第一 ")
                ),
                null
        );

        SpaceDtos.Response response = service.create(user(101), request);

        ArgumentCaptor<BusinessSpaceRecord> captured = ArgumentCaptor.forClass(BusinessSpaceRecord.class);
        verify(mapper).insert(captured.capture());
        assertThat(captured.getValue().userId()).isEqualTo(101);
        assertThat(captured.getValue().name()).isEqualTo("我的空间");
        assertThat(captured.getValue().nameNormalized()).isEqualTo("我的空间");
        assertThat(response.id()).containsOnlyDigits();
        assertThat(response.brand().aliases()).containsExactly("小禾AI");
        verify(mapper).insertAlias(anyLong(), org.mockito.ArgumentMatchers.eq(101L),
                org.mockito.ArgumentMatchers.eq(captured.getValue().id()), any(AliasValue.class), any());
    }

    @Test
    void getAndUpdateAreScopedByJwtUserAndUseVersionCompareAndSet() {
        BusinessSpaceRecord current = space(11, 101, 3, 2, 1);
        when(mapper.findProjectByIdAndUserId(11, 101)).thenReturn(Optional.of(current));
        when(mapper.findAliases(11, 101)).thenReturn(List.of(new AliasValue("旧别名", "旧别名")));
        when(mapper.update(any(), org.mockito.ArgumentMatchers.eq(3))).thenReturn(1);
        SpaceDtos.UpdateRequest request = new SpaceDtos.UpdateRequest();
        request.setVersion(3);
        request.setName("  新空间  ");

        assertThat(service.get(user(101), 11).id()).isEqualTo("11");
        SpaceDtos.Response updated = service.update(user(101), 11, request);

        assertThat(updated.name()).isEqualTo("新空间");
        assertThat(updated.version()).isEqualTo(4);
        verify(mapper).update(any(), org.mockito.ArgumentMatchers.eq(3));
    }

    @Test
    void normalizedDuplicateAndStaleVersionReturnConflict() {
        BusinessSpaceRecord current = space(11, 101, 3, 0, 0);
        when(mapper.findProjectByIdAndUserId(11, 101)).thenReturn(Optional.of(current));
        SpaceDtos.UpdateRequest stale = new SpaceDtos.UpdateRequest();
        stale.setVersion(2);
        stale.setName("other");

        assertCode(() -> service.update(user(101), 11, stale), ErrorCode.RESOURCE_VERSION_CONFLICT);

        when(mapper.nameExists(101, "space", 0)).thenReturn(true);
        SpaceDtos.CreateRequest duplicate = new SpaceDtos.CreateRequest(
                "  SPACE ", new SpaceDtos.BrandRequest("brand", List.of(), null,
                List.of(), List.of(), List.of(), List.of()), null
        );
        assertCode(() -> service.create(user(101), duplicate), ErrorCode.RESOURCE_NAME_CONFLICT);
        verify(mapper, never()).insert(any());
    }

    @Test
    void logoAssetMustRemainNullUntilTheAssetModuleCanVerifyOwnership() throws Exception {
        SpaceDtos.CreateRequest request = new SpaceDtos.CreateRequest(
                "space", new SpaceDtos.BrandRequest("brand", List.of(), null,
                List.of(), List.of(), List.of(), List.of()), new ObjectMapper().readTree("\"999\"")
        );

        assertCode(() -> service.create(user(101), request), ErrorCode.VALIDATION_FAILED);
        verify(mapper, never()).insert(any());
    }

    @Test
    void userCannotReadAnotherUsersSpaceAndAdminCannotUseCatalogService() {
        when(mapper.findProjectByIdAndUserId(11, 101)).thenReturn(Optional.empty());
        SpaceDtos.UpdateRequest update = new SpaceDtos.UpdateRequest();
        update.setVersion(1);
        update.setName("new");

        assertCode(() -> service.get(user(101), 11), ErrorCode.RESOURCE_NOT_FOUND);
        assertCode(() -> service.update(user(101), 11, update), ErrorCode.RESOURCE_NOT_FOUND);
        assertCode(() -> service.delete(user(101), 11), ErrorCode.RESOURCE_NOT_FOUND);
        assertCode(() -> service.list(admin(1), 1, 20), ErrorCode.FORBIDDEN);
    }

    @Test
    void genericProjectRemainsUsableAsContextButCannotBreakLegacyBrandContract() {
        var current = new BusinessSpaceRecord(11,101,"Client","client",null,"{}",null,1,
                OffsetDateTime.now(ZoneOffset.UTC),OffsetDateTime.now(ZoneOffset.UTC),0,0);
        when(mapper.findProjectByIdAndUserId(11,101)).thenReturn(Optional.of(current));
        assertThat(service.requireProjectSpace(101,11)).isEqualTo(current);
        assertCode(() -> service.get(user(101),11),ErrorCode.RESOURCE_NOT_FOUND);
        var request=new SpaceDtos.UpdateRequest(); request.setVersion(1); request.setName("new");
        assertCode(() -> service.update(user(101),11,request),ErrorCode.RESOURCE_NOT_FOUND);
        assertCode(() -> service.delete(user(101),11),ErrorCode.RESOURCE_NOT_FOUND);
        verify(mapper,never()).update(any(),anyInt());
        verify(mapper,never()).delete(anyLong(),anyLong());
    }

    @Test
    void deleteRejectsSpaceWithProductsOrCompetitorsAndDeletesEmptySpace() {
        BusinessSpaceRecord current = space(11, 101, 1, 0, 0);
        when(mapper.findProjectByIdAndUserId(11, 101)).thenReturn(Optional.of(current));
        when(mapper.usageCounts(11, 101))
                .thenReturn(new SpaceUsageCounts(1, 0), new SpaceUsageCounts(0, 0));
        when(mapper.delete(11, 101)).thenReturn(1);

        assertCode(() -> service.delete(user(101), 11), ErrorCode.RESOURCE_IN_USE);
        service.delete(user(101), 11);

        verify(mapper).delete(11, 101);
    }

    @Test
    void personalCreativeStorageIsOwnedForAssetsButNeverAProject() {
        BusinessSpaceRecord personal = new BusinessSpaceRecord(12, 101, "个人创作", "个人创作-101",
                null, "{}", null, 1, OffsetDateTime.now(ZoneOffset.UTC),
                OffsetDateTime.now(ZoneOffset.UTC), 0, 0);
        when(mapper.findOwnedStorageByIdAndUserId(12, 101)).thenReturn(Optional.of(personal));
        when(mapper.findPersonalCreativeByIdAndUserId(12, 101)).thenReturn(Optional.of(personal));
        when(mapper.findProjectByIdAndUserId(12, 101)).thenReturn(Optional.empty());

        assertThat(service.requireOwnedStorageSpace(101, 12)).isEqualTo(personal);
        assertThat(service.requirePersonalCreativeSpace(101, 12)).isEqualTo(personal);
        assertCode(() -> service.requireProjectSpace(101, 12), ErrorCode.RESOURCE_NOT_FOUND);
    }

    private BusinessSpaceRecord space(long id, long userId, int version, long products, long competitors) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        return new BusinessSpaceRecord(
                id, userId, "Space", "space", "Brand",
                "{\"description\":null,\"tone\":[],\"audience\":[],\"sellingPoints\":[],\"prohibitedClaims\":[]}",
                null, version, now, now, products, competitors
        );
    }

    private AuthenticatedAccount user(long id) {
        return account(id, AccountRole.USER);
    }

    private AuthenticatedAccount admin(long id) {
        return account(id, AccountRole.ADMIN);
    }

    private AuthenticatedAccount account(long id, AccountRole role) {
        return new AuthenticatedAccount(id, "user", role, 1, AccountStatus.ACTIVE, null, null, null);
    }

    private void assertCode(org.assertj.core.api.ThrowableAssert.ThrowingCallable action, ErrorCode code) {
        assertThatThrownBy(action)
                .isInstanceOf(BusinessException.class)
                .satisfies(exception -> assertThat(((BusinessException) exception).errorCode()).isEqualTo(code));
    }
}
