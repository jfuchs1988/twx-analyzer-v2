import { useEffect, useState } from "react";
import {
  Badge, Button, Code, Group, Loader, Paper, Select, Stack, Text, TextInput, Title
} from "@mantine/core";
import { IconSearch } from "@tabler/icons-react";
import { api, VariableRow, VariableUsageRow } from "../api";
import ObjectContentModal from "../components/ObjectContentModal";
import ColumnTable, { Column } from "./ColumnTable";
import { formatNumber, useLang } from "../i18n";

const SCOPES = ["local", "processVariables", "variables", "parameters", "inputs", "outputs"];

/** Usage table + index: identical duplicate occurrences (same object,
 *  same line, same text) would otherwise produce duplicate React keys. */
type UsageRow = VariableUsageRow & { idx: number };

/**
 * Variable catalog + line-exact usage (tw.local.x, tw.processVariables.x, …)
 * within ONE snapshot (process app or toolkit) — as a tab in the snapshot
 * detail, so the app/toolkit selection is not duplicated.
 */
export default function VariablesPanel({ snapshotId }: { snapshotId: number }) {
  const { lang, tr } = useLang();
  const [scope, setScope] = useState<string | null>(null);
  const [q, setQ] = useState("");
  const [rows, setRows] = useState<VariableRow[] | null>(null);
  const [loading, setLoading] = useState(false);

  const [selected, setSelected] = useState<VariableRow | null>(null);
  const [usage, setUsage] = useState<UsageRow[] | null>(null);
  const [usageLoading, setUsageLoading] = useState(false);
  const [openObject, setOpenObject] = useState<{ id: string; line?: number } | null>(null);
  const [backfilling, setBackfilling] = useState(false);
  const [backfillInfo, setBackfillInfo] = useState<string | null>(null);

  const load = async () => {
    setLoading(true);
    try {
      setRows(await api.variables(
        snapshotId,
        scope ?? undefined,
        q.trim() || undefined
      ));
    } finally {
      setLoading(false);
    }
  };

  // Reload catalog on snapshot and filter change
  useEffect(() => {
    load();
    setSelected(null);
    setUsage(null);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [snapshotId, scope]);

  const pick = async (row: VariableRow) => {
    setSelected(row);
    setUsage(null);
    setUsageLoading(true);
    try {
      setUsage((await api.variableUsage(snapshotId, row.variable, row.scope))
        .map((u, idx) => ({ ...u, idx })));
    } finally {
      setUsageLoading(false);
    }
  };

  const needle = selected ? `tw.${selected.scope}.${selected.variable}` : undefined;

  const catalogCols: Column<VariableRow>[] = [
    { id: "var", label: tr("Variable", "Variable"), render: (r) => <Code>tw.{r.scope}.{r.variable}</Code> },
    { id: "assigns", label: tr("Zuweisungen", "Assignments"), width: 110, render: (r) => (
      r.assigns > 0 ? <Badge color="blue" variant="light">{r.assigns}</Badge> : <Text span c="dimmed">0</Text>) },
    { id: "reads", label: tr("Lesend", "Reads"), width: 80, render: (r) => r.reads },
    { id: "objects", label: tr("Objekte", "Objects"), width: 80, render: (r) => r.objects },
  ];

  const usageCols: Column<UsageRow>[] = [
    { id: "object", label: tr("Objekt", "Object"), width: 260, render: (u) => (
      <Text size="xs" truncate>{u.objectName ?? u.objectId}</Text>) },
    { id: "prefix", label: tr("Typ", "Type"), width: 70, render: (u) => <Badge variant="light" size="xs">{u.prefix}</Badge> },
    { id: "line", label: tr("Zeile", "Line"), width: 70, render: (u) => u.line },
    { id: "access", label: tr("Zugriff", "Access"), width: 110, render: (u) => (
      u.assign
        ? <Badge size="xs" color="blue" variant="light">{tr("Zuweisung", "Assignment")}</Badge>
        : <Badge size="xs" variant="light">{tr("Lesezugriff", "Read access")}</Badge>) },
    { id: "text", label: tr("Stelle", "Code"), render: (u) => (
      <Code block style={{ whiteSpace: "pre", fontSize: 11, padding: "2px 6px" }}>{u.lineText}</Code>) },
  ];

  return (
    <Stack gap="sm">
      <Text c="dimmed" size="sm">
        {tr("Gefundene BAW-Variablenzugriffe aus allen Scripts dieses Snapshots (tw.local, tw.processVariables, tw.variables, tw.parameters, tw.inputs, tw.outputs). Klick auf eine Variable zeigt, wo sie verwendet wird. Hinweis: Scans berücksichtigen auch Kommentare und Strings.", "BAW variable accesses found in all scripts of this snapshot (tw.local, tw.processVariables, tw.variables, tw.parameters, tw.inputs, tw.outputs). Click a variable to see where it is used. Note: scans also match comments and strings.")}
      </Text>

      <Group align="flex-end" wrap="wrap">
        <Select
          label={tr("Geltungsbereich", "Scope")}
          placeholder={tr("Alle", "All")}
          clearable
          w={180}
          value={scope}
          onChange={(v) => setScope(v)}
          data={SCOPES}
        />
        <TextInput
          label={tr("Variablenname enthält…", "Variable name contains…")}
          placeholder={tr("z. B. upload", "e.g. upload")}
          value={q}
          onChange={(e) => setQ(e.currentTarget.value)}
          onKeyDown={(e) => e.key === "Enter" && load()}
          leftSection={<IconSearch size={16} />}
        />
        <Button
          size="xs"
          variant="light"
          mb={1}
          loading={backfilling}
          title={tr("Liest alle Snapshots neu ein (z. B. nach Parser-Änderungen)", "Re-reads all snapshots (e.g. after parser changes)")}
          onClick={async () => {
            setBackfilling(true);
            try {
              const n = await api.variablesBackfill();
              setBackfillInfo(tr(`${formatNumber(n, lang)} Fundstellen neu eingelesen.`, `${formatNumber(n, lang)} occurrences re-read.`));
              load();
            } catch {
              setBackfillInfo(tr("Backfill fehlgeschlagen.", "Backfill failed."));
            } finally {
              setBackfilling(false);
            }
          }}
        >
          {backfilling ? tr("Scan läuft…", "Scanning…") : tr("Bestands-Snapshots neu scannen", "Rescan existing snapshots")}
        </Button>
        {backfillInfo && <Text size="xs" c="dimmed">{backfillInfo}</Text>}
      </Group>

      {loading && <Group><Loader size="sm" /><Text c="dimmed">{tr("Lade Katalog…", "Loading catalog…")}</Text></Group>}
      {rows && !loading && (
        <Paper withBorder p="xs">
          <ColumnTable
            storageKey="vars-catalog"
            columns={catalogCols}
            rows={rows.slice(0, 200)}
            rowKey={(r) => `${r.snapshotId}:${r.scope}:${r.variable}`}
            onRowClick={pick}
            empty={<Text c="dimmed">{tr("Keine Variablen gefunden — Snapshot ggf. noch nicht neu eingelesen („Bestands-Snapshots neu scannen“ oben).", "No variables found — the snapshot may not have been re-read yet (“Rescan existing snapshots” above).")}</Text>}
          />
          {rows.length > 200 && (
            <Text size="xs" c="dimmed" mt={4}>{tr(`${rows.length} Variablen — die ersten 200 nach Nutzungen gezeigt.`, `${rows.length} variables — showing the first 200 ordered by usage.`)}</Text>
          )}
        </Paper>
      )}

      {selected && (
        <Stack gap={4}>
          <Group>
            <Title order={4}>{tr("Nutzung von", "Usage of")} <Code>{needle}</Code></Title>
            {usage && <Text size="xs" c="dimmed">{tr(`${usage.length} Fundstellen${usage.length >= 500 ? " (Cap)" : ""}`, `${usage.length} occurrences${usage.length >= 500 ? " (cap)" : ""}`)}</Text>}
          </Group>
          {usageLoading && <Loader size="sm" />}
          {usage && (
            <Paper withBorder p="xs">
              <ColumnTable
                storageKey="vars-usage"
                columns={usageCols}
                rows={usage}
                rowKey={(u) => `${u.objectId}:${u.line}:${u.assign ? "a" : "r"}:${u.idx}`}
                onRowClick={(u) => setOpenObject({ id: u.objectId, line: u.line })}
                empty={<Text c="dimmed">{tr("Keine Fundstellen.", "No occurrences.")}</Text>}
              />
            </Paper>
          )}
        </Stack>
      )}

      <ObjectContentModal
        objectId={openObject?.id ?? null}
        onClose={() => setOpenObject(null)}
        highlight={needle}
        focusLine={openObject?.line}
      />
    </Stack>
  );
}
