package com.guanghe.backend.conversation.context;

import com.guanghe.backend.catalog.AliasValue;
import com.guanghe.backend.catalog.CatalogJsonCodec;
import com.guanghe.backend.catalog.competitor.CompetitorMapper;
import com.guanghe.backend.catalog.competitor.CompetitorRecord;
import com.guanghe.backend.catalog.product.ProductMapper;
import com.guanghe.backend.catalog.product.ProductRecord;
import com.guanghe.backend.catalog.space.BusinessSpaceMapper;
import com.guanghe.backend.catalog.space.BusinessSpaceRecord;
import com.guanghe.backend.catalog.space.SpaceDtos;
import com.guanghe.backend.common.api.BusinessException;
import com.guanghe.backend.common.api.ErrorCode;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Builds the catalog-only, user-authorized portion shared by Run and paid-task snapshots. */
@Component
@ConditionalOnProperty(prefix = "zhixiaohe.conversation", name = "enabled", havingValue = "true", matchIfMissing = true)
public class AuthorizedCatalogContextFactory {
    private final BusinessSpaceMapper spaceMapper;
    private final ProductMapper productMapper;
    private final CompetitorMapper competitorMapper;
    private final CatalogJsonCodec jsonCodec;

    public AuthorizedCatalogContextFactory(
            BusinessSpaceMapper spaceMapper,
            ProductMapper productMapper,
            CompetitorMapper competitorMapper,
            CatalogJsonCodec jsonCodec
    ) {
        this.spaceMapper = spaceMapper;
        this.productMapper = productMapper;
        this.competitorMapper = competitorMapper;
        this.jsonCodec = jsonCodec;
    }

    public Context create(long userId, Long selectedSpaceId, Long selectedProductId) {
        if (selectedProductId != null && selectedSpaceId == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        InternalRunContextDtos.Space space = null;
        InternalRunContextDtos.Product product = null;
        List<InternalRunContextDtos.Competitor> competitors = List.of();
        if (selectedSpaceId != null) {
            BusinessSpaceRecord storedSpace = spaceMapper.findProjectByIdAndUserId(selectedSpaceId, userId)
                    .orElseThrow(this::notFound);
            // A generic project groups work; its display name is not a verified brand fact.
            // Do not synthesize a brand from it or send name=null to the legacy brand schema.
            if (storedSpace.brandName() == null) {
                if (selectedProductId != null) throw new BusinessException(ErrorCode.VALIDATION_FAILED);
                return new Context(null, null, List.of());
            }
            SpaceDtos.StoredBrandProfile profile = jsonCodec.read(
                    storedSpace.brandProfileJson(), SpaceDtos.StoredBrandProfile.class
            );
            space = new InternalRunContextDtos.Space(
                    Long.toString(storedSpace.id()), storedSpace.name(),
                    new InternalRunContextDtos.Brand(
                            storedSpace.brandName(), aliases(spaceMapper.findAliases(storedSpace.id(), userId)),
                            profile.description(), safe(profile.tone()), safe(profile.audience()),
                            safe(profile.sellingPoints()), safe(profile.prohibitedClaims())
                    ), null
            );
            competitors = competitorMapper.findActiveForRunContext(userId, storedSpace.id())
                    .stream().map(value -> competitor(value, userId)).toList();
        }
        if (selectedProductId != null) {
            ProductRecord storedProduct = productMapper.findByIdAndUserId(selectedProductId, userId)
                    .orElseThrow(this::notFound);
            if (storedProduct.spaceId() != selectedSpaceId.longValue()) {
                throw notFound();
            }
            product = new InternalRunContextDtos.Product(
                    Long.toString(storedProduct.id()), Long.toString(storedProduct.spaceId()), storedProduct.name(),
                    aliases(productMapper.findAliases(storedProduct.id(), userId, storedProduct.spaceId())),
                    storedProduct.category(), storedProduct.modelSpec(),
                    jsonCodec.readStringList(storedProduct.sellingPointsJson()),
                    jsonCodec.readStringMap(storedProduct.parametersJson()),
                    jsonCodec.readStringList(storedProduct.audienceJson()),
                    jsonCodec.readStringList(storedProduct.usageScenariosJson()),
                    jsonCodec.readStringList(storedProduct.prohibitedClaimsJson()),
                    storedProduct.primaryAssetId() == null ? null : Long.toString(storedProduct.primaryAssetId()),
                    productMapper.findIdentityAssets(storedProduct.id(), userId, storedProduct.spaceId())
                            .stream().map(item -> Long.toString(item.assetId())).toList()
            );
        }
        return new Context(space, product, competitors);
    }

    private InternalRunContextDtos.Competitor competitor(CompetitorRecord value, long userId) {
        return new InternalRunContextDtos.Competitor(
                Long.toString(value.id()), Long.toString(value.spaceId()), value.name(),
                aliases(competitorMapper.findAliases(value.id(), userId, value.spaceId())),
                value.type(), value.description(), value.status()
        );
    }

    private List<String> aliases(List<AliasValue> values) {
        return values.stream().map(AliasValue::value).toList();
    }

    private List<String> safe(List<String> value) {
        return value == null ? List.of() : value;
    }

    private BusinessException notFound() {
        return new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
    }

    public record Context(InternalRunContextDtos.Space space,
                          InternalRunContextDtos.Product product,
                          List<InternalRunContextDtos.Competitor> competitors) {
        public Context {
            competitors = List.copyOf(competitors);
        }
    }
}
