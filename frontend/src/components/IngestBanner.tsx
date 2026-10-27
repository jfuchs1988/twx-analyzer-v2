import { useEffect, useRef, useState } from "react";
import { Button, Group, Loader, Progress, Stack, Text } from "@mantine/core";
import { backendError } from "../i18n";
import { api, getClientUpload, IngestStatus } from "../api";
import { useLang } from "../i18n";

const PHASE_DE: Record<string, string> = {
  UPLOAD: "Datei wird zum Server hochgeladen",
  EXPORT: "TWX wird von BPM heruntergeladen",
  PARSE: "TWX wird gelesen",
  ANALYZE: "Regeln werden ausgeführt",
};

const PHASE_EN: Record<string, string> = {
  UPLOAD: "Uploading file to the server",
  EXPORT: "Downloading TWX from BPM",
  PARSE: "Reading TWX",
  ANALYZE: "Running rules",
};

/** Custom event type: finished snapshot -> lists refresh along with it. */
export const INGEST_DONE_EVENT = "twx:ingest-done";

/**
 * Global progress banner: shows all running, queued and recently finished
 * ingests (uploads and BPM imports) above the page content — stacked, one
 * row per job. Status comes server-side from /api/ingest/status, so it is
 * stable across page switches and browser refresh.
 */
export default function IngestBanner({ onOpenSnapshot }: { onOpenSnapshot: (id: number) => void }) {
  const [tasks, setTasks] = useState<IngestStatus[]>([]);
  const [, tick] = useState(0);
  const [dismissed, setDismissed] = useState<Set<number>>(new Set());
  const doneAt = useRef<Map<number, number>>(new Map()); // taskId -> time DONE was detected
  const prevDone = useRef<Set<number>>(new Set()); // for list change notification

  useEffect(() => {
    let stopped = false;
    const poll = async () => {
      try {
        const list = await api.ingestStatus();
        if (stopped) return;
        const doneIds = new Set(list.filter((t) => t.phase === "DONE").map((t) => t.taskId));
        const now = Date.now();
        for (const t of list) {
          if (t.phase === "DONE" && !doneAt.current.has(t.taskId)) doneAt.current.set(t.taskId, now);
        }
        // newly finished jobs -> refresh lists (SnapshotList/ToolkitPage)
        for (const id of doneIds) {
          if (!prevDone.current.has(id)) {
            const t = list.find((x) => x.taskId === id);
            if (t?.snapshotId != null) window.dispatchEvent(new CustomEvent(INGEST_DONE_EVENT, { detail: t.snapshotId }));
          }
        }
        prevDone.current = doneIds;
        setTasks((prev) => (JSON.stringify(prev) === JSON.stringify(list) ? prev : list));
      } catch {
        /* backend temporarily unreachable – next attempt */
      }
    };
    poll();
    const id = window.setInterval(poll, 1500);
    const t = window.setInterval(() => tick((n) => n + 1), 1000); // seconds counter
    return () => {
      stopped = true;
      window.clearInterval(id);
      window.clearInterval(t);
    };
  }, []);

  const now = Date.now();
  const visible = tasks.filter(
    (t) =>
      !dismissed.has(t.taskId) &&
      // hide DONE banners after 15 s (server-side they stay visible for 2 min)
      !(t.phase === "DONE" && now - (doneAt.current.get(t.taskId) ?? now) > 15_000)
  );
  let rows: IngestStatus[] = [...visible];
  // The banner already shows an in-progress browser->server transfer before the
  // server job exists (multipart is only complete at the end of the request).
  const cu = getClientUpload();
  if (cu && !cu.done && cu.loaded < cu.total && !rows.some((t) => t.active && t.phase === "UPLOAD" && t.label === cu.label)) {
    rows = [{ taskId: -1, active: true, phase: "UPLOAD", label: cu.label, startedAt: now, queuePosition: 0 }, ...rows];
  }
  if (rows.length === 0) return null;

  return (
    <Stack gap={6} mb="md">
      {rows.map((status) => (
        <BannerRow
          key={status.taskId}
          status={status}
          onOpenSnapshot={onOpenSnapshot}
          onDismiss={() => setDismissed((p) => new Set(p).add(status.taskId))}
        />
      ))}
    </Stack>
  );
}

function BannerRow({
  status,
  onOpenSnapshot,
  onDismiss,
}: {
  status: IngestStatus;
  onOpenSnapshot: (id: number) => void;
  onDismiss: () => void;
}) {
  const { tr } = useLang();
  // Show the browser->server step only while this upload is actually running.
  const cu = getClientUpload();
  const clientActive =
    status.phase === "UPLOAD" && status.active && !!cu && !cu.done && cu.loaded < cu.total && status.label === cu.label;
  const steps: { label: string; done: boolean; current: boolean }[] = [];
  if (clientActive && cu) {
    steps.push({
      label: tr(`Hochladen ${Math.round((cu.loaded / Math.max(cu.total, 1)) * 100)} %`, `Uploading ${Math.round((cu.loaded / Math.max(cu.total, 1)) * 100)} %`),
      done: false,
      current: true,
    });
  }
  if (status.phase === "EXPORT") {
    steps.push({ label: tr("1 · Von BPM herunterladen", "1 · Download from BPM"), done: false, current: true });
  }
  if (status.phase === "PARSE" || status.phase === "ANALYZE") {
    if (status.label && !/\.twx$/i.test(status.label)) {
      // BPM import: the download step succeeded
      steps.push({ label: tr("1 · Von BPM herunterladen", "1 · Download from BPM"), done: true, current: false });
    }
    steps.push({
      label: tr(PHASE_DE[status.phase], PHASE_EN[status.phase]),
      done: status.phase === "ANALYZE",
      current: status.phase === "ANALYZE",
    });
  }

  const queued = status.phase === "QUEUED";
  const running = status.active && !queued;
  const error = status.phase === "ERROR";
  const done = status.phase === "DONE";
  const currentIdx = steps.findIndex((s) => s.current);
  const completed = steps.filter((s) => s.done).length;
  const total = Math.max(steps.length, 1);
  const percent = running
    ? Math.min(95, ((completed + (currentIdx >= 0 ? 0.4 : 0.15)) / total) * 100)
    : 100;
  const elapsed = status.startedAt ? Math.max(0, Math.round((Date.now() - status.startedAt) / 1000)) : 0;

  const info = queued
    ? tr(`Wartend – Position ${status.queuePosition} in der Schlange`, `Waiting – queue position ${status.queuePosition}`)
    : running
      ? steps.find((s) => s.current)?.label ?? tr("Verarbeitung läuft", "Processing")
      : done
        ? tr("Analyse abgeschlossen", "Analysis complete")
        : tr(`Fehler: ${status.error ? backendError(status.error) : "unbekannt"}`, `Error: ${status.error ? backendError(status.error) : "unknown"}`);

  return (
    <Group
      wrap="nowrap"
      gap="md"
      px="md"
      py={6}
      style={{
        borderRadius: 6,
        background: error
          ? "var(--mantine-color-red-9)"
          : queued
            ? "var(--mantine-color-dark-8)"
            : "var(--mantine-color-dark-7)",
        color: "#fff",
        boxShadow: "0 2px 8px rgba(0,0,0,0.3)",
        opacity: queued ? 0.85 : 1,
      }}
    >
      <Group gap="xs" style={{ flex: 1, minWidth: 0 }}>
        <Text size="sm" fw={700} style={{ whiteSpace: "nowrap" }}>
          {queued ? (
            <Group gap={6}>
              <Loader size={12} />
              <Text component="span" size="sm" fw={700}>
                {tr("Wartend", "Waiting")}
              </Text>
            </Group>
          ) : running ? (
            tr("Analyse läuft", "Analysis running")
          ) : done ? (
            tr("✓ Fertig", "✓ Done")
          ) : (
            tr("✕ Fehler", "✕ Error")
          )}
        </Text>
        {status.label && (
          <Text size="sm" style={{ overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap" }}>
            {status.label}
          </Text>
        )}
        <Text size="xs" style={{ whiteSpace: "nowrap", color: "#fff" }}>
          {info}
          {running || queued ? ` · ${elapsed} s` : ""}
        </Text>
      </Group>
      {running && (
        <Progress
          value={percent}
          animated
          size="sm"
          style={{ width: 180, flexShrink: 0 }}
           color="brand"
        />
      )}
      {done && status.snapshotId != null && (
        <Button size="xs" color="teal" onClick={() => onOpenSnapshot(status.snapshotId!)}>
          {tr("Ergebnis anzeigen", "View result")}
        </Button>
      )}
      {error && (
        <Button size="xs" variant="white" color="dark" onClick={onDismiss}>
          {tr("Schließen", "Dismiss")}
        </Button>
      )}
    </Group>
  );
}
