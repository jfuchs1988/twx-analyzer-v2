import { useEffect, useMemo, useRef, useState } from "react";
import { Badge, Group, Loader, Modal, Stack, Text } from "@mantine/core";
import { api, ObjectContent } from "@/shared/api";
import { formatNumber, useLang } from "@/shared/i18n";

const MAX_RENDERED_LINES = 8000;

/** highlights occurrences of `needle` (case-insensitive) within a line (file-internal) */
function highlight(line: string, needle?: string) {
  if (!needle || needle.length < 2) return line;
  const lower = line.toLowerCase();
  const n = needle.toLowerCase();
  if (!lower.includes(n)) return line;
  const parts: React.ReactNode[] = [];
  let i = 0;
  let k: number;
  let key = 0;
  while ((k = lower.indexOf(n, i)) >= 0) {
    if (k > i) parts.push(line.slice(i, k));
    parts.push(
      <mark key={key++} style={{ background: "var(--mantine-color-yellow-light)", color: "inherit", borderRadius: 2 }}>
        {line.slice(k, k + n.length)}
      </mark>
    );
    i = k + n.length;
  }
  if (i < line.length) parts.push(line.slice(i));
  return parts;
}

/**
 * Content of a TWX object (scripts, XML, JSON …) with line numbers.
 * `highlight` marks a search term; `focusLine` scrolls to a line (1-based).
 */
export default function ObjectContentModal({
  objectId, onClose, highlight: needle, focusLine
}: {
  objectId: string | null;
  onClose: () => void;
  highlight?: string;
  focusLine?: number;
}) {
  const { lang, tr } = useLang();
  const [data, setData] = useState<ObjectContent | null>(null);
  const [loading, setLoading] = useState(false);
  const scrollRef = useRef<HTMLDivElement>(null);
  const seq = useRef(0);

  useEffect(() => {
    if (!objectId) {
      setData(null);
      return;
    }
    const my = ++seq.current;
    setLoading(true);
    api.objectContent(objectId)
      .then((d) => { if (seq.current === my) setData(d); })
      .catch(() => { if (seq.current === my) setData(null); })
      .finally(() => { if (seq.current === my) setLoading(false); });
  }, [objectId]);

  // TWX line endings are mixed (also lone CR) — same as ObjectBrowserModal
  const lines = useMemo(() => (data?.content ?? "").split(/\r\n|\r|\n/), [data]);
  const needleLower = (needle ?? "").toLowerCase();

  // scroll to the finding as soon as content is available
  useEffect(() => {
    if (!data || !scrollRef.current) return;
    const t = window.setTimeout(() => {
      const el = scrollRef.current?.querySelector("[data-focus='1']");
      el?.scrollIntoView({ block: "center" });
    }, 50);
    return () => window.clearTimeout(t);
  }, [data, needle, focusLine]);

  let focusIdx = focusLine ? focusLine - 1 : -1;
  if (focusIdx < 0 && needleLower) {
    focusIdx = lines.findIndex(l => l.toLowerCase().includes(needleLower));
  }

  const shown = lines.length > MAX_RENDERED_LINES;
  const visible = shown ? lines.slice(0, MAX_RENDERED_LINES) : lines;
  const gutterWidth = Math.max(4, String(visible.length).length + 1);

  return (
    <Modal
      opened={!!objectId}
      onClose={onClose}
      size="85%"
      title={
        data ? (
          <Group gap="xs">
            <span>{data.name ?? objectId}</span>
            <Text span c="dimmed" size="sm">({data.subType || data.typeName})</Text>
            {needle && <Badge size="xs" variant="light" color="yellow">{needle}</Badge>}
          </Group>
        ) : tr("Objektinhalt", "Object content")
      }
    >
      {loading && <Group justify="center"><Loader size="sm" /></Group>}
      {!loading && !data && (
        <Text c="dimmed">{tr("Kein Inhalt verfuegbar (Objekt unbekannt oder Inhalt nicht gespeichert).", "No content available (object unknown or content not stored).")}</Text>
      )}
      {!loading && data && data.content == null && (
        <Text c="dimmed">{tr("Kein Textinhalt gespeichert (Datei zu groß oder binaer).", "No text content stored (file too large or binary).")}</Text>
      )}
      {!loading && data && data.content != null && (
        <Stack gap={4}>
          <Text size="xs" c="dimmed">
            {tr(`${formatNumber(lines.length, lang)} Zeilen · ${formatNumber(data.content.length, lang)} Zeichen`, `${formatNumber(lines.length, lang)} lines · ${formatNumber(data.content.length, lang)} characters`)}
            {shown ? tr(` (nur die ersten ${formatNumber(MAX_RENDERED_LINES, lang)} angezeigt)`, ` (only the first ${formatNumber(MAX_RENDERED_LINES, lang)} shown)`) : ""}
          </Text>
          <div
            ref={scrollRef}
            style={{
              overflow: "auto",
              maxHeight: "70vh",
              background: "var(--mantine-color-body)",
              border: "1px solid var(--mantine-color-default-border)",
              borderRadius: "var(--mantine-radius-sm)",
              fontFamily: "var(--mantine-font-family-monospace)",
              fontSize: 12,
              lineHeight: "18px"
            }}
          >
            {visible.map((l, i) => (
              <div
                key={i}
                data-focus={i === focusIdx ? "1" : undefined}
                style={{
                  display: "flex",
                  background: i === focusIdx ? "var(--mantine-color-yellow-light)" : undefined
                }}
              >
                <span
                  style={{
                    flex: "0 0 auto",
                    width: `${gutterWidth}ch`,
                    padding: "0 8px",
                    textAlign: "right",
                    color: "var(--mantine-color-dimmed)",
                    background: "var(--mantine-color-gray-light)",
                    userSelect: "none",
                    position: "sticky",
                    left: 0
                  }}
                >
                  {i + 1}
                </span>
                <span style={{ padding: "0 8px", whiteSpace: "pre" }}>{highlight(l, needle)}</span>
              </div>
            ))}
          </div>
        </Stack>
      )}
    </Modal>
  );
}
