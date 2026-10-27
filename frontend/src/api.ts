import { backendError, loadLang } from "./i18n";

/** Short label outside React components (language from localStorage). */
const msg2 = (de: string, en: string) => (loadLang() === "en" ? en : de);

export interface Snapshot {
  id: number;
  filename: string;
  sizeBytes: number;
  projectName?: string;
  projectAcronym?: string;
  branchName?: string;
  snapshotName?: string;
  snapshotDescription?: string;
  exportTime?: string;
  buildVersion?: string;
  isToolkit?: boolean | null;
  objectCount: number;
  toolkitCount: number;
  findingCount: number;
  /** Health score 0..100 (from finding scores and object count), null for legacy data. */
  healthScore?: number | null;
  status: string;
  statusMessage?: string;
  uploadedAt: string;
}

export type Severity = "CRITICAL" | "MAJOR" | "MINOR" | "INFO";

export const SEVERITIES: Severity[] = ["CRITICAL", "MAJOR", "MINOR", "INFO"];

export const SEV_COLOR: Record<Severity, string> = {
  CRITICAL: "red", MAJOR: "orange", MINOR: "yellow", INFO: "gray",
};

/** Health score color: >= 80 green, >= 60 yellow, otherwise red. */
export function healthColor(h: number): string {
  return h >= 80 ? "green" : h >= 60 ? "yellow" : "red";
}

export interface Finding {
  id: number;
  ruleId: string;
  severity: Severity;
  category?: string;
  score?: number | null;
  needsReview: boolean;
  objectId?: string;
  objectName?: string;
  location?: string;
  line?: number | null;
  snippet?: string;
  message: string;
  /** English message (i18n); missing in older findings. */
  messageEn?: string | null;
  /** Team status (B5): OPEN | RESOLVED | ACCEPTED. */
  status?: "OPEN" | "RESOLVED" | "ACCEPTED";
  statusComment?: string;
  statusAt?: string;
}

export const FINDING_STATUSES = ["OPEN", "RESOLVED", "ACCEPTED"] as const;
export type FindingStatus = (typeof FINDING_STATUSES)[number];

export const STATUS_LABEL: Record<FindingStatus, string> = {
  OPEN: "offen", RESOLVED: "gelöst", ACCEPTED: "akzeptiert",
};
export const STATUS_COLOR: Record<FindingStatus, string> = {
  OPEN: "red", RESOLVED: "green", ACCEPTED: "gray",
};

export interface TwxObject {
  id: string;
  prefix: string;
  typeName: string;
  /** Display subtype (service, cshs, subBpd, ...), if present. */
  subType?: string | null;
  name?: string;
  description?: string;
  contentBytes: number;
  hasContent: boolean;
  /** Excluded from checks (exclude function) — never appears in findings. */
  excluded?: boolean;
}

/** One object exclusion from GET/PUT /api/snapshots/{id}/exclusions. */
export interface Exclusion {
  twxId: string;
  name?: string;
  typeName?: string;
  /** MANUAL | AUTO_NAME | AUTO_TAG */
  source: string;
  /** for AUTO_NAME: the matching name pattern; for AUTO_TAG: the tag */
  reason?: string;
}

export interface ObjectContent {
  id: string;
  name?: string;
  typeName: string;
  subType?: string | null;
  content?: string;
}

/** A script fragment from /api/objects/{id}/scripts (line number = start within the object). */
export interface ScriptFragment {
  description: string;
  js: string;
  line: number;
}

export interface Toolkit {
  toolkitId: string;
  declaredName?: string;
  declaredAcronym?: string;
  declaredVersion?: string;
  actualVersion?: string;
  sha256?: string;
  objectCount?: number;
  embedded: boolean;
  declared: boolean;
  /** Usage status (B4.2): "in use" | "no usage detected" | "unknown". */
  usage?: string;
  usageEvidence?: string[];
}

/** Variable catalog (aggregate per snapshot+scope+variable). */
export interface VariableRow {
  snapshotId: string;
  snapshotLabel: string;
  scope: string;
  variable: string;
  assigns: number;
  reads: number;
  objects: number;
}

export interface VariableUsageRow {
  objectId: string;
  objectName?: string;
  prefix: string;
  scope: string;
  line: number;
  assign: boolean;
  lineText: string;
}

export interface TypeCount {
  prefix: string;
  /** set when the prefix group is split by subtype (e.g. service/cshs). */
  subType?: string | null;
  name: string;
  count: number;
}

/** B10: one segment in the rule-hit bar of the snapshot list. */
export interface RuleBar {
  rule: string;
  severity: string;
  count: number;
}

export interface SearchMatch {
  /** Hit scope: name | docs | scripts | raw. */
  scope: string;
  /** Location (e.g. "script block 3" / "line 12 of XML"). */
  where?: string;
  line: number;
  snippet: string;
}

export interface SearchHit {
  objectId: string;
  snapshotId: number;
  snapshotLabel: string;
  typeName: string;
  name?: string;
  isToolkit?: boolean | null;
  matches: SearchMatch[];
}

export interface SearchResponse {
  hits: SearchHit[];
  truncated: boolean;
  elapsedMs: number;
  error?: string | null;
}

/** Nested toolkit dependency (from the package.xml of the toolkit ZIP). */
export interface ToolkitNested {
  childToolkitId: string;
  childAcronym?: string;
  childName?: string;
  childVersion?: string;
  /** Version per inventory (only set when the child ZIP is known). */
  childVersionActual?: string;
  childSha256?: string;
  known: boolean;
  /** Highest version of the child abbreviation known in the global inventory. */
  childVersionLatest?: string;
  /** Embedded (known) or declared (unknown) version < newest. */
  childOutdated?: boolean;
  /** Child lies on a dependency cycle (B4.1). */
  childInCycle?: boolean;
}

/** Global toolkit inventory entry (dedup store). */
export interface ToolkitInventory {
  id: string;
  projectAcronym?: string;
  projectName?: string;
  version?: string;
  sha256?: string;
  objectCount: number;
  usedBy: number;
  firstSeen: string;
  nested?: ToolkitNested[];
  /** Node lies on a dependency cycle (B4.1). */
  inCycle?: boolean;
}

// ---------- Toolkit graph (app perspective) ----------

export interface GraphNode {
  toolkitId: string;
  acronym?: string;
  name?: string;
  version?: string;
  sha256?: string;
  objectCount?: number | null;
  depth: number;
  declaredVersion?: string;
  known: boolean;
}

/** Edge parentId -> childId; parentId "" = app (manifest). */
export interface GraphEdge {
  parentId: string;
  childId: string;
  version?: string;
}

export interface GraphUse {
  toolkitId: string;
  version?: string;
  sha256?: string;
  known: boolean;
  usedBy: string[];
}

/** Same abbreviation, different variants in the same graph. */
export interface GraphConflict {
  acronym: string;
  name?: string;
  sameContent: boolean;
  uses: GraphUse[];
}

export interface ToolkitGraph {
  nodes: GraphNode[];
  edges: GraphEdge[];
  conflicts: GraphConflict[];
  /** Toolkit IDs on a cycle (B4.1). */
  cycleNodes?: string[];
}

/** Toolkit version conflicts per app (toolkits menu). */
export interface AppToolkitConflicts {
  snapshotId: number;
  app: string;
  version?: string;
  conflicts: GraphConflict[];
}

/** Type separation: process apps vs. toolkits (legacy without type counts as app). */
export type SnapshotType = "app" | "toolkit";

// ---------- Quality gate, tasks, dashboard (B5/B9) ----------

export interface GateDto {
  snapshotId: number;
  passed: boolean;
  exitCode: number;
  critical: number;
  major: number;
  health: number;
  minHealth: number;
  maxCritical: number;
  maxMajor: number;
  accepted: number;
  reasons: string[];
}

export interface TaskRow {
  objectId: string;
  objectName?: string;
  open: number;
  resolved: number;
  accepted: number;
  total: number;
  rules: string;
}

export interface DashboardPoint {
  snapshotId: number;
  snapshotName: string;
  health: number | null;
  uploadedAt: string;
}

export interface DashboardProject {
  key: string;
  acronym?: string;
  name?: string;
  trend: DashboardPoint[];
  latestSnapshotId: number | null;
  latestSnapshotName?: string;
  latestHealth: number | null;
  openCritical: number;
  lastImport: string;
  toolkit: boolean;
  // size metrics of the latest analysis
  serviceCount: number;
  bpdCount: number;
  scriptLines: number;
}

// ---------- Version comparison (B6) ----------

export interface ObjectDiff {
  id: string;
  name?: string;
  typeName: string;
  prefix: string;
}

export interface RuleDelta {
  rule: string;
  baseCount: number;
  targetCount: number;
  baseScore: number;
  targetScore: number;
}

export interface CompareDto {
  base: Snapshot;
  target: Snapshot;
  addedObjects: ObjectDiff[];
  removedObjects: ObjectDiff[];
  changedObjects: ObjectDiff[];
  rules: RuleDelta[];
  baseFindings: number;
  targetFindings: number;
  baseScore: number;
  targetScore: number;
  baseHealth: number;
  targetHealth: number;
}

// ---------- Object detail and diagram (B7/B8) ----------

export interface DetailSection {
  title: string;
  columns: string[];
  rows: string[][];
}

export interface RefRow {
  objectId: string;
  objectName?: string;
  typeName: string;
}

export interface ObjectDetail {
  id: string;
  prefix: string;
  typeName: string;
  subType?: string | null;
  name?: string;
  description?: string;
  snapshotId: number;
  snapshotAcronym?: string;
  snapshotVersion?: string;
  contentBytes: number;
  hasContent: boolean;
  sha256?: string;
  sections: DetailSection[];
  outRefs: RefRow[];
  inRefs: RefRow[];
  outRefCount: number;
  inRefCount: number;
}

export interface DiagramNode {
  id: string;
  kind: string;
  name?: string;
  x: number;
  y: number;
  width: number;
  height: number;
  color?: string;
}

export interface DiagramEdge {
  id: string;
  name?: string;
  source: string;
  target: string;
  sourcePort?: string;
  targetPort?: string;
  condition?: string;
}

/** Swim lane (B8 follow-up): band geometry from the bpmn2Data section. */
export interface DiagramLane {
  id?: string;
  name?: string;
  x: number;
  y: number;
  width: number;
  height: number;
  color?: string;
  systemLane: boolean;
  nodeIds: string[];
}

export interface DiagramDto {
  objectId: string;
  name?: string;
  minX: number;
  minY: number;
  width: number;
  height: number;
  nodes: DiagramNode[];
  edges: DiagramEdge[];
  lanes: DiagramLane[];
}

// ---------- XML detail diff (B6 follow-up) ----------

/** kind: 0=context, 1=deleted (base only), 2=new (target only), 3=omission. */
export interface ContentDiffLine {
  kind: number;
  baseLine?: number;
  targetLine?: number;
  text?: string;
}

export interface ContentDiff {
  baseObjectId?: string;
  targetObjectId?: string;
  baseName?: string;
  targetName?: string;
  baseBytes: number;
  targetBytes: number;
  additions: number;
  deletions: number;
  truncated: boolean;
  note?: string;
  lines: ContentDiffLine[];
}

// ---------- Rule catalog and rule settings ----------

export interface RuleInfo {
  id: string;
  title: string;
  category: string;
  /** Effective severity (default or override). */
  severity: Severity;
  impact: number;
  enabled: boolean;
  customized: boolean;
  rationale: string;
  guidance: string;
  /** English catalog texts (i18n); null = UI shows German. */
  titleEn?: string | null;
  categoryEn?: string | null;
  rationaleEn?: string | null;
  guidanceEn?: string | null;
}

export interface RuleOverride {
  enabled?: boolean;
  severity?: Severity;
  impact?: number;
}

export interface RuleSettingsJson {
  overrides?: Record<string, RuleOverride>;
  thresholds?: Record<string, number | boolean | string>;
  /** Auto-exclusion: name substrings (project and toolkit objects) */
  excludeNamePatterns?: string[];
  /** Auto-exclusion: Process Center tags (project objects only) */
  excludeTags?: string[];
}

export interface BpmSettings {
  baseUrl?: string;
  username?: string;
  passwordSet: boolean;
  caFile?: string;
}

export interface BpmContainer {
  acronym: string;
  name: string;
  toolkit: boolean;
}

export interface BpmSnapshot {
  version: string;
  versionName: string;
  creationDate: string;
  description: string;
  branchName: string;
  active: boolean;
  tip: boolean;
  status: string;
}

async function getJson<T>(url: string): Promise<T> {
  const r = await fetch(url);
  if (!r.ok) {
    // Prefer backend error ({error}), otherwise raw text — translated to EN.
    const raw = await r.text().catch(() => "");
    const err = (() => { try { return JSON.parse(raw)?.error; } catch { return null; } })();
    throw new Error(backendError(err) ?? `${r.status} ${r.statusText}${raw ? `: ${raw.slice(0, 200)}` : ""}`);
  }
  return r.json() as Promise<T>;
}

/** One entry of the server-side ingest queue (upload or BPM import). */
export interface IngestStatus {
  taskId: number;
  active: boolean;
  phase?: "QUEUED" | "UPLOAD" | "EXPORT" | "PARSE" | "ANALYZE" | "DONE" | "ERROR";
  label?: string;
  startedAt: number;
  snapshotId?: number;
  error?: string;
  /** 1 = next in line; 0 = not waiting. */
  queuePosition: number;
}

/** Upload response in fetch Response style (XHR-based, with progress). */
export interface UploadResponse {
  ok: boolean;
  status: number;
  json: () => Promise<any>;
}

// Progress browser -> server (only the uploading page sees it via XHR,
// the rest via the server status). Module level so the global banner
// can show it even after component changes and on other pages.
export interface ClientUpload {
  label: string;
  loaded: number;
  total: number;
  done: boolean;
}
let clientUpload: ClientUpload | null = null;
export const getClientUpload = (): ClientUpload | null => clientUpload;

export const api = {
  snapshots: (type?: SnapshotType) =>
    getJson<Snapshot[]>(`/api/snapshots${type ? `?type=${type}` : ""}`),
  snapshot: (id: number) => getJson<Snapshot>(`/api/snapshots/${id}`),
  deleteSnapshot: async (id: number) => {
    const r = await fetch(`/api/snapshots/${id}`, { method: "DELETE" });
    if (!r.ok) {
      const body = await r.json().catch(() => null);
      throw new Error(backendError(body?.error) ?? `${msg2("Löschen fehlgeschlagen", "Delete failed")}: HTTP ${r.status}`);
    }
  },
  upload: (file: File, onProgress?: (loaded: number, total: number) => void) =>
    new Promise<UploadResponse>((resolve, reject) => {
      const fd = new FormData();
      fd.append("file", file);
      clientUpload = { label: file.name, loaded: 0, total: file.size, done: false };
      const xhr = new XMLHttpRequest();
      xhr.open("POST", "/api/snapshots");
      xhr.upload.onprogress = (e) => {
        if (e.lengthComputable && clientUpload) {
          clientUpload = { ...clientUpload, loaded: e.loaded, total: e.total };
          onProgress?.(e.loaded, e.total);
        }
      };
      xhr.onload = () => {
        if (clientUpload) clientUpload = { ...clientUpload, done: true };
        resolve({
          ok: xhr.status >= 200 && xhr.status < 300,
          status: xhr.status,
          json: () => Promise.resolve(JSON.parse(xhr.responseText || "{}")),
        });
      };
      xhr.onerror = () => {
        if (clientUpload) clientUpload = { ...clientUpload, done: true };
        reject(new Error(msg2("Netzwerkfehler beim Hochladen", "Network error while uploading")));
      };
      xhr.send(fd);
    }),
  ingestStatus: () => getJson<IngestStatus[]>("/api/ingest/status"),
  findings: (id: number, severity?: string, rule?: string, reviewOnly = false) => {
    const p = new URLSearchParams();
    if (severity) p.set("severity", severity);
    if (rule) p.set("rule", rule);
    if (reviewOnly) p.set("reviewOnly", "true");
    return getJson<Finding[]>(`/api/snapshots/${id}/findings?${p}`);
  },
  setFindingStatus: async (findingId: number, status: FindingStatus, comment?: string) => {
    const r = await fetch(`/api/findings/${findingId}/status`, {
      method: "PUT",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ status, comment }),
    });
    if (!r.ok) {
      const body = await r.json().catch(() => null);
      throw new Error(backendError(body?.error) ?? `${msg2("Statusänderung fehlgeschlagen", "Status change failed")}: HTTP ${r.status}`);
    }
    return r.json() as Promise<Finding>;
  },
  setBulkStatus: async (
    snapshotId: number,
    status: FindingStatus,
    filters: { severity?: string; rule?: string; objectId?: string; needsReview?: boolean; comment?: string }
  ) => {
    const r = await fetch(`/api/snapshots/${snapshotId}/findings/status`, {
      method: "PUT",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ status, ...filters }),
    });
    if (!r.ok) {
      const body = await r.json().catch(() => null);
      throw new Error(backendError(body?.error) ?? `${msg2("Massenänderung fehlgeschlagen", "Bulk change failed")}: HTTP ${r.status}`);
    }
    return (await r.json()) as { changed: number };
  },
  gate: (id: number) => getJson<GateDto>(`/api/snapshots/${id}/gate`),
  tasks: (id: number) => getJson<TaskRow[]>(`/api/snapshots/${id}/tasks`),
  dashboard: () => getJson<DashboardProject[]>("/api/dashboard"),
  compare: (base: number, target: number) =>
    getJson<CompareDto>(`/api/compare?base=${base}&target=${target}`),
  /** XML detail diff of an object (B6 follow-up); objectId = DB ID of one side. */
  compareObject: (base: number, target: number, objectId: string) =>
    getJson<ContentDiff>(`/api/compare/object?base=${base}&target=${target}&objectId=${encodeURIComponent(objectId)}`),
  objectDetail: (id: string) =>
    getJson<ObjectDetail>(`/api/objects/${encodeURIComponent(id)}/detail`),
  objectDiagram: (id: string) =>
    getJson<DiagramDto>(`/api/objects/${encodeURIComponent(id)}/diagram`),
  objects: (id: number, q?: string, prefix?: string, page = 0, size = 100, subType?: string) => {
    const p = new URLSearchParams({ page: String(page), size: String(size) });
    if (q) p.set("q", q);
    if (prefix) p.set("prefix", prefix);
    if (subType) p.set("subType", subType);
    return getJson<TwxObject[]>(`/api/snapshots/${id}/objects?${p}`);
  },
  // ---------------- Exclude function ----------------
  exclusions: (id: number) => getJson<Exclusion[]>(`/api/snapshots/${id}/exclusions`),
  /** Exclude object from all rules; existing findings are removed immediately. */
  excludeObject: async (id: number, objectId: string) => {
    const r = await fetch(`/api/snapshots/${id}/exclusions`, {
      method: "PUT",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ objectId }),
    });
    if (!r.ok) {
      const body = await r.json().catch(() => null);
      throw new Error(backendError(body?.error) ?? `${msg2("Ausschließen fehlgeschlagen", "Exclude failed")}: HTTP ${r.status}`);
    }
    return (await r.json()) as Exclusion;
  },
  /** Lift exclusion; findings reappear only after a re-analysis. */
  unexcludeObject: async (id: number, twxId: string) => {
    const r = await fetch(`/api/snapshots/${id}/exclusions/${encodeURIComponent(twxId)}`, {
      method: "DELETE",
    });
    if (!r.ok) {
      const body = await r.json().catch(() => null);
      throw new Error(backendError(body?.error) ?? `${msg2("Wieder aufnehmen fehlgeschlagen", "Re-include failed")}: HTTP ${r.status}`);
    }
  },
  /** Re-analysis from stored file (without a new upload). */
  reanalyze: async (id: number) => {
    const r = await fetch(`/api/snapshots/${id}/reanalyze`, { method: "POST" });
    if (!r.ok) {
      const body = await r.json().catch(() => null);
      throw new Error(backendError(body?.error) ?? `${msg2("Re-analyse fehlgeschlagen", "Re-analysis failed")}: HTTP ${r.status}`);
    }
    return (await r.json()) as { taskId: number; replacedSnapshotId: number | null };
  },
  objectContent: (id: string) =>
    getJson<ObjectContent>(`/api/objects/${encodeURIComponent(id)}`),
  /** Script fragments (script blocks, BPMN-JSON fields, JS files); entities unpacked. */
  objectScripts: (id: string) =>
    getJson<ScriptFragment[]>(`/api/objects/${encodeURIComponent(id)}/scripts`),
  toolkits: (id: number) => getJson<Toolkit[]>(`/api/snapshots/${id}/toolkits`),
  toolkitInventory: (latestOnly = false) =>
    getJson<ToolkitInventory[]>(`/api/toolkits${latestOnly ? "?latestOnly=true" : ""}`),
  deleteToolkit: async (id: string) => {
    const r = await fetch(`/api/toolkits/${encodeURIComponent(id)}`, { method: "DELETE" });
    if (!r.ok) {
      const body = await r.json().catch(() => null);
      throw new Error(backendError(body?.error) ?? `${msg2("Löschen fehlgeschlagen", "Delete failed")}: HTTP ${r.status}`);
    }
  },
  deleteUnusedToolkits: async (): Promise<number> => {
    const r = await fetch("/api/toolkits/unused", { method: "DELETE" });
    const body = await r.json().catch(() => null);
    if (!r.ok) {
      throw new Error(backendError(body?.error) ?? `${msg2("Löschen fehlgeschlagen", "Delete failed")}: HTTP ${r.status}`);
    }
    return body?.deleted ?? 0;
  },
  toolkitGraph: (id: number) => getJson<ToolkitGraph>(`/api/snapshots/${id}/toolkit-graph`),
  toolkitConflicts: () => getJson<AppToolkitConflicts[]>(`/api/snapshots/toolkit-conflicts`),
  types: (id: number) => getJson<TypeCount[]>(`/api/snapshots/${id}/types`),
  search: (
    q: string,
    snapshotId?: number,
    type?: SnapshotType,
    prefixes?: string[],
    opts?: { scope?: string[]; regex?: boolean; caseSensitive?: boolean; limit?: number; budgetMs?: number }
  ) => {
    const p = new URLSearchParams({ q });
    if (snapshotId) p.set("snapshotId", String(snapshotId));
    if (type) p.set("type", type);
    if (prefixes?.length) p.set("prefixes", prefixes.join(","));
    if (opts?.scope?.length) p.set("scope", opts.scope.join(","));
    if (opts?.regex) p.set("regex", "true");
    if (opts?.caseSensitive) p.set("caseSensitive", "true");
    if (opts?.limit) p.set("limit", String(opts.limit));
    if (opts?.budgetMs) p.set("budgetMs", String(opts.budgetMs));
    return getJson<SearchResponse>(`/api/search?${p}`);
  },
  variables: (snapshotId?: number, scope?: string, q?: string) => {
    const p = new URLSearchParams();
    if (snapshotId) p.set("snapshotId", String(snapshotId));
    if (scope) p.set("scope", scope);
    if (q) p.set("q", q);
    return getJson<VariableRow[]>(`/api/variables?${p}`);
  },
  variableUsage: (snapshotId: number, variable: string, scope?: string) => {
    const p = new URLSearchParams({ snapshotId: String(snapshotId), variable });
    if (scope) p.set("scope", scope);
    return getJson<VariableUsageRow[]>(`/api/variables/usage?${p}`);
  },
  variablesBackfill: () =>
    fetch("/api/variables/backfill", { method: "POST" }).then((r) => {
      if (!r.ok) throw new Error(`backfill ${r.status}`);
      return r.json() as Promise<number>;
    }),
  rules: () => getJson<RuleInfo[]>("/api/rules"),
  ruleSettingsDefaults: () => getJson<RuleSettingsJson>("/api/rules/settings/defaults"),
  ruleSettings: (snapshotId?: number) =>
    getJson<RuleSettingsJson>(
      snapshotId ? `/api/rules/settings/snapshot/${snapshotId}` : "/api/rules/settings"),
  saveRuleSettings: async (s: RuleSettingsJson, snapshotId?: number) => {
    const r = await fetch(snapshotId ? `/api/rules/settings/snapshot/${snapshotId}` : "/api/rules/settings", {
      method: "PUT",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(s),
    });
    if (!r.ok) {
      const body = await r.json().catch(() => null);
      throw new Error(backendError(body?.error) ?? `${msg2("Speichern fehlgeschlagen", "Save failed")}: HTTP ${r.status}`);
    }
  },
  deleteSnapshotRuleSettings: async (snapshotId: number) => {
    const r = await fetch(`/api/rules/settings/snapshot/${snapshotId}`, { method: "DELETE" });
    if (!r.ok && r.status !== 404) throw new Error(`${msg2("Löschen fehlgeschlagen", "Delete failed")}: HTTP ${r.status}`);
  },
  bpmSettings: () => getJson<BpmSettings>("/api/bpm/settings"),
  bpmSaveSettings: (s: { baseUrl?: string; username?: string; password?: string; caFile?: string }) =>
    fetch("/api/bpm/settings", {
      method: "PUT",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(s),
    }),
  bpmTest: () => fetch("/api/bpm/test", { method: "POST" }),
  bpmContainers: (q?: string) => {
    const p = new URLSearchParams();
    if (q) p.set("q", q);
    return getJson<BpmContainer[]>(`/api/bpm/containers?${p}`);
  },
  bpmSnapshots: (acronym: string) =>
    getJson<BpmSnapshot[]>(`/api/bpm/snapshots?acronym=${encodeURIComponent(acronym)}`),
  bpmImport: (acronym: string, version: string) =>
    fetch("/api/bpm/import", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ acronym, version }),
    }),
  /** Download URL of the finding export (B9); set token only if the instance requires an API token. */
  exportUrl: (id: number, format: "json" | "csv" | "html", status = "effective", token?: string) => {
    const p = new URLSearchParams({ status });
    if (token) p.set("token", token);
    const suffix = format === "csv" ? "/csv" : format === "html" ? "/html" : "";
    return `/api/export/${id}${suffix}?${p}`;
  },
  /** B10: rule-hit bar per snapshot (top-8 rules), SnapshotId -> segments. */
  ruleSummary: () => getJson<Record<string, RuleBar[]>>("/api/snapshots/rule-summary"),
};
