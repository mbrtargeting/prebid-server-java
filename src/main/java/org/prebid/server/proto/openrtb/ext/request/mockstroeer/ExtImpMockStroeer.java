package org.prebid.server.proto.openrtb.ext.request.mockstroeer;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Value;

@Value(staticConstructor = "of")
public class ExtImpMockStroeer {

    @JsonProperty("sid")
    String slotId;

    @JsonProperty("name")
    String partnerName;
}
