import { useEffect, useMemo, useRef, useState } from "react";
import {
  Accordion, Badge, Button, Group, Loader, Modal, Notification, ScrollArea, SegmentedControl, Stack, Table, Text,
} from "@mantine/core";
import { IconFilterOff, IconRestore } from "@tabler/icons-react";
import { api, DiagramDto, ObjectContent, ObjectDetail, ScriptFragment } from "../api";
import DiagramSvg from "./DiagramSvg";
import { localizeDetailText, useLang } from "../i18n";

const MAX_RENDERED_LINES = 8000;

/**
 * B7/B8: Structured object browser — model details (declarations, steps,
 * attributes …), incoming/outgoing references, raw content and (for
 * processes/BPDs) the designer diagram. Clicking a reference opens the
 * referenced object. Plus: exclude toggle — exclude the object from all
 * rules of the snapshot.
 */
export default function ObjectBrowserModal({ objectId, onClose, hotLocations, onChanged }: {
  objectId: string | null;
  onClose: () => void;
  /** Finding texts of the start object (red nodes in the diagram). */
  hotLocations?: string[];
  /** After exclude/re-include: parent page should reload lists. */
  onChanged?: () => void;
}) {
  const { lang, tr } = useLang();
  const locale = lang === "de" ? "de-DE" : "en-US";
  const [tab, setTab] = useState<"detail" | "scripts" | "content" | "diagram" | "refs">("detail");
  const [detail, setDetail] = useState<ObjectDetail | null>(null);
  const [content, setContent] = useState<ObjectContent | null>(null);
  const [diagram, setDiagram] = useState<DiagramDto | null>(null);
  const [scripts, setScripts] = useState<ScriptFragment[] | null>(null);
  const [loading, setLoading] = useState(false);
  const [current, setCurrent] = useState<string | null>(null);
  const scrollRef = useRef<HTMLDivElement>(null);
  const seq = useRef(0);
  // Exclude feature: status of the visible object (+ feedback to the user)
  const [excluded, setExcluded] = useState(false);
  const [excludeBusy, setExcludeBusy] = useState(false);
  const [excludeNote, setExcludeNote] = useState<{ color: string; text: string } | null>(null);

  useEffect(() => {
    setCurrent(objectId);
    setTab("detail");
  }, [objectId]);

  useEffect(() => {
    setExcludeNote(null);
    if (!current) {
      setExcluded(false);
      return;
    }
    const snapId = Number(current.split(":")[0]);
    const twxId = current.slice(current.indexOf(":") + 1);
    api.exclusions(snapId)
      .then((xs) => setExcluded(xs.some((x) => x.twxId === twxId)))
      .catch(() => setExcluded(false));
  }, [current]);

  const toggleExclusion = () => {
    if (!current) {
      return;
    }
    const snapId = Number(current.split(":")[0]);
    const twxId = current.slice(current.indexOf(":") + 1);
    const wasExcluded = excluded;
    setExcludeBusy(true);
    (wasExcluded ? api.unexcludeObject(snapId, twxId) : api.excludeObject(snapId, current))
      .then(() => {
        setExcluded(!wasExcluded);
        setExcludeNote(wasExcluded
          ? { color: "yellow", text: tr("Wieder aufgenommen — die Befunde des Objekts erscheinen erst nach einer neuen Analyse (Schaltfläche „Neu analysieren“ oben).", "Re-included — the object's findings reappear only after a new analysis („Re-analyze“ button above).") }
          : { color: "green", text: tr("Ausgeschlossen — vorhandene Befunde des Objekts wurden entfernt; künftige Analysen lassen es aus.", "Excluded — existing findings were removed; future analyses will skip this object.") });
        onChanged?.();
      })
      .catch((e: unknown) => setExcludeNote({ color: "red", text: String((e as Error)?.message ?? e) }))
      .finally(() => setExcludeBusy(false));
  };

  useEffect(() => {
    if (!current) {
      setDetail(null); setContent(null); setDiagram(null); setScripts(null);
      return;
    }
    // Ordering guard: on fast reference switches, discard late responses
    // from earlier objects (otherwise detail/content/diagram get mixed up)
    const my = ++seq.current;
    const stale = () => seq.current !== my;
    setLoading(true);
    setDetail(null); setContent(null); setDiagram(null); setScripts(null);
    api.objectDetail(current)
      .then((d) => { if (!stale()) setDetail(d); })
      .catch(() => { if (!stale()) setDetail(null); })
      .finally(() => { if (!stale()) setLoading(false); });
    api.objectContent(current).then((c) => { if (!stale()) setContent(c); }).catch(() => { if (!stale()) setContent(null); });
    // Diagram only for model carriers (process 1 / BPD 25) — endpoint returns empty otherwise
    if (current.includes(":1.") || current.includes(":25.")) {
      api.objectDiagram(current).then((d) => { if (!stale()) setDiagram(d); }).catch(() => { if (!stale()) setDiagram(null); });
    }
  }, [current]);

  // Script fragments lazy: load only when the tab is opened (on reference
  // switch scripts is reset, then the effect refetches).
  useEffect(() => {
    if (!current || tab !== "scripts" || scripts !== null) {
      return;
    }
    const my = seq.current;
    const stale = () => seq.current !== my;
    api.objectScripts(current)
      .then((s) => { if (!stale()) setScripts(s); })
      .catch(() => { if (!stale()) setScripts([]); });
  }, [current, tab, scripts]);

  // TWX content has mixed line endings (also lone CR, designer export) —
  // splitting on \n only shifts all line numbers against API findings
  const lines = useMemo(() => (content?.content ?? "").split(/\r\n|\r|\n/), [content]);
  const shown = lines.length > MAX_RENDERED_LINES;
  const visible = shown ? lines.slice(0, MAX_RENDERED_LINES) : lines;
  const gutterWidth = Math.max(4, String(visible.length).length + 1);

  const openRef = (id: string) => {
    if (id !== current) setCurrent(id);
  };

  return (
    <Modal
      opened={!!objectId}
      onClose={onClose}
      size="85%"
      title={
        <Group gap="xs">
          <span>{detail?.name ?? current ?? tr("Objekt", "Object")}</span>
          <Text span c="dimmed" size="sm">({detail?.subType || detail?.typeName})</Text>
          {detail?.snapshotAcronym && (
            <Badge size="xs" variant="light">{detail.snapshotAcronym} {detail.snapshotVersion}</Badge>
          )}
          {current && (
            <Button size="xs" ml="auto"
              variant={excluded ? "light" : "outline"} color={excluded ? "orange" : "gray"}
              loading={excludeBusy} onClick={toggleExclusion} leftSection={excluded ? <IconRestore size={13} /> : <IconFilterOff size={13} />}
              title={excluded
                ? tr("Objekt wieder an allen Regeln teilnehmen lassen", "Let the object participate in all rules again")
                : tr("Objekt aus allen Regeln dieses Snapshots ausschließen (z. B. Test-/Template-Services)", "Exclude the object from all rules of this snapshot (e.g. test/template services)")}>
              {excluded ? tr("Wieder aufnehmen", "Re-include") : tr("Vom Check ausschließen", "Exclude from checks")}
            </Button>
          )}
        </Group>
      }
    >
      <Stack gap="sm">
        <SegmentedControl
          value={tab}
          onChange={(v) => setTab(v as typeof tab)}
          data={[
            { value: "detail", label: tr(`Modell${detail?.sections.length ? ` (${detail.sections.length})` : ""}`, `Model${detail?.sections.length ? ` (${detail.sections.length})` : ""}`) },
            { value: "scripts", label: tr(`Skripte${scripts ? ` (${scripts.length})` : ""}`, `Scripts${scripts ? ` (${scripts.length})` : ""}`) },
            { value: "refs", label: tr(`Referenzen${detail ? ` (${detail.inRefCount}↙ ${detail.outRefCount}↗)` : ""}`, `References${detail ? ` (${detail.inRefCount}↙ ${detail.outRefCount}↗)` : ""}`) },
            { value: "content", label: tr("Inhalt", "Content") },
            { value: "diagram", label: tr("Diagramm", "Diagram") },
          ]}
        />
        {excludeNote && (
          <Notification color={excludeNote.color} onClose={() => setExcludeNote(null)} withCloseButton>
            {excludeNote.text}
          </Notification>
        )}
        {loading && <Group justify="center"><Loader size="sm" /></Group>}

        {!loading && tab === "detail" && (
          detail && detail.sections.length > 0 ? (
            <Stack gap="md">
              {detail.sections.map((s) => (
                <div key={s.title}>
                  <Text fw={700} size="sm" mb={4}>{localizeDetailText(s.title, lang)} ({s.rows.length})</Text>
                  <Table.ScrollContainer minWidth={300} type="native">
                    <Table striped withTableBorder fz="xs" verticalSpacing={2}>
                      <Table.Thead>
                        <Table.Tr>{s.columns.map((c) => <Table.Th key={c}>{localizeDetailText(c, lang)}</Table.Th>)}</Table.Tr>
                      </Table.Thead>
                      <Table.Tbody>
                        {s.rows.map((r, i) => (
                          <Table.Tr key={i}>
                             {r.map((cell, j) => <Table.Td key={j} style={{ whiteSpace: "pre-wrap" }}>{localizeDetailText(cell, lang)}</Table.Td>)}
                          </Table.Tr>
                        ))}
                      </Table.Tbody>
                    </Table>
                  </Table.ScrollContainer>
                </div>
              ))}
              {detail.sha256 && (
                <Text size="xs" c="dimmed">SHA-256: {detail.sha256} · {detail.contentBytes.toLocaleString(locale)} {tr("Bytes", "bytes")}</Text>
              )}
            </Stack>
          ) : (
            <Text c="dimmed" size="sm">
              {tr("Keine geparsten Modell-Details (dieser Objekttyp wird als Ganzes im Reiter „Inhalt“ gezeigt).", "No parsed model details (this object type is shown as a whole on the “Content” tab).")}
            </Text>
          )
        )}

        {!loading && tab === "scripts" && (
          scripts === null ? <Group justify="center"><Loader size="sm" /></Group>
            : scripts.length === 0 ? (
              <Text c="dimmed" size="sm">
                {tr("Keine Skripte gefunden (kein Skriptblock, kein Skriptfeld im Modell-JSON, keine JS-Datei).",
                    "No scripts found (no script block, no script field in the model JSON, no JS file).")}
              </Text>
            ) : (
              <Stack gap="sm">
                <Text size="xs" c="dimmed">
                  {tr(`${scripts.length} Skript(e) — entpackt wie von der Analyse gesehen (XML-Entities gelöst, Duplikate Modell-JSON ↔ Skriptblock entfernt).`,
                      `${scripts.length} script(s) — shown as the analysis sees them (XML entities resolved, model-JSON ↔ script-block duplicates removed).`)}
                </Text>
                <Accordion multiple chevronPosition="left" variant="separated">
                  {scripts.map((s, i) => (
                    <Accordion.Item key={`${s.description}-${i}`} value={String(i)}>
                      <Accordion.Control>
                        <Group gap="xs">
                          <Text span size="sm" fw={600}>{localizeDetailText(s.description, lang)}</Text>
                          <Badge size="xs" variant="light">{tr(`Zeile ${s.line}`, `line ${s.line}`)}</Badge>
                          <Text span size="xs" c="dimmed">
                            {s.js.split(/\r\n|\r|\n/).length.toLocaleString(locale)} {tr("Zeilen", "lines")}
                          </Text>
                        </Group>
                      </Accordion.Control>
                      <Accordion.Panel>
                        <CodeLines js={s.js} />
                      </Accordion.Panel>
                    </Accordion.Item>
                  ))}
                </Accordion>
              </Stack>
            )
        )}

        {!loading && tab === "refs" && detail && (
          <Stack gap="md">
            <RefTable title={tr("Zeigt auf dieses Objekt (eingehend)", "Points to this object (incoming)")} rows={detail.inRefs}
              cap={detail.inRefCount} onOpen={openRef} />
            <RefTable title={tr("Dieses Objekt zeigt auf (ausgehend)", "This object points to (outgoing)")} rows={detail.outRefs}
              cap={detail.outRefCount} onOpen={openRef} />
            {detail.inRefCount === 0 && detail.outRefCount === 0 && (
              <Text c="dimmed" size="sm">{tr("Keine Referenzen erkannt.", "No references detected.")}</Text>
            )}
          </Stack>
        )}

        {!loading && tab === "content" && (
          content?.content != null ? (
            <>
              <Text size="xs" c="dimmed">
                {tr(`${lines.length.toLocaleString(locale)} Zeilen · ${content.content.length.toLocaleString(locale)} Zeichen`, `${lines.length.toLocaleString(locale)} lines · ${content.content.length.toLocaleString(locale)} characters`)}
                {shown ? tr(` (nur die ersten ${MAX_RENDERED_LINES.toLocaleString(locale)} angezeigt)`, ` (only the first ${MAX_RENDERED_LINES.toLocaleString(locale)} shown)`) : ""}
              </Text>
              <div
                ref={scrollRef}
                style={{
                  overflow: "auto", maxHeight: "62vh",
                  background: "var(--mantine-color-body)",
                  border: "1px solid var(--mantine-color-default-border)",
                  borderRadius: "var(--mantine-radius-sm)",
                  fontFamily: "var(--mantine-font-family-monospace)",
                  fontSize: 12, lineHeight: "18px",
                }}
              >
                {visible.map((l, i) => (
                  <div key={i} style={{ display: "flex" }}>
                    <span style={{
                      flex: "0 0 auto", width: `${gutterWidth}ch`, padding: "0 8px",
                      textAlign: "right", color: "var(--mantine-color-dimmed)",
                      background: "var(--mantine-color-gray-light)", userSelect: "none",
                      position: "sticky", left: 0,
                    }}>{i + 1}</span>
                    <span style={{ padding: "0 8px", whiteSpace: "pre" }}>{l}</span>
                  </div>
                ))}
              </div>
            </>
          ) : (
            <Text c="dimmed" size="sm">{tr("Kein Textinhalt gespeichert (Datei zu groß oder binär).", "No text content stored (file too large or binary).")}</Text>
          )
        )}

        {!loading && tab === "diagram" && (
          diagram
            ? <DiagramSvg diagram={diagram} hotLocations={current === objectId ? hotLocations : undefined} />
            : <Text c="dimmed" size="sm">{tr("Kein Diagramm verfügbar (nur Prozesse und BPDs mit Modell-JSON).", "No diagram available (only processes and BPDs with model JSON).")}</Text>
        )}
      </Stack>
    </Modal>
  );
}

function RefTable({ title, rows, cap, onOpen }: {
  title: string;
  rows: { objectId: string; objectName?: string; typeName: string }[];
  cap: number;
  onOpen: (id: string) => void;
}) {
  const { tr } = useLang();
  if (rows.length === 0) return null;
  return (
    <div>
      <Text fw={700} size="sm" mb={4}>
        {title} ({cap > rows.length ? tr(`erste ${rows.length} von ${cap}`, `first ${rows.length} of ${cap}`) : rows.length})
      </Text>
      <ScrollArea.Autosize mah={220} type="auto">
        <Table striped fz="xs" verticalSpacing={2} withTableBorder>
          <Table.Tbody>
            {rows.map((r) => (
              <Table.Tr key={r.objectId} style={{ cursor: "pointer" }} onClick={() => onOpen(r.objectId)}>
                <Table.Td>{r.objectName ?? r.objectId}</Table.Td>
                <Table.Td><Badge size="xs" variant="light">{r.typeName}</Badge></Table.Td>
              </Table.Tr>
            ))}
          </Table.Tbody>
        </Table>
      </ScrollArea.Autosize>
    </div>
  );
}

/** Code block with line numbers (like the raw-content tab, identical cap logic). */
function CodeLines({ js }: { js: string }) {
  const lines = useMemo(() => js.split(/\r\n|\r|\n/), [js]);
  const shown = lines.slice(0, MAX_RENDERED_LINES);
  return (
    <code style={{ display: "block", fontSize: 12, lineHeight: 1.45, whiteSpace: "pre" }}>
      {shown.map((ln, i) => (
        <div key={i}>
          <span
            style={{
              color: "var(--mantine-color-dimmed)", display: "inline-block",
              width: "3.5em", textAlign: "right", marginRight: "1em", userSelect: "none",
            }}
          >
            {i + 1}
          </span>
          {ln}
        </div>
      ))}
      {lines.length > MAX_RENDERED_LINES && (
        <div style={{ color: "var(--mantine-color-dimmed)" }}>…</div>
      )}
    </code>
  );
}
