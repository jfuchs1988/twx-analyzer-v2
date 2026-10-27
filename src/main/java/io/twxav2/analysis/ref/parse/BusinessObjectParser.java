// Derived from Dosvak LLC twx-code-analyzer (Apache-2.0); modified for TWX Analyzer v2. See NOTICE.
// Changes: package/rule IDs, false-positive fixes, adapters.
package io.twxav2.analysis.ref.parse;

import java.util.*;
import io.twxav2.analysis.ref.model.*;
import io.twxav2.analysis.ref.util.Xml;

/** Parses business-object XML into {@link BusinessObjectModel} (types, members, documentation). */
public final class BusinessObjectParser {
    private BusinessObjectParser() {}
    public static BusinessObjectModel parse(TwxObject o) {
        BusinessObjectModel b = new BusinessObjectModel(); b.object = o; b.id = o.id; b.name = o.name; String x = o.xml;
        b.documentation = Xml.text(x, "description"); b.shared = Xml.bool(x, "shared"); b.system = Xml.bool(x, "isSystem");
        String def = Xml.textRaw(x, "definition");
        for (String p : Xml.blocks(def, "property")) { BusinessObjectModel.Property pr = new BusinessObjectModel.Property(); pr.name = Xml.text(p, "name"); pr.classRef = Xml.text(p, "classRef"); pr.isList = Xml.bool(p, "arrayProperty"); pr.required = Xml.bool(p, "propertyRequired"); pr.documentation = Xml.text(p, "description"); pr.defaultValue = Xml.text(p, "propertyDefault"); b.properties.add(pr); }
        return b;
    }
}
