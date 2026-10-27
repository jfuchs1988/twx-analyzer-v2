import { useEffect, useRef, useState } from "react";
import { Alert, Badge, Group, Loader, Modal, ScrollArea, Stack, Text } from "@mantine/core";
import { api, ContentDiff, ObjectDiff } from "../api";
import { formatNumber, localizeDiffNote, useLang } from "../i18n";

/**
 * B6 follow-up: XML detail diff of one object version base -> target. The
 * server delivers compressed diff lines (context around changes, omission
 * markers); line numbers of both sides are shown in the gutter.
 */
export default function ObjectDiffModal({ base, target, obj, onClose }: {
  base: number;
  target: number;
  /** tapped comparison row (DB ID + name), null = closed */
  obj: ObjectDiff | null;
  onClose: () => void;
}) {
  const { lang, tr } = useLang();
  const [diff, setDiff] = useState<ContentDiff | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const seq = useRef(0);

  useEffect(() => {
    if (!obj) {
      setDiff(null);
      return;
    }
    // Ordering guard: on fast row switches, discard late responses from
    // earlier clicks (otherwise the modal shows the wrong object)
    const my = ++seq.current;
    setBusy(true);
    setError(null);
    setDiff(null);
    api.compareObject(base, target, obj.id)
      .then((d) => { if (seq.current === my) setDiff(d); })
      .catch((e) => { if (seq.current === my) setError(e instanceof Error ? e.message : String(e)); })
      .finally(() => { if (seq.current === my) setBusy(false); });
  }, [obj, base, target]);

  return (
    <Modal
      opened={!!obj}
      onClose={onClose}
      size="90%"
      centered
      title={
        <Group gap="xs">
          <Text size="sm" fw={600} truncate>
            {obj?.name ?? tr("(ohne Namen)", "(unnamed)")}
          </Text>
          {obj && <Badge variant="light" size="sm">{obj.typeName}</Badge>}
          {diff && (
            <>
              <Badge variant="light" color="red" size="sm">− {diff.deletions}</Badge>
              <Badge variant="light" color="green" size="sm">+ {diff.additions}</Badge>
              {diff.truncated && <Badge variant="outline" color="orange" size="sm">{tr("begrenzt", "truncated")}</Badge>}
            </>
          )}
        </Group>
      }
    >
      {busy && (
        <Group justify="center" py="xl">
          <Loader size="sm" />
          <Text size="sm" c="dimmed">{tr("Diff wird berechnet…", "Computing diff…")}</Text>
        </Group>
      )}
      {error && <Alert color="red" title={tr("Fehler", "Error")}>{error}</Alert>}
      {diff && !busy && (
        <Stack gap="xs">
          {diff.note && <Alert color="gray" withCloseButton={false}>{localizeDiffNote(diff.note, lang)}</Alert>}
          <Text size="xs" c="dimmed">
            {(diff.baseName ?? "—")} ({formatNumber(diff.baseBytes, lang)} {tr("Byte", "bytes")},
            {` ${diff.baseObjectId ?? tr("fehlt", "missing")}`})
            {" → "}
            {(diff.targetName ?? "—")} ({formatNumber(diff.targetBytes, lang)} {tr("Byte", "bytes")},
            {` ${diff.targetObjectId ?? tr("fehlt", "missing")}`})
          </Text>
          <ScrollArea.Autosize mah="65vh" style={{ border: "1px solid var(--mantine-color-gray-3)", borderRadius: 4 }}>
            <div style={{ fontFamily: "var(--mantine-font-family-monospace)", fontSize: 12, lineHeight: 1.45 }}>
              {diff.lines.map((l, i) => (
                <DiffRow key={i} line={l} />
              ))}
            </div>
          </ScrollArea.Autosize>
        </Stack>
      )}
    </Modal>
  );
}

function DiffRow({ line }: { line: ContentDiff["lines"][number] }) {
  if (line.kind === 3) {
    return (
      <div style={{ padding: "1px 8px", color: "var(--mantine-color-dimmed)", fontStyle: "italic", fontSize: 11 }}>
        ⋯ {line.text}
      </div>
    );
  }
  const bg = line.kind === 1
    ? "rgba(225, 29, 72, 0.14)"
    : line.kind === 2
      ? "rgba(21, 128, 61, 0.15)"
      : undefined;
  const sign = line.kind === 1 ? "−" : line.kind === 2 ? "+" : " ";
  return (
    <div style={{ display: "flex", background: bg, whiteSpace: "pre" }}>
      <span style={gutter}>{line.baseLine ?? ""}</span>
      <span style={gutter}>{line.targetLine ?? ""}</span>
      <span style={{ width: 14, flexShrink: 0, color: "var(--mantine-color-dimmed)", userSelect: "none" }}>{sign}</span>
      <span style={{ paddingRight: 8, overflowWrap: "anywhere" }}>{line.text}</span>
    </div>
  );
}

const gutter: React.CSSProperties = {
  width: 52,
  flexShrink: 0,
  textAlign: "right",
  paddingRight: 8,
  color: "var(--mantine-color-dimmed)",
  userSelect: "none",
};
