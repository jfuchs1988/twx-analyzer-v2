import { useEffect, useRef, useState } from "react";
import {
  Accordion, Badge, Button, Divider, Group, Loader, Modal, Notification,
  ScrollArea, Stack, Text, TextInput
} from "@mantine/core";
import { api, BpmContainer, BpmSnapshot } from "@/shared/api";
import { backendError, useLang } from "@/shared/i18n";

interface Props {
  opened: boolean;
  onClose: () => void;
  /** true: show only toolkit projects (toolkits page); otherwise only process apps. */
  toolkitOnly?: boolean;
}

function sortVersions(list: BpmSnapshot[]): BpmSnapshot[] {
  return [...list].sort((a, b) =>
    (b.creationDate ?? "").localeCompare(a.creationDate ?? ""));
}

function groupByBranch(list: BpmSnapshot[], standardBranchLabel: string): Map<string, BpmSnapshot[]> {
  const byBranch = new Map<string, BpmSnapshot[]>();
  for (const s of list) {
    const b = s.branchName || standardBranchLabel;
    byBranch.set(b, [...(byBranch.get(b) ?? []), s]);
  }
  return byBranch;
}

/**
 * Load TWX directly from the BAW operations interface (read-only GETs).
 * Projects (abbreviations) as an expandable list; below them the versions,
 * grouped by branch if there are several.
 */
export default function BpmImportModal({ opened, onClose, toolkitOnly }: Props) {
  const { tr } = useLang();
  const [containers, setContainers] = useState<BpmContainer[]>([]);
  const [filter, setFilter] = useState("");
  const [expanded, setExpanded] = useState<string[]>([]);
  const [versionsByAcronym, setVersionsByAcronym] = useState<Record<string, BpmSnapshot[]>>({});
  const [loadingFor, setLoadingFor] = useState<Record<string, boolean>>({});
  const [panelError, setPanelError] = useState<Record<string, string>>({});
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);
  const [queued, setQueued] = useState<Set<string>>(new Set());
  const [queuedTotal, setQueuedTotal] = useState(0);
  const [analyzed, setAnalyzed] = useState<Set<string>>(new Set());
  const inFlight = useRef<Set<string>>(new Set());

  const dupKey = (acr: string, ver: string) => `${acr}|${ver}`.toLowerCase();

  useEffect(() => {
    if (!opened) return;
    setError(null);
    setContainers([]);
    setExpanded([]);
    setVersionsByAcronym({});
    setLoadingFor({});
    setPanelError({});
    setFilter("");
    setQueued(new Set());
    setQueuedTotal(0);
    inFlight.current = new Set();
    setLoading(true);
    // already-analyzed project+version combos (duplicate guard in the dialog)
    api.snapshots()
      .then((list) =>
        setAnalyzed(
          new Set(list.map((x) => dupKey(x.projectAcronym ?? "", x.snapshotName ?? "")))
        ))
      .catch(() => { /* duplicate marking is optional */ });
    api.bpmContainers()
      .then((list) => setContainers(
        // Type separation: each page shows only its project kind (flag comes from BPM itself)
        list.filter((c) => (toolkitOnly ? c.toolkit : !c.toolkit))))
      .catch((e) => setError(tr(`BPM nicht erreichbar – Zugang in den Settings prüfen. (${e})`, `BPM not reachable – check the access in the settings. (${e})`)))
      .finally(() => setLoading(false));
  }, [opened, toolkitOnly, tr]);

  const needle = filter.trim().toLowerCase();
  const visible = needle
    ? containers.filter((c) =>
        c.acronym.toLowerCase().includes(needle) || c.name.toLowerCase().includes(needle))
    : containers;

  const loadVersions = (acr: string) => {
    if (inFlight.current.has(acr) || versionsByAcronym[acr]) return;
    inFlight.current.add(acr);
    setLoadingFor((p) => ({ ...p, [acr]: true }));
    setPanelError((p) => ({ ...p, [acr]: "" }));
    api.bpmSnapshots(acr)
      .then((list) => setVersionsByAcronym((p) => ({ ...p, [acr]: list })))
      .catch((e) => setPanelError((p) => ({ ...p, [acr]: String(e) })))
      .finally(() => {
        inFlight.current.delete(acr);
        setLoadingFor((p) => ({ ...p, [acr]: false }));
      });
  };

  // Import is queued (202): loads/analyzes in the background, several in parallel.
  const doImport = async (acronym: string, version: string) => {
    const key = dupKey(acronym, version);
    if (queued.has(key) || analyzed.has(key)) return;
    setQueued((p) => new Set(p).add(key));
    setError(null);
    try {
      const r = await api.bpmImport(acronym, version);
      const body = await r.json().catch(() => null);
      if (!r.ok) throw new Error(backendError(body?.error) ?? `HTTP ${r.status}`);
      setQueuedTotal((n) => n + 1);
      setAnalyzed((p) => new Set(p).add(key)); // gray out button, avoid multiple queueing
    } catch (e) {
      setQueued((p) => {
        const n = new Set(p);
        n.delete(key);
        return n;
      });
      setError(`${acronym} ${version}: ${e}`);
    }
  };

  const renderPanel = (c: BpmContainer) => {
    if (loadingFor[c.acronym]) {
      return <Group gap="xs"><Loader size="sm" /><Text size="sm" c="dimmed">{tr("Versionen werden geladen…", "Loading versions…")}</Text></Group>;
    }
    if (panelError[c.acronym]) {
      return <Text size="sm" c="red">{panelError[c.acronym]}</Text>;
    }
    const list = versionsByAcronym[c.acronym];
    if (!list) return null;
    if (list.length === 0) {
      return (
        <Text size="sm" c="dimmed">
          {tr("Keine benannten Snapshots", "No named snapshots")}.
        </Text>
      );
    }
    const branches = groupByBranch(sortVersions(list), tr("Standard-Branch", "Standard branch"));
    const showBranchHeaders = branches.size > 1;
    return (
      <Stack gap={showBranchHeaders ? "xs" : 4}>
        {[...branches.entries()].map(([branch, items], i) => (
          <Stack key={branch} gap={4}>
            {showBranchHeaders && (
              <>
                {i > 0 && <Divider />}
                <Text size="xs" fw={700} c="dimmed">Branch: {branch}</Text>
              </>
            )}
            {sortVersions(items).map((s) => {
              const key = dupKey(c.acronym, s.version);
              const isQueued = queued.has(key);
              const known = analyzed.has(key);
              return (
                <Group key={`${branch}/${s.version}`} justify="space-between" wrap="nowrap">
                  <Stack gap={0}>
                    <Text size="sm">
                      {s.versionName || tr("Entwicklungsstand (TIP)", "Development tip")}
                      {s.tip && <Badge size="xs" color="orange" ml={6}
                        title={tr("Immer der letzte Stand des Projekts (TIP) – auch ohne benannten Snapshot",
                                  "Always the latest state of the project (TIP) – even without a named snapshot")}>
                        {tr("letzter Stand", "latest state")}
                      </Badge>}
                      {s.active && <Badge size="xs" color="teal" ml={6}>{tr("aktiv", "active")}</Badge>}
                      {known && !isQueued && <Badge size="xs" color="gray" ml={6}>{tr("bereits analysiert", "already analyzed")}</Badge>}
                      {isQueued && <Badge size="xs" color="blue" ml={6}>{tr("eingereiht", "queued")}</Badge>}
                    </Text>
                    <Text size="xs" c="dimmed">
                      {s.version}  ·  {s.creationDate?.slice(0, 10) ?? ""}
                      {showBranchHeaders ? "" : s.branchName ? `  ·  ${s.branchName}` : ""}
                    </Text>
                  </Stack>
                  <Button
                    size="xs"
                    variant="light"
                    loading={isQueued}
                    disabled={known}
                    onClick={() => doImport(c.acronym, s.version)}
                  >
                    {isQueued ? tr("Wartet…", "Waiting…") : known ? tr("Vorhanden", "Already present") : tr("Analysieren", "Analyze")}
                  </Button>
                </Group>
              );
            })}
          </Stack>
        ))}
      </Stack>
    );
  };

  return (
    <Modal opened={opened} onClose={onClose}
      title={toolkitOnly ? tr("Toolkit-TWX von BPM laden", "Load toolkit TWX from BPM") : tr("TWX von BPM laden", "Load TWX from BPM")} size="lg">
      <Stack>
        {error && (
          <Notification color="red" onClose={() => setError(null)}>{error}</Notification>
        )}
        <Group justify="space-between" wrap="nowrap">
          <TextInput
            style={{ flex: 1 }}
            placeholder={tr("Kürzel oder Name suchen…", "Search acronym or name…")}
            value={filter}
            onChange={(e) => setFilter(e.currentTarget.value)}
            disabled={loading}
            description={loading
              ? tr("Projekte werden geladen…", "Loading projects…")
              : tr(`${visible.length} von ${containers.length} Projekten – Version aufklappen und „Analysieren“ klicken. Falls 0: BPM-Zugang in den Settings prüfen.`, `${visible.length} of ${containers.length} projects – expand a version and click “Analyze”. If 0: check the BPM access in the settings.`)}
          />
        </Group>
        {loading && (
          <Group gap="xs"><Loader size="sm" /><Text size="sm" c="dimmed">{tr("Projekte werden geladen…", "Loading projects…")}</Text></Group>
        )}
        {!loading && visible.length === 0 && (
          <Text size="sm" c="dimmed">{tr("Keine Projekte gefunden.", "No projects found.")}</Text>
        )}
        {visible.length > 0 && (
          <ScrollArea.Autosize mih={200} mah="60vh" type="hover">
            <Accordion multiple value={expanded} onChange={(v) => {
              setExpanded(v);
              v.forEach(loadVersions);
            }}>
              {visible.map((c) => (
                <Accordion.Item key={c.acronym} value={c.acronym}>
                  <Accordion.Control>
                    {c.acronym} — {c.name}
                    {c.toolkit && <Badge size="xs" color="violet" ml={6}>Toolkit</Badge>}
                  </Accordion.Control>
                  <Accordion.Panel>{renderPanel(c)}</Accordion.Panel>
                </Accordion.Item>
              ))}
            </Accordion>
          </ScrollArea.Autosize>
        )}
        <Group gap="xs">
          {queuedTotal > 0 && <Loader size="sm" />}
          <Text size="sm" c="dimmed">
            {queuedTotal > 0
              ? tr(`${queuedTotal} Import${queuedTotal > 1 ? "s" : ""} eingereiht – Download und Analyse laufen im Hintergrund (Banner oben), der Dialog kann geschlossen werden.`, `${queuedTotal} import${queuedTotal > 1 ? "s" : ""} queued – download and analysis run in the background (banner above); the dialog can be closed.`)
              : tr("Mehrere Versionen nacheinander einreihbar – Download und Analyse laufen parallel. Es werden ausschließlich lesende Endpunkte verwendet.", "Multiple versions can be queued one after another – download and analysis run in parallel. Only read-only endpoints are used.")}
          </Text>
        </Group>
      </Stack>
    </Modal>
  );
}
