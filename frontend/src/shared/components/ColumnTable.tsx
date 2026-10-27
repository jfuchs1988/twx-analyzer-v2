import { ReactNode, useEffect, useMemo, useState } from "react";
import { Group, Pagination, Table, Text } from "@mantine/core";
import { useLang } from "@/shared/i18n";

/**
 * Table with drag & drop reorderable columns (order per table persisted in
 * localStorage; double-clicking a column resets it). Row rendering stays the
 * caller's responsibility (render per column).
 */
export interface Column<T> {
  id: string;
  label: ReactNode;
  width?: number | string;
  align?: "left" | "right" | "center";
  render: (row: T) => ReactNode;
}

interface Props<T> {
  /** Stable key for the persisted column order (e.g. "findings"). */
  storageKey?: string;
  columns: Column<T>[];
  rows: T[];
  rowKey: (row: T) => string;
  empty?: ReactNode;
  striped?: boolean;
  onRowClick?: (row: T) => void;
  /** Background color per row (e.g. status; undefined = default). */
  rowColor?: (row: T) => string | undefined;
  /**
   * Client-side page size (default 100; 0 = off). Otherwise large renders
   * such as 8000+ finding snippets block the browser main thread.
   */
  pageSize?: number;
}

const PREFIX = "twxav2.cols.";

function loadOrder(storageKey?: string): string[] {
  if (!storageKey) return [];
  try {
    const raw = localStorage.getItem(PREFIX + storageKey);
    const arr = raw ? JSON.parse(raw) : null;
    return Array.isArray(arr) ? arr.filter((x) => typeof x === "string") : [];
  } catch {
    return [];
  }
}

export default function ColumnTable<T>({
  storageKey, columns, rows, rowKey, empty, striped = true, onRowClick, rowColor, pageSize = 100,
}: Props<T>) {
  const { tr } = useLang();
  const shape = columns.map((c) => c.id).join(",");
  const [order, setOrder] = useState<string[]>(() => loadOrder(storageKey));
  const [dragId, setDragId] = useState<string | null>(null);
  const [overId, setOverId] = useState<string | null>(null);
  const [page, setPage] = useState(0);

  // different column set (e.g. filter change) -> reload order
  useEffect(() => setOrder(loadOrder(storageKey)), [storageKey, shape]);
  // new row set (filter/load) -> back to page 1
  useEffect(() => setPage(0), [rows]);

  const pages = pageSize > 0 ? Math.max(1, Math.ceil(rows.length / pageSize)) : 1;
  const safePage = Math.min(page, pages - 1);
  const visible = pages > 1 ? rows.slice(safePage * pageSize, (safePage + 1) * pageSize) : rows;

  const ordered = useMemo(() => {
    const byId = new Map(columns.map((c) => [c.id, c]));
    const known = order.map((id) => byId.get(id)).filter(Boolean) as Column<T>[];
    const rest = columns.filter((c) => !order.includes(c.id));
    return [...known, ...rest];
  }, [order, columns]);

  const persist = (ids: string[]) => {
    setOrder(ids);
    if (storageKey) {
      try {
        localStorage.setItem(PREFIX + storageKey, JSON.stringify(ids));
      } catch { /* storage full or similar — ordering then applies to this session only */ }
    }
  };

  const drop = (targetId: string) => {
    if (!dragId || dragId === targetId) return;
    const ids = ordered.map((c) => c.id);
    const from = ids.indexOf(dragId);
    const to = ids.indexOf(targetId);
    if (from < 0 || to < 0) return;
    ids.splice(from, 1);
    ids.splice(to, 0, dragId);
    persist(ids);
  };

  const reset = () => {
    setOrder([]);
    if (storageKey) localStorage.removeItem(PREFIX + storageKey);
  };

  return (
    <>
    <Table.ScrollContainer minWidth={400} type="native">
      <Table striped={striped} highlightOnHover withTableBorder withColumnBorders
        horizontalSpacing="xs" verticalSpacing={4} fz="xs">
        <Table.Thead>
          <Table.Tr>
            {ordered.map((c) => (
              <Table.Th
                key={c.id}
                w={c.width}
                style={{
                  textAlign: c.align ?? "left",
                  cursor: "grab",
                  userSelect: "none",
                  opacity: dragId === c.id ? 0.4 : 1,
                  borderTop: overId === c.id && dragId && dragId !== c.id
                     ? "2px solid var(--mantine-color-blue-outline)" : undefined,
                }}
                title={tr("Spalte ziehen, um die Reihenfolge zu ändern; Doppelklick = zurücksetzen", "Drag a column to reorder it; double-click to reset")}
                draggable
                onDragStart={(e) => {
                  setDragId(c.id);
                  e.dataTransfer.effectAllowed = "move";
                  e.dataTransfer.setData("text/plain", c.id);
                }}
                onDragOver={(e) => {
                  if (dragId && dragId !== c.id) {
                    e.preventDefault();
                    e.dataTransfer.dropEffect = "move";
                    setOverId(c.id);
                  }
                }}
                onDragLeave={() => setOverId((o) => (o === c.id ? null : o))}
                onDrop={(e) => {
                  e.preventDefault();
                  drop(c.id);
                  setDragId(null);
                  setOverId(null);
                }}
                onDragEnd={() => { setDragId(null); setOverId(null); }}
                onDoubleClick={reset}
              >
                {c.label}
              </Table.Th>
            ))}
          </Table.Tr>
        </Table.Thead>
        <Table.Tbody>
          {rows.length === 0 && empty !== undefined && (
            <Table.Tr>
              <Table.Td colSpan={ordered.length} style={{ border: "none" }}>{empty}</Table.Td>
            </Table.Tr>
          )}
          {visible.map((row) => {
            const bg = rowColor?.(row);
            return (
              <Table.Tr
                key={rowKey(row)}
                style={{
                  cursor: onRowClick ? "pointer" : undefined,
                  background: bg,
                }}
                onClick={onRowClick ? () => onRowClick(row) : undefined}
              >
                {ordered.map((c) => (
                  <Table.Td key={c.id} style={{ textAlign: c.align ?? "left", verticalAlign: "top" }}>
                    {c.render(row)}
                  </Table.Td>
                ))}
              </Table.Tr>
            );
          })}
        </Table.Tbody>
      </Table>
      {rows.length === 0 && empty === undefined && (
        <Text c="dimmed" size="sm" fw={700} m="xs">{tr("Keine Einträge.", "No entries.")}</Text>
      )}
    </Table.ScrollContainer>
    {pages > 1 && (
      <Group justify="space-between" mt="xs" wrap="wrap">
        <Text size="xs" c="dimmed">
          {tr(
            `Zeige ${safePage * pageSize + 1}–${Math.min((safePage + 1) * pageSize, rows.length)} von ${rows.length} Zeilen`,
            `Showing ${safePage * pageSize + 1}–${Math.min((safePage + 1) * pageSize, rows.length)} of ${rows.length} rows`
          )}
        </Text>
        <Pagination size="xs" total={pages} value={safePage + 1} onChange={(v) => setPage(v - 1)} withEdges />
      </Group>
    )}
    </>
  );
}
