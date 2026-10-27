import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import {
  ActionIcon, Alert, Badge, Button, Code, Group, Loader, Paper,
  Select, Stack, Switch, Table, Tabs, Text, TextInput, Title, Tooltip
} from "@mantine/core";
import {
  IconAlertTriangle, IconArrowLeft, IconCheck, IconDeviceFloppy, IconFileExport,
  IconLayoutDashboard, IconRefresh, IconRestore, IconSearch, IconX
} from "@tabler/icons-react";
import {
  api, Exclusion, Finding, GateDto, healthColor, RuleInfo, SEVERITIES, SEV_COLOR, Snapshot, STATUS_COLOR,
  STATUS_LABEL, TaskRow, Toolkit, ToolkitGraph, TwxObject, TypeCount
} from "@/shared/api";
import ObjectContentModal from "@/shared/components/ObjectContentModal";
import ObjectBrowserModal from "@/features/snapshots/components/ObjectBrowserModal";
import FindingStatusModal, { BulkTarget } from "@/features/snapshots/components/FindingStatusModal";
import ColumnTable, { Column } from "@/shared/components/ColumnTable";
import VariablesPanel from "@/features/snapshots/components/VariablesPanel";
import { backendError, useLang } from "@/shared/i18n";

/** Colors of the usage levels (B4.2) from the toolkit tab. */
const USAGE_COLOR: Record<string, string> = {
  "in use": "green",
  "no usage detected": "orange",
  "unknown": "gray",
};
export default function SnapshotDetail({ id, onBack, onOpenRules, onReplaced }: {
  id: number;
  onBack: () => void;
  onOpenRules?: (id: number) => void;
  /** After successful re-analyze: switch the view to the new snapshot. */
  onReplaced?: (newId: number) => void;
}) {
  const { tr, pick } = useLang();
  const [snapshot, setSnapshot] = useState<Snapshot | null>(null);
  const [allFindings, setAllFindings] = useState<Finding[]>([]);
  const [objects, setObjects] = useState<TwxObject[]>([]);
  const [toolkits, setToolkits] = useState<Toolkit[]>([]);
  const [types, setTypes] = useState<TypeCount[]>([]);
  const [tab, setTab] = useState("findings");
  const [sevFilter, setSevFilter] = useState<string | null>(null);
  const [ruleFilter, setRuleFilter] = useState<string | null>(null);
  const [reviewOnly, setReviewOnly] = useState(false);
  const [hideDone, setHideDone] = useState(false);
  const [objectFilter, setObjectFilter] = useState<{ id: string; name: string } | null>(null);
  const [objectQuery, setObjectQuery] = useState("");
  const [typeFilter, setTypeFilter] = useState<string | null>(null);
  const [showUndeclared, setShowUndeclared] = useState(false);
  const [page, setPage] = useState(0);
  const [openObjectId, setOpenObjectId] = useState<string | null>(null);
  const [browserObjectId, setBrowserObjectId] = useState<string | null>(null);
  const [graph, setGraph] = useState<ToolkitGraph | null>(null);
  const [tasks, setTasks] = useState<TaskRow[] | null>(null);
  const [gate, setGate] = useState<GateDto | null>(null);
  const [gateBusy, setGateBusy] = useState(false);
  const [statusTarget, setStatusTarget] = useState<
    { finding?: Finding; bulk?: BulkTarget } | null>(null);
  const [notice, setNotice] = useState<{ color: string; text: string } | null>(null);
  /** Rule catalog (ID -> title/description/remediation) for hover tooltips in the findings table. */
  const [ruleDocs, setRuleDocs] = useState<Record<string, RuleInfo>>({});

  useEffect(() => {
    api.rules()
      .then((rs) => setRuleDocs(Object.fromEntries(rs.map((r) => [r.id, r]))))
      .catch(() => setRuleDocs({}));
  }, []);

  const reloadSnapshot = useCallback(
    () => api.snapshot(id).then(setSnapshot).catch(() => setSnapshot(null)), [id]);

  useEffect(() => {
    reloadSnapshot();
    setObjects([]);
    setPage(0);
    setGate(null);
    setTasks(null);
    setObjectFilter(null);
    api.toolkits(id).then(setToolkits).catch(() => setToolkits([]));
    api.types(id).then(setTypes).catch(() => setTypes([]));
    api.toolkitGraph(id).then(setGraph).catch(() => setGraph(null));
  }, [id, reloadSnapshot]);

  // Load the full list; filtering is purely client-side — keeps the
  // severity counts stable and makes the selection visible as state.
  const reloadFindings = useCallback(
    () => api.findings(id).then(setAllFindings).catch(() => setAllFindings([])), [id]);
  useEffect(() => { reloadFindings(); }, [reloadFindings]);

  // Re-analyze: fresh analysis from the stored file (no upload needed).
  // The new snapshot inherits the exclude list and rule settings; the UI
  // stays on the old one until READY, then switches to the new ID.
  const [reanalyzeBusy, setReanalyzeBusy] = useState(false);
  const reanalyzeTimer = useRef<number | null>(null);
  useEffect(() => () => {
    if (reanalyzeTimer.current !== null) {
      window.clearInterval(reanalyzeTimer.current);
    }
  }, []);
  const runReanalyze = () => {
    setReanalyzeBusy(true);
    setNotice(null);
    const oldId = id;
    const acronym = snapshot?.projectAcronym ?? null;
    const version = snapshot?.snapshotName ?? null;
    api.reanalyze(oldId)
      .then(() => {
        reanalyzeTimer.current = window.setInterval(() => {
          api.snapshots().then((snaps) => {
            const nw = snaps.find((s) => s.id !== oldId
              && s.projectAcronym === acronym && s.snapshotName === version);
            if (!nw) {
              return;
            }
            if (nw.status === "READY") {
              window.clearInterval(reanalyzeTimer.current!);
              reanalyzeTimer.current = null;
              setReanalyzeBusy(false);
              setNotice({ color: "green", text: tr(`Neue Analyse fertig — Snapshot ${nw.id} hat ${oldId} ersetzt.`, `New analysis complete — snapshot ${nw.id} replaced ${oldId}.`) });
              onReplaced?.(nw.id);
            } else if (nw.status === "ERROR") {
              window.clearInterval(reanalyzeTimer.current!);
              reanalyzeTimer.current = null;
              setReanalyzeBusy(false);
              setNotice({ color: "red", text: tr("Neue Analyse fehlgeschlagen — der alte Snapshot bleibt erhalten.", "New analysis failed — the old snapshot is kept.") });
            }
          }).catch(() => { /* next poll */ });
        }, 3000);
      })
      .catch((e: unknown) => {
        setReanalyzeBusy(false);
        setNotice({ color: "red", text: String((e as Error)?.message ?? e) });
      });
  };

  const findings = useMemo(
    () =>
      allFindings.filter(
        (f) =>
          (!sevFilter || f.severity === sevFilter) &&
          (!ruleFilter || f.ruleId.startsWith(ruleFilter)) &&
          (!reviewOnly || f.needsReview) &&
          (!hideDone || !f.status || f.status === "OPEN") &&
          (!objectFilter || f.objectId === objectFilter.id)
      ),
    [allFindings, sevFilter, ruleFilter, reviewOnly, hideDone, objectFilter]
  );

  const reloadObjects = useCallback(() => {
    // typeFilter = "<prefix>#<subType|->"  (group from /types; '-' = group without subtype)
    const tf = typeFilter ? typeFilter.split("#") : [];
    const tfSub = tf.length > 1 ? tf.slice(1).join("#") : undefined;
    return api.objects(id, objectQuery || undefined, tf[0] || undefined, page, 100, tfSub || undefined)
      .then(setObjects).catch(() => setObjects([]));
  }, [id, objectQuery, typeFilter, page]);
  useEffect(() => { reloadObjects(); }, [reloadObjects]);

  useEffect(() => {
    if (tab === "tasks" && tasks === null) {
      api.tasks(id).then(setTasks).catch(() => setTasks([]));
    }
  }, [tab, id, tasks]);

  // Tab "Ausgeschlossen": load exclusion rows (manual + automatic) on first open
  const [excludedRows, setExcludedRows] = useState<Exclusion[] | null>(null);
  const [unexcludeBusy, setUnexcludeBusy] = useState<string | null>(null);
  useEffect(() => {
    if (tab === "excluded" && excludedRows === null) {
      api.exclusions(id).then(setExcludedRows).catch(() => setExcludedRows([]));
    }
  }, [tab, id, excludedRows]);
  const restoreExcluded = (x: Exclusion) => {
    setUnexcludeBusy(x.twxId);
    api.unexcludeObject(id, x.twxId)
      .then(() => {
        setExcludedRows((rows) => (rows ?? []).filter((r) => r.twxId !== x.twxId));
        setNotice({ color: "blue", text: tr("Ausschluss aufgehoben — die Befunde des Objekts erscheinen nach der nächsten Analyse (Re-analyse) wieder.", "Exclusion removed — the object's findings reappear after the next analysis (re-analyze).") });
      })
      .catch(() => setNotice({ color: "red", text: tr("Aufheben fehlgeschlagen.", "Could not remove the exclusion.") }))
      .finally(() => setUnexcludeBusy(null));
  };

  const sevCounts = useMemo(() => {
    const c: Record<string, number> = { CRITICAL: 0, MAJOR: 0, MINOR: 0, INFO: 0 };
    allFindings.forEach((f) => (c[f.severity] = (c[f.severity] ?? 0) + 1));
    return c;
  }, [allFindings]);

  const reviewOpen = useMemo(
    () => allFindings.filter((f) => f.needsReview && (!f.status || f.status === "OPEN")).length,
    [allFindings]
  );
  const doneCount = useMemo(
    () => allFindings.filter((f) => f.status && f.status !== "OPEN").length,
    [allFindings]
  );

  // Counters for the rule chips (second filter row): they reflect the
  // severity selection above — e.g. set to CRITICAL and rules that
  // then have no findings drop to (0) and are grayed out.
  const findingsForRuleCounts = useMemo(
    () =>
      allFindings.filter(
        (f) =>
          (!sevFilter || f.severity === sevFilter) &&
          (!reviewOnly || f.needsReview) &&
          (!hideDone || !f.status || f.status === "OPEN") &&
          (!objectFilter || f.objectId === objectFilter.id)
      ),
    [allFindings, sevFilter, reviewOnly, hideDone, objectFilter]
  );

  const ruleCounts = useMemo(() => {
    const c = new Map<string, number>();
    allFindings.forEach((f) => c.set(f.ruleId.split(" ")[0], 0)); // all types stay visible
    findingsForRuleCounts.forEach((f) => {
      const k = f.ruleId.split(" ")[0];
      c.set(k, (c.get(k) ?? 0) + 1);
    });
    return [...c.entries()]
      .sort(([a, ca], [b, cb]) => (cb > 0 ? 1 : 0) - (ca > 0 ? 1 : 0) || a.localeCompare(b));
  }, [allFindings, findingsForRuleCounts]);

  // "Not declared" means: not declared by the app itself but inside
  // an imported toolkit (ToolkitNest) — still in use.
  // Hidden by default, shown only on request.
  const declaredToolkits = useMemo(() => toolkits.filter((t) => t.declared), [toolkits]);
  const undeclaredCount = toolkits.length - declaredToolkits.length;
  const shownToolkits = showUndeclared ? toolkits : declaredToolkits;

  // Display name of a node in the toolkit graph (abbreviation, else name, else id)
  const nodeLabel = (tid: string) => {
    const n = graph?.nodes.find((x) => x.toolkitId === tid);
    return n?.acronym || n?.name || tid;
  };
  const cycleNodes = useMemo(() => new Set(graph?.cycleNodes ?? []), [graph]);

  /** ruleId ("R-JS-042 Deutscher Titel") -> "R-JS-042" (short code for display). */
  const ruleCode = (ruleId: string) => {
    const m = ruleId.match(/^R-[A-Z]+-\d+/);
    return m ? m[0] : ruleId;
  };
  /** Rule text: catalog title (bilingual), else title from the ruleId without code. */
  const ruleTitleText = (ruleId: string, d?: RuleInfo) => {
    if (d) return pick(d.title, d.titleEn);
    const code = ruleCode(ruleId);
    return ruleId.length > code.length ? ruleId.slice(code.length).trim() : "";
  };
  /** Catalog entry for a findings' ruleId (which may read "<id> <title>"). */
  const ruleDoc = (ruleId: string) => ruleDocs[ruleCode(ruleId)];
  /** Rule tooltip: heading, category, rationale and remediation in UI language. */
  const ruleTip = (d: RuleInfo) => (
    <Stack gap={3}>
      <Text size="xs" fw={700}>{d.id} · {pick(d.title, d.titleEn)}</Text>
      <Text size="xs" c="dimmed">{pick(d.category, d.categoryEn)}</Text>
      {d.rationale && <Text size="xs">{pick(d.rationale, d.rationaleEn)}</Text>}
      {d.guidance && (
        <Text size="xs" c="dimmed">
          {tr("Behebung", "Fix")}: {pick(d.guidance, d.guidanceEn)}
        </Text>
      )}
    </Stack>
  );

  /** Hit locations of the currently open object (red nodes in the diagram). */
  const browserHotLocations = useMemo(() => {
    if (!browserObjectId) return undefined;
    const bare = browserObjectId.includes(":") ? browserObjectId.split(":").slice(1).join(":") : browserObjectId;
    return allFindings
      .filter((f) => f.objectId === bare && f.location)
      .map((f) => f.location as string);
  }, [browserObjectId, allFindings]);

  const quickResolve = async (f: Finding) => {
    try {
      await api.setFindingStatus(f.id, f.status === "RESOLVED" ? "OPEN" : "RESOLVED");
      reloadFindings();
    } catch (e) {
      setNotice({ color: "red", text: e instanceof Error ? e.message : String(e) });
    }
  };

  const runGate = () => {
    setGateBusy(true);
    api.gate(id)
      .then(setGate)
      .catch(() => setGate(null))
      .finally(() => setGateBusy(false));
  };

  /** Derive gate reasons locally in the active language (backend string stays German);
   *  unknown backend reasons pass through so nothing gets lost. */
  const gateReasons = (g: GateDto): string[] => {
    if (g.passed) return [];
    const derived: string[] = [];
    if (g.maxCritical >= 0 && g.critical > g.maxCritical)
      derived.push(tr(`Critical-Befunde: ${g.critical} (erlaubt ${g.maxCritical})`, `Critical findings: ${g.critical} (allowed ${g.maxCritical})`));
    if (g.maxMajor >= 0 && g.major > g.maxMajor)
      derived.push(tr(`Major-Befunde: ${g.major} (erlaubt ${g.maxMajor})`, `Major findings: ${g.major} (allowed ${g.maxMajor})`));
    if (g.minHealth >= 0 && g.health < g.minHealth)
      derived.push(tr(`Health-Score: ${g.health} (gefordert ${g.minHealth})`, `Health score: ${g.health} (required ${g.minHealth})`));
    return derived.length ? derived : g.reasons;
  };

  const findingsCols: Column<Finding>[] = [
    { id: "score", label: "Score", width: 60, align: "right", render: (f) => (
      <Text size="xs" fw={f.score && f.score >= 100 ? 700 : undefined}>{f.score ?? "—"}</Text>) },
    { id: "sev", label: tr("Schwere", "Severity"), width: 120, render: (f) => (
      <Group gap={4} wrap="nowrap">
        <Badge color={SEV_COLOR[f.severity]}>{f.severity}</Badge>
        {f.needsReview && (
          <Badge variant="outline" color="gray" title={tr("Heuristischer Treffer — bitte selbst prüfen", "Heuristic match — please verify manually")}>
            {tr("prüfen", "review")}
          </Badge>
        )}
      </Group>) },
    { id: "status", label: "Status", width: 150, render: (f) => {
      const st = f.status ?? "OPEN";
      return (
        <Group gap={4} wrap="nowrap">
          <Tooltip label={f.statusComment
            ? tr(`Kommentar: ${f.statusComment}`, `Comment: ${f.statusComment}`)
            : tr("Status setzen (Kommentar möglich)", "Set status (comment optional)")}>
            <Badge
              color={STATUS_COLOR[st]}
              variant={st === "OPEN" ? "outline" : "light"}
              style={{ cursor: "pointer" }}
              onClick={() => setStatusTarget({ finding: f })}
            >
              {tr(STATUS_LABEL[st], st === "OPEN" ? "open" : st === "RESOLVED" ? "resolved" : "accepted")}
            </Badge>
          </Tooltip>
          <Tooltip label={st === "RESOLVED"
            ? tr("Zurück auf offen", "Reopen")
            : tr("Als erledigt markieren", "Mark as resolved")}>
            <ActionIcon
              size="sm"
              variant={st === "RESOLVED" ? "filled" : "light"}
              color="green"
              onClick={() => quickResolve(f)}
            >
              <IconCheck size={12} />
            </ActionIcon>
          </Tooltip>
        </Group>
      );
    } },
    {
      id: "rule", label: tr("Regel", "Rule"), width: 300, render: (f) => {
        const d = ruleDoc(f.ruleId);
        const code = ruleCode(f.ruleId);
        const shownTitle = ruleTitleText(f.ruleId, d);
        return (
          <Tooltip
            maw={420}
            multiline
            withArrow
            disabled={!d}
            label={d && ruleTip(d)}
          >
            <Text size="xs" component="span">
              <span style={{ fontWeight: 600 }}>{code}</span>
              {shownTitle ? ` · ${shownTitle}` : ""}
            </Text>
          </Tooltip>
        );
      }
    },
    { id: "object", label: tr("Objekt", "Object"), width: 260, render: (f) => (
      f.objectId ? (
        <Text
          size="xs"
          style={{ cursor: "pointer", textDecoration: "underline" }}
          title={tr("Modell-Details, Referenzen und Diagramm öffnen", "Open model details, references and diagram")}
          onClick={() => setBrowserObjectId(`${id}:${f.objectId}`)}
        >
          {f.objectName ?? f.objectId}
        </Text>
      ) : <Text size="xs">{f.objectName ?? "—"}</Text>) },
    { id: "msg", label: tr("Meldung", "Message"), render: (f) => (
      <>
        <Text size="xs">{pick(f.message, f.messageEn)}</Text>
        {(f.location || f.line != null) && (
          <Text size="xs" c="dimmed">
            {f.location}{f.line != null ? ` · ${tr("Zeile", "line")} ${f.line}` : ""}
          </Text>
        )}
      </>) },
  ];

  const objectCols: Column<TwxObject>[] = [
    { id: "type", label: tr("Typ", "Type"), width: 140, render: (o) => <Badge variant="light">{o.subType || o.typeName}</Badge> },
    { id: "name", label: tr("Name", "Name"), render: (o) => (
      <>
        <Group gap={6} wrap="nowrap">
          <Text size="sm">{o.name ?? <Text span fs="italic" c="dimmed">{tr("(ohne Namen)", "(unnamed)")}</Text>}</Text>
          {o.excluded && (
            <Badge size="xs" color="gray" variant="outline"
              title={tr("Vom Nutzer aus allen Regeln ausgeschlossen", "Excluded from all rules by the user")}>
              {tr("ausgeschlossen", "excluded")}
            </Badge>
          )}
        </Group>
        <Text size="xs" c="dimmed">{o.id}</Text>
      </>) },
    { id: "bytes", label: "Bytes", width: 90, align: "right", render: (o) => <>{o.hasContent ? o.contentBytes : "—"}</> },
    { id: "act", label: "", width: 90, render: (o) => (
      <Group gap={4} wrap="nowrap">
        {o.hasContent && (
          <ActionIcon variant="light" onClick={() => setOpenObjectId(o.id)} title={tr("Inhalt ansehen", "View content")}>
            <IconSearch size={14} />
          </ActionIcon>
        )}
        <ActionIcon variant="light" color="brand" onClick={() => setBrowserObjectId(o.id)} title={tr("Details / Referenzen / Diagramm", "Details / references / diagram")}>
          <IconLayoutDashboard size={14} />
        </ActionIcon>
      </Group>) },
  ];

  const toolkitCols: Column<Toolkit>[] = [
    { id: "tk", label: "Toolkit", render: (t) => (
      <>
        <Text size="sm">{t.declaredName ?? t.declaredAcronym ?? t.toolkitId}</Text>
        <Text size="xs" c="dimmed">{t.declaredAcronym}</Text>
      </>) },
    { id: "usage", label: tr("Nutzung", "Usage"), width: 170, render: (t) => (
      <Tooltip label={(t.usageEvidence ?? []).join(" · ") || tr("Kein Nachweis im Export", "No evidence in the export")}>
        <Badge color={USAGE_COLOR[t.usage ?? "unknown"] ?? "gray"} variant="light">
          {t.usage === "in use" ? tr("benutzt", "in use")
            : t.usage === "no usage detected" ? tr("keine Nutzung erkannt", "no usage detected")
            : !t.usage || t.usage === "unknown" ? tr("unbekannt", "unknown")
            : t.usage}
        </Badge>
      </Tooltip>) },
    { id: "declared", label: tr("Deklarierter Stand", "Declared version"), width: 150, render: (t) => (
      t.declared
        ? (t.declaredVersion ?? "—")
        : <Badge color="yellow" variant="light">{tr("nicht deklariert", "not declared")}</Badge>) },
    { id: "embedded", label: tr("Eingebettet", "Embedded"), width: 170, render: (t) => (
      <Group gap={4} wrap="nowrap">
        {t.embedded
          ? <Badge color="green">{tr("ja", "yes")} · {t.actualVersion} · {t.objectCount} {tr("Obj.", "obj.")}</Badge>
          : <Badge color="gray">{tr("nicht im TWX", "not in TWX")}</Badge>}
        {cycleNodes.has(t.toolkitId) && (
          <Badge color="red" variant="light" title={tr("Toolkit liegt in einem Abhängigkeitszyklus", "Toolkit is part of a dependency cycle")}>
            {tr("Zyklus", "Cycle")}
          </Badge>
        )}
      </Group>) },
    { id: "sha", label: "SHA-256", width: 130, render: (t) => (
      <>{t.sha256 && <Code>{t.sha256.slice(0, 12)}…</Code>}</>) },
  ];

  const taskCols: Column<TaskRow>[] = [
    { id: "object", label: tr("Objekt", "Object"), render: (t) => (
      <Text size="sm">{t.objectName ?? t.objectId ?? "—"}<Text span c="dimmed" size="xs"> {t.objectId ? `(${t.objectId})` : ""}</Text></Text>) },
    { id: "open", label: tr("offen", "open"), width: 70, align: "right", render: (t) => (
      <Badge color={t.open > 0 ? "red" : "gray"} variant="light">{t.open}</Badge>) },
    { id: "resolved", label: tr("erledigt", "resolved"), width: 80, align: "right", render: (t) => <Text size="xs">{t.resolved}</Text> },
    { id: "accepted", label: tr("akzeptiert", "accepted"), width: 80, align: "right", render: (t) => <Text size="xs">{t.accepted}</Text> },
    { id: "rules", label: tr("Regeln", "Rules"), width: 180, render: (t) => <Code style={{ fontSize: 11 }}>{t.rules}</Code> },
  ];

  if (!snapshot) {
    return <Text c="dimmed">{tr("Lädt…", "Loading…")}</Text>;
  }

  const bulkTarget: BulkTarget | null = statusTarget?.bulk ?? null;

  return (
    <Stack>
      <Group justify="space-between">
        <Group>
          <Button variant="subtle" leftSection={<IconArrowLeft size={16} />} onClick={onBack}>
            {tr("Zurück", "Back")}
          </Button>
          <Title order={3}>
            {snapshot.projectAcronym ?? snapshot.projectName} — {snapshot.snapshotName}
          </Title>
          {snapshot.isToolkit != null && (
            <Badge variant="light" color={snapshot.isToolkit ? "violet" : "gray"}>
              {snapshot.isToolkit ? "Toolkit" : tr("Prozess-App", "Process app")}
            </Badge>
          )}
          {snapshot.healthScore != null && (
            <Badge
              size="lg"
              variant="light"
              color={healthColor(snapshot.healthScore)}
              title={tr("Health-Score: 100 = unauffällig; bewertet Findings je Objekt (Score absteigend gewichtet)", "Health score: 100 = no issues; rates findings per object (score-weighted descending)")}
            >
              Health {snapshot.healthScore}/100
            </Badge>
          )}
          {onOpenRules && (
            <Button size="xs" variant="light" onClick={() => onOpenRules(id)}
              title={tr("Regeln/Schwellwerte für diesen Snapshot anpassen; wirksam bei nächster Analyse", "Adjust rules/thresholds for this snapshot; takes effect on next analysis")}>
              {tr("Regeleinstellungen", "Rule settings")}
            </Button>
          )}
          <Button size="xs" variant="light" leftSection={<IconDeviceFloppy size={14} />}
            loading={gateBusy} onClick={runGate} title={tr("Qualitäts-Gate prüfen (Grenzwerte der Instanz)", "Run quality gate (instance thresholds)")}>
            {tr("Gate prüfen", "Run gate")}
          </Button>
          <Tooltip label={tr("Snapshot neu analysieren (aus gespeicherter Datei, ohne Upload); übernimmt Exclude-Liste und ersetzt den alten Snapshot, sobald die neue Analyse fertig ist", "Re-analyze the snapshot (from the stored file, no upload); keeps the exclusion list and replaces the old snapshot once the new analysis is ready")}>
            <Button size="xs" variant="light" leftSection={<IconRefresh size={14} />}
              loading={reanalyzeBusy} onClick={runReanalyze}>
              {tr("Neu analysieren", "Re-analyze")}
            </Button>
          </Tooltip>
          <Tooltip label={tr("Befund-Export — akzeptierte Befunde standardmäßig hinausgerechnet; HTML öffnet den druckoptimierten Report (Browser: „Als PDF speichern“)", "Findings export — accepted findings excluded by default; HTML opens the print-optimized report (browser: “Save as PDF”)")}>
            <Group gap={4}>
              <Button component="a" href={api.exportUrl(id, "html")} size="xs" variant="default"
                target="_blank" leftSection={<IconFileExport size={14} />}>HTML</Button>
              <Button component="a" href={api.exportUrl(id, "csv")} size="xs" variant="default"
                leftSection={<IconFileExport size={14} />}>CSV</Button>
              <Button component="a" href={api.exportUrl(id, "json")} size="xs" variant="default"
                leftSection={<IconFileExport size={14} />}>JSON</Button>
            </Group>
          </Tooltip>
        </Group>
        <Text size="sm" c="dimmed">
          BAW {snapshot.buildVersion} · {tr("Export", "Export")} {snapshot.exportTime ?? "?"} · {(snapshot.sizeBytes / 1048576).toFixed(1)} MB
        </Text>
      </Group>

      {notice && (
        <Alert color={notice.color} withCloseButton onClose={() => setNotice(null)}>
          {notice.text}
        </Alert>
      )}

      {gate && (
        <Alert
          color={gate.passed ? "green" : "red"}
          title={gate.passed ? tr("Gate bestanden", "Gate passed") : tr("Gate nicht bestanden", "Gate failed")}
          withCloseButton
          onClose={() => setGate(null)}
        >
          <Stack gap={2}>
            <Text size="sm">
              Critical: {gate.critical} {tr(`(erlaubt ${gate.maxCritical})`, `(allowed ${gate.maxCritical})`)} · Major: {gate.major}
              {gate.maxMajor >= 0 ? tr(` (erlaubt ${gate.maxMajor})`, ` (allowed ${gate.maxMajor})`) : tr(" (keine Grenze)", " (no limit)")} · Health: {gate.health}
              {tr(` (gefordert ${gate.minHealth})`, ` (required ${gate.minHealth})`)} · {tr("akzeptierte ausgeklammert", "accepted excluded")}: {gate.accepted}
            </Text>
            {gateReasons(gate).map((r) => <Text key={r} size="xs">− {r}</Text>)}
            <Text size="xs" c="dimmed">
              CI: GET /api/snapshots/{id}/gate {tr("liefert HTTP 200/409 … exitCode", "returns HTTP 200/409 … exitCode")} {gate.exitCode};{" "}
              {tr("Grenzwerte per twxav2.gate.* oder Query-Parametern.", "Thresholds via twxav2.gate.* or query parameters.")}
            </Text>
          </Stack>
        </Alert>
      )}

      {snapshot.status === "ERROR" && (
        <Paper p="sm" withBorder style={{ borderColor: "var(--mantine-color-red-6)" }}>
          <Text c="red">{tr("Fehler bei der Analyse", "Analysis error")}: {backendError(snapshot.statusMessage)}</Text>
        </Paper>
      )}

      <Tabs value={tab} onChange={(v) => setTab(v ?? "findings")}>
        <Tabs.List>
          <Tabs.Tab value="findings">{tr("Findings", "Findings")} ({snapshot.findingCount})</Tabs.Tab>
          <Tabs.Tab value="tasks">{tr("Aufgaben", "Tasks")}</Tabs.Tab>
          <Tabs.Tab value="objects">{tr("Objekte", "Objects")} ({snapshot.objectCount})</Tabs.Tab>
          <Tabs.Tab value="excluded">{tr("Ausgeschlossen", "Excluded")}{excludedRows ? ` (${excludedRows.length})` : ""}</Tabs.Tab>
          <Tabs.Tab value="toolkits">Toolkits ({snapshot.toolkitCount})</Tabs.Tab>
          <Tabs.Tab value="variables">{tr("Variablen", "Variables")}</Tabs.Tab>
        </Tabs.List>

        {/* ---------------- Findings ---------------- */}
        <Tabs.Panel value="findings" pt="md">
          <Group mb="sm">
            {SEVERITIES.map((s) => {
              const active = sevFilter === s;
              return (
                <Badge
                  key={s}
                  color={SEV_COLOR[s]}
                  variant={active ? "filled" : "light"}
                  style={{
                    cursor: "pointer",
                    opacity: sevFilter && !active ? 0.45 : 1,
                    outline: active ? `2px solid var(--mantine-color-${SEV_COLOR[s]}-7)` : "none",
                    outlineOffset: 1
                  }}
                  onClick={() => setSevFilter(active ? null : s)}
                >
                  {active ? "✓ " : ""}{s}: {sevCounts[s]}
                </Badge>
              );
            })}
            {reviewOpen > 0 && (
              <Switch
                size="xs"
                label={tr(`nur „bitte prüfen“ (${reviewOpen})`, `“review” only (${reviewOpen})`)}
                checked={reviewOnly}
                onChange={(e) => setReviewOnly(e.currentTarget.checked)}
              />
            )}
            <Switch
              size="xs"
              label={tr(`erledigte/akzeptierte ausblenden (${doneCount})`, `hide resolved/accepted (${doneCount})`)}
              checked={hideDone}
              onChange={(e) => setHideDone(e.currentTarget.checked)}
            />
            <Button
              size="xs" variant="light" leftSection={<IconCheck size={14} />}
              disabled={findings.length === 0}
              onClick={() => setStatusTarget({
                bulk: {
                  snapshotId: id,
                  label: tr(`${findings.length} gefilterte Funde`, `${findings.length} filtered findings`)
                    + (sevFilter ? tr(` (Schwere ${sevFilter})`, ` (severity ${sevFilter})`) : "")
                    + (ruleFilter ? tr(` (Regel ${ruleFilter})`, ` (rule ${ruleFilter})`) : "")
                    + (reviewOnly ? tr(" (nur „bitte prüfen“)", " (review only)") : "")
                    + (objectFilter ? tr(` (Objekt ${objectFilter.name})`, ` (object ${objectFilter.name})`) : ""),
                  severity: sevFilter ?? undefined,
                  rule: ruleFilter ?? undefined,
                  objectId: objectFilter?.id,
                  needsReview: reviewOnly ? true : undefined,
                },
              })}
            >
              {tr("Gefilterte als…", "Set filtered to…")}
            </Button>
            <Text size="xs" c="dimmed">
              {findings.length} {tr("von", "of")} {allFindings.length} Findings
              {(sevFilter || ruleFilter || objectFilter) && (
                <Text component="span" inherit fw={700} style={{ cursor: "pointer" }}
                  onClick={() => { setSevFilter(null); setRuleFilter(null); setObjectFilter(null); }}>
                  {" "}{tr("[Filter zurücksetzen]", "[reset filters]")}
                </Text>
              )}
            </Text>
          </Group>
          {/* second filter row: one chip per finding type — plain rule title
              (the R-xxx id is deliberately not shown, it made the chips too
              long; id + full explanation on hover), ellipsized. */}
          <Group mb="sm" gap="xs">
            {ruleCounts.map(([rid, count]) => {
              const active = ruleFilter === rid;
              const zero = count === 0 && !active; // selected chip stays clickable (deselect)
              const doc = ruleDocs[rid];
              const shown = doc ? pick(doc.title, doc.titleEn) : ruleTitleText(rid) || rid;
              return (
                <Tooltip key={rid} maw={420} multiline withArrow disabled={!doc}
                  label={doc ? ruleTip(doc) : rid}>
                  <Badge
                    component="button"
                    type="button"
                    variant={active ? "filled" : "outline"}
                    color={active ? "brand" : "gray"}
                    style={{
                      cursor: zero ? "default" : "pointer", opacity: zero ? 0.35 : 1,
                      maxWidth: 380, display: "inline-flex", gap: 6,
                    }}
                    onClick={() => { if (!zero) setRuleFilter(active ? null : rid); }}
                  >
                    <span style={{ minWidth: 0, overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap" }}>
                      {shown}
                    </span>
                    <span style={{ flex: "0 0 auto" }}>({count})</span>
                  </Badge>
                </Tooltip>
              );
            })}
          </Group>
          {objectFilter && (
            <Group gap={6} mb="xs">
              <Badge color="brand" rightSection={<IconX size={12} style={{ cursor: "pointer" }}
                onClick={() => setObjectFilter(null)} />}>
                {tr("nur Objekt", "object only")}: {objectFilter.name}
              </Badge>
            </Group>
          )}
          <Text size="xs" c="dimmed" mb="sm">
            {tr("Objektnamen anklicken öffnet Modell-Details, Referenzen und Diagramm — der Name entspricht dem Objektnamen im Process-Designer-Baum; die Meldung nennt die konkrete Stelle (URL, Error-Code, Zeile). Status: ✓ = als erledigt markieren, Badge anklicken für Kommentar/Akzeptieren.", "Click object names to open model details, references and diagram — the name matches the object name in the Process Designer tree; the message names the exact spot (URL, error code, line). Status: ✓ = mark as resolved, click the badge for comment/accept.")}
          </Text>
          <ColumnTable
            storageKey="findings"
            columns={findingsCols}
            rows={findings}
            rowKey={(f) => String(f.id)}
            rowColor={(f) =>
              f.status === "RESOLVED" ? "var(--mantine-color-green-0)"
                : f.status === "ACCEPTED" ? "var(--mantine-color-gray-1)" : undefined}
            empty={<Text c="dimmed" ta="center" py="md">{tr("Keine Findings (für diesen Filter).", "No findings (for this filter).")}</Text>}
          />
        </Tabs.Panel>

        {/* ---------------- Tasks (B5.2) ---------------- */}
        <Tabs.Panel value="tasks" pt="md">
          <Text size="xs" c="dimmed" mb="sm">
            {tr("Arbeitsvorrat je Objekt: Befunde von Dokumentations-Regeln und alles mit „TODO“ im Text. Zeile anklicken → zeigt die Funde des Objekts im Findings-Tab.", "Work backlog per object: documentation-rule findings and anything containing “TODO” in the text. Click a row → shows the object's findings in the Findings tab.")}
          </Text>
          {tasks === null && <Group justify="center"><Loader size="sm" /></Group>}
          {tasks && (
            <ColumnTable
              storageKey="tasks"
              columns={taskCols}
              rows={tasks}
              rowKey={(t) => t.objectId ?? t.objectName ?? ""}
              onRowClick={(t) => {
                if (!t.objectId) return;
                setObjectFilter({ id: t.objectId, name: t.objectName ?? t.objectId });
                setTab("findings");
              }}
              empty={<Text c="dimmed" ta="center" py="md">{tr("Keine Aufgaben — nichts zu TODO.", "No tasks — nothing left to do.")}</Text>}
            />
          )}
        </Tabs.Panel>

        {/* ---------------- Objects ---------------- */}
        <Tabs.Panel value="objects" pt="md">
          <Group mb="sm">
            <TextInput
              placeholder={tr("Name oder ID suchen…", "Search name or ID…")}
              leftSection={<IconSearch size={14} />}
              value={objectQuery}
              onChange={(e) => { setObjectQuery(e.currentTarget.value); setPage(0); }}
              w={300}
            />
            <Select
              placeholder={tr("Typ filtern", "Filter by type")}
              data={types.map((t) => ({ value: `${t.prefix}#${t.subType ?? "-"}`, label: `${t.name} (${t.count})` }))}
              value={typeFilter}
              onChange={(v) => { setTypeFilter(v); setPage(0); }}
              clearable
              searchable
              w={260}
            />
          </Group>
          <ColumnTable
            storageKey="objects"
            columns={objectCols}
            rows={objects}
            rowKey={(o) => o.id}
            empty={<Text c="dimmed" ta="center" py="md">{tr("Keine Objekte gefunden.", "No objects found.")}</Text>}
          />
          <Group justify="center" mt="md">
            <Button size="xs" variant="default" disabled={page === 0} onClick={() => setPage(page - 1)}>
              ← {tr("Zurück", "Back")}
            </Button>
            <Text size="sm">{tr("Seite", "Page")} {page + 1}</Text>
            <Button size="xs" variant="default" disabled={objects.length < 100} onClick={() => setPage(page + 1)}>
              {tr("Weiter", "Next")} →
            </Button>
          </Group>
        </Tabs.Panel>

        {/* ---------------- Excluded (manual + automatic) ---------------- */}
        <Tabs.Panel value="excluded" pt="md">
          <Text size="xs" c="dimmed" mb="sm" maw={760}>
            {tr(
              "Objekte, die nicht geprüft werden: manuell ausgeschlossen oder automatisch über Namensmuster (z. B. _deprecated) bzw. Prozess-Center-Tags. Die Auto-Listen pflegst du unter Regeleinstellungen (global oder je App); Tags vergibt der Process Center im Objekt — Toolkit-Objekte haben nie Tags, dort wirkt nur das Namensmuster. Befunde dieser Objekte entfallen bei jeder Analyse; das Objekt selbst bleibt im Modell und in der Suche sichtbar.",
              "Objects that are not checked: excluded manually or automatically via name patterns (e.g. _deprecated) or Process Center tags. Edit the auto lists in the rule settings (global or per app); tags are set on the object in the Process Center — toolkit objects never have tags, so only the name pattern applies there. Findings for these objects drop in every analysis; the object itself remains visible in the model and search."
            )}
          </Text>
          {excludedRows === null ? (
            <Loader size="sm" />
          ) : excludedRows.length === 0 ? (
            <Text c="dimmed" ta="center" py="md">{tr("Keine Objekte ausgeschlossen.", "No excluded objects.")}</Text>
          ) : (
            <Table.ScrollContainer minWidth={640} type="native">
            <Table striped highlightOnHover fs="sm">
              <Table.Thead>
                <Table.Tr>
                  <Table.Th>{tr("Objekt", "Object")}</Table.Th>
                  <Table.Th>{tr("Typ", "Type")}</Table.Th>
                  <Table.Th>{tr("Quelle", "Source")}</Table.Th>
                  <Table.Th>{tr("Grund", "Reason")}</Table.Th>
                  <Table.Th />
                </Table.Tr>
              </Table.Thead>
              <Table.Tbody>
                {excludedRows.map((x) => (
                  <Table.Tr key={x.twxId}>
                    <Table.Td>
                      {x.typeName ? (
                        <Text
                          component="button"
                          style={{ background: "none", border: "none", cursor: "pointer", padding: 0, color: "inherit", textDecoration: "underline" }}
                          onClick={() => setOpenObjectId(`${id}:${x.twxId}`)}
                        >
                          {x.name || x.twxId}
                        </Text>
                      ) : (
                        x.name || x.twxId
                      )}
                    </Table.Td>
                    <Table.Td>{x.typeName ?? tr("Toolkit-Objekt", "Toolkit object")}</Table.Td>
                    <Table.Td>
                      {x.source === "AUTO_NAME" ? (
                        <Badge color="violet" variant="light">{tr("Namensmuster", "Name pattern")}</Badge>
                      ) : x.source === "AUTO_TAG" ? (
                        <Badge color="blue" variant="light">{tr("Tag", "Tag")}</Badge>
                      ) : (
                        <Badge color="orange" variant="light">{tr("Manuell", "Manual")}</Badge>
                      )}
                    </Table.Td>
                    <Table.Td><Code>{x.reason ?? "—"}</Code></Table.Td>
                    <Table.Td ta="right">
                      {x.source === "MANUAL" && (
                        <Tooltip label={tr("Ausschluss aufheben (Befunde erscheinen nach Re-analyse)", "Remove exclusion (findings reappear after re-analysis)")}>
                          <ActionIcon
                            color="gray" variant="subtle" loading={unexcludeBusy === x.twxId}
                            onClick={() => restoreExcluded(x)}
                          >
                            <IconRestore size={15} />
                          </ActionIcon>
                        </Tooltip>
                      )}
                    </Table.Td>
                  </Table.Tr>
                ))}
              </Table.Tbody>
            </Table>
            </Table.ScrollContainer>
          )}
        </Tabs.Panel>

        {/* ---------------- Toolkits ---------------- */}
        <Tabs.Panel value="toolkits" pt="md">
          <Group justify="space-between" align="flex-end" mb="sm">
            <Text size="xs" c="dimmed" maw={640}>
              {tr("ZIP-Inhalte werden bewusst nicht gespeichert (nur Metadaten: SHA-256, Objektanzahl, Version aus dem Toolkit-eigenen package.xml). „nicht deklariert“ = nicht von der App selbst, sondern innerhalb eines importierten Toolkits deklariert — deshalb trotzdem im Einsatz.", "ZIP contents are deliberately not stored (metadata only: SHA-256, object count, version from the toolkit's own package.xml). “not declared” = declared inside an imported toolkit rather than by the app itself — still in use.")}
              <Text span inherit fw={700}> {tr("Nutzung:", "Usage:")}</Text>{" "}
              {tr("„benutzt“ = die App deklariert es in ihrer package.xml oder ein importiertes Toolkit bindet es ein; „keine Nutzung erkannt“ = eingebettet, aber ohne jeden Verwendungsbeleg im Export.", "“in use” = the app declares it in its package.xml or an imported toolkit embeds it; “no use detected” = embedded, but without any usage evidence in the export.")}
            </Text>
            {undeclaredCount > 0 && (
              <Switch
                size="xs"
                label={tr(undeclaredCount === 1 ? `${undeclaredCount} Toolkit innerhalb importierter Toolkits anzeigen` : `${undeclaredCount} Toolkits innerhalb importierter Toolkits anzeigen`, undeclaredCount === 1 ? `show ${undeclaredCount} toolkit inside imported toolkits` : `show ${undeclaredCount} toolkits inside imported toolkits`)}
                checked={showUndeclared}
                onChange={(e) => setShowUndeclared(e.currentTarget.checked)}
              />
            )}
          </Group>
          <ColumnTable
            storageKey="toolkits"
            columns={toolkitCols}
            rows={shownToolkits}
            rowKey={(t) => `${t.toolkitId}/${t.sha256 ?? ""}`}
            empty={<Text c="dimmed" ta="center" py="md">{tr("Keine deklarierten Toolkit-Abhängigkeiten.", "No declared toolkit dependencies.")}</Text>}
          />

          {/* Cycles (B4.1) */}
          {graph && (graph.cycleNodes?.length ?? 0) > 0 && (
            <Alert
              icon={<IconAlertTriangle size={16} />}
              color="red"
              title={tr(`Abhängigkeitszyklus: ${graph.cycleNodes!.map((c) => nodeLabel(c)).join(" ↔ ")}`, `Dependency cycle: ${graph.cycleNodes!.map((c) => nodeLabel(c)).join(" ↔ ")}`)}
              mt="md"
            >
              <Text size="sm">
                {tr("Diese Toolkits binden sich (mittelbar) selbst ein — Updates sind schwer durchschaubar.", "These toolkits embed themselves (indirectly) — updates are hard to oversee.")}
              </Text>
            </Alert>
          )}

          {/* Nested toolkit dependencies + version columns */}
          {graph && graph.conflicts.length > 0 && (
            <Alert
              icon={<IconAlertTriangle size={16} />}
              color="red"
              title={tr(`${graph.conflicts.length} Toolkit${graph.conflicts.length === 1 ? "" : "s"} mit Versionskonflikt in dieser App`, `${graph.conflicts.length} toolkit${graph.conflicts.length === 1 ? "" : "s"} with a version conflict in this app`)}
              mt="md"
            >
              <Stack gap={6}>
                {graph.conflicts.map((c) => (
                  <div key={c.acronym}>
                    <Text size="sm" fw={600}>
                      {c.acronym}
                      {c.sameContent && (
                        <Text component="span" size="xs" c="dimmed">
                          {" "}{tr("(Varianten inhaltlich gleich — nur Versionsname weicht ab)", "(variants identical in content — only the version name differs)")}
                        </Text>
                      )}
                    </Text>
                    {c.uses.map((u) => (
                      <Group key={u.toolkitId} gap={6} wrap="nowrap">
                        <Text size="xs" c="dimmed" w={200}>
                          {tr("genutzt von", "used by")}: {u.usedBy.join(", ") || "?"}
                        </Text>
                        <Code style={{ fontSize: 11 }}>{u.version ?? "?"}</Code>
                        {!u.known && <Badge size="xs" color="gray" variant="light">{tr("ZIP fehlt", "ZIP missing")}</Badge>}
                      </Group>
                    ))}
                  </div>
                ))}
              </Stack>
            </Alert>
          )}
          {graph && graph.edges.some((e) => e.parentId !== "") && (
            <>
              <Title order={5} mt="md">
                {tr("Verschachtelte Toolkit-Abhängigkeiten", "Nested toolkit dependencies")}
              </Title>
              <Text size="xs" c="dimmed" maw={640}>
                {tr("Was die deklarierten Toolkits laut eigenem package.xml selbst einbinden (rekursiv). „Dekariert“ = vom Eltern-Toolkit geforderte Version, „im TWX“ = tatsächlich mitgeliefert — Abweichungen sind potenziell problematisch.", "What the declared toolkits embed themselves according to their own package.xml (recursive). “Declared” = version required by the parent toolkit, “in TWX” = actually shipped — deviations can be problematic.")}
              </Text>
              <Table.ScrollContainer minWidth={560} type="native">
              <Table striped withTableBorder fz="sm" mt="xs">
                <Table.Thead>
                  <Table.Tr>
                    <Table.Th>{tr("Eltern-Toolkit", "Parent toolkit")}</Table.Th>
                    <Table.Th>{tr("Enthält", "Contains")}</Table.Th>
                    <Table.Th>{tr("Dekariert", "Declared")}</Table.Th>
                    <Table.Th>{tr("Im TWX / Inventar", "In TWX / inventory")}</Table.Th>
                  </Table.Tr>
                </Table.Thead>
                <Table.Tbody>
                  {graph.nodes.length > 0
                    ? graph.edges
                        .filter((e) => e.parentId !== "")
                        .sort((a, b) => {
                          const la = nodeLabel(a.parentId), lb = nodeLabel(b.parentId);
                          return la.localeCompare(lb) || nodeLabel(a.childId).localeCompare(nodeLabel(b.childId));
                        })
                        .map((e, i) => {
                          const child = graph.nodes.find((n) => n.toolkitId === e.childId);
                          const mismatch = child?.known && e.version != null && child.version != null
                            && e.version !== child.version;
                          return (
                            <Table.Tr key={`${e.parentId}>${e.childId}#${i}`}>
                              <Table.Td>
                                <Text size="sm">
                                  {nodeLabel(e.parentId)}
                                  {cycleNodes.has(e.parentId) && (
                                    <Badge size="xs" color="red" variant="light" ml={4}>{tr("Zyklus", "Cycle")}</Badge>
                                  )}
                                </Text>
                              </Table.Td>
                              <Table.Td>
                                <Text size="sm">
                                  {nodeLabel(e.childId)}
                                  {cycleNodes.has(e.childId) && (
                                    <Badge size="xs" color="red" variant="light" ml={4}>{tr("Zyklus", "Cycle")}</Badge>
                                  )}
                                </Text>
                              </Table.Td>
                              <Table.Td><Code style={{ fontSize: 12 }}>{e.version ?? "?"}</Code></Table.Td>
                              <Table.Td>
                                {!child?.known ? (
                                  <Badge color="gray" variant="light">{tr("ZIP nie eingebettet", "ZIP never embedded")}</Badge>
                                ) : mismatch ? (
                                  <Group gap={6} wrap="nowrap">
                                    <Code style={{ fontSize: 12 }}>{child.version}</Code>
                                    <Badge color="red" variant="light">{tr("andere Version", "other version")}</Badge>
                                  </Group>
                                ) : (
                                  <Badge color="green" variant="light">{child.version}</Badge>
                                )}
                              </Table.Td>
                            </Table.Tr>
                          );
                        })
                    : null}
                </Table.Tbody>
              </Table>
              </Table.ScrollContainer>
            </>
          )}
        </Tabs.Panel>

        {/* ---------------- Variables ---------------- */}
        <Tabs.Panel value="variables" pt="md">
          <VariablesPanel snapshotId={id} />
        </Tabs.Panel>
      </Tabs>

      <ObjectContentModal objectId={openObjectId} onClose={() => setOpenObjectId(null)} />
      <ObjectBrowserModal
        objectId={browserObjectId}
        onClose={() => setBrowserObjectId(null)}
        hotLocations={browserHotLocations}
        onChanged={() => { reloadObjects(); reloadFindings(); reloadSnapshot(); }}
      />
      <FindingStatusModal
        finding={statusTarget?.finding ?? null}
        bulk={bulkTarget}
        onClose={() => setStatusTarget(null)}
        onSaved={(changed) => {
          setStatusTarget(null);
          reloadFindings();
          setTasks(null);
          setNotice({ color: "green", text: tr(`${changed} Befund${changed === 1 ? "" : "e"} aktualisiert.`, `${changed} finding${changed === 1 ? "" : "s"} updated.`) });
        }}
      />
    </Stack>
  );
}
