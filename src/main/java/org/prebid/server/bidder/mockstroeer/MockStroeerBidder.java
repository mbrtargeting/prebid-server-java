package org.prebid.server.bidder.mockstroeer;

import com.fasterxml.jackson.core.type.TypeReference;
import com.iab.openrtb.request.BidRequest;
import com.iab.openrtb.request.Imp;
import com.iab.openrtb.response.Bid;
import com.iab.openrtb.response.BidResponse;
import com.iab.openrtb.response.SeatBid;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.prebid.server.bidder.Bidder;
import org.prebid.server.bidder.model.BidderBid;
import org.prebid.server.bidder.model.BidderCall;
import org.prebid.server.bidder.model.BidderError;
import org.prebid.server.bidder.model.HttpRequest;
import org.prebid.server.bidder.model.Price;
import org.prebid.server.bidder.model.Result;
import org.prebid.server.currency.CurrencyConversionService;
import org.prebid.server.exception.PreBidException;
import org.prebid.server.json.DecodeException;
import org.prebid.server.json.JacksonMapper;
import org.prebid.server.proto.openrtb.ext.ExtPrebid;
import org.prebid.server.proto.openrtb.ext.request.mockstroeer.ExtImpMockStroeer;
import org.prebid.server.proto.openrtb.ext.response.BidType;
import org.prebid.server.util.BidderUtil;
import org.prebid.server.util.HttpUtil;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

public class MockStroeerBidder implements Bidder<BidRequest> {

    private static final String BIDDER_CURRENCY = "EUR";
    private static final TypeReference<ExtPrebid<?, ExtImpMockStroeer>> MOCK_BIDDER_EXT_TYPE_REFERENCE =
            new TypeReference<>() {
            };

    private final String endpointUrl;
    private final JacksonMapper mapper;
    private final CurrencyConversionService currencyConversionService;

    public MockStroeerBidder(String endpointUrl,
                             JacksonMapper mapper,
                             CurrencyConversionService currencyConversionService) {
        this.endpointUrl = HttpUtil.validateUrl(endpointUrl);
        this.mapper = Objects.requireNonNull(mapper);
        this.currencyConversionService = Objects.requireNonNull(currencyConversionService);
    }

    @Override
    public Result<List<HttpRequest<BidRequest>>> makeHttpRequests(BidRequest bidRequest) {
        final List<Imp> modifiedImps = new ArrayList<>();
        final List<BidderError> errors = new ArrayList<>();

        for (Imp imp : bidRequest.getImp()) {
            final ExtImpMockStroeer impExt;
            final Price price;

            try {
                validateImp(imp);

                impExt = parseImpExt(imp);
                validateImpExt(impExt);

                price = convertBidFloor(bidRequest, imp);
            } catch (PreBidException e) {
                errors.add(BidderError.badInput("%s. Ignore imp id = %s.".formatted(e.getMessage(), imp.getId())));
                continue;
            }

            modifiedImps.add(modifyImp(imp, impExt, price));
        }

        if (modifiedImps.isEmpty()) {
            return Result.withErrors(errors);
        }

        final BidRequest outgoingRequest = bidRequest.toBuilder().imp(modifiedImps).build();
        final HttpRequest<BidRequest> httpRequest = BidderUtil.defaultRequest(outgoingRequest, endpointUrl, mapper);
        return Result.of(Collections.singletonList(httpRequest), errors);
    }

    private static void validateImp(Imp imp) {
        if (imp.getBanner() == null && imp.getVideo() == null) {
            throw new PreBidException("Expected banner or video impression");
        }
    }

    private ExtImpMockStroeer parseImpExt(Imp imp) {
        try {
            return mapper.mapper().convertValue(imp.getExt(), MOCK_BIDDER_EXT_TYPE_REFERENCE).getBidder();
        } catch (IllegalArgumentException e) {
            throw new PreBidException(e.getMessage());
        }
    }

    private static void validateImpExt(ExtImpMockStroeer impExt) {
        if (StringUtils.isBlank(impExt.getSlotId())) {
            throw new PreBidException("Custom param slot id (sid) is empty");
        }
    }

    private Price convertBidFloor(BidRequest bidRequest, Imp imp) {
        final BigDecimal bidFloor = imp.getBidfloor();
        final String bidFloorCurrency = imp.getBidfloorcur();

        if (!shouldConvertBidFloor(bidFloor, bidFloorCurrency)) {
            return Price.of(bidFloorCurrency, bidFloor);
        }

        final BigDecimal convertedBidFloor = currencyConversionService.convertCurrency(
                bidFloor, bidRequest, bidFloorCurrency, BIDDER_CURRENCY);

        return Price.of(BIDDER_CURRENCY, convertedBidFloor);
    }

    private static boolean shouldConvertBidFloor(BigDecimal bidFloor, String bidFloorCurrency) {
        return BidderUtil.isValidPrice(bidFloor) && !StringUtils.equalsIgnoreCase(bidFloorCurrency, BIDDER_CURRENCY);
    }

    private static Imp modifyImp(Imp imp, ExtImpMockStroeer impExt, Price price) {
        return imp.toBuilder()
                .bidfloorcur(price.getCurrency())
                .bidfloor(price.getValue())
                .tagid(impExt.getSlotId())
                .build();
    }

    @Override
    public Result<List<BidderBid>> makeBids(BidderCall<BidRequest> httpCall, BidRequest bidRequest) {
        try {
            final String body = httpCall.getResponse().getBody();
            final BidResponse bidResponse = mapper.decodeValue(body, BidResponse.class);
            return Result.withValues(extractBids(httpCall.getRequest().getPayload(), bidResponse));
        } catch (DecodeException e) {
            return Result.withError(BidderError.badServerResponse(e.getMessage()));
        }
    }

    private static List<BidderBid> extractBids(BidRequest bidRequest, BidResponse bidResponse) {
        if (bidResponse == null || CollectionUtils.isEmpty(bidResponse.getSeatbid())) {
            return Collections.emptyList();
        }

        return bidResponse.getSeatbid().stream()
                .filter(Objects::nonNull)
                .map(SeatBid::getBid)
                .filter(Objects::nonNull)
                .flatMap(Collection::stream)
                .filter(Objects::nonNull)
                .map(bid -> toBidderBid(bidRequest, bid))
                .toList();
    }

    private static BidderBid toBidderBid(BidRequest bidRequest, Bid bid) {
        return BidderBid.of(bid, getBidType(bid.getImpid(), bidRequest.getImp()), BIDDER_CURRENCY);
    }

    private static BidType getBidType(String impId, List<Imp> imps) {
        for (Imp imp : imps) {
            if (imp.getId().equals(impId)) {
                if (imp.getBanner() != null) {
                    return BidType.banner;
                } else if (imp.getVideo() != null) {
                    return BidType.video;
                }
            }
        }

        return BidType.banner;
    }
}
