package io.twxav2.service;

import io.twxav2.ingest.TwxParser;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/** Runtime-configurable end-to-end budgets for one TWX parse. */
@ApplicationScoped
public class TwxParseLimits {

    @ConfigProperty(name = "twxav2.parser.max-archive-bytes", defaultValue = "2147483648")
    long maxArchiveBytes;

    @ConfigProperty(name = "twxav2.parser.max-entry-count", defaultValue = "100000")
    int maxEntryCount;

    @ConfigProperty(name = "twxav2.parser.max-object-count", defaultValue = "50000")
    int maxObjectCount;

    @ConfigProperty(name = "twxav2.parser.max-expanded-bytes", defaultValue = "4294967296")
    long maxExpandedBytes;

    @ConfigProperty(name = "twxav2.parser.max-retained-content-bytes", defaultValue = "268435456")
    long maxRetainedContentBytes;

    @ConfigProperty(name = "twxav2.parser.max-toolkit-count", defaultValue = "128")
    int maxToolkitCount;

    @ConfigProperty(name = "twxav2.parser.max-toolkit-bytes", defaultValue = "268435456")
    long maxToolkitBytes;

    @ConfigProperty(name = "twxav2.parser.max-total-toolkit-bytes", defaultValue = "1073741824")
    long maxTotalToolkitBytes;

    public TwxParser.Limits limits() {
        return new TwxParser.Limits(maxArchiveBytes, maxEntryCount, maxObjectCount,
                maxExpandedBytes, maxRetainedContentBytes, maxToolkitCount,
                maxToolkitBytes, maxTotalToolkitBytes);
    }
}
