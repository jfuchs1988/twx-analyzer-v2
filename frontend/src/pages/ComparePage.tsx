import { useEffect, useMemo, useState } from "react";
import {
  Badge, Card, Group, SegmentedControl, Select, SimpleGrid, Stack, Text, Title
} from "@mantine/core";
import { IconArrowsLeftRight } from "@tabler/icons-react";
import { api, CompareDto, healthColor, Snapshot } from "../api";
import ColumnTable, { Column } from "../components/ColumnTable";
import ObjectDiffModal from "../components/ObjectDiffModal";
import type { ObjectDiff, RuleDelta } from "../api";
import { formatNumber, useLang } from "../i18n";

/**
 * B6: version comparison of two snapshots — objects (new/removed/changed via
 * SHA-256) and finding trend per rule. Numbers exclude accepted findings.
 * Row click opens the XML detail diff; the selection stays as a deep link
 * #/compare/{base}/{target} in the address bar (B6 follow-up).
 */
export default function ComparePage({ initialBase, initialTarget }: {
  initialBase?: number | null;
  initialTarget?: number | null;
}) {
  const { tr, pick } = useLang();
  const [snapshots, setSnapshots] = useState<Snapshot[]>([]);
  const [base, setBase] = useState<string | null>(initialBase ? String(initialBase) : null);
  const [target, setTarget] = useState<string | null>(initialTarget ? String(initialTarget) : null);
  const [result, setResult] = useState<CompareDto | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [objView, setObjView] = useState<"added" | "removed" | "changed">("changed");
  const [diffObj, setDiffObj] = useState<ObjectDiff | null>(null);
  const [ruleTitles, setRuleTitles] = useState<Record<string, { de: string; en?: string | null }>>({});

  useEffect(() => {
    api.snapshots().then(setSnapshots).catch(() => setSnapshots([]));
    // Rule catalog for readable names in the delta table ("R-JS-042" -> "R-JS-042 · tw.local nie geschrieben")
    api.rules()
      .then((rs) => setRuleTitles(Object.fromEntries(rs.map((r) => [r.id, { de: r.title, en: r.titleEn }]))))
      .catch(() => setRuleTitles({}));
  }, []);

  // Deep link: both snapshots chosen -> update hash (replaceState
  // so no hashchange feedback loop against App.tsx arises).
  useEffect(() => {
    if (base && target && base !== target) {
      const h = `#/compare/${base}/${target}`;
      if (window.location.hash !== h) {
        window.history.replaceState(null, "", h);
      }
    }
  }, [base, target]);

  // Suggest base = older, target = newer: newest + second newest
  useEffect(() => {
    if (!base && !target && snapshots.length >= 2) {
      const sorted = [...snapshots].sort((a, b) => a.id - b.id);
      setBase(String(sorted[sorted.length - 2].id));
      setTarget(String(sorted[sorted.length - 1].id));
    }
  }, [snapshots, base, target]);

  useEffect(() => {
    if (!base || !target || base === target) {
      setResult(null);
      return;
    }
    setError(null);
    api.compare(Number(base), Number(target))
      .then(setResult)
      .catch((e) => { setResult(null); setError(e instanceof Error ? e.message : String(e)); });
  }, [base, target]);

  const options = useMemo(
    () => snapshots
      .slice()
      .sort((a, b) => a.id - b.id)
      .map((s) => ({
        value: String(s.id),
        label: `#${s.id} · ${s.projectAcronym ?? s.projectName ?? ""} ${s.snapshotName ?? s.filename}`,
      })),
    [snapshots]
  );

  const ruleCols: Column<RuleDelta>[] = [
    {
      id: "rule", label: tr("Regel", "Rule"), width: 260, render: (r) => {
        const t = ruleTitles[r.rule];
        // Title without "R-<KAT>-<nnn> " prefix (ID already precedes it)
        const name = t ? pick(t.de, t.en).replace(/^R-[A-Z]+-\d+\s+/, "") : null;
        return (
          <Text size="xs" fw={600} title={name ?? r.rule}>
            {r.rule}{name ? ` · ${name}` : ""}
          </Text>
        );
      }
    },
    { id: "base", label: tr("Basis", "Base"), width: 80, align: "right", render: (r) => <Text size="xs">{r.baseCount}</Text> },
    { id: "target", label: tr("Ziel", "Target"), width: 80, align: "right", render: (r) => <Text size="xs">{r.targetCount}</Text> },
    { id: "delta", label: tr("Δ Befunde", "Δ findings"), width: 100, align: "right", render: (r) => {
      const d = r.targetCount - r.baseCount;
      return (
        <Text size="xs" fw={d !== 0 ? 700 : undefined} c={d > 0 ? "red" : d < 0 ? "green" : "dimmed"}>
          {d > 0 ? `+${d}` : d}
        </Text>
      );
    } },
    { id: "score", label: "Δ Score", width: 90, align: "right", render: (r) => {
      const d = r.targetScore - r.baseScore;
      return (
        <Text size="xs" c={d > 0 ? "red" : d < 0 ? "green" : "dimmed"}>
          {d > 0 ? `+${d}` : d}
        </Text>
      );
    } },
  ];

  const objectCols: Column<ObjectDiff>[] = [
    { id: "type", label: tr("Typ", "Type"), width: 150, render: (o) => <Badge variant="light" size="sm">{o.typeName}</Badge> },
    { id: "name", label: "Name", render: (o) => (
      <>
        <Text size="sm">{o.name ?? <Text span fs="italic" c="dimmed">{tr("(ohne Namen)", "(unnamed)")}</Text>}</Text>
        <Text size="xs" c="dimmed">{o.id}</Text>
      </>) },
  ];

  const objRows = result
    ? objView === "added" ? result.addedObjects : objView === "removed" ? result.removedObjects : result.changedObjects
    : [];

  return (
    <Stack maw={1100}>
      <Group gap="xs">
        <IconArrowsLeftRight size={22} />
        <Title order={3}>{tr("Versionsvergleich", "Version comparison")}</Title>
      </Group>
      <Group align="flex-end">
        <Select
          label={tr("Basis (älter)", "Base (older)")}
          data={options}
          value={base}
          onChange={setBase}
          searchable
          w={380}
          placeholder={tr("Snapshot wählen…", "Select snapshot…")}
        />
        <Text pb="xs">→</Text>
        <Select
          label={tr("Ziel (neuer)", "Target (newer)")}
          data={options}
          value={target}
          onChange={setTarget}
          searchable
          w={380}
          placeholder={tr("Snapshot wählen…", "Select snapshot…")}
        />
      </Group>
      {error && <Text c="red" size="sm">{error}</Text>}

      {result && (
        <>
          <SimpleGrid cols={{ base: 2, md: 4 }} spacing="md">
            <StatCard label={tr("Befunde (ohne akzeptierte)", "Findings (excluding accepted)")} base={result.baseFindings} target={result.targetFindings} invert />
            <StatCard label={tr("Score-Summe", "Total score")} base={result.baseScore} target={result.targetScore} invert />
            <Card withBorder p="sm">
              <Text size="xs" c="dimmed">Health</Text>
              <Group gap="xs">
                <Badge variant="light" color={healthColor(result.baseHealth)}>{result.baseHealth}</Badge>
                <Text size="xs">→</Text>
                <Badge variant="light" color={healthColor(result.targetHealth)}>{result.targetHealth}</Badge>
              </Group>
            </Card>
            <Card withBorder p="sm">
              <Text size="xs" c="dimmed">{tr("Objekte", "Objects")}</Text>
              <Group gap="xs">
                <Text size="sm">{result.addedObjects.length} {tr("neu", "new")}</Text>
                <Text size="sm">{result.removedObjects.length} {tr("entfernt", "removed")}</Text>
                <Text size="sm">{result.changedObjects.length} {tr("geändert", "changed")}</Text>
              </Group>
            </Card>
          </SimpleGrid>

          <Title order={5}>{tr("Befunde je Regel", "Findings per rule")}</Title>
          <ColumnTable
            storageKey="compare-rules"
            columns={ruleCols}
            rows={result.rules}
            rowKey={(r) => r.rule}
            rowColor={(r) => {
              const d = r.targetCount - r.baseCount;
              return d > 0 ? "var(--mantine-color-red-0)" : d < 0 ? "var(--mantine-color-green-0)" : undefined;
            }}
            empty={<Text c="dimmed" ta="center" py="md">{tr("Keine Regel hat sich verändert.", "No rule has changed.")}</Text>}
          />

          <Group mt="md">
            <Title order={5}>{tr("Objekte", "Objects")}</Title>
            <SegmentedControlLocal
              value={objView}
              onChange={setObjView}
              counts={{
                changed: result.changedObjects.length,
                added: result.addedObjects.length,
                removed: result.removedObjects.length,
              }}
            />
          </Group>
          <Text size="xs" c="dimmed">{tr("Zeile anklicken für den XML-Detail-Diff dieser Objektversion.", "Click a row to open the XML detail diff for this object version.")}</Text>
          <ColumnTable
            storageKey="compare-objects"
            columns={objectCols}
            rows={objRows}
            rowKey={(o) => o.id}
            onRowClick={setDiffObj}
            empty={<Text c="dimmed" ta="center" py="md">{tr("Keine Objekte in dieser Kategorie.", "No objects in this category.")}</Text>}
          />
        </>
      )}
      {!result && !error && base === target && base !== null && (
        <Text c="dimmed">{tr("Bitte zwei verschiedene Snapshots wählen.", "Please select two different snapshots.")}</Text>
      )}
      {result && (
        <ObjectDiffModal
          base={result.base.id}
          target={result.target.id}
          obj={diffObj}
          onClose={() => setDiffObj(null)}
        />
      )}
    </Stack>
  );
}

function StatCard({ label, base, target, invert }: {
  label: string; base: number; target: number; invert?: boolean;
}) {
  const { lang } = useLang();
  const d = target - base;
  const good = invert ? d < 0 : d > 0;
  return (
    <Card withBorder p="sm">
      <Text size="xs" c="dimmed">{label}</Text>
      <Group gap="xs">
        <Text size="sm">{formatNumber(base, lang)}</Text>
        <Text size="xs">→</Text>
        <Text size="sm" fw={700}>{formatNumber(target, lang)}</Text>
        {d !== 0 && (
          <Text size="xs" fw={700} c={good ? "green" : "red"}>({d > 0 ? `+${d}` : d})</Text>
        )}
      </Group>
    </Card>
  );
}

function SegmentedControlLocal({ value, onChange, counts }: {
  value: string;
  onChange: (v: "added" | "removed" | "changed") => void;
  counts: { changed: number; added: number; removed: number };
}) {
  const { tr } = useLang();
  return (
    <SegmentedControl
      value={value}
      onChange={(v: string) => onChange(v as "added" | "removed" | "changed")}
      data={[
        { value: "changed", label: tr(`geändert (${counts.changed})`, `changed (${counts.changed})`) },
        { value: "added", label: tr(`neu (${counts.added})`, `new (${counts.added})`) },
        { value: "removed", label: tr(`entfernt (${counts.removed})`, `removed (${counts.removed})`) },
      ]}
    />
  );
}
