package org.prebid.server.proto.openrtb.ext.request.mockbidder;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Value;

@Value(staticConstructor = "of")
public class ExtImpMockBidder {

    @JsonProperty("sid")
    String slotId;

    @JsonProperty("name")
    String partnerName;
}
