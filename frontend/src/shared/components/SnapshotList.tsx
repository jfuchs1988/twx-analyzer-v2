import { Fragment, useEffect, useState } from "react";
import {
  ActionIcon, Badge, Button, Group, Modal, Notification, Table, Text, Tooltip
} from "@mantine/core";
import { IconEye, IconRefresh, IconTrash } from "@tabler/icons-react";
import { api, healthColor, RuleBar, Snapshot, SnapshotType } from "@/shared/api";
import { useLang } from "@/shared/i18n";
import { INGEST_DONE_EVENT } from "./IngestBanner";

/** Color per severity for the rule-hit bar (B10). */
const BAR_COLOR: Record<string, string> = {
  CRITICAL: "var(--mantine-color-red-8)",
  MAJOR: "var(--mantine-color-orange-7)",
  MINOR: "var(--mantine-color-blue-6)",
  INFO: "var(--mantine-color-gray-5)",
};

/** B10: stacked bar of the top rules (segment width = share of hits). */
function TrefferBar({ bars }: { bars?: RuleBar[] }) {
  if (!bars || bars.length === 0) {
    return <Text size="xs" c="dimmed">—</Text>;
  }
  const total = bars.reduce((a, b) => a + b.count, 0);
  return (
    <Tooltip label={bars.map((b) => `${b.rule}: ${b.count}`).join("  ·  ")}>
      <div style={{
        display: "flex", height: 10, borderRadius: 3, overflow: "hidden", minWidth: 110,
        background: "light-dark(var(--mantine-color-gray-2), var(--mantine-color-dark-5))",
      }}>
        {bars.map((b) => (
          <div
            key={b.rule}
            style={{
              width: `${(100 * b.count) / total}%`,
              background: BAR_COLOR[b.severity] ?? "var(--mantine-color-gray-5)",
            }}
          />
        ))}
      </div>
    </Tooltip>
  );
}

/**
 * Snapshot list grouped by project, with delete dialog.
 * Split by type: "app" = process apps (incl. legacy entries without type), "toolkit" = toolkits.
 * reload-count upgrade (refreshKey) gives a fresh reload after upload/import.
 */
export default function SnapshotList({
  type,
  refreshKey,
  onOpen,
  emptyText,
}: {
  type: SnapshotType;
  refreshKey: number;
  onOpen: (id: number) => void;
  emptyText: string;
}) {
  const [snapshots, setSnapshots] = useState<Snapshot[]>([]);
  const [bars, setBars] = useState<Record<string, RuleBar[]>>({});
  const [error, setError] = useState<string | null>(null);
  const [deleteTarget, setDeleteTarget] = useState<Snapshot | null>(null);
  const [deleting, setDeleting] = useState(false);
  const [reanalyzeTarget, setReanalyzeTarget] = useState<Snapshot | null>(null);
  const [reanalyzing, setReanalyzing] = useState(false);
  const [notice, setNotice] = useState<string | null>(null);
  const { tr } = useLang();

  /** Display label per snapshot status (backend enum, display-only). */
  const statusLabel = (st: string) =>
    st === "UPLOADED" ? tr("Hochgeladen", "Uploaded")
      : st === "PARSED" ? tr("Geparst", "Parsed")
      : st === "READY" ? tr("Bereit", "Ready")
      : st === "ERROR" ? tr("Fehler", "Error")
      : st;

  useEffect(() => {
    api.snapshots(type).then(setSnapshots).catch((e) => setError(String(e)));
    api.ruleSummary().then(setBars).catch(() => setBars({}));
  }, [type, refreshKey]);

  // Background ingests can finish while the list is open
  useEffect(() => {
    const onDone = () => {
      api.snapshots(type).then(setSnapshots).catch((e) => setError(String(e)));
      api.ruleSummary().then(setBars).catch(() => setBars({}));
    };
    window.addEventListener(INGEST_DONE_EVENT, onDone);
    return () => window.removeEventListener(INGEST_DONE_EVENT, onDone);
  }, [type]);

  // Grouping: project -> snapshots. Projects alphabetical by acronym,
  // within each group descending by upload time — newest on top.
  const keyOf = (s: Snapshot) => s.projectAcronym || s.projectName || s.filename;
  const tsOf = (s: Snapshot) => s.uploadedAt || "";
  const groups = new Map<string, Snapshot[]>();
  for (const s of snapshots) {
    const k = keyOf(s);
    groups.set(k, [...(groups.get(k) ?? []), s]);
  }
  const groupList = [...groups.entries()]
    .map(([key, items]) => {
      items.sort((a, b) => tsOf(b).localeCompare(tsOf(a)) || Number(b.id) - Number(a.id));
      return { key, items, latest: items[0] };
    })
    .sort((a, b) => a.key.localeCompare(b.key, undefined, { sensitivity: "base" }));

  return (
    <>
      {error && <Notification color="red" onClose={() => setError(null)}>{error}</Notification>}
      {notice && <Notification color="green" onClose={() => setNotice(null)}>{notice}</Notification>}

      {/* ScrollContainer: narrow viewport -> the table scrolls internally instead of
          pushing the page horizontally (otherwise footer/navbar shift by the scrollbar
          width and appear to "overlap" at the bottom). */}
      <Table.ScrollContainer minWidth={900} type="native">
      <Table striped highlightOnHover withTableBorder>
        <Table.Thead>
          <Table.Tr>
            <Table.Th>{tr("Datei", "File")}</Table.Th>
            <Table.Th>{tr("Snapshot", "Snapshot")}</Table.Th>
            <Table.Th>{tr("BAW-Version", "BAW version")}</Table.Th>
            <Table.Th ta="right">{tr("Objekte", "Objects")}</Table.Th>
            <Table.Th ta="right" title={tr("nur deklarierte Toolkit-Abhängigkeiten (Manifest)", "declared toolkit dependencies only (manifest)")}>{tr("Toolkits", "Toolkits")}<sup>dek</sup></Table.Th>
            <Table.Th ta="right">{tr("Findings", "Findings")}</Table.Th>
            <Table.Th title={tr("Regel-Treffer-Balken: Top-Regeln nach Treffern (ohne akzeptierte)", "Rule hit bar: top rules by hits (excluding accepted)")} style={{ width: 130 }}>{tr("Treffer", "Hits")}</Table.Th>
            <Table.Th ta="right" title={tr("Health-Score: 100 = unauffällig, bewertet Findings je Objekt", "Health score: 100 = clean, rates findings per object")}>{tr("Health", "Health")}</Table.Th>
            <Table.Th>{tr("Status", "Status")}</Table.Th>
            <Table.Th />
          </Table.Tr>
        </Table.Thead>
        <Table.Tbody>
          {groupList.map(({ key, items }) => (
            <Fragment key={key}>
              <Table.Tr>
                <Table.Td colSpan={10} fw={700} style={{ background: "light-dark(var(--mantine-color-gray-1), var(--mantine-color-dark-6))" }}>
                  <Group gap="xs" wrap="nowrap">
                    <Text fw={700} size="sm">{key}</Text>
                    {items[0].isToolkit != null && (
                      <Badge size="xs" variant="light" color={items[0].isToolkit ? "violet" : "gray"}>
                        {items[0].isToolkit ? tr("Toolkit", "Toolkit") : tr("Prozess-App", "Process app")}
                      </Badge>
                    )}
                    {items[0].projectName && items[0].projectName !== key && (
                      <Text size="xs" c="dimmed" lineClamp={1}>{items[0].projectName}</Text>
                    )}
                    <Badge size="xs" variant="light" c="gray">{tr(`${items.length} Version${items.length > 1 ? "en" : ""}`, `${items.length} version${items.length > 1 ? "s" : ""}`)}</Badge>
                  </Group>
                </Table.Td>
              </Table.Tr>
              {items.map((s) => (
                <Table.Tr key={s.id} style={{ cursor: "pointer" }} onClick={() => onOpen(s.id)}>
                  <Table.Td>
                    <Text size="xs" c="dimmed">{s.filename}</Text>
                  </Table.Td>
                  <Table.Td>
                    {s.snapshotName ?? "—"}
                    {s.branchName && s.branchName !== "Main" && (
                      <Badge size="xs" variant="light" ml={6}>{s.branchName}</Badge>
                    )}
                  </Table.Td>
                  <Table.Td>{s.buildVersion}</Table.Td>
                  <Table.Td ta="right">{s.objectCount}</Table.Td>
                  <Table.Td ta="right">{s.toolkitCount}</Table.Td>
                  <Table.Td ta="right">{s.findingCount}</Table.Td>
                  <Table.Td><TrefferBar bars={bars[String(s.id)]} /></Table.Td>
                  <Table.Td ta="right">
                    {s.healthScore != null ? (
                      <Badge variant="light" color={healthColor(s.healthScore)}>{s.healthScore}</Badge>
                    ) : "—"}
                  </Table.Td>
                  <Table.Td>
                    <Badge
                      color={s.status === "READY" ? "green" : s.status === "ERROR" ? "red" : "yellow"}
                    >
                      {statusLabel(s.status)}
                    </Badge>
                  </Table.Td>
                  <Table.Td>
                    <Group gap={4} onClick={(e) => e.stopPropagation()}>
                      <ActionIcon variant="light" onClick={() => onOpen(s.id)} title={tr("Details", "Details")}>
                        <IconEye size={16} />
                      </ActionIcon>
                      <ActionIcon
                        variant="light"
                        color="blue"
                        title={tr("Neu analysieren (aus gespeicherter Datei, ohne BAW-Export)", "Re-analyze (from stored file, no BAW export)")}
                        disabled={s.status !== "READY"}
                        onClick={() => setReanalyzeTarget(s)}
                      >
                        <IconRefresh size={16} />
                      </ActionIcon>
                      <ActionIcon
                        variant="light"
                        color="red"
                        title={tr("Löschen", "Delete")}
                        onClick={() => setDeleteTarget(s)}
                      >
                        <IconTrash size={16} />
                      </ActionIcon>
                    </Group>
                  </Table.Td>
                </Table.Tr>
              ))}
            </Fragment>
          ))}
          {snapshots.length === 0 && (
            <Table.Tr>
              <Table.Td colSpan={10}>
                <Text c="dimmed" ta="center" py="md">{emptyText}</Text>
              </Table.Td>
            </Table.Tr>
          )}
        </Table.Tbody>
      </Table>
      </Table.ScrollContainer>

      <Modal
        opened={deleteTarget !== null}
        onClose={() => !deleting && setDeleteTarget(null)}
        title={tr("Snapshot löschen", "Delete snapshot")}
        size="sm"
      >
        <Text size="sm">
          {tr(
            `Snapshot „${deleteTarget?.filename}“ wirklich löschen? Die Analyse wird entfernt; globale Toolkit-Einträge (Dedup-Speicher) bleiben erhalten.`,
            `Really delete snapshot "${deleteTarget?.filename}"? The analysis will be removed; global toolkit entries (dedup store) are kept.`,
          )}
        </Text>
        <Group justify="flex-end" mt="md">
          <Button variant="subtle" disabled={deleting} onClick={() => setDeleteTarget(null)}>
            {tr("Abbrechen", "Cancel")}
          </Button>
          <Button
            color="red"
            loading={deleting}
            onClick={async () => {
              if (!deleteTarget) return;
              setDeleting(true);
              try {
                await api.deleteSnapshot(deleteTarget.id);
                setDeleteTarget(null);
                api.snapshots(type).then(setSnapshots).catch((e) => setError(String(e)));
              } catch (e) {
                setError(String(e));
                setDeleteTarget(null);
              } finally {
                setDeleting(false);
              }
            }}
          >
            {tr("Löschen", "Delete")}
          </Button>
        </Group>
      </Modal>

      <Modal
        opened={reanalyzeTarget !== null}
        onClose={() => !reanalyzing && setReanalyzeTarget(null)}
        title={tr("Snapshot neu analysieren", "Re-analyze snapshot")}
        size="sm"
      >
        <Text size="sm">
          {tr(
            `Die gespeicherte TWX-Datei von „${reanalyzeTarget?.filename}“ läuft erneut durch die Analyse — ohne erneuten Export aus dem Process Center. Bei Erfolg ersetzt die neue Analyse den alten Snapshot; Triage-Stände (erledigt/akzeptiert) entfallen.`,
            `The stored TWX file of "${reanalyzeTarget?.filename}" runs through the analysis again — no re-export from the Process Center needed. On success the new analysis replaces the old snapshot; triage states (resolved/accepted) are dropped.`,
          )}
        </Text>
        <Group justify="flex-end" mt="md">
          <Button variant="subtle" disabled={reanalyzing} onClick={() => setReanalyzeTarget(null)}>
            {tr("Abbrechen", "Cancel")}
          </Button>
          <Button
            loading={reanalyzing}
            onClick={async () => {
              if (!reanalyzeTarget) return;
              setReanalyzing(true);
              try {
                await api.reanalyze(reanalyzeTarget.id);
                setNotice(tr(
                  "Re-analyse eingequeuet — Fortschritt in der Warteschlangen-Leiste; die Liste aktualisiert sich, sobald die neue Analyse bereit ist.",
                  "Re-analysis queued — progress shows in the queue banner; the list refreshes once the new analysis is ready.",
                ));
                setReanalyzeTarget(null);
              } catch (e) {
                setError(String(e));
                setReanalyzeTarget(null);
              } finally {
                setReanalyzing(false);
              }
            }}
          >
            {tr("Neu analysieren", "Re-analyze")}
          </Button>
        </Group>
      </Modal>
    </>
  );
}
