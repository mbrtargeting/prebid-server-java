package org.prebid.server.auction;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.iab.openrtb.request.App;
import com.iab.openrtb.request.Asset;
import com.iab.openrtb.request.BidRequest;
import com.iab.openrtb.request.DataObject;
import com.iab.openrtb.request.ImageObject;
import com.iab.openrtb.request.Imp;
import com.iab.openrtb.request.Native;
import com.iab.openrtb.request.Request;
import com.iab.openrtb.request.Video;
import com.iab.openrtb.response.Bid;
import com.iab.openrtb.response.BidResponse;
import com.iab.openrtb.response.Response;
import com.iab.openrtb.response.SeatBid;
import io.vertx.core.Future;
import org.apache.commons.collections4.CollectionUtils;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;
import org.prebid.server.VertxTest;
import org.prebid.server.auction.model.AuctionContext;
import org.prebid.server.auction.model.BidInfo;
import org.prebid.server.auction.model.BidRequestCacheInfo;
import org.prebid.server.auction.model.BidderResponse;
import org.prebid.server.bidder.BidderCatalog;
import org.prebid.server.bidder.model.BidderBid;
import org.prebid.server.bidder.model.BidderError;
import org.prebid.server.bidder.model.BidderSeatBid;
import org.prebid.server.cache.CacheService;
import org.prebid.server.cache.model.CacheContext;
import org.prebid.server.cache.model.CacheInfo;
import org.prebid.server.cache.model.CacheServiceResult;
import org.prebid.server.cache.model.DebugHttpCall;
import org.prebid.server.events.EventsContext;
import org.prebid.server.events.EventsService;
import org.prebid.server.execution.Timeout;
import org.prebid.server.execution.TimeoutFactory;
import org.prebid.server.proto.openrtb.ext.ExtPrebid;
import org.prebid.server.proto.openrtb.ext.request.ExtGranularityRange;
import org.prebid.server.proto.openrtb.ext.request.ExtImp;
import org.prebid.server.proto.openrtb.ext.request.ExtImpPrebid;
import org.prebid.server.proto.openrtb.ext.request.ExtMediaTypePriceGranularity;
import org.prebid.server.proto.openrtb.ext.request.ExtOptions;
import org.prebid.server.proto.openrtb.ext.request.ExtPriceGranularity;
import org.prebid.server.proto.openrtb.ext.request.ExtRequest;
import org.prebid.server.proto.openrtb.ext.request.ExtRequestPrebid;
import org.prebid.server.proto.openrtb.ext.request.ExtRequestPrebidAdservertargetingRule;
import org.prebid.server.proto.openrtb.ext.request.ExtRequestPrebidChannel;
import org.prebid.server.proto.openrtb.ext.request.ExtRequestTargeting;
import org.prebid.server.proto.openrtb.ext.request.ExtStoredRequest;
import org.prebid.server.proto.openrtb.ext.response.BidType;
import org.prebid.server.proto.openrtb.ext.response.CacheAsset;
import org.prebid.server.proto.openrtb.ext.response.Events;
import org.prebid.server.proto.openrtb.ext.response.ExtBidPrebid;
import org.prebid.server.proto.openrtb.ext.response.ExtBidPrebidVideo;
import org.prebid.server.proto.openrtb.ext.response.ExtBidResponse;
import org.prebid.server.proto.openrtb.ext.response.ExtBidResponsePrebid;
import org.prebid.server.proto.openrtb.ext.response.ExtBidderError;
import org.prebid.server.proto.openrtb.ext.response.ExtHttpCall;
import org.prebid.server.proto.openrtb.ext.response.ExtResponseCache;
import org.prebid.server.settings.model.Account;
import org.prebid.server.settings.model.AccountAnalyticsConfig;
import org.prebid.server.settings.model.VideoStoredDataResult;
import org.prebid.server.vast.VastModifier;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;

import static java.util.Arrays.asList;
import static java.util.Collections.emptyList;
import static java.util.Collections.emptyMap;
import static java.util.Collections.singletonList;
import static java.util.Collections.singletonMap;
import static java.util.function.UnaryOperator.identity;
import static org.apache.commons.lang3.exception.ExceptionUtils.rethrow;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.prebid.server.proto.openrtb.ext.request.ExtRequestPrebidAdservertargetingRule.Source.xStatic;
import static org.prebid.server.proto.openrtb.ext.response.BidType.banner;
import static org.prebid.server.proto.openrtb.ext.response.BidType.video;
import static org.prebid.server.proto.openrtb.ext.response.BidType.xNative;

public class BidResponseCreatorTest extends VertxTest {

    private static final BidRequestCacheInfo CACHE_INFO = BidRequestCacheInfo.builder().build();

    private static final String IMP_ID = "impId1";

    @Rule
    public final MockitoRule mockitoRule = MockitoJUnit.rule();

    @Mock
    private CacheService cacheService;
    @Mock
    private BidderCatalog bidderCatalog;
    @Mock
    private VastModifier vastModifier;
    @Mock
    private EventsService eventsService;
    @Mock
    private StoredRequestProcessor storedRequestProcessor;

    private Clock clock;

    private Timeout timeout;

    private BidResponseCreator bidResponseCreator;

    @Before
    public void setUp() {
        given(cacheService.getEndpointHost()).willReturn("testHost");
        given(cacheService.getEndpointPath()).willReturn("testPath");
        given(cacheService.getCachedAssetURLTemplate()).willReturn("uuid=");

        given(storedRequestProcessor.videoStoredDataResult(any(), anyList(), anyList(), any()))
                .willReturn(Future.succeededFuture(VideoStoredDataResult.empty()));

        clock = Clock.fixed(Instant.ofEpochMilli(1000L), ZoneOffset.UTC);

        bidResponseCreator = new BidResponseCreator(
                cacheService,
                bidderCatalog,
                vastModifier,
                eventsService,
                storedRequestProcessor,
                false,
                0,
                clock,
                jacksonMapper);

        timeout = new TimeoutFactory(Clock.fixed(Instant.now(), ZoneId.systemDefault())).create(500);
    }

    @Test
    public void shouldPassOriginalTimeoutToCacheServiceIfCachingIsRequested() {
        // given
        final AuctionContext auctionContext = givenAuctionContext(givenBidRequest(givenImp()));

        final Bid bid = Bid.builder()
                .id("bidId1")
                .impid(IMP_ID)
                .price(BigDecimal.valueOf(5.67))
                .build();
        final List<BidderResponse> bidderResponses = singletonList(
                BidderResponse.of("bidder1", givenSeatBid(BidderBid.of(bid, banner, "USD")), 100));

        final BidRequestCacheInfo cacheInfo = BidRequestCacheInfo.builder().doCaching(true).build();

        givenCacheServiceResult(singletonMap(bid, CacheInfo.empty()));

        // when
        bidResponseCreator.create(bidderResponses, auctionContext, cacheInfo, false);

        // then
        verify(cacheService).cacheBidsOpenrtb(anyList(), any(), any(), any());
    }

    @Test
    public void shouldRequestCacheServiceWithExpectedArguments() {
        // given
        final Imp imp1 = givenImp("impId1");
        final Imp imp2 = givenImp("impId2");
        final AuctionContext auctionContext = givenAuctionContext(
                givenBidRequest(builder -> builder.ext(ExtRequest.of(ExtRequestPrebid.builder()
                                .events(mapper.createObjectNode())
                                .build())),
                        imp1, imp2),
                builder -> builder.account(Account.builder()
                        .id("accountId")
                        .eventsEnabled(true)
                        .build()));

        final Bid bid1 = Bid.builder().id("bidId1").impid("impId1").price(BigDecimal.valueOf(5.67)).build();
        final Bid bid2 = Bid.builder().id("bidId2").impid("impId2").price(BigDecimal.valueOf(7.19)).build();
        final Bid bid3 = Bid.builder().id("bidId3").impid("impId1").price(BigDecimal.valueOf(3.74)).build();
        final Bid bid4 = Bid.builder().id("bidId4").impid("impId2").price(BigDecimal.valueOf(6.74)).build();
        final List<BidderResponse> bidderResponses = asList(
                BidderResponse.of("bidder1",
                        givenSeatBid(
                                BidderBid.of(bid1, banner, "USD"),
                                BidderBid.of(bid2, banner, "USD")),
                        100),
                BidderResponse.of("bidder2",
                        givenSeatBid(
                                BidderBid.of(bid3, banner, "USD"),
                                BidderBid.of(bid4, banner, "USD")),
                        100));

        final BidRequestCacheInfo cacheInfo = BidRequestCacheInfo.builder()
                .doCaching(true)
                .shouldCacheBids(true)
                .shouldCacheVideoBids(true)
                .cacheBidsTtl(99)
                .cacheVideoBidsTtl(101)
                .build();

        // just a stub to get through method call chain
        givenCacheServiceResult(singletonMap(bid1, CacheInfo.empty()));

        // when
        bidResponseCreator.create(bidderResponses, auctionContext, cacheInfo, false);

        // then
        final BidInfo bidInfo1 = toBidInfo(bid1, imp1, "bidder1", banner);
        final BidInfo bidInfo2 = toBidInfo(bid2, imp2, "bidder1", banner);
        final BidInfo bidInfo3 = toBidInfo(bid3, imp1, "bidder2", banner);
        final BidInfo bidInfo4 = toBidInfo(bid4, imp2, "bidder2", banner);
        ArgumentCaptor<CacheContext> contextArgumentCaptor = ArgumentCaptor.forClass(CacheContext.class);
        verify(cacheService).cacheBidsOpenrtb(
                argThat(t -> t.containsAll(asList(bidInfo1, bidInfo2, bidInfo3, bidInfo4))),
                same(auctionContext),
                contextArgumentCaptor.capture(),
                eq(EventsContext.builder()
                        .enabledForAccount(true)
                        .enabledForRequest(true)
                        .auctionTimestamp(1000L)
                        .build()));

        assertThat(contextArgumentCaptor.getValue())
                .satisfies(context -> {
                    assertThat(context.isShouldCacheBids()).isTrue();
                    assertThat(context.isShouldCacheVideoBids()).isTrue();
                    assertThat(context.getCacheBidsTtl()).isEqualTo(99);
                    assertThat(context.getCacheVideoBidsTtl()).isEqualTo(101);
                });
    }

    @Test
    public void shouldRequestCacheServiceWithWinningBidsOnlyWhenWinningonlyIsTrue() {
        // given
        final Imp imp1 = givenImp("impId1");
        final Imp imp2 = givenImp("impId2");
        final AuctionContext auctionContext = givenAuctionContext(givenBidRequest(
                identity(),
                extBuilder -> extBuilder.targeting(givenTargeting()),
                imp1, imp2, givenImp("impId3"), givenImp("impId4")));

        final Bid bid1 = Bid.builder().id("bidId1").impid("impId1").price(BigDecimal.valueOf(5.67)).build();
        final Bid bid2 = Bid.builder().id("bidId2").impid("impId2").price(BigDecimal.valueOf(7.19)).build();
        final Bid bid3 = Bid.builder().id("bidId3").impid("impId1").price(BigDecimal.valueOf(3.74)).build();
        final Bid bid4 = Bid.builder().id("bidId4").impid("impId2").price(BigDecimal.valueOf(6.74)).build();
        final List<BidderResponse> bidderResponses = asList(
                BidderResponse.of("bidder1",
                        givenSeatBid(
                                BidderBid.of(bid1, banner, "USD"),
                                BidderBid.of(bid2, banner, "USD")),
                        100),
                BidderResponse.of("bidder2",
                        givenSeatBid(
                                BidderBid.of(bid3, banner, "USD"),
                                BidderBid.of(bid4, banner, "USD")),
                        100));

        final BidRequestCacheInfo cacheInfo = BidRequestCacheInfo.builder()
                .doCaching(true)
                .shouldCacheWinningBidsOnly(true)
                .build();

        // just a stub to get through method call chain
        givenCacheServiceResult(singletonMap(bid1, CacheInfo.empty()));

        // when
        bidResponseCreator.create(bidderResponses, auctionContext, cacheInfo, false);

        // then
        final BidInfo bidInfo1 = toBidInfo(bid1, imp1, "bidder1", banner);
        final BidInfo bidInfo2 = toBidInfo(bid2, imp2, "bidder1", banner);
        verify(cacheService).cacheBidsOpenrtb(
                eq(asList(bidInfo1, bidInfo2)),
                same(auctionContext),
                any(),
                eq(EventsContext.builder().auctionTimestamp(1000L).build()));
    }

    @Test
    public void shouldRequestCacheServiceWithVideoBidsToModify() {
        // given
        final Account account = Account.builder().id("accountId").eventsEnabled(true).build();

        final Imp imp1 = givenImp("impId1");
        final Imp imp2 = givenImp("impId2");
        final AuctionContext auctionContext = givenAuctionContext(
                givenBidRequest(
                        identity(),
                        extBuilder -> extBuilder.events(mapper.createObjectNode()),
                        imp1, imp2),
                contextBuilder -> contextBuilder.account(account));

        final Bid bid1 = Bid.builder().id("bidId1").impid("impId1").price(BigDecimal.valueOf(5.67)).build();
        final Bid bid2 = Bid.builder().id("bidId2").impid("impId2").price(BigDecimal.valueOf(7.19)).build();
        final List<BidderResponse> bidderResponses = asList(
                BidderResponse.of("bidder1", givenSeatBid(BidderBid.of(bid1, video, "USD")), 100),
                BidderResponse.of("bidder2", givenSeatBid(BidderBid.of(bid2, banner, "USD")), 100));

        final BidRequestCacheInfo cacheInfo = BidRequestCacheInfo.builder()
                .doCaching(true)
                .shouldCacheVideoBids(true)
                .build();

        // just a stub to get through method call chain
        givenCacheServiceResult(singletonMap(bid1, CacheInfo.empty()));

        // when
        bidResponseCreator.create(bidderResponses, auctionContext, cacheInfo, false);

        // then
        final BidInfo bidInfo1 = toBidInfo(bid1, imp1, "bidder1", video);
        final BidInfo bidInfo2 = toBidInfo(bid2, imp2, "bidder2", banner);
        verify(cacheService).cacheBidsOpenrtb(
                argThat(argument -> CollectionUtils.isEqualCollection(argument, asList(bidInfo1, bidInfo2))),
                same(auctionContext),
                eq(CacheContext.builder().shouldCacheVideoBids(true).build()),
                eq(EventsContext.builder()
                        .enabledForAccount(true)
                        .enabledForRequest(true)
                        .auctionTimestamp(1000L)
                        .build()));
    }

    @Test
    public void shouldCallCacheServiceEvenRoundedCpmIsZero() {
        // given
        final Imp imp1 = givenImp();
        final AuctionContext auctionContext = givenAuctionContext(givenBidRequest(imp1));

        final Bid bid1 = Bid.builder().id("bidId1").impid(IMP_ID).price(BigDecimal.valueOf(0.05)).build();
        final List<BidderResponse> bidderResponses = singletonList(
                BidderResponse.of("bidder1", givenSeatBid(BidderBid.of(bid1, banner, "USD")), 100));

        final BidRequestCacheInfo cacheInfo = BidRequestCacheInfo.builder().doCaching(true).build();
        // just a stub to get through method call chain
        givenCacheServiceResult(singletonMap(bid1, CacheInfo.empty()));

        // when
        bidResponseCreator.create(bidderResponses, auctionContext, cacheInfo, false);

        // then
        final BidInfo bidInfo1 = toBidInfo(bid1, imp1, "bidder1", banner);
        verify(cacheService).cacheBidsOpenrtb(
                eq(singletonList(bidInfo1)),
                same(auctionContext),
                eq(CacheContext.builder().build()),
                eq(EventsContext.builder().auctionTimestamp(1000L).build()));
    }

    @Test
    public void shouldSetExpectedConstantResponseFields() {
        // given
        final AuctionContext auctionContext = givenAuctionContext(givenBidRequest(givenImp()));

        final List<BidderResponse> bidderResponses = singletonList(BidderResponse.of("bidder1", givenSeatBid(), 100));

        // when
        final BidResponse bidResponse =
                bidResponseCreator.create(bidderResponses, auctionContext, null, false).result();

        // then
        final BidResponse responseWithExpectedFields = BidResponse.builder()
                .id("123")
                .cur("USD")
                .ext(mapper.valueToTree(
                        ExtBidResponse.of(null, null, singletonMap("bidder1", 100), 1000L, null,
                                ExtBidResponsePrebid.of(1000L))))
                .build();

        assertThat(bidResponse)
                .isEqualToIgnoringGivenFields(responseWithExpectedFields, "nbr", "seatbid");

        verify(cacheService, never()).cacheBidsOpenrtb(anyList(), any(), any(), any());

    }

    @Test
    public void shouldSetNbrValueTwoAndEmptySeatbidWhenIncomingBidResponsesAreEmpty() {
        // given
        final AuctionContext auctionContext = givenAuctionContext(givenBidRequest(givenImp()));

        // when
        final BidResponse bidResponse = bidResponseCreator.create(emptyList(), auctionContext, null, false).result();

        // then
        assertThat(bidResponse).returns(0, BidResponse::getNbr);
        assertThat(bidResponse).returns(emptyList(), BidResponse::getSeatbid);

        verify(cacheService, never()).cacheBidsOpenrtb(anyList(), any(), any(), any());
    }

    @Test
    public void shouldSetNbrValueTwoAndEmptySeatbidWhenIncomingBidResponsesDoNotContainAnyBids() {
        // given
        final AuctionContext auctionContext = givenAuctionContext(givenBidRequest(givenImp()));

        final List<BidderResponse> bidderResponses = singletonList(BidderResponse.of("bidder1", givenSeatBid(), 100));

        // when
        final BidResponse bidResponse =
                bidResponseCreator.create(bidderResponses, auctionContext, null, false).result();

        // then
        assertThat(bidResponse).returns(0, BidResponse::getNbr);
        assertThat(bidResponse).returns(emptyList(), BidResponse::getSeatbid);

        verify(cacheService, never()).cacheBidsOpenrtb(anyList(), any(), any(), any());
    }

    @Test
    public void shouldSetNbrNullAndPopulateSeatbidWhenAtLeastOneBidIsPresent() {
        // given
        final AuctionContext auctionContext = givenAuctionContext(givenBidRequest(givenImp()));

        final Bid bid = Bid.builder().impid(IMP_ID).build();
        final List<BidderResponse> bidderResponses = singletonList(BidderResponse.of("bidder1",
                givenSeatBid(BidderBid.of(bid, banner, null)), 100));

        // when
        final BidResponse bidResponse =
                bidResponseCreator.create(bidderResponses, auctionContext, CACHE_INFO, false).result();

        // then
        assertThat(bidResponse.getNbr()).isNull();
        assertThat(bidResponse.getSeatbid()).hasSize(1);

        verify(cacheService, never()).cacheBidsOpenrtb(anyList(), any(), any(), any());
    }

    @Test
    public void shouldSkipBidderResponsesWhereSeatBidContainEmptyBids() {
        // given
        final AuctionContext auctionContext = givenAuctionContext(givenBidRequest(givenImp()));

        final Bid bid = Bid.builder().impid(IMP_ID).build();
        final List<BidderResponse> bidderResponses = asList(
                BidderResponse.of("bidder1", givenSeatBid(), 0),
                BidderResponse.of("bidder2", givenSeatBid(BidderBid.of(bid, banner, "USD")), 0));

        // when
        final BidResponse bidResponse =
                bidResponseCreator.create(bidderResponses, auctionContext, CACHE_INFO, false).result();

        // then
        assertThat(bidResponse.getSeatbid()).hasSize(1);

        verify(cacheService, never()).cacheBidsOpenrtb(anyList(), any(), any(), any());
    }

    @Test
    public void shouldOverrideBidIdWhenGenerateBidIdIsTurnedOn() {
        // given
        final AuctionContext auctionContext = givenAuctionContext(givenBidRequest(givenImp()));
        final ExtPrebid<ExtBidPrebid, ?> prebid = ExtPrebid.of(ExtBidPrebid.builder().type(banner).build(), null);
        final Bid bid = Bid.builder()
                .id("123")
                .impid(IMP_ID)
                .ext(mapper.valueToTree(prebid))
                .build();
        final List<BidderResponse> bidderResponses = singletonList(
                BidderResponse.of("bidder2", givenSeatBid(BidderBid.of(bid, banner, "USD")), 0));

        final BidResponseCreator bidResponseCreator = new BidResponseCreator(
                cacheService,
                bidderCatalog,
                vastModifier,
                eventsService,
                storedRequestProcessor,
                true,
                0,
                clock,
                jacksonMapper);

        // when
        final BidResponse bidResponse =
                bidResponseCreator.create(bidderResponses, auctionContext, CACHE_INFO, false).result();

        // then
        assertThat(bidResponse.getSeatbid())
                .flatExtracting(SeatBid::getBid)
                .extracting(Bid::getExt)
                .extracting(ext -> ext.get("prebid"))
                .extracting(extPrebid -> mapper.treeToValue(extPrebid, ExtBidPrebid.class))
                .extracting(ExtBidPrebid::getBidid)
                .hasSize(1)
                .first()
                .satisfies(bidId -> assertThat(UUID.fromString(bidId)).isInstanceOf(UUID.class));

        verify(cacheService, never()).cacheBidsOpenrtb(anyList(), any(), any(), any());
    }

    @Test
    public void shouldSetExpectedResponseSeatBidAndBidFields() {
        // given
        final AuctionContext auctionContext = givenAuctionContext(givenBidRequest(givenImp()));
        final Bid bid = Bid.builder()
                .id("bidId")
                .price(BigDecimal.ONE)
                .adm("adm")
                .impid(IMP_ID)
                .ext(mapper.valueToTree(singletonMap("bidExt", 1)))
                .build();
        final List<BidderResponse> bidderResponses = singletonList(BidderResponse.of("bidder1",
                givenSeatBid(BidderBid.of(bid, banner, "USD")), 100));

        // when
        final BidResponse bidResponse =
                bidResponseCreator.create(bidderResponses, auctionContext, CACHE_INFO, false).result();

        // then
        assertThat(bidResponse.getSeatbid())
                .containsOnly(SeatBid.builder()
                        .seat("bidder1")
                        .group(0)
                        .bid(singletonList(Bid.builder()
                                .id("bidId")
                                .impid(IMP_ID)
                                .price(BigDecimal.ONE)
                                .adm("adm")
                                .ext(mapper.valueToTree(ExtPrebid.of(
                                        ExtBidPrebid.builder().type(banner).build(),
                                        singletonMap("bidExt", 1))))
                                .build()))
                        .build());

        verify(cacheService, never()).cacheBidsOpenrtb(anyList(), any(), any(), any());
    }

    @Test
    public void shouldFilterByDealsAndPriceBidsWhenImpIdsAreEqual() {
        // given
        final AuctionContext auctionContext = givenAuctionContext(givenBidRequest(givenImp("i1"), givenImp("i2")));

        final Bid simpleBidImp1 = Bid.builder().id("bidId1i1").price(BigDecimal.valueOf(5.67)).impid("i1").build();
        final Bid simpleBid1Imp2 = Bid.builder().id("bidId1i2").price(BigDecimal.valueOf(15.67)).impid("i2").build();
        final Bid simpleBid2Imp2 = Bid.builder().id("bidId2i2").price(BigDecimal.valueOf(17.67)).impid("i2").build();
        final Bid dealBid1Imp1 = Bid.builder().id("bidId1i1d").dealid("d1").price(BigDecimal.valueOf(4.98)).impid("i1")
                .build();
        final Bid dealBid2Imp1 = Bid.builder().id("bidId2i1d").dealid("d2").price(BigDecimal.valueOf(5.00)).impid("i1")
                .build();
        final BidderSeatBid seatBidWithDeals = givenSeatBid(
                BidderBid.of(simpleBidImp1, banner, null),
                BidderBid.of(simpleBid1Imp2, banner, null),
                BidderBid.of(simpleBid2Imp2, banner, null), // will stay (top price)
                BidderBid.of(simpleBid2Imp2, banner, null), // duplicate should be removed
                BidderBid.of(dealBid2Imp1, banner, null),   // will stay (deal + topPrice)
                BidderBid.of(dealBid1Imp1, banner, null));

        final Bid simpleBid3Imp2 = Bid.builder().id("bidId3i2").price(BigDecimal.valueOf(7.25)).impid("i2").build();
        final Bid simpleBid4Imp2 = Bid.builder().id("bidId4i2").price(BigDecimal.valueOf(7.26)).impid("i2").build();
        final BidderSeatBid seatBidWithSimpleBids = givenSeatBid(
                BidderBid.of(simpleBid3Imp2, banner, null),
                BidderBid.of(simpleBid4Imp2, banner, null)); // will stay (top price)

        final List<BidderResponse> bidderResponses = asList(
                BidderResponse.of("bidder1", seatBidWithDeals, 100),
                BidderResponse.of("bidder2", seatBidWithSimpleBids, 111));

        // when
        final BidResponse bidResponse =
                bidResponseCreator.create(bidderResponses, auctionContext, CACHE_INFO, false).result();

        // then
        assertThat(bidResponse.getSeatbid())
                .flatExtracting(SeatBid::getBid).hasSize(3)
                .extracting(Bid::getId)
                .containsOnly("bidId2i2", "bidId2i1d", "bidId4i2");
    }

    @Test
    public void shouldAddTypeToNativeBidAdm() throws JsonProcessingException {
        // given
        final Request nativeRequest = Request.builder()
                .assets(singletonList(Asset.builder()
                        .id(123)
                        .img(ImageObject.builder().type(1).build())
                        .data(DataObject.builder().type(2).build())
                        .build()))
                .build();

        final BidRequest bidRequest = BidRequest.builder()
                .cur(singletonList("USD"))
                .tmax(1000L)
                .app(App.builder().build())
                .imp(singletonList(Imp.builder()
                        .id(IMP_ID)
                        .xNative(Native.builder().request(mapper.writeValueAsString(nativeRequest)).build())
                        .build()))
                .build();
        final AuctionContext auctionContext = givenAuctionContext(bidRequest);

        final Response responseAdm = Response.builder()
                .assets(singletonList(com.iab.openrtb.response.Asset.builder()
                        .id(123)
                        .img(com.iab.openrtb.response.ImageObject.builder().build())
                        .data(com.iab.openrtb.response.DataObject.builder().build())
                        .build()))
                .build();

        final Bid bid = Bid.builder().id("bidId").price(BigDecimal.ONE).impid(IMP_ID)
                .adm(mapper.writeValueAsString(responseAdm))
                .ext(mapper.valueToTree(singletonMap("bidExt", 1))).build();
        final List<BidderResponse> bidderResponses = singletonList(BidderResponse.of("bidder1",
                givenSeatBid(BidderBid.of(bid, xNative, "USD")), 100));

        // when
        final BidResponse bidResponse =
                bidResponseCreator.create(bidderResponses, auctionContext, CACHE_INFO, false).result();

        // then
        assertThat(bidResponse.getSeatbid()).hasSize(1)
                .flatExtracting(SeatBid::getBid)
                .extracting(Bid::getAdm)
                .extracting(adm -> mapper.readValue(adm, Response.class))
                .flatExtracting(Response::getAssets).hasSize(1)
                .containsOnly(com.iab.openrtb.response.Asset.builder()
                        .id(123)
                        .img(com.iab.openrtb.response.ImageObject.builder().type(1).build())
                        .data(com.iab.openrtb.response.DataObject.builder().type(2).build())
                        .build());

        verify(cacheService, never()).cacheBidsOpenrtb(anyList(), any(), any(), any());
    }

    @Test
    public void shouldReturnEmptyAssetIfImageTypeIsEmpty() throws JsonProcessingException {
        // given
        final Request nativeRequest = Request.builder()
                .assets(singletonList(Asset.builder()
                        .id(123)
                        .img(ImageObject.builder().type(null).build())
                        .data(DataObject.builder().type(2).build())
                        .build()))
                .build();

        final BidRequest bidRequest = BidRequest.builder()
                .cur(singletonList("USD"))
                .tmax(1000L)
                .app(App.builder().build())
                .imp(singletonList(Imp.builder()
                        .id(IMP_ID)
                        .xNative(Native.builder().request(mapper.writeValueAsString(nativeRequest)).build())
                        .build()))
                .build();

        final AuctionContext auctionContext = givenAuctionContext(bidRequest);

        final Response responseAdm = Response.builder()
                .assets(singletonList(com.iab.openrtb.response.Asset.builder()
                        .id(123)
                        .img(com.iab.openrtb.response.ImageObject.builder().type(null).build())
                        .data(com.iab.openrtb.response.DataObject.builder().build())
                        .build()))
                .build();

        final Bid bid = Bid.builder()
                .id("bidId")
                .price(BigDecimal.ONE)
                .impid(IMP_ID)
                .adm(mapper.writeValueAsString(responseAdm))
                .ext(mapper.valueToTree(singletonMap("bidExt", 1)))
                .build();
        final List<BidderResponse> bidderResponses = singletonList(BidderResponse.of("bidder1",
                givenSeatBid(BidderBid.of(bid, xNative, "USD")), 100));

        // when
        final BidResponse bidResponse =
                bidResponseCreator.create(bidderResponses, auctionContext, CACHE_INFO, false).result();

        // then
        assertThat(bidResponse.getSeatbid()).hasSize(1)
                .flatExtracting(SeatBid::getBid)
                .extracting(Bid::getAdm)
                .extracting(adm -> mapper.readValue(adm, Response.class))
                .flatExtracting(Response::getAssets)
                .isEmpty();
    }

    @Test
    public void shouldReturnEmptyAssetIfDataTypeIsEmpty() throws JsonProcessingException {
        // given
        final Request nativeRequest = Request.builder()
                .assets(singletonList(Asset.builder()
                        .id(123)
                        .img(ImageObject.builder().type(1).build())
                        .data(DataObject.builder().type(null).build())
                        .build()))
                .build();

        final BidRequest bidRequest = BidRequest.builder()
                .cur(singletonList("USD"))
                .tmax(1000L)
                .app(App.builder().build())
                .imp(singletonList(Imp.builder()
                        .id(IMP_ID)
                        .xNative(Native.builder().request(mapper.writeValueAsString(nativeRequest)).build())
                        .build()))
                .build();

        final AuctionContext auctionContext = givenAuctionContext(bidRequest);

        final Response responseAdm = Response.builder()
                .assets(singletonList(com.iab.openrtb.response.Asset.builder()
                        .id(123)
                        .img(com.iab.openrtb.response.ImageObject.builder().build())
                        .data(com.iab.openrtb.response.DataObject.builder().build())
                        .build()))
                .build();

        final Bid bid = Bid.builder().id("bidId")
                .price(BigDecimal.ONE)
                .impid(IMP_ID)
                .adm(mapper.writeValueAsString(responseAdm))
                .ext(mapper.valueToTree(singletonMap("bidExt", 1)))
                .build();
        final List<BidderResponse> bidderResponses = singletonList(BidderResponse.of("bidder1",
                givenSeatBid(BidderBid.of(bid, xNative, "USD")), 100));

        // when
        final BidResponse bidResponse =
                bidResponseCreator.create(bidderResponses, auctionContext, CACHE_INFO, false).result();

        // then
        assertThat(bidResponse.getSeatbid()).hasSize(1)
                .flatExtracting(SeatBid::getBid)
                .extracting(Bid::getAdm)
                .extracting(adm -> mapper.readValue(adm, Response.class))
                .flatExtracting(Response::getAssets)
                .isEmpty();
    }

    @Test
    public void shouldSetBidAdmToNullIfCacheIdIsPresentAndReturnCreativeBidsIsFalse() {
        // given
        final AuctionContext auctionContext = givenAuctionContext(givenBidRequest(
                identity(),
                extBuilder -> extBuilder.targeting(givenTargeting()),
                givenImp()));

        final Bid bid = Bid.builder()
                .price(BigDecimal.ONE)
                .adm("adm")
                .impid(IMP_ID)
                .build();
        final List<BidderResponse> bidderResponses = singletonList(BidderResponse.of("bidder1",
                givenSeatBid(BidderBid.of(bid, banner, "USD")), 100));

        givenCacheServiceResult(singletonMap(bid, CacheInfo.of("id", null, null, null)));

        final BidRequestCacheInfo cacheInfo = BidRequestCacheInfo.builder().doCaching(true).build();

        // when
        final BidResponse bidResponse =
                bidResponseCreator.create(bidderResponses, auctionContext, cacheInfo, false).result();

        // then
        assertThat(bidResponse.getSeatbid())
                .flatExtracting(SeatBid::getBid).hasSize(1)
                .extracting(Bid::getAdm)
                .containsNull();

        verify(cacheService).cacheBidsOpenrtb(anyList(), any(), any(), any());
    }

    @Test
    public void shouldSetBidAdmToNullIfVideoCacheIdIsPresentAndReturnCreativeVideoBidsIsFalse() {
        // given
        final AuctionContext auctionContext = givenAuctionContext(givenBidRequest(
                identity(),
                extBuilder -> extBuilder.targeting(givenTargeting()),
                Imp.builder().id(IMP_ID).build()));

        final Bid bid = Bid.builder().price(BigDecimal.ONE).adm("adm").impid(IMP_ID).build();
        final List<BidderResponse> bidderResponses = singletonList(BidderResponse.of("bidder1",
                givenSeatBid(BidderBid.of(bid, banner, "USD")), 100));

        final BidRequestCacheInfo cacheInfo = BidRequestCacheInfo.builder().doCaching(true).build();

        givenCacheServiceResult(singletonMap(bid, CacheInfo.of("id", null, null, null)));

        // when
        final BidResponse bidResponse =
                bidResponseCreator.create(bidderResponses, auctionContext, cacheInfo, false).result();

        // then
        assertThat(bidResponse.getSeatbid())
                .flatExtracting(SeatBid::getBid).hasSize(1)
                .extracting(Bid::getAdm)
                .containsNull();

        verify(cacheService).cacheBidsOpenrtb(anyList(), any(), any(), any());
    }

    @Test
    public void shouldModifyBidAdmWhenBidVideoAndVastModifierReturnValue() {
        // given
        final AuctionContext auctionContext = givenAuctionContext(givenBidRequest(
                identity(),
                extBuilder -> extBuilder.targeting(givenTargeting()),
                givenImp()));

        final Bid bid = Bid.builder()
                .price(BigDecimal.ONE)
                .adm("adm")
                .impid(IMP_ID)
                .build();
        final List<BidderResponse> bidderResponses = singletonList(BidderResponse.of("bidder1",
                givenSeatBid(BidderBid.of(bid, video, "USD")), 100));

        final String modifiedVast = "modifiedVast";
        given(vastModifier.createBidVastXml(any(), anyString(), anyString(), any())).willReturn(modifiedVast);

        // when
        final BidResponse bidResponse =
                bidResponseCreator.create(bidderResponses, auctionContext, CACHE_INFO, false).result();

        // then
        assertThat(bidResponse.getSeatbid())
                .flatExtracting(SeatBid::getBid).hasSize(1)
                .extracting(Bid::getAdm)
                .containsOnly(modifiedVast);

        verify(vastModifier).createBidVastXml(eq(bid), eq("bidder1"), eq("accountId"), any());
    }

    @Test
    public void shouldSetBidExpWhenCacheIdIsMatched() {
        // given
        final AuctionContext auctionContext = givenAuctionContext(givenBidRequest(
                identity(),
                extBuilder -> extBuilder.targeting(givenTargeting()),
                givenImp()));

        final Bid bid = Bid.builder().price(BigDecimal.ONE).impid(IMP_ID).build();
        final List<BidderResponse> bidderResponses = singletonList(BidderResponse.of("bidder1",
                givenSeatBid(BidderBid.of(bid, banner, "USD")), 100));

        givenCacheServiceResult(singletonMap(bid, CacheInfo.of("id", null, 100, null)));

        final BidRequestCacheInfo cacheInfo = BidRequestCacheInfo.builder().doCaching(true).build();

        // when
        final BidResponse bidResponse =
                bidResponseCreator.create(bidderResponses, auctionContext, cacheInfo, false).result();

        // then
        assertThat(bidResponse.getSeatbid())
                .flatExtracting(SeatBid::getBid).hasSize(1)
                .extracting(Bid::getExp)
                .containsOnly(100);

        verify(cacheService).cacheBidsOpenrtb(anyList(), any(), any(), any());
    }

    @Test
    public void shouldSetBidExpMaxTtlWhenCacheIdIsMatchedAndBothTtlIsSet() {
        // given
        final AuctionContext auctionContext = givenAuctionContext(givenBidRequest(
                identity(),
                extBuilder -> extBuilder.targeting(givenTargeting()),
                givenImp()));

        final Bid bid = Bid.builder().price(BigDecimal.ONE).impid(IMP_ID).build();
        final List<BidderResponse> bidderResponses = singletonList(BidderResponse.of("bidder1",
                givenSeatBid(BidderBid.of(bid, banner, "USD")), 100));

        givenCacheServiceResult(singletonMap(bid, CacheInfo.of("id", null, 100, 200)));

        final BidRequestCacheInfo cacheInfo = BidRequestCacheInfo.builder().doCaching(true).build();

        // when
        final BidResponse bidResponse =
                bidResponseCreator.create(bidderResponses, auctionContext, cacheInfo, false).result();

        // then
        assertThat(bidResponse.getSeatbid())
                .flatExtracting(SeatBid::getBid).hasSize(1)
                .extracting(Bid::getExp)
                .containsOnly(200);

        verify(cacheService).cacheBidsOpenrtb(anyList(), any(), any(), any());
    }

    @Test
    public void shouldTolerateMissingExtInSeatBidAndBid() {
        // given
        final AuctionContext auctionContext = givenAuctionContext(givenBidRequest(givenImp()));

        final Bid bid = Bid.builder().id("bidId").price(BigDecimal.ONE).impid(IMP_ID).build();
        final List<BidderResponse> bidderResponses = singletonList(BidderResponse.of("bidder1",
                givenSeatBid(BidderBid.of(bid, banner, "USD")), 100));

        // when
        final BidResponse bidResponse =
                bidResponseCreator.create(bidderResponses, auctionContext, CACHE_INFO, false).result();

        // then
        assertThat(bidResponse.getSeatbid()).hasSize(1)
                .flatExtracting(SeatBid::getBid)
                .containsOnly(Bid.builder()
                        .id("bidId")
                        .impid(IMP_ID)
                        .price(BigDecimal.ONE)
                        .ext(mapper.valueToTree(
                                ExtPrebid.of(ExtBidPrebid.builder().type(banner).build(), null)))
                        .build());

        verify(cacheService, never()).cacheBidsOpenrtb(anyList(), any(), any(), any());
    }

    @Test
    public void shouldPopulateTargetingKeywords() {
        // given
        final AuctionContext auctionContext = givenAuctionContext(givenBidRequest(
                identity(),
                extBuilder -> extBuilder.targeting(givenTargeting()),
                givenImp()));

        final Bid bid = Bid.builder().id("bidId1").price(BigDecimal.valueOf(5.67)).impid(IMP_ID).build();
        final List<BidderResponse> bidderResponses = singletonList(BidderResponse.of("bidder1",
                givenSeatBid(BidderBid.of(bid, banner, "USD")), 100));

        // when
        final BidResponse bidResponse =
                bidResponseCreator.create(bidderResponses, auctionContext, CACHE_INFO, false).result();

        // then
        assertThat(bidResponse.getSeatbid())
                .flatExtracting(SeatBid::getBid).hasSize(1)
                .extracting(extractedBid -> toExtPrebid(extractedBid.getExt()).getPrebid().getTargeting())
                .flatExtracting(Map::entrySet)
                .extracting(Map.Entry::getKey, Map.Entry::getValue)
                .containsOnly(
                        tuple("hb_pb", "5.00"),
                        tuple("hb_pb_bidder1", "5.00"),
                        tuple("hb_bidder", "bidder1"),
                        tuple("hb_bidder_bidder1", "bidder1"));

        verify(cacheService, never()).cacheBidsOpenrtb(anyList(), any(), any(), any());
    }

    @Test
    public void shouldTruncateTargetingKeywordsByGlobalConfig() {
        // given
        final AuctionContext auctionContext = givenAuctionContext(givenBidRequest(
                identity(),
                extBuilder -> extBuilder.targeting(givenTargeting()),
                givenImp()));

        final Bid bid = Bid.builder().id("bidId1").price(BigDecimal.valueOf(5.67)).impid(IMP_ID).build();
        final List<BidderResponse> bidderResponses = singletonList(BidderResponse.of("someVeryLongBidderName",
                givenSeatBid(BidderBid.of(bid, banner, "USD")), 100));

        final BidResponseCreator bidResponseCreator = new BidResponseCreator(
                cacheService,
                bidderCatalog,
                vastModifier,
                eventsService,
                storedRequestProcessor,
                false,
                20,
                clock,
                jacksonMapper);

        // when
        final BidResponse bidResponse =
                bidResponseCreator.create(bidderResponses, auctionContext, CACHE_INFO, false).result();

        // then
        assertThat(bidResponse.getSeatbid())
                .flatExtracting(SeatBid::getBid).hasSize(1)
                .extracting(extractedBid -> toExtPrebid(extractedBid.getExt()).getPrebid().getTargeting())
                .flatExtracting(Map::entrySet)
                .extracting(Map.Entry::getKey, Map.Entry::getValue)
                .containsOnly(
                        tuple("hb_pb", "5.00"),
                        tuple("hb_pb_someVeryLongBi", "5.00"),
                        tuple("hb_bidder", "someVeryLongBidderName"),
                        tuple("hb_bidder_someVeryLo", "someVeryLongBidderName"),
                        tuple("hb_bidder_someVeryLo", "someVeryLongBidderName"));
    }

    @Test
    public void shouldTruncateTargetingKeywordsByAccountConfig() {
        // given
        final Account account = Account.builder().id("accountId").truncateTargetAttr(20).build();
        final BidRequest bidRequest = givenBidRequest(
                identity(),
                extBuilder -> extBuilder.targeting(givenTargeting()),
                givenImp());
        final AuctionContext auctionContext = givenAuctionContext(
                bidRequest,
                contextBuilder -> contextBuilder.account(account));

        final Bid bid = Bid.builder().id("bidId1").price(BigDecimal.valueOf(5.67)).impid(IMP_ID).build();
        final List<BidderResponse> bidderResponses = singletonList(BidderResponse.of("someVeryLongBidderName",
                givenSeatBid(BidderBid.of(bid, banner, "USD")), 100));

        // when
        final BidResponse bidResponse =
                bidResponseCreator.create(bidderResponses, auctionContext, CACHE_INFO, false).result();

        // then
        assertThat(bidResponse.getSeatbid())
                .flatExtracting(SeatBid::getBid).hasSize(1)
                .extracting(extractedBid -> toExtPrebid(extractedBid.getExt()).getPrebid().getTargeting())
                .flatExtracting(Map::entrySet)
                .extracting(Map.Entry::getKey, Map.Entry::getValue)
                .containsOnly(
                        tuple("hb_pb", "5.00"),
                        tuple("hb_pb_someVeryLongBi", "5.00"),
                        tuple("hb_bidder", "someVeryLongBidderName"),
                        tuple("hb_bidder_someVeryLo", "someVeryLongBidderName"));
    }

    @Test
    public void shouldTruncateTargetingKeywordsByRequestPassedValue() {
        // given
        final Account account = Account.builder().id("accountId").truncateTargetAttr(25).build();
        final ExtRequestTargeting targeting = ExtRequestTargeting.builder()
                .pricegranularity(mapper.valueToTree(
                        ExtPriceGranularity.of(2,
                                singletonList(ExtGranularityRange.of(BigDecimal.valueOf(5), BigDecimal.valueOf(0.5))))))
                .includewinners(true)
                .includebidderkeys(true)
                .includeformat(false)
                .truncateattrchars(20)
                .build();
        final BidRequest bidRequest = givenBidRequest(
                identity(),
                extBuilder -> extBuilder.targeting(targeting),
                givenImp());

        final AuctionContext auctionContext = givenAuctionContext(
                bidRequest,
                contextBuilder -> contextBuilder.account(account));

        final Bid bid = Bid.builder().id("bidId1").price(BigDecimal.valueOf(5.67)).impid(IMP_ID).build();
        final List<BidderResponse> bidderResponses = singletonList(BidderResponse.of("someVeryLongBidderName",
                givenSeatBid(BidderBid.of(bid, banner, "USD")), 100));

        // when
        final BidResponse bidResponse =
                bidResponseCreator.create(bidderResponses, auctionContext, CACHE_INFO, false).result();

        // then
        assertThat(bidResponse.getSeatbid())
                .flatExtracting(SeatBid::getBid).hasSize(1)
                .extracting(extractedBid -> toExtPrebid(extractedBid.getExt()).getPrebid().getTargeting())
                .flatExtracting(Map::entrySet)
                .extracting(Map.Entry::getKey, Map.Entry::getValue)
                .containsOnly(
                        tuple("hb_pb", "5.00"),
                        tuple("hb_pb_someVeryLongBi", "5.00"),
                        tuple("hb_bidder", "someVeryLongBidderName"),
                        tuple("hb_bidder_someVeryLo", "someVeryLongBidderName"));
    }

    @Test
    public void shouldPopulateTargetingKeywordsForWinningBidsAndWinningBidsByBidder() {
        // given
        final AuctionContext auctionContext = givenAuctionContext(givenBidRequest(
                identity(),
                extBuilder -> extBuilder.targeting(givenTargeting()),
                givenImp("i1"), givenImp("i2")));

        final Bid firstBid = Bid.builder().id("bidId1").price(BigDecimal.valueOf(5.67)).impid("i1").build();
        final Bid secondBid = Bid.builder().id("bidId2").price(BigDecimal.valueOf(4.98)).impid("i2").build();
        final Bid thirdBid = Bid.builder().id("bidId3").price(BigDecimal.valueOf(7.25)).impid("i2").build();
        final List<BidderResponse> bidderResponses = asList(
                BidderResponse.of("bidder1",
                        givenSeatBid(BidderBid.of(firstBid, banner, null),
                                BidderBid.of(secondBid, banner, null)), 100),
                BidderResponse.of("bidder2",
                        givenSeatBid(BidderBid.of(thirdBid, banner, null)), 111));

        // when
        final BidResponse bidResponse =
                bidResponseCreator.create(bidderResponses, auctionContext, CACHE_INFO, false).result();

        // then
        assertThat(bidResponse.getSeatbid())
                .flatExtracting(SeatBid::getBid).hasSize(3)
                .extracting(
                        Bid::getId,
                        bid -> toTargetingByKey(bid, "hb_bidder"),
                        bid -> toTargetingByKey(bid, "hb_bidder_bidder1"),
                        bid -> toTargetingByKey(bid, "hb_bidder_bidder2"))
                .containsOnly(
                        tuple("bidId1", "bidder1", "bidder1", null),
                        tuple("bidId2", null, "bidder1", null),
                        tuple("bidId3", "bidder2", null, "bidder2"));

        verify(cacheService, never()).cacheBidsOpenrtb(anyList(), any(), any(), any());
    }

    @Test
    public void shouldPopulateTargetingKeywordsFromMediaTypePriceGranularities() {
        // given
        final ExtMediaTypePriceGranularity extMediaTypePriceGranularity = ExtMediaTypePriceGranularity.of(
                mapper.valueToTree(ExtPriceGranularity.of(
                        3,
                        singletonList(ExtGranularityRange.of(
                                BigDecimal.valueOf(10), BigDecimal.valueOf(1))))),
                null,
                null);
        final ExtPriceGranularity extPriceGranularity = ExtPriceGranularity.of(2,
                singletonList(ExtGranularityRange.of(BigDecimal.valueOf(5), BigDecimal.valueOf(0.5))));

        final ExtRequestTargeting targeting = ExtRequestTargeting.builder()
                .pricegranularity(mapper.valueToTree(extPriceGranularity))
                .mediatypepricegranularity(extMediaTypePriceGranularity)
                .includewinners(true)
                .includebidderkeys(true)
                .includeformat(false)
                .build();
        final AuctionContext auctionContext = givenAuctionContext(givenBidRequest(
                identity(),
                extBuilder -> extBuilder.targeting(targeting),
                givenImp()));

        final Bid bid = Bid.builder().id("bidId").price(BigDecimal.valueOf(5.67)).impid(IMP_ID).build();
        final List<BidderResponse> bidderResponses = singletonList(BidderResponse.of("bidder1",
                givenSeatBid(BidderBid.of(bid, banner, "USD")), 100));

        // when
        final BidResponse bidResponse =
                bidResponseCreator.create(bidderResponses, auctionContext, CACHE_INFO, false).result();

        // then
        assertThat(bidResponse.getSeatbid())
                .flatExtracting(SeatBid::getBid)
                .extracting(extractedBid -> toExtPrebid(extractedBid.getExt()).getPrebid().getTargeting())
                .flatExtracting(Map::entrySet)
                .extracting(Map.Entry::getKey, Map.Entry::getValue)
                .containsOnly(
                        tuple("hb_pb", "5.000"),
                        tuple("hb_bidder", "bidder1"),
                        tuple("hb_pb_bidder1", "5.000"),
                        tuple("hb_bidder_bidder1", "bidder1"));

        verify(cacheService, never()).cacheBidsOpenrtb(anyList(), any(), any(), any());
    }

    @Test
    public void shouldPopulateCacheIdHostPathAndUuidTargetingKeywords() {
        // given
        final AuctionContext auctionContext = givenAuctionContext(givenBidRequest(
                identity(),
                extBuilder -> extBuilder.targeting(givenTargeting()),
                givenImp()));

        final Bid bid = Bid.builder().id("bidId").price(BigDecimal.valueOf(5.67)).impid(IMP_ID).build();
        final List<BidderResponse> bidderResponses = singletonList(BidderResponse.of("bidder1",
                givenSeatBid(BidderBid.of(bid, banner, "USD")), 100));
        final BidRequestCacheInfo cacheInfo = BidRequestCacheInfo.builder().doCaching(true).build();

        givenCacheServiceResult(singletonMap(bid, CacheInfo.of("cacheId", "videoId", null, null)));

        // when
        final BidResponse bidResponse =
                bidResponseCreator.create(bidderResponses, auctionContext, cacheInfo, false).result();

        // then
        assertThat(bidResponse.getSeatbid())
                .flatExtracting(SeatBid::getBid).hasSize(1)
                .extracting(extractedBid -> toExtPrebid(extractedBid.getExt()).getPrebid().getTargeting())
                .flatExtracting(Map::entrySet)
                .extracting(Map.Entry::getKey, Map.Entry::getValue)
                .containsOnly(
                        tuple("hb_pb", "5.00"),
                        tuple("hb_bidder", "bidder1"),
                        tuple("hb_cache_id", "cacheId"),
                        tuple("hb_uuid", "videoId"),
                        tuple("hb_cache_host", "testHost"),
                        tuple("hb_cache_path", "testPath"),
                        tuple("hb_pb_bidder1", "5.00"),
                        tuple("hb_bidder_bidder1", "bidder1"),
                        tuple("hb_cache_id_bidder1", "cacheId"),
                        tuple("hb_uuid_bidder1", "videoId"),
                        tuple("hb_cache_host_bidder1", "testHost"),
                        tuple("hb_cache_path_bidder1", "testPath"));

        verify(cacheService).cacheBidsOpenrtb(anyList(), any(), any(), any());
    }

    @Test
    public void shouldPopulateTargetingKeywordsWithAdditionalValuesFromRequest() {
        // given
        final AuctionContext auctionContext = givenAuctionContext(givenBidRequest(
                identity(),
                extBuilder -> extBuilder
                        .targeting(givenTargeting())
                        .adservertargeting(singletonList(ExtRequestPrebidAdservertargetingRule.of(
                                "static_keyword1", xStatic, "static_keyword1"))),
                givenImp()));

        final Bid bid = Bid.builder().id("bidId1").price(BigDecimal.valueOf(5.67)).impid(IMP_ID).build();
        final List<BidderResponse> bidderResponses = singletonList(BidderResponse.of(
                "bidder1", givenSeatBid(BidderBid.of(bid, banner, "USD")), 100));

        // when
        final BidResponse bidResponse =
                bidResponseCreator.create(bidderResponses, auctionContext, CACHE_INFO, false).result();

        // then
        assertThat(bidResponse.getSeatbid())
                .flatExtracting(SeatBid::getBid).hasSize(1)
                .extracting(extractedBid -> toExtPrebid(extractedBid.getExt()).getPrebid().getTargeting())
                .flatExtracting(Map::entrySet)
                .extracting(Map.Entry::getKey, Map.Entry::getValue)
                .contains(tuple("static_keyword1", "static_keyword1"));
    }

    @Test
    public void shouldPopulateTargetingKeywordsIfBidWasCachedAndAdmWasRemoved() {
        // given
        final AuctionContext auctionContext = givenAuctionContext(givenBidRequest(
                identity(),
                extBuilder -> extBuilder.targeting(givenTargeting()),
                givenImp()));

        final Bid bid = Bid.builder().id("bidId").price(BigDecimal.valueOf(5.67)).impid(IMP_ID).adm("adm").build();
        final List<BidderResponse> bidderResponses = singletonList(BidderResponse.of("bidder1",
                givenSeatBid(BidderBid.of(bid, banner, "USD")), 100));

        final BidRequestCacheInfo cacheInfo = BidRequestCacheInfo.builder()
                .doCaching(true)
                .returnCreativeBids(false) // this will cause erasing of bid.adm
                .build();

        givenCacheServiceResult(singletonMap(bid, CacheInfo.of("cacheId", null, null, null)));

        // when
        final BidResponse bidResponse =
                bidResponseCreator.create(bidderResponses, auctionContext, cacheInfo, false).result();

        // then
        assertThat(bidResponse.getSeatbid())
                .flatExtracting(SeatBid::getBid).hasSize(1)
                .extracting(extractedBid -> toExtPrebid(extractedBid.getExt()).getPrebid().getTargeting())
                .doesNotContainNull();
    }

    @Test
    public void shouldAddExtPrebidEventsIfEventsAreEnabledAndExtRequestPrebidEventPresent() {
        // given
        final Account account = Account.builder().id("accountId").eventsEnabled(true).build();
        final BidRequest bidRequest = givenBidRequest(
                identity(),
                extBuilder -> extBuilder
                        .events(mapper.createObjectNode())
                        .integration("pbjs"),
                givenImp());

        final AuctionContext auctionContext = givenAuctionContext(
                bidRequest,
                contextBuilder -> contextBuilder.account(account));

        final Bid bid = Bid.builder()
                .id("bidId1")
                .price(BigDecimal.valueOf(5.67))
                .impid(IMP_ID)
                .build();
        final List<BidderResponse> bidderResponses = singletonList(
                BidderResponse.of("bidder1", givenSeatBid(BidderBid.of(bid, banner, "USD")), 100));

        final Events events = Events.of("http://event-type-win", "http://event-type-view");
        given(eventsService.createEvent(anyString(), anyString(), anyString(), anyLong(), anyString()))
                .willReturn(events);

        // when
        final BidResponse bidResponse =
                bidResponseCreator.create(bidderResponses, auctionContext, CACHE_INFO, false).result();

        // then
        assertThat(bidResponse.getSeatbid()).hasSize(1)
                .flatExtracting(SeatBid::getBid)
                .extracting(responseBid -> toExtPrebid(responseBid.getExt()).getPrebid().getEvents())
                .containsOnly(events);

        verify(cacheService, never()).cacheBidsOpenrtb(anyList(), any(), any(), any());
    }

    @Test
    public void shouldAddExtPrebidEventsIfEventsAreEnabledAndAccountSupportEventsForChannel() {
        // given
        final Account account = Account.builder()
                .id("accountId")
                .eventsEnabled(true)
                .analyticsConfig(AccountAnalyticsConfig.of(singletonMap("web", true)))
                .build();
        final BidRequest bidRequest = givenBidRequest(
                identity(),
                extBuilder -> extBuilder
                        .channel(ExtRequestPrebidChannel.of("web"))
                        .integration("pbjs"),
                givenImp());
        final AuctionContext auctionContext = givenAuctionContext(
                bidRequest,
                contextBuilder -> contextBuilder.account(account));

        final Bid bid = Bid.builder()
                .id("bidId1")
                .price(BigDecimal.valueOf(5.67))
                .impid(IMP_ID)
                .build();
        final List<BidderResponse> bidderResponses = singletonList(
                BidderResponse.of("bidder1", givenSeatBid(BidderBid.of(bid, banner, "USD")), 100));

        final Events events = Events.of("http://event-type-win", "http://event-type-view");
        given(eventsService.createEvent(anyString(), anyString(), anyString(), anyLong(), anyString()))
                .willReturn(events);

        // when
        final BidResponse bidResponse =
                bidResponseCreator.create(bidderResponses, auctionContext, CACHE_INFO, false).result();

        // then
        assertThat(bidResponse.getSeatbid()).hasSize(1)
                .flatExtracting(SeatBid::getBid)
                .extracting(responseBid -> toExtPrebid(responseBid.getExt()).getPrebid().getEvents())
                .containsOnly(events);

        verify(cacheService, never()).cacheBidsOpenrtb(anyList(), any(), any(), any());
    }

    @Test
    public void shouldAddExtPrebidEventsIfEventsAreEnabledAndDefaultAccountAnalyticsConfig() {
        // given
        final Account account = Account.builder().id("accountId").eventsEnabled(true).build();
        final BidRequest bidRequest = givenBidRequest(
                identity(),
                extBuilder -> extBuilder
                        .channel(ExtRequestPrebidChannel.of("amp"))
                        .integration("pbjs"),
                givenImp());
        final AuctionContext auctionContext = givenAuctionContext(
                bidRequest,
                contextBuilder -> contextBuilder.account(account));

        final Bid bid = Bid.builder()
                .id("bidId1")
                .price(BigDecimal.valueOf(5.67))
                .impid(IMP_ID)
                .build();
        final List<BidderResponse> bidderResponses = singletonList(
                BidderResponse.of("bidder1", givenSeatBid(BidderBid.of(bid, banner, "USD")), 100));

        final Events events = Events.of("http://event-type-win", "http://event-type-view");
        given(eventsService.createEvent(anyString(), anyString(), anyString(), anyLong(), anyString()))
                .willReturn(events);

        // when
        final BidResponse bidResponse =
                bidResponseCreator.create(bidderResponses, auctionContext, CACHE_INFO, false).result();

        // then
        assertThat(bidResponse.getSeatbid()).hasSize(1)
                .flatExtracting(SeatBid::getBid)
                .extracting(responseBid -> toExtPrebid(responseBid.getExt()).getPrebid().getEvents())
                .containsOnly(events);

        verify(cacheService, never()).cacheBidsOpenrtb(anyList(), any(), any(), any());
    }

    @Test
    public void shouldAddExtPrebidVideo() {
        // given
        final AuctionContext auctionContext = givenAuctionContext(givenBidRequest(givenImp()));

        final ExtBidPrebid extBidPrebid = ExtBidPrebid.builder().video(ExtBidPrebidVideo.of(1, "category")).build();
        final Bid bid = Bid.builder()
                .id("bidId1")
                .price(BigDecimal.valueOf(5.67))
                .impid(IMP_ID)
                .ext(mapper.createObjectNode().set("prebid", mapper.valueToTree(extBidPrebid)))
                .build();
        final List<BidderResponse> bidderResponses = singletonList(BidderResponse.of("bidder1",
                givenSeatBid(BidderBid.of(bid, banner, "USD")), 100));

        // when
        final BidResponse bidResponse =
                bidResponseCreator.create(bidderResponses, auctionContext, CACHE_INFO, false).result();

        // then
        assertThat(bidResponse.getSeatbid()).hasSize(1)
                .flatExtracting(SeatBid::getBid)
                .extracting(responseBid -> toExtPrebid(responseBid.getExt()).getPrebid().getVideo())
                .containsOnly(ExtBidPrebidVideo.of(1, "category"));
    }

    @Test
    public void shouldNotAddExtPrebidEventsIfEventsAreNotEnabled() {
        // given
        final Account account = Account.builder().id("accountId").eventsEnabled(false).build();
        final AuctionContext auctionContext = givenAuctionContext(
                givenBidRequest(
                        identity(),
                        extBuilder -> extBuilder.events(mapper.createObjectNode()),
                        givenImp()),
                contextBuilder -> contextBuilder.account(account));

        final Bid bid = Bid.builder().id("bidId1").price(BigDecimal.valueOf(5.67)).impid(IMP_ID).build();
        final List<BidderResponse> bidderResponses = singletonList(BidderResponse.of("bidder1",
                givenSeatBid(BidderBid.of(bid, banner, "USD")), 100));

        // when
        final BidResponse bidResponse =
                bidResponseCreator.create(bidderResponses, auctionContext, CACHE_INFO, false).result();

        // then
        assertThat(bidResponse.getSeatbid()).hasSize(1)
                .flatExtracting(SeatBid::getBid)
                .extracting(responseBid -> toExtPrebid(responseBid.getExt()).getPrebid().getEvents())
                .containsNull();
    }

    @Test
    public void shouldNotAddExtPrebidEventsIfExtRequestPrebidEventsNull() {
        // given
        final Account account = Account.builder().id("accountId").eventsEnabled(true).build();
        final AuctionContext auctionContext = givenAuctionContext(
                givenBidRequest(givenImp()),
                contextBuilder -> contextBuilder.account(account));

        final Bid bid = Bid.builder()
                .id("bidId1")
                .price(BigDecimal.valueOf(5.67))
                .impid(IMP_ID)
                .build();
        final List<BidderResponse> bidderResponses = singletonList(
                BidderResponse.of("bidder1", givenSeatBid(BidderBid.of(bid, banner, "USD")), 100));

        // when
        final BidResponse bidResponse =
                bidResponseCreator.create(bidderResponses, auctionContext, CACHE_INFO, false).result();

        // then
        assertThat(bidResponse.getSeatbid()).hasSize(1)
                .flatExtracting(SeatBid::getBid)
                .extracting(responseBid -> toExtPrebid(responseBid.getExt()).getPrebid().getEvents())
                .containsNull();
    }

    @Test
    public void shouldNotAddExtPrebidEventsIfAccountDoesNotSupportEventsForChannel() {
        // given
        final Account account = Account.builder()
                .id("accountId")
                .eventsEnabled(true)
                .analyticsConfig(AccountAnalyticsConfig.of(singletonMap("web", true)))
                .build();
        final BidRequest bidRequest = givenBidRequest(
                identity(),
                extBuilder -> extBuilder.channel(ExtRequestPrebidChannel.of("amp")),
                givenImp());
        final AuctionContext auctionContext = givenAuctionContext(
                bidRequest,
                contextBuilder -> contextBuilder.account(account));

        final Bid bid = Bid.builder()
                .id("bidId1")
                .price(BigDecimal.valueOf(5.67))
                .impid(IMP_ID)
                .build();
        final List<BidderResponse> bidderResponses = singletonList(
                BidderResponse.of("bidder1", givenSeatBid(BidderBid.of(bid, banner, "USD")), 100));

        // when
        final BidResponse bidResponse =
                bidResponseCreator.create(bidderResponses, auctionContext, CACHE_INFO, false).result();

        // then
        assertThat(bidResponse.getSeatbid()).hasSize(1)
                .flatExtracting(SeatBid::getBid)
                .extracting(responseBid -> toExtPrebid(responseBid.getExt()).getPrebid().getEvents())
                .containsNull();
    }

    @Test
    public void shouldReturnCacheEntityInExt() {
        // given
        final AuctionContext auctionContext = givenAuctionContext(givenBidRequest(
                identity(),
                extBuilder -> extBuilder.targeting(givenTargeting()),
                givenImp()));

        final Bid bid = Bid.builder().id("bidId").price(BigDecimal.valueOf(5.67)).impid(IMP_ID).build();
        final List<BidderResponse> bidderResponses = singletonList(BidderResponse.of("bidder1",
                givenSeatBid(BidderBid.of(bid, banner, "USD")), 100));

        final BidRequestCacheInfo cacheInfo = BidRequestCacheInfo.builder()
                .doCaching(true)
                .shouldCacheBids(true)
                .shouldCacheVideoBids(true)
                .build();

        givenCacheServiceResult(singletonMap(bid, CacheInfo.of("cacheId", "videoId", null, null)));

        // when
        final BidResponse bidResponse =
                bidResponseCreator.create(bidderResponses, auctionContext, cacheInfo, false).result();

        // then
        assertThat(bidResponse.getSeatbid())
                .flatExtracting(SeatBid::getBid)
                .extracting(extractedBid -> toExtPrebid(extractedBid.getExt()).getPrebid().getCache())
                .extracting(ExtResponseCache::getBids, ExtResponseCache::getVastXml)
                .containsExactly(tuple(
                        CacheAsset.of("uuid=cacheId", "cacheId"),
                        CacheAsset.of("uuid=videoId", "videoId")));

        verify(cacheService).cacheBidsOpenrtb(anyList(), any(), any(), any());
    }

    @Test
    public void shouldNotPopulateWinningBidTargetingIfIncludeWinnersFlagIsFalse() {
        // given
        final AuctionContext auctionContext = givenAuctionContext(givenBidRequest(
                identity(),
                extBuilder -> extBuilder.targeting(ExtRequestTargeting.builder()
                        .pricegranularity(mapper.valueToTree(
                                ExtPriceGranularity.of(2, singletonList(ExtGranularityRange.of(BigDecimal.valueOf(5),
                                        BigDecimal.valueOf(0.5))))))
                        .includewinners(false)
                        .includebidderkeys(true)
                        .includeformat(false)
                        .build()),
                givenImp()));

        final Bid bid = Bid.builder().id("bidId").price(BigDecimal.valueOf(5.67)).impid(IMP_ID).build();
        final List<BidderResponse> bidderResponses = singletonList(BidderResponse.of("bidder1",
                givenSeatBid(BidderBid.of(bid, banner, "USD")), 100));

        final BidRequestCacheInfo cacheInfo = BidRequestCacheInfo.builder()
                .doCaching(true)
                .shouldCacheBids(true)
                .shouldCacheVideoBids(true)
                .build();

        givenCacheServiceResult(singletonMap(bid, CacheInfo.of("cacheId", "videoId", null, null)));

        // when
        final BidResponse bidResponse =
                bidResponseCreator.create(bidderResponses, auctionContext, cacheInfo, false).result();

        // then
        assertThat(bidResponse.getSeatbid()).flatExtracting(SeatBid::getBid)
                .extracting(
                        Bid::getId,
                        extractedBid -> toTargetingByKey(extractedBid, "hb_bidder"),
                        extractedBid -> toTargetingByKey(extractedBid, "hb_bidder_bidder1"))
                .containsOnly(
                        tuple("bidId", null, "bidder1"));

        verify(cacheService).cacheBidsOpenrtb(anyList(), any(), any(), any());
    }

    @Test
    public void shouldNotPopulateBidderKeysTargetingIfIncludeBidderKeysFlagIsFalse() {
        // given
        final AuctionContext auctionContext = givenAuctionContext(givenBidRequest(
                identity(),
                extBuilder -> extBuilder.targeting(ExtRequestTargeting.builder()
                        .pricegranularity(mapper.valueToTree(
                                ExtPriceGranularity.of(2, singletonList(ExtGranularityRange.of(BigDecimal.valueOf(5),
                                        BigDecimal.valueOf(0.5))))))
                        .includewinners(true)
                        .includebidderkeys(false)
                        .includeformat(false)
                        .build()),
                givenImp()));

        final Bid bid = Bid.builder().id("bidId").price(BigDecimal.valueOf(5.67)).impid(IMP_ID).build();
        final List<BidderResponse> bidderResponses = singletonList(BidderResponse.of("bidder1",
                givenSeatBid(BidderBid.of(bid, banner, "USD")), 100));

        final BidRequestCacheInfo cacheInfo = BidRequestCacheInfo.builder()
                .doCaching(true)
                .shouldCacheBids(true)
                .shouldCacheVideoBids(true)
                .build();

        givenCacheServiceResult(singletonMap(bid, CacheInfo.of("cacheId", "videoId", null, null)));

        // when
        final BidResponse bidResponse =
                bidResponseCreator.create(bidderResponses, auctionContext, cacheInfo, false).result();

        // then
        assertThat(bidResponse.getSeatbid()).flatExtracting(SeatBid::getBid)
                .extracting(
                        Bid::getId,
                        extractedBid -> toTargetingByKey(extractedBid, "hb_bidder"),
                        extractedBid -> toTargetingByKey(extractedBid, "hb_bidder_bidder1"))
                .containsOnly(
                        tuple("bidId", "bidder1", null));

        verify(cacheService).cacheBidsOpenrtb(anyList(), any(), any(), any());
    }

    @Test
    public void shouldNotPopulateCacheIdTargetingKeywordsIfBidCpmIsZero() {
        // given
        final AuctionContext auctionContext = givenAuctionContext(givenBidRequest(
                identity(),
                extBuilder -> extBuilder.targeting(givenTargeting()),
                givenImp("impId1"), givenImp("impId2")));

        final Bid firstBid = Bid.builder().id("bidId1").impid("impId1").price(BigDecimal.ZERO).build();
        final Bid secondBid = Bid.builder().id("bidId2").impid("impId2").price(BigDecimal.valueOf(5.67)).build();

        final List<BidderResponse> bidderResponses = asList(
                BidderResponse.of("bidder1", givenSeatBid(BidderBid.of(firstBid, banner, null)), 99),
                BidderResponse.of("bidder2", givenSeatBid(BidderBid.of(secondBid, banner, null)), 123));

        final BidRequestCacheInfo cacheInfo = BidRequestCacheInfo.builder().doCaching(true).build();

        givenCacheServiceResult(singletonMap(secondBid, CacheInfo.of("cacheId2", null, null, null)));

        // when
        final BidResponse bidResponse =
                bidResponseCreator.create(bidderResponses, auctionContext, cacheInfo, false).result();

        // then
        assertThat(bidResponse.getSeatbid()).flatExtracting(SeatBid::getBid).hasSize(2)
                .extracting(
                        bid -> toTargetingByKey(bid, "hb_bidder"),
                        bid -> toTargetingByKey(bid, "hb_cache_id"),
                        bid -> toTargetingByKey(bid, "hb_cache_id_bidder2"))
                .containsOnly(
                        tuple("bidder1", null, null),
                        tuple("bidder2", "cacheId2", "cacheId2"));

        verify(cacheService).cacheBidsOpenrtb(anyList(), any(), any(), any());
    }

    @Test
    public void shouldNotCacheNonDealBidWithCpmIsZeroAndCacheDealBidWithZeroCpm() {
        // given
        final Imp imp2 = givenImp("impId2");
        final AuctionContext auctionContext = givenAuctionContext(givenBidRequest(givenImp("impId1"), imp2));

        final Bid bid1 = Bid.builder().id("bidId1").impid("impId1").price(BigDecimal.ZERO).build();
        final Bid bid2 = Bid.builder().id("bidId2").impid("impId2").price(BigDecimal.ZERO).dealid("dealId2").build();

        final List<BidderResponse> bidderResponses = asList(
                BidderResponse.of("bidder1", givenSeatBid(BidderBid.of(bid1, banner, null)), 99),
                BidderResponse.of("bidder2", givenSeatBid(BidderBid.of(bid2, banner, null)), 99));

        final BidRequestCacheInfo cacheInfo = BidRequestCacheInfo.builder().doCaching(true).build();
        givenCacheServiceResult(singletonMap(bid2, CacheInfo.of("cacheId2", null, null, null)));

        // when
        bidResponseCreator.create(bidderResponses, auctionContext, cacheInfo, false).result();

        // then
        final BidInfo bidInfo2 = toBidInfo(bid2, imp2, "bidder2", banner);
        verify(cacheService).cacheBidsOpenrtb(eq(singletonList(bidInfo2)), any(), any(), any());
    }

    @Test
    public void shouldPopulateBidResponseExtension() throws JsonProcessingException {
        // given
        final BidRequest bidRequest = BidRequest.builder()
                .cur(singletonList("USD"))
                .tmax(1000L)
                .app(App.builder().build())
                .imp(singletonList(givenImp()))
                .build();
        final AuctionContext auctionContext = givenAuctionContext(bidRequest);

        final Bid bid = Bid.builder().id("bidId1").impid(IMP_ID).adm("[]").price(BigDecimal.valueOf(5.67)).build();
        final List<BidderResponse> bidderResponses = singletonList(BidderResponse.of("bidder1",
                BidderSeatBid.of(singletonList(BidderBid.of(bid, xNative, null)), null,
                        singletonList(BidderError.badInput("bad_input"))), 100));
        final BidRequestCacheInfo cacheInfo = BidRequestCacheInfo.builder().doCaching(true).build();

        givenCacheServiceResult(CacheServiceResult.of(
                DebugHttpCall.builder().endpoint("http://cache-service/cache").responseTimeMillis(666).build(),
                new RuntimeException("cacheError"), emptyMap()));

        // when
        final BidResponse bidResponse =
                bidResponseCreator.create(bidderResponses, auctionContext, cacheInfo, false).result();

        // then
        final ExtBidResponse responseExt = mapper.treeToValue(bidResponse.getExt(), ExtBidResponse.class);

        assertThat(responseExt.getDebug()).isNull();
        assertThat(responseExt.getUsersync()).isNull();
        assertThat(responseExt.getTmaxrequest()).isEqualTo(1000L);

        assertThat(responseExt.getErrors()).hasSize(2).containsOnly(
                entry("bidder1", asList(
                        ExtBidderError.of(2, "bad_input"),
                        ExtBidderError.of(3, "Failed to decode: Cannot deserialize instance of `com.iab."
                                + "openrtb.response.Response` out of START_ARRAY token\n at [Source: (String)\"[]\"; "
                                + "line: 1, column: 1]"))),
                entry("cache", singletonList(ExtBidderError.of(999, "cacheError"))));

        assertThat(responseExt.getResponsetimemillis()).hasSize(2)
                .containsOnly(entry("bidder1", 100), entry("cache", 666));

        verify(cacheService).cacheBidsOpenrtb(anyList(), any(), any(), any());
    }

    @Test
    public void impToStoredVideoJsonShouldTolerateWhenStoredVideoFetchIsFailed() {
        // given
        final Imp imp = Imp.builder().id(IMP_ID).ext(
                mapper.valueToTree(
                        ExtImp.of(
                                ExtImpPrebid.builder()
                                        .storedrequest(ExtStoredRequest.of("st1"))
                                        .options(ExtOptions.of(true))
                                        .build(),
                                null
                        )))
                .build();
        final AuctionContext auctionContext = givenAuctionContext(givenBidRequest(imp));

        final Bid bid = Bid.builder().id("bidId1").impid(IMP_ID).price(BigDecimal.valueOf(5.67)).build();
        final List<BidderResponse> bidderResponses = singletonList(BidderResponse.of("bidder1",
                givenSeatBid(BidderBid.of(bid, banner, "USD")), 100));

        given(storedRequestProcessor.videoStoredDataResult(any(), anyList(), anyList(), any())).willReturn(
                Future.failedFuture("Fetch failed"));

        // when
        final Future<BidResponse> result =
                bidResponseCreator.create(bidderResponses, auctionContext, CACHE_INFO, false);

        // then
        verify(storedRequestProcessor).videoStoredDataResult(any(), eq(singletonList(imp)), anyList(), eq(timeout));

        assertThat(result.succeeded()).isTrue();
    }

    @Test
    public void impToStoredVideoJsonShouldInjectStoredVideoWhenExtOptionsIsTrueAndVideoNotEmpty() {
        // given
        final Imp imp1 = Imp.builder().id("impId1").ext(
                mapper.valueToTree(
                        ExtImp.of(ExtImpPrebid.builder()
                                .storedrequest(ExtStoredRequest.of("st1"))
                                .options(ExtOptions.of(true))
                                .build(), null)))
                .build();
        final Imp imp2 = Imp.builder().id("impId2").ext(
                mapper.valueToTree(
                        ExtImp.of(ExtImpPrebid.builder()
                                .storedrequest(ExtStoredRequest.of("st2"))
                                .options(ExtOptions.of(false))
                                .build(), null)))
                .build();
        final Imp imp3 = Imp.builder().id("impId3").ext(
                mapper.valueToTree(
                        ExtImp.of(ExtImpPrebid.builder()
                                .storedrequest(ExtStoredRequest.of("st3"))
                                .options(ExtOptions.of(true))
                                .build(), null)))
                .build();
        final BidRequest bidRequest = givenBidRequest(imp1, imp2, imp3);
        final AuctionContext auctionContext = givenAuctionContext(bidRequest);

        final Bid bid1 = Bid.builder().id("bidId1").impid("impId1").price(BigDecimal.valueOf(5.67)).build();
        final Bid bid2 = Bid.builder().id("bidId2").impid("impId2").price(BigDecimal.valueOf(2)).build();
        final Bid bid3 = Bid.builder().id("bidId3").impid("impId3").price(BigDecimal.valueOf(3)).build();
        final List<BidderBid> bidderBids = mutableList(
                BidderBid.of(bid1, banner, "USD"),
                BidderBid.of(bid2, banner, "USD"),
                BidderBid.of(bid3, banner, "USD"));
        final List<BidderResponse> bidderResponses = singletonList(
                BidderResponse.of("bidder1", BidderSeatBid.of(bidderBids, emptyList(), emptyList()), 100));

        final Video storedVideo = Video.builder().maxduration(100).h(2).w(2).build();
        given(storedRequestProcessor.videoStoredDataResult(any(), anyList(), anyList(), any()))
                .willReturn(Future.succeededFuture(
                        VideoStoredDataResult.of(singletonMap("impId1", storedVideo), emptyList())));

        // when
        final Future<BidResponse> result =
                bidResponseCreator.create(bidderResponses, auctionContext, CACHE_INFO, false);

        // then
        verify(storedRequestProcessor).videoStoredDataResult(any(), eq(asList(imp1, imp3)), anyList(),
                eq(timeout));

        assertThat(result.result().getSeatbid())
                .flatExtracting(SeatBid::getBid).hasSize(3)
                .extracting(extractedBid -> toExtPrebid(extractedBid.getExt()).getPrebid().getStoredRequestAttributes())
                .containsOnly(storedVideo, null, null);
    }

    @Test
    public void impToStoredVideoJsonShouldAddErrorsWithPrebidBidderWhenStoredVideoRequestFailed() {
        // given
        final Imp imp1 = Imp.builder().id(IMP_ID).ext(
                mapper.valueToTree(
                        ExtImp.of(ExtImpPrebid.builder()
                                        .storedrequest(ExtStoredRequest.of("st1"))
                                        .options(ExtOptions.of(true))
                                        .build(),
                                null)))
                .build();
        final BidRequest bidRequest = givenBidRequest(imp1);
        final AuctionContext auctionContext = givenAuctionContext(bidRequest);

        final Bid bid1 = Bid.builder().id("bidId1").impid(IMP_ID).price(BigDecimal.valueOf(5.67)).build();
        final List<BidderBid> bidderBids = singletonList(BidderBid.of(bid1, banner, "USD"));
        final List<BidderResponse> bidderResponses = singletonList(
                BidderResponse.of("bidder1", BidderSeatBid.of(bidderBids, emptyList(), emptyList()), 100));

        given(storedRequestProcessor.videoStoredDataResult(any(), anyList(), anyList(), any()))
                .willReturn(Future.failedFuture("Bad timeout"));

        // when
        final Future<BidResponse> result =
                bidResponseCreator.create(bidderResponses, auctionContext, CACHE_INFO, false);

        // then
        verify(storedRequestProcessor).videoStoredDataResult(any(), eq(singletonList(imp1)), anyList(), eq(timeout));

        assertThat(result.result().getExt()).isEqualTo(
                mapper.valueToTree(ExtBidResponse.of(null, singletonMap(
                        "prebid", singletonList(ExtBidderError.of(BidderError.Type.generic.getCode(),
                                "Bad timeout"))), singletonMap("bidder1", 100), 1000L, null,
                        ExtBidResponsePrebid.of(1000L))));
    }

    @Test
    public void shouldProcessRequestAndAddErrorAboutDeprecatedBidder() {
        // given
        final String invalidBidderName = "invalid";

        final BidRequest bidRequest = givenBidRequest(Imp.builder()
                .ext(mapper.valueToTree(singletonMap(invalidBidderName, 0)))
                .build());
        final AuctionContext auctionContext = givenAuctionContext(bidRequest);

        final List<BidderResponse> bidderResponses = singletonList(BidderResponse.of("bidder1", givenSeatBid(), 100));

        given(bidderCatalog.isDeprecatedName(invalidBidderName)).willReturn(true);
        given(bidderCatalog.errorForDeprecatedName(invalidBidderName)).willReturn(
                "invalid has been deprecated and is no longer available. Use valid instead.");

        // when
        final BidResponse bidResponse =
                bidResponseCreator.create(bidderResponses, auctionContext, CACHE_INFO, false).result();

        // then
        assertThat(bidResponse.getExt()).isEqualTo(
                mapper.valueToTree(ExtBidResponse.of(null, singletonMap(
                        invalidBidderName, singletonList(ExtBidderError.of(BidderError.Type.bad_input.getCode(),
                                "invalid has been deprecated and is no longer available. Use valid instead."))),
                        singletonMap("bidder1", 100), 1000L, null, ExtBidResponsePrebid.of(1000L))));

        verify(cacheService, never()).cacheBidsOpenrtb(anyList(), any(), any(), any());
    }

    @Test
    public void shouldProcessRequestAndAddErrorFromAuctionContext() {
        // given
        final AuctionContext auctionContext = givenAuctionContext(
                givenBidRequest(givenImp()),
                contextBuilder -> contextBuilder.prebidErrors(singletonList("privacy error")));

        final Bid bid1 = Bid.builder().id("bidId1").impid(IMP_ID).price(BigDecimal.valueOf(5.67)).build();
        final List<BidderBid> bidderBids = singletonList(BidderBid.of(bid1, banner, "USD"));
        final List<BidderResponse> bidderResponses = singletonList(
                BidderResponse.of("bidder1", BidderSeatBid.of(bidderBids, emptyList(), emptyList()), 100));

        // when
        final Future<BidResponse> result =
                bidResponseCreator.create(bidderResponses, auctionContext, CACHE_INFO, false);

        // then
        assertThat(result.result().getExt()).isEqualTo(
                mapper.valueToTree(ExtBidResponse.of(null, singletonMap(
                        "prebid", singletonList(ExtBidderError.of(BidderError.Type.generic.getCode(),
                                "privacy error"))), singletonMap("bidder1", 100), 1000L, null,
                        ExtBidResponsePrebid.of(1000L))));
    }

    @Test
    public void shouldPopulateBidResponseDebugExtensionIfDebugIsEnabled() throws JsonProcessingException {
        // given
        final BidRequest bidRequest = givenBidRequest(givenImp());
        final AuctionContext auctionContext = givenAuctionContext(bidRequest);
        givenCacheServiceResult(CacheServiceResult.of(
                DebugHttpCall.builder()
                        .endpoint("http://cache-service/cache")
                        .requestUri("test.uri")
                        .responseStatus(500)
                        .build(),
                null,
                emptyMap()));

        final BidRequestCacheInfo cacheInfo = BidRequestCacheInfo.builder().doCaching(true).build();

        final Bid bid = Bid.builder().id("bidId1").impid(IMP_ID).price(BigDecimal.valueOf(5.67)).build();
        final List<BidderResponse> bidderResponses = singletonList(BidderResponse.of("bidder1",
                BidderSeatBid.of(singletonList(BidderBid.of(bid, banner, null)),
                        singletonList(ExtHttpCall.builder().status(200).build()), null), 100));

        // when
        final BidResponse bidResponse =
                bidResponseCreator.create(bidderResponses, auctionContext, cacheInfo, true).result();

        // then
        final ExtBidResponse responseExt = mapper.treeToValue(bidResponse.getExt(), ExtBidResponse.class);

        assertThat(responseExt.getDebug()).isNotNull();
        assertThat(responseExt.getDebug().getHttpcalls()).hasSize(2)
                .containsOnly(
                        entry("bidder1", singletonList(ExtHttpCall.builder().status(200).build())),
                        entry("cache", singletonList(ExtHttpCall.builder().uri("test.uri").status(500).build())));

        assertThat(responseExt.getDebug().getResolvedrequest()).isEqualTo(bidRequest);

        verify(cacheService).cacheBidsOpenrtb(anyList(), any(), any(), any());
    }

    @Test
    public void shouldPassIntegrationToCacheServiceAndBidEvents() {
        // given
        final Account account = Account.builder().id("accountId").eventsEnabled(true).build();
        final BidRequest bidRequest = BidRequest.builder()
                .cur(singletonList("USD"))
                .imp(singletonList(givenImp()))
                .ext(ExtRequest.of(ExtRequestPrebid.builder()
                        .events(mapper.createObjectNode())
                        .integration("integration")
                        .build()))
                .build();
        final AuctionContext auctionContext = givenAuctionContext(
                bidRequest,
                contextBuilder -> contextBuilder.account(account));

        final Bid bid = Bid.builder().id("bidId1").impid(IMP_ID).price(BigDecimal.valueOf(5.67)).build();
        final List<BidderResponse> bidderResponses = singletonList(
                BidderResponse.of("bidder1", givenSeatBid(BidderBid.of(bid, banner, "USD")), 100));

        final BidRequestCacheInfo cacheInfo = BidRequestCacheInfo.builder().doCaching(true).build();

        givenCacheServiceResult(singletonMap(bid, CacheInfo.empty()));

        given(eventsService.createEvent(anyString(), anyString(), anyString(), anyLong(), anyString()))
                .willReturn(Events.of(
                        "http://win-url?param=value&int=integration",
                        "http://imp-url?param=value&int=integration"));

        // when
        final Future<BidResponse> result =
                bidResponseCreator.create(bidderResponses, auctionContext, cacheInfo, false);

        // then
        verify(cacheService).cacheBidsOpenrtb(anyList(), any(), any(),
                argThat(eventsContext -> eventsContext.getIntegration().equals("integration")));

        assertThat(result.result().getSeatbid())
                .flatExtracting(SeatBid::getBid).hasSize(1)
                .extracting(extractedBid -> toExtPrebid(extractedBid.getExt()).getPrebid().getEvents())
                .containsOnly(Events.of("http://win-url?param=value&int=integration",
                        "http://imp-url?param=value&int=integration"));
    }

    private AuctionContext givenAuctionContext(BidRequest bidRequest,
                                               UnaryOperator<AuctionContext.AuctionContextBuilder> contextCustomizer) {

        final AuctionContext.AuctionContextBuilder auctionContextBuilder = AuctionContext.builder()
                .account(Account.empty("accountId"))
                .bidRequest(bidRequest)
                .timeout(timeout)
                .prebidErrors(emptyList());

        return contextCustomizer.apply(auctionContextBuilder).build();
    }

    private AuctionContext givenAuctionContext(BidRequest bidRequest) {
        return givenAuctionContext(bidRequest, identity());
    }

    private void givenCacheServiceResult(Map<Bid, CacheInfo> cacheBids) {
        givenCacheServiceResult(CacheServiceResult.of(null, null, cacheBids));
    }

    private void givenCacheServiceResult(CacheServiceResult cacheServiceResult) {
        given(cacheService.cacheBidsOpenrtb(any(), any(), any(), any()))
                .willReturn(Future.succeededFuture(cacheServiceResult));
    }

    private static BidInfo toBidInfo(Bid bid, Imp correspondingImp, String bidder, BidType bidType) {
        return BidInfo.builder().bid(bid).correspondingImp(correspondingImp).bidder(bidder).bidType(bidType).build();
    }

    private static Imp givenImp() {
        return Imp.builder().id(IMP_ID).build();
    }

    private static Imp givenImp(String impId) {
        return Imp.builder().id(impId).build();
    }

    private static BidRequest givenBidRequest(
            UnaryOperator<BidRequest.BidRequestBuilder> bidRequestCustomizer,
            UnaryOperator<ExtRequestPrebid.ExtRequestPrebidBuilder> extRequestCustomizer,
            Imp... imps) {

        final ExtRequestPrebid.ExtRequestPrebidBuilder extRequestBuilder = ExtRequestPrebid.builder();

        final BidRequest.BidRequestBuilder bidRequestBuilder = BidRequest.builder()
                .id("123")
                .cur(singletonList("USD"))
                .tmax(1000L)
                .imp(asList(imps))
                .ext(ExtRequest.of(extRequestCustomizer.apply(extRequestBuilder).build()));

        return bidRequestCustomizer.apply(bidRequestBuilder)
                .build();
    }

    private static BidRequest givenBidRequest(UnaryOperator<BidRequest.BidRequestBuilder> bidRequestCustomizer,
                                              Imp... imps) {

        return givenBidRequest(bidRequestCustomizer, identity(), imps);
    }

    private static BidRequest givenBidRequest(Imp... imps) {
        return givenBidRequest(identity(), imps);
    }

    private static BidderSeatBid givenSeatBid(BidderBid... bids) {
        return BidderSeatBid.of(mutableList(bids), emptyList(), emptyList());
    }

    private static ExtRequestTargeting givenTargeting() {
        return ExtRequestTargeting.builder()
                .pricegranularity(mapper.valueToTree(
                        ExtPriceGranularity.of(2, singletonList(ExtGranularityRange.of(BigDecimal.valueOf(5),
                                BigDecimal.valueOf(0.5))))))
                .includewinners(true)
                .includebidderkeys(true)
                .includeformat(false)
                .build();
    }

    private static ExtPrebid<ExtBidPrebid, ?> toExtPrebid(ObjectNode ext) {
        try {
            return mapper.readValue(mapper.treeAsTokens(ext), new TypeReference<ExtPrebid<ExtBidPrebid, ?>>() {
            });
        } catch (IOException e) {
            return rethrow(e);
        }
    }

    private static String toTargetingByKey(Bid bid, String targetingKey) {
        final Map<String, String> targeting = toExtPrebid(bid.getExt()).getPrebid().getTargeting();
        return targeting != null ? targeting.get(targetingKey) : null;
    }

    @SafeVarargs
    private static <T> List<T> mutableList(T... values) {
        return Arrays.stream(values).collect(Collectors.toList());
    }
}
