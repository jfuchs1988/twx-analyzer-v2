// Derived from Dosvak LLC twx-code-analyzer (Apache-2.0); modified for TWX Analyzer v2. See NOTICE.
// Changes: package/rule IDs, false-positive fixes, adapters.
package io.twxav2.analysis.ref.model;

import java.util.*;

/** Parsed coach view (id 64.*): options, binding, layout, scripts, resources. */
public class CoachViewModel {
    public TwxObject object;
    public String id, name, bindingType = "", bindingName = "", documentation = "", layoutXml = "", paletteIcon = "", previewHtml = "", previewJs = "";
    public boolean bindingList, prototypeFunc, template, hasLabel;
    public final List<Option> options = new ArrayList<>();
    public final List<Script> scripts = new ArrayList<>();
    public final List<String> resources = new ArrayList<>(), childViewRefs = new ArrayList<>(), amdModules = new ArrayList<>();
    public int layoutItems;
    public static class Option { public String name = "", label = "", propertyType = "", classRef = "", description = "", groupName = ""; public boolean isList; }
    public static class Script { public String kind, name, code; public Script(String kind, String name, String code) { this.kind = kind; this.name = name; this.code = code; } }
}
