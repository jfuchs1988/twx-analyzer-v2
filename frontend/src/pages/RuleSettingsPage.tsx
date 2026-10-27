import { Fragment, useEffect, useMemo, useState } from "react";
import {
  ActionIcon, Alert, Badge, Button, Code, Group, JsonInput, NumberInput, Paper,
  SegmentedControl, Select, Stack, Switch, Table, Text, TextInput, Title, Tooltip
} from "@mantine/core";
import {
  IconDownload, IconRefresh, IconUpload, IconSelector
} from "@tabler/icons-react";
import {
  api, RuleInfo, RuleSettingsJson, SEVERITIES, SEV_COLOR, Severity, Snapshot
} from "../api";
import { useLang } from "../i18n";

/**
 * Rule settings: enable/disable rules, override severity and impact,
 * set thresholds — globally as default or overlaid per snapshot.
 * Everything exportable/importable as JSON (expansion plan B1.5).
 */
export default function RuleSettingsPage({ initialSnapshotId }: { initialSnapshotId?: number }) {
  const { tr, pick } = useLang();
  const [scope, setScope] = useState<"global" | "snapshot">(
    initialSnapshotId ? "snapshot" : "global");
  const [snapshotId, setSnapshotId] = useState<number | null>(initialSnapshotId ?? null);
  const [snapshots, setSnapshots] = useState<Snapshot[]>([]);
  const [rules, setRules] = useState<RuleInfo[]>([]);
  const [defaults, setDefaults] = useState<RuleSettingsJson>({});
  const [settings, setSettings] = useState<RuleSettingsJson>({});
  const [excludeNamePatternsText, setExcludeNamePatternsText] = useState("");
  const [excludeTagsText, setExcludeTagsText] = useState("");
  const [expanded, setExpanded] = useState<string | null>(null);
  const [jsonMode, setJsonMode] = useState(false);
  const [jsonText, setJsonText] = useState("{}");
  const [msg, setMsg] = useState<{ color: string; text: string } | null>(null);
  const [dirty, setDirty] = useState(false);

  const scopeId = scope === "snapshot" ? snapshotId : null;

  useEffect(() => {
    api.snapshots("app").then(setSnapshots).catch(() => setSnapshots([]));
    api.rules().then(setRules).catch(() => setRules([]));
    api.ruleSettingsDefaults().then(setDefaults).catch(() => setDefaults({}));
  }, []);

  useEffect(() => {
    if (scope === "snapshot" && snapshotId == null) {
      setSettings({});
      setExcludeNamePatternsText("");
      setExcludeTagsText("");
      return;
    }
    setDirty(false);
    api.ruleSettings(scopeId ?? undefined)
      .then((s) => {
        const loaded = s ?? {};
        setSettings(loaded);
        setExcludeNamePatternsText((loaded.excludeNamePatterns ?? []).join(", "));
        setExcludeTagsText((loaded.excludeTags ?? []).join(", "));
      })
      .catch(() => {
        setSettings({});
        setExcludeNamePatternsText("");
        setExcludeTagsText("");
      });
  }, [scope, snapshotId]); // eslint-disable-line react-hooks/exhaustive-deps

  const customizedCount = useMemo(
    () => Object.values(settings.overrides ?? {}).filter((o) =>
      o.enabled !== undefined || o.severity !== undefined || o.impact !== undefined).length,
    [settings]);

  const setOverride = (ruleId: string, patch: { enabled?: boolean; severity?: Severity | null; impact?: number | null }) => {
    const next: RuleSettingsJson = {
      ...settings,
      overrides: { ...(settings.overrides ?? {}) },
      thresholds: { ...(settings.thresholds ?? {}) },
    };
    const ov = { ...(next.overrides![ruleId] ?? {}) };
    if ("enabled" in patch) ov.enabled = patch.enabled;
    if ("severity" in patch) {
      const sv = patch.severity;
      if (sv) ov.severity = sv; else delete ov.severity;
    }
    if ("impact" in patch) {
      const im = patch.impact;
      if (im) ov.impact = im; else delete ov.impact;
    }
    next.overrides![ruleId] = ov;
    setSettings(next);
    setDirty(true);
  };

  const setThreshold = (key: string, value: number | boolean | string | null) => {
    const next: RuleSettingsJson = {
      ...settings,
      overrides: { ...(settings.overrides ?? {}) },
      thresholds: { ...(settings.thresholds ?? {}) },
    };
    if (value == null || value === "") delete next.thresholds![key];
    else next.thresholds![key] = value;
    setSettings(next);
    setDirty(true);
  };

  const save = async () => {
    try {
      const normalized = clean(settings);
      await api.saveRuleSettings(normalized, scopeId ?? undefined);
      setSettings(normalized);
      setExcludeNamePatternsText((normalized.excludeNamePatterns ?? []).join(", "));
      setExcludeTagsText((normalized.excludeTags ?? []).join(", "));
      setDirty(false);
      setMsg({ color: "green", text: tr(
        "Gespeichert. Wirksam ist die Einstellung bei der nächsten Analyse (Upload/Import).",
        "Saved. The setting takes effect at the next analysis (upload/import).") });
      api.rules().then(setRules).catch(() => undefined);
    } catch (e) {
      setMsg({ color: "red", text: String(e instanceof Error ? e.message : e) });
    }
  };

  const removeSnapshotSettings = async () => {
    if (scopeId == null) return;
    try {
      await api.deleteSnapshotRuleSettings(scopeId);
      setSettings({});
      setExcludeNamePatternsText("");
      setExcludeTagsText("");
      setDirty(false);
      setMsg({ color: "green", text: tr(
        "Snapshot-Einstellungen gelöscht — globaler Standard gilt.",
        "Snapshot settings deleted — the global default applies.") });
    } catch (e) {
      setMsg({ color: "red", text: String(e instanceof Error ? e.message : e) });
    }
  };

  const exportJson = () => {
    const blob = new Blob([JSON.stringify(clean(settings), null, 2)], { type: "application/json" });
    const a = document.createElement("a");
    a.href = URL.createObjectURL(blob);
    a.download = scopeId == null ? "twx-analyzer-v2-rule-settings.json" : `twx-analyzer-v2-rule-settings-snapshot-${scopeId}.json`;
    a.click();
    URL.revokeObjectURL(a.href);
  };

  const importJson = (file: File | undefined) => {
    if (!file) return;
    file.text().then((t) => {
      try {
        const parsed = JSON.parse(t) as RuleSettingsJson;
        if (typeof parsed !== "object" || parsed === null) throw new Error(tr("kein Objekt", "not an object"));
        setSettings(parsed);
        setExcludeNamePatternsText((parsed.excludeNamePatterns ?? []).join(", "));
        setExcludeTagsText((parsed.excludeTags ?? []).join(", "));
        setDirty(true);
        setMsg({ color: "blue", text: tr("JSON geladen — mit „Speichern“ anwenden.", "JSON loaded — click “Save” to apply.") });
      } catch (e) {
        setMsg({ color: "red", text: tr(`JSON unlesbar: ${e instanceof Error ? e.message : e}`, `Invalid JSON: ${e instanceof Error ? e.message : e}`) });
      }
    });
  };

  const thresholdKeys = useMemo(() => {
    const keys = new Set<string>([
      ...Object.keys(defaults.thresholds ?? {}),
      ...Object.keys(settings.thresholds ?? {}),
    ]);
    return [...keys].sort();
  }, [defaults, settings]);

  /** Group catalog into sections per category (alphabetical, rules by ID). */
  const groups = useMemo(() => {
    const byCat = new Map<string, { en: string | null; rules: RuleInfo[] }>();
    for (const r of rules) {
      const cat = r.category || "Allgemein";
      const entry = byCat.get(cat) ?? { en: null, rules: [] };
      if (entry.en == null && r.categoryEn) entry.en = r.categoryEn;
      entry.rules.push(r);
      byCat.set(cat, entry);
    }
    return [...byCat.entries()].sort((a, b) => a[0].localeCompare(b[0], "de"));
  }, [rules]);

  return (
    <Stack>
      <Group justify="space-between">
        <Title order={3}>{tr("Regeleinstellungen", "Rule settings")}</Title>
        <Group>
          <Tooltip label={tr("Einstellungen als JSON-Datei speichern", "Save settings as a JSON file")}>
            <Button size="xs" variant="default" leftSection={<IconDownload size={14} />} onClick={exportJson}>
              Export
            </Button>
          </Tooltip>
          <Tooltip label={tr("JSON-Datei laden ( erst mit „Speichern“ wirksam )", "Load a JSON file (takes effect only after “Save”)")}>
            <Button
              size="xs"
              variant="default"
              component="label"
              leftSection={<IconUpload size={14} />}
            >
              Import
              <input
                type="file"
                accept="application/json,.json"
                hidden
                onChange={(e) => importJson(e.currentTarget.files?.[0])}
              />
            </Button>
          </Tooltip>
          <Button size="xs" color="brand" disabled={!dirty} onClick={save}>
            {tr("Speichern", "Save")}
          </Button>
        </Group>
      </Group>

      <Group>
        <SegmentedControl
          data={[
            { value: "Global (Standard)", label: tr("Global (Standard)", "Global (default)") },
            { value: "Je Snapshot", label: tr("Je Snapshot", "Per snapshot") },
          ]}
          value={scope === "global" ? "Global (Standard)" : "Je Snapshot"}
          onChange={(v) => setScope(v === "Global (Standard)" ? "global" : "snapshot")}
        />
        {scope === "snapshot" && (
          <Select
            placeholder={tr("Snapshot wählen…", "Select snapshot…")}
            data={snapshots.map((s) => ({
              value: String(s.id),
              label: `#${s.id} ${s.projectAcronym ?? s.filename} ${s.snapshotName ?? ""}`
            }))}
            value={snapshotId != null ? String(snapshotId) : null}
            onChange={(v) => setSnapshotId(v ? Number(v) : null)}
            searchable
            w={340}
          />
        )}
        {scope === "snapshot" && scopeId != null && (
          <Button
            size="xs"
            variant="default"
            color="red"
            leftSection={<IconRefresh size={14} />}
            onClick={removeSnapshotSettings}
          >
            {tr("Snapshot-Einstellungen löschen", "Delete snapshot settings")}
          </Button>
        )}
        <Text size="xs" c="dimmed">
          {scope === "global"
            ? tr("Globaler Standard für alle Analysen.", "Global default for all analyses.")
            : tr("Überlagert den globalen Standard nur für diesen Snapshot.", "Overrides the global default for this snapshot only.")}
          {" "}{tr(`Geändert: ${customizedCount} Regel(n).`, `Changed: ${customizedCount} rule(s).`)}
        </Text>
      </Group>

      {msg && <Alert color={msg.color} onClose={() => setMsg(null)}>{msg.text}</Alert>}

      {jsonMode ? (
        <Stack gap="xs">
          <Text size="xs" c="dimmed">
            {tr("JSON direkt bearbeiten (Schema:", "Edit the JSON directly (schema:")} {"{ overrides: { 'R-JS-001': { enabled, severity, impact } }, thresholds: { … } }"}
            {tr(". Mit „Speichern“ anwenden.", ". Click “Save” to apply.")}
          </Text>
          <JsonInput
            minRows={12}
            value={jsonText}
            onChange={setJsonText}
            validationError={tr("Kein gültiges JSON", "Not valid JSON")}
            formatOnBlur
          />
          <Group>
            <Button
              size="xs"
              onClick={() => {
                setJsonText(JSON.stringify(settings, null, 2));
              }}
            >
              {tr("Editor mit aktuellen Werten füllen", "Fill editor with current values")}
            </Button>
            <Button
              size="xs"
              variant="default"
              onClick={() => {
                try {
                  const parsed = JSON.parse(jsonText) as RuleSettingsJson;
                  setSettings(parsed);
                  setExcludeNamePatternsText((parsed.excludeNamePatterns ?? []).join(", "));
                  setExcludeTagsText((parsed.excludeTags ?? []).join(", "));
                  setDirty(true);
                  setMsg({ color: "blue", text: tr("JSON übernommen — mit „Speichern“ anwenden.", "JSON applied — click “Save” to activate.") });
                } catch {
                  setMsg({ color: "red", text: tr("JSON unlesbar — bitte korrigieren.", "Invalid JSON — please correct it.") });
                }
              }}
            >
              {tr("JSON übernehmen", "Apply JSON")}
            </Button>
          </Group>
        </Stack>
      ) : (
        <>
          <Table.ScrollContainer minWidth={640} type="native">
          <Table striped highlightOnHover withTableBorder fz="sm">
            <Table.Thead>
              <Table.Tr>
                <Table.Th w={70}>{tr("Aktiv", "Active")}</Table.Th>
                <Table.Th w={70}>ID</Table.Th>
                <Table.Th>{tr("Regel", "Rule")}</Table.Th>
                <Table.Th w={150}>{tr("Schwere", "Severity")}</Table.Th>
                <Table.Th w={130} ta="right" title={tr("Score = Schwere-Gewicht × Impact", "Score = severity weight × impact")}>Impact</Table.Th>
                <Table.Th w={40} />
              </Table.Tr>
            </Table.Thead>
            <Table.Tbody>
              {groups.map(([cat, group]) => {
                const catRules = group.rules;
                const activeCount = catRules.filter((r) => {
                  const ov = (settings.overrides ?? {})[r.id] ?? {};
                  return (ov.enabled !== undefined ? ov.enabled : r.enabled);
                }).length;
                const catLabel = pick(cat, group.en ?? (cat === "Allgemein" ? "General" : null));
                return (
                  <Fragment key={cat}>
                    {/* section header per category */}
                    <Table.Tr>
                      <Table.Th colSpan={6} fw={700} style={{ background: "rgba(128,128,128,0.10)" }}>
                        <Group gap="xs" wrap="nowrap">
                          <Text size="sm" fw={700}>{catLabel}</Text>
                          <Badge variant="light" size="xs">{activeCount}/{catRules.length} {tr("aktiv", "active")}</Badge>
                        </Group>
                      </Table.Th>
                    </Table.Tr>
                    {catRules.map((r) => {
                      const ov = (settings.overrides ?? {})[r.id] ?? {};
                      const isOv = ov.enabled !== undefined || ov.severity !== undefined || ov.impact !== undefined;
                      // Title without "R<nnn> " prefix (ID has its own column)
                      const title = pick(r.title, r.titleEn).replace(/^R\d+\s+/, "");
                      return (
                        <Fragment key={r.id}>
                          <Table.Tr>
                            <Table.Td>
                              <Switch
                                size="xs"
                                checked={ov.enabled !== undefined ? ov.enabled : r.enabled}
                                onChange={(e) => setOverride(r.id, { enabled: e.currentTarget.checked })}
                              />
                            </Table.Td>
                            <Table.Td><Code style={{ fontSize: 12 }}>{r.id}</Code></Table.Td>
                            <Table.Td>
                              <Text size="sm">{title}</Text>
                              {r.rationale && (
                                <Text size="xs" c="dimmed" lineClamp={2}>{pick(r.rationale, r.rationaleEn)}</Text>
                              )}
                            </Table.Td>
                            <Table.Td>
                              <Select
                                size="xs"
                                w={130}
                                data={[
                                  { value: "", label: tr(`Standard (${r.severity})`, `Default (${r.severity})`) },
                                  ...SEVERITIES.map((s) => ({ value: s, label: s }))
                                ]}
                                value={ov.severity ?? ""}
                                onChange={(v) => setOverride(r.id, { severity: (v as Severity) || null })}
                              />
                            </Table.Td>
                            <Table.Td>
                              <Select
                                size="xs"
                                w={110}
                                data={[
                                  { value: "", label: tr(`Standard (${r.impact})`, `Default (${r.impact})`) },
                                  { value: "1", label: tr("1 · gering", "1 · low") },
                                  { value: "2", label: tr("2 · mittel", "2 · medium") },
                                  { value: "3", label: tr("3 · hoch", "3 · high") },
                                ]}
                                value={ov.impact != null ? String(ov.impact) : ""}
                                onChange={(v) => setOverride(r.id, { impact: v ? Number(v) : null })}
                              />
                            </Table.Td>
                            <Table.Td>
                              <Group gap={2}>
              {isOv && <Badge size="xs" color="brand" variant="light" title={tr("hier überschrieben", "overridden here")}>{tr("ändert", "override")}</Badge>}
                                <ActionIcon
                                  variant="subtle"
                                  size="xs"
                                  title={tr("Behebung und Score", "Remediation and score")}
                                  onClick={() => setExpanded(expanded === r.id ? null : r.id)}
                                >
                                  <IconSelector size={14} />
                                </ActionIcon>
                              </Group>
                            </Table.Td>
                          </Table.Tr>
                          {expanded === r.id && (
                            <Table.Tr>
                              <Table.Td colSpan={6}>
                                <Stack gap={2}>
                                  <Text size="xs"><b>{tr("Warum:", "Why:")}</b> {pick(r.rationale, r.rationaleEn) || "—"}</Text>
                                  <Text size="xs"><b>{tr("Behebung:", "Remediation:")}</b> {pick(r.guidance, r.guidanceEn) || "—"}</Text>
                                  <Text size="xs" c="dimmed">
                                    {tr("Score je Treffer = Gewicht", "Score per hit = weight")}{" "}
                                    <span style={{ color: `var(--mantine-color-${SEV_COLOR[r.severity]}-7)` }}>
                                      {r.severity}
                                    </span>{" "}
                                    × Impact {ov.impact ?? r.impact} = {sevWeight(r.severity) * (ov.impact ?? r.impact)}
                                  </Text>
                                </Stack>
                              </Table.Td>
                            </Table.Tr>
                          )}
                        </Fragment>
                      );
                    })}
                  </Fragment>
                );
              })}
              {rules.length === 0 && (
                <Table.Tr><Table.Td colSpan={6}><Text c="dimmed" ta="center" py="md">{tr("Keine Regeln geladen.", "No rules loaded.")}</Text></Table.Td></Table.Tr>
              )}
            </Table.Tbody>
          </Table>
          </Table.ScrollContainer>

          <Paper withBorder p="md">
            <Title order={5} mb="xs">{tr("Schwellwerte", "Thresholds")}</Title>
            <Text size="xs" c="dimmed" mb="sm">
              {tr("Leer = Standardwert. Nach dem Speichern wirksam bei der nächsten Analyse.", "Empty = default value. After saving, applies to the next analysis.")}
            </Text>
            <Group gap="md">
              {thresholdKeys.map((k) => (
                <NumberInput
                  key={k}
                  size="xs"
                  w={220}
                  label={k}
                  placeholder={String(defaults.thresholds?.[k] ?? "")}
                  value={(settings.thresholds ?? {})[k] as number ?? ""}
                  onChange={(v) => setThreshold(k, v === "" ? null : Number(v))}
                />
              ))}
            </Group>
          </Paper>
          <Paper withBorder p="md">
            <Title order={5} mb="xs">{tr("Automatisch nicht prüfen", "Automatically skip checks")}</Title>
            <Text size="xs" c="dimmed" mb="sm">
              {tr(
                "Kommagetrennte Listen. Namensmuster treffen Objektnamen (Teilstring, ohne Groß/Kleinschreibung; Projekt- und Toolkit-Objekte), Tags treffen nur Projektobjekte mit dem passenden Process-Center-Tag. Nach dem Speichern wirksam ab der nächsten Analyse; die betroffenen Objekte sind im Snapshot-Tab „Ausgeschlossen“ sichtbar. Diese Liste erweitert die globale Voreinstellung (EXCLUSION_NAME_PATTERNS / EXCLUSION_TAGS).",
                "Comma-separated lists. Name patterns match object names (substring, case-insensitive; project and toolkit objects); tags only match project objects carrying the matching Process Center tag. After saving, applies from the next analysis; affected objects appear in the snapshot tab “Excluded”. This list extends the global defaults (EXCLUSION_NAME_PATTERNS / EXCLUSION_TAGS)."
              )}
            </Text>
            <Group gap="md" align="flex-start">
              <TextInput
                size="xs"
                w={340}
                label={tr("Namensteile (z. B. _deprecated)", "Name parts (e.g. _deprecated)")}
                placeholder="_deprecated, _obsolete"
                value={excludeNamePatternsText}
                onChange={(e) => {
                  const text = e.currentTarget.value;
                  const list = text.split(",").map((v) => v.trim()).filter(Boolean);
                  setExcludeNamePatternsText(text);
                  setSettings({ ...settings, excludeNamePatterns: list });
                  setDirty(true);
                }}
              />
              <TextInput
                size="xs"
                w={340}
                label={tr("Tags (z. B. DoNotCheck)", "Tags (e.g. DoNotCheck)")}
                placeholder="DoNotCheck, Prototype"
                value={excludeTagsText}
                onChange={(e) => {
                  const text = e.currentTarget.value;
                  const list = text.split(",").map((v) => v.trim()).filter(Boolean);
                  setExcludeTagsText(text);
                  setSettings({ ...settings, excludeTags: list });
                  setDirty(true);
                }}
              />
            </Group>
          </Paper>
          <Group justify="space-between">
            <Button size="xs" variant="subtle" onClick={() => { setJsonText(JSON.stringify(settings, null, 2)); setJsonMode(true); }}>
              {tr("JSON direkt bearbeiten", "Edit JSON directly")}
            </Button>
            <Group gap="sm">
              {dirty && (
                <Text size="xs" c="orange">
                  {tr("Ungespeicherte Änderungen — erst hier speichern.", "Unsaved changes — save here first.")}
                </Text>
              )}
              <Button size="sm" color="brand" disabled={!dirty} onClick={save}>
                {tr("Speichern", "Save")}
              </Button>
            </Group>
          </Group>
        </>
      )}
    </Stack>
  );
}

/** Score weight per severity (mirror of the backend model, display only). */
function sevWeight(s: Severity): number {
  return s === "CRITICAL" ? 100 : s === "MAJOR" ? 40 : s === "MINOR" ? 10 : 2;
}

/** Drop empty overrides so export/storage stays lean. */
function clean(s: RuleSettingsJson): RuleSettingsJson {
  const overrides: RuleSettingsJson["overrides"] = {};
  for (const [k, v] of Object.entries(s.overrides ?? {})) {
    if (v.enabled !== undefined || v.severity !== undefined || v.impact !== undefined) {
      overrides[k] = v;
    }
  }
  const thresholds: RuleSettingsJson["thresholds"] = {};
  for (const [k, v] of Object.entries(s.thresholds ?? {})) {
    if (v !== undefined && v !== null && v !== "") thresholds[k] = v;
  }
  const lists: Pick<RuleSettingsJson, "excludeNamePatterns" | "excludeTags"> = {};
  if (s.excludeNamePatterns?.length) lists.excludeNamePatterns = s.excludeNamePatterns;
  if (s.excludeTags?.length) lists.excludeTags = s.excludeTags;
  return Object.keys(overrides).length || Object.keys(thresholds).length || Object.keys(lists).length
    ? { overrides, thresholds, ...lists } : {};
}
