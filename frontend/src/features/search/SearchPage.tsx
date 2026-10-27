import { useEffect, useState } from "react";
import {
  ActionIcon, Alert, Badge, Card, Checkbox, Group, Loader, MultiSelect, SegmentedControl,
  Select, Stack, Switch, Text, TextInput, Title, Tooltip
} from "@mantine/core";
import { IconRegex, IconSearch } from "@tabler/icons-react";
import { api, SearchMatch, SearchResponse, Snapshot, SnapshotType } from "@/shared/api";
import ObjectContentModal from "@/shared/components/ObjectContentModal";
import { useLang } from "@/shared/i18n";

/** Common object types for narrowing (prefix per TWX format). */
const PREFIX_OPTIONS = [
  { value: "1", de: "process (1) — Scripts im Prozess-XML", en: "process (1) — scripts in the process XML" },
  { value: "25", de: "bpd (25) — Scripts im BPD-XML", en: "bpd (25) — scripts in the BPD XML" },
  { value: "64", de: "coachView (64) — Coach-JavaScript", en: "coachView (64) — coach JavaScript" },
  { value: "12", de: "twClass (12) — Klassendefinitionen", en: "twClass (12) — class definitions" },
  { value: "4", de: "underCoverAgent (4)", en: "underCoverAgent (4)" },
  { value: "2051", de: "restService (2051)", en: "restService (2051)" },
  { value: "6023", de: "eSArtifact (6023)", en: "eSArtifact (6023)" },
  { value: "62", de: "environmentVariableSet (62)", en: "environmentVariableSet (62)" },
];

/** Search scope (B3): where is the search performed? */
const SCOPE_OPTIONS = [
  { value: "name", de: "Name", en: "Name" },
  { value: "docs", de: "Beschreibungen", en: "Descriptions" },
  { value: "scripts", de: "Skripte", en: "Scripts" },
  { value: "raw", de: "Rohinhalt (XML/JSON)", en: "Raw content (XML/JSON)" },
];

const SCOPE_LABEL: Record<string, { de: string; en: string }> = {
  name: { de: "Name", en: "Name" }, docs: { de: "Beschreibung", en: "Description" },
  scripts: { de: "Skript", en: "Script" }, raw: { de: "Rohinhalt", en: "Raw content" },
};

export default function SearchPage({
  onOpenSnapshot,
}: {
  onOpenSnapshot: (id: number) => void;
}) {
  const { tr } = useLang();
  const [query, setQuery] = useState("");
  const [result, setResult] = useState<SearchResponse | null>(null);
  const [loading, setLoading] = useState(false);
  const [openObjectId, setOpenObjectId] = useState<string | null>(null);
  const [focus, setFocus] = useState<{ line?: number; needle?: string }>({});
  const [snapshots, setSnapshots] = useState<Snapshot[]>([]);
  const [snapshotId, setSnapshotId] = useState<string | null>(null);
  const [type, setType] = useState<SnapshotType | null>(null);
  const [prefixes, setPrefixes] = useState<string[]>([]);
  const [scopes, setScopes] = useState<string[]>([]); // empty = server default (name+raw)
  const [regex, setRegex] = useState(false);
  const [caseSensitive, setCaseSensitive] = useState(false);

  useEffect(() => {
    api.snapshots()
      .then(setSnapshots)
      .catch(() => setSnapshots([]));
  }, []);

  const matchesType = (s: Snapshot) =>
    type === null ? true : type === "toolkit" ? s.isToolkit === true : s.isToolkit !== true;
  const visibleSnapshots = snapshots.filter(matchesType);

  const run = async () => {
    const min = regex ? 1 : 2;
    if (query.trim().length < min) return;
    setLoading(true);
    try {
      setResult(await api.search(
        query.trim(),
        snapshotId ? Number(snapshotId) : undefined,
        type ?? undefined,
        prefixes.length ? prefixes : undefined,
        {
          scope: scopes.length ? scopes : undefined,
          regex: regex || undefined,
          caseSensitive: caseSensitive || undefined,
        }
      ));
    } finally {
      setLoading(false);
    }
  };

  const openMatch = (objectId: string, m: SearchMatch, needle: string) => {
    setFocus({ line: m.line > 0 ? m.line : undefined, needle: m.scope === "name" ? undefined : needle });
    setOpenObjectId(objectId);
  };

  const scopeLabel = (scope: string) => {
    const l = SCOPE_LABEL[scope];
    return l ? tr(l.de, l.en) : scope;
  };

  return (
    <Stack maw={900}>
      <Title order={2}>{tr("Suche über alle Snapshots", "Search across all snapshots")}</Title>
      <SegmentedControl
        value={type ?? "all"}
        onChange={(v) => {
          setType(v === "all" ? null : (v as SnapshotType));
          setSnapshotId(null); // snapshot selection may no longer match the type
          setResult(null);
        }}
        data={[
          { label: tr("Alle", "All"), value: "all" },
          { label: tr("Prozess-Apps", "Process apps"), value: "app" },
          { label: "Toolkits", value: "toolkit" },
        ]}
      />
      <Group align="flex-end">
        <TextInput
          placeholder={regex
            ? tr("Regex (min. 1 Zeichen, z. B. tw\\.local\\.\\w+ = …)…", "Regex (min. 1 character, e.g. tw\\.local\\.\\w+ = …)…")
            : tr("Volltext über Namen und Inhalte (min. 2 Zeichen)…", "Full text across names and contents (min. 2 characters)…")}
          value={query}
          onChange={(e) => setQuery(e.currentTarget.value)}
          onKeyDown={(e) => e.key === "Enter" && run()}
          leftSection={regex ? <IconRegex size={16} /> : <IconSearch size={16} />}
          error={result?.error ?? undefined}
        />
        <Select
          label={tr("Nur in Snapshot", "Only in snapshot")}
          placeholder={type === "toolkit" ? tr("Alle Toolkit-Snapshots", "All toolkit snapshots") : type === "app" ? tr("Alle Prozess-Apps", "All process apps") : tr("Alle Snapshots", "All snapshots")}
          clearable
          w={260}
          value={snapshotId}
          onChange={(value) => setSnapshotId(value)}
          data={visibleSnapshots.map((s) => ({
            value: String(s.id),
            label: `${s.projectAcronym ? s.projectAcronym + " " : ""}${s.snapshotName ?? s.filename}`,
          }))}
        />
        <ActionIcon variant="filled" size="lg" onClick={run}>
          <IconSearch size={18} />
        </ActionIcon>
      </Group>
      <Group gap="lg">
        <Checkbox.Group label={tr("Suchen in", "Search in")} value={scopes} onChange={setScopes}>
          <Group gap="md">
            {SCOPE_OPTIONS.map((o) => (
              <Checkbox key={o.value} value={o.value} label={tr(o.de, o.en)} size="sm" />
            ))}
          </Group>
        </Checkbox.Group>
        <Tooltip label={tr("Gesicherte Regex: Lookaround und Rückwärtsbezüge sind nicht erlaubt; Laufzeitbudget begrenzt die Suche", "Secured regex: lookaround and backreferences are not allowed; a runtime budget limits the search")}>
          <Switch
            label="Regex"
            checked={regex}
            onChange={(e) => setRegex(e.currentTarget.checked)}
          />
        </Tooltip>
        <Switch
          label={tr("Groß/Kleinschreibung", "Case sensitive")}
          checked={caseSensitive}
          onChange={(e) => setCaseSensitive(e.currentTarget.checked)}
        />
      </Group>
      {!scopes.length && (
        <Text size="xs" c="dimmed" mt={-8}>
          {tr("Ohne Auswahl wird in Name und Rohinhalt gesucht — „Skripte“ liefert fundstellen-genaue Treffer je Skriptblock.", "Without a selection the search covers name and raw content — “Scripts” returns exact per-script-block matches.")}
        </Text>
      )}
      <MultiSelect
        label={tr("Objekttypen eingrenzen (z. B. nur Scripts)", "Limit object types (e.g. scripts only)")}
        placeholder={tr("Alle Objekttypen", "All object types")}
        clearable
        searchable
        data={PREFIX_OPTIONS.map((o) => ({ value: o.value, label: tr(o.de, o.en) }))}
        value={prefixes}
        onChange={setPrefixes}
        maw={520}
      />
      {loading && <Group><Loader size="sm" /><Text c="dimmed">{tr("Sucht…", "Searching…")}</Text></Group>}
      {result && result.error && <Alert color="red">{result.error}</Alert>}
      {result && !result.error && (
        <Stack gap="xs">
          <Text c="dimmed" size="sm">
            {result.hits.length} {tr("Treffer", "matches")} · {result.elapsedMs} ms
            {result.truncated && (
              <Text span inherit c="orange">
                {" "}— {tr("Grenze erreicht (Trefferliste unvollständig; per Snapshot/Objekttyp eingrenzen)", "limit reached (result list incomplete; narrow down by snapshot/object type)")}
              </Text>
            )}
          </Text>
          {result.hits.map((h) => (
            <Card key={`${h.snapshotId}:${h.objectId}`} p="sm" shadow="xs" withBorder
              onClick={() => openMatch(`${h.snapshotId}:${h.objectId}`, h.matches[0] ?? { scope: "", where: "", line: 0, snippet: "" }, query.trim())}>
              <Text fw={600} size="sm">{h.name ?? h.objectId}</Text>
              <Text size="xs" c="dimmed">
                <span
                  style={{ cursor: "pointer", textDecoration: "underline dotted" }}
                  title={tr("Snapshot-Details öffnen", "Open snapshot details")}
                  onClick={(e) => { e.stopPropagation(); onOpenSnapshot(Number(h.snapshotId)); }}
                >
                  {h.snapshotLabel}
                </span>
                {h.isToolkit != null && (
                  <Badge size="xs" variant="light" color={h.isToolkit ? "violet" : "gray"} ml={6}>
                    {h.isToolkit ? "Toolkit" : tr("Prozess-App", "Process app")}
                  </Badge>
                )}
                {" · "}<Badge variant="light">{h.typeName}</Badge> · {h.objectId}
              </Text>
              <Stack gap={2} mt={4}>
                {h.matches.map((m, i) => (
                  <Group key={i} gap={6} wrap="nowrap" style={{ cursor: "pointer" }}
                    onClick={(e) => { e.stopPropagation(); openMatch(`${h.snapshotId}:${h.objectId}`, m, query.trim()); }}>
                    <Badge size="xs" variant="outline" c="dimmed" w={86}>
                      {scopeLabel(m.scope)}{m.line > 0 ? ` · ${tr("Z", "line")} ${m.line}` : ""}
                    </Badge>
                    {m.where && <Text size="xs" c="dimmed" w={150} truncate>{m.where}</Text>}
                    <Text size="xs" ff="monospace" style={{ flex: 1 }} truncate>{m.snippet}</Text>
                  </Group>
                ))}
              </Stack>
            </Card>
          ))}
        </Stack>
      )}
      <ObjectContentModal
        objectId={openObjectId}
        onClose={() => setOpenObjectId(null)}
        highlight={focus.needle ?? query.trim()}
        focusLine={focus.line}
      />
    </Stack>
  );
}
