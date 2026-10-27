import { Fragment, useEffect, useMemo, useState } from "react";
import {
  ActionIcon, Badge, Box, Button, Code, CopyButton, Group, Loader, Modal, Notification, Paper, Stack, Switch, Table, Text, TextInput, Title, Tooltip
} from "@mantine/core";
import { IconAlertTriangle, IconCloudDownload, IconFile, IconSearch, IconSitemap, IconTrash } from "@tabler/icons-react";
import { Dropzone } from "@mantine/dropzone";
import type { DropzoneProps } from "@mantine/dropzone";
import { api, AppToolkitConflicts, ToolkitInventory } from "@/shared/api";
import BpmImportModal from "@/features/import/BpmImportModal";
import SnapshotList from "@/shared/components/SnapshotList";
import { INGEST_DONE_EVENT } from "@/shared/components/IngestBanner";
import { backendError, useLang } from "@/shared/i18n";

/**
 * Toolkits: own page for toolkit snapshots (upload/BPM import) and the
 * global inventory (SHA-256 dedup store) so process apps and toolkits
 * are not mixed.
 */
export default function ToolkitPage({
  onOpen,
}: {
  onOpen: (id: number, from?: "list" | "toolkits") => void;
}) {
  const { tr } = useLang();
  const [uploading, setUploading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [bpmOpen, setBpmOpen] = useState(false);
  const [refreshKey, setRefreshKey] = useState(0);
  const [inventory, setInventory] = useState<ToolkitInventory[] | null>(null);
  const [invFilter, setInvFilter] = useState("");
  const [invLatestOnly, setInvLatestOnly] = useState(false);
  const [invOutdatedOnly, setInvOutdatedOnly] = useState(false);
  const [deleteTarget, setDeleteTarget] = useState<ToolkitInventory | null>(null);
  const [purgeOpen, setPurgeOpen] = useState(false);
  const [deleteBusy, setDeleteBusy] = useState(false);
  const [conflicts, setConflicts] = useState<AppToolkitConflicts[] | null>(null);

  useEffect(() => {
    api.toolkitInventory(invLatestOnly).then(setInventory).catch(() => setInventory([]));
    api.toolkitConflicts().then(setConflicts).catch(() => setConflicts([]));
  }, [refreshKey, invLatestOnly]);

  // Refresh inventory and lists when an ingest finishes in the background
  useEffect(() => {
    const onDone = () => setRefreshKey((k) => k + 1);
    window.addEventListener(INGEST_DONE_EVENT, onDone);
    return () => window.removeEventListener(INGEST_DONE_EVENT, onDone);
  }, []);

  const upload: DropzoneProps["onDrop"] = async (files) => {
    if (files.length === 0) return;
    setUploading(true);
    setError(null);
    setNotice(null);
    try {
      // upload one after another (browser->server); the ingest itself runs
      // server-side in the queue and may continue in parallel
      for (const f of files) {
        const r = await api.upload(f);
        const body = await r.json().catch(() => null);
        if (!r.ok) throw new Error(backendError(body?.error) ?? `HTTP ${r.status}`);
      }
      setNotice(tr(`${files.length} Datei${files.length > 1 ? "en" : ""} eingereiht – Fortschritt läuft im Banner oben, mehrere Analysen parallel.`, `${files.length} file${files.length > 1 ? "s" : ""} queued – progress runs in the banner above, multiple analyses in parallel.`));
    } catch (e) {
      setError(String(e));
    } finally {
      setUploading(false);
    }
  };

  const confirmDelete = async () => {
    if (!deleteTarget) return;
    setDeleteBusy(true);
    try {
      await api.deleteToolkit(deleteTarget.id);
      setDeleteTarget(null);
      setNotice(tr("Inventar-Eintrag gelöscht — beim nächsten Einbetten wird das ZIP wieder neu angelegt.", "Inventory entry deleted — the ZIP is recreated automatically on the next embed."));
      setRefreshKey((k) => k + 1);
    } catch (e) {
      setError(String(e));
    } finally {
      setDeleteBusy(false);
    }
  };

  const confirmPurge = async () => {
    setDeleteBusy(true);
    try {
      const n = await api.deleteUnusedToolkits();
      setPurgeOpen(false);
      setNotice(tr(`${n} ungenutzte Inventar-Einträge gelöscht.`, `${n} unused inventory entries deleted.`));
      setRefreshKey((k) => k + 1);
    } catch (e) {
      setError(String(e));
    } finally {
      setDeleteBusy(false);
    }
  };

  // Inventory: filter -> groups per toolkit abbreviation, versions descending (newest first)
  const invNeedle = invFilter.trim().toLowerCase();
  const invFiltered = (inventory ?? []).filter((t) => !invNeedle
    || [t.projectAcronym, t.projectName, t.version, t.sha256]
      .some((x) => x?.toLowerCase().includes(invNeedle)));
      // "outdated dependencies only": choose a toolkit parent and show only the selected
  // child ROWS limited to outdated edges (the child's row shows there its
  // own children - this makes the check visible recursively inward). The
  // row's nested array stays untouched so info badges ("▸ N
  // sub-toolkits", "M outdated") show the complete state.
  const invShown = invOutdatedOnly
    ? invFiltered.filter((t) => (t.nested ?? []).some((n) => n.childOutdated))
    : invFiltered;
  const invOutdatedTotal = (inventory ?? []).reduce(
    (n, t) => n + (t.nested ?? []).filter((n) => n.childOutdated).length, 0);
  // Unused = no usage reference in the currently loaded state; deletion is
  // server-side only without any reference (filter "only newest app version"
  // can make rows appear unused that older analyses still use).
  const invUnused = (inventory ?? []).filter((t) => t.usedBy === 0).length;
  const invGroups = new Map<string, ToolkitInventory[]>();
  for (const t of invShown) {
    const k = t.projectAcronym || t.projectName || t.id;
    invGroups.set(k, [...(invGroups.get(k) ?? []), t]);
  }
  const invGroupList = [...invGroups.entries()]
    .map(([key, items]) => ({
      key,
      items: [...items].sort((a, b) => (b.version ?? "").localeCompare(a.version ?? "", undefined, { numeric: true })),
    }))
    .sort((a, b) => a.key.localeCompare(b.key));

  // Explorer tree (B4.3): over the unfiltered inventory — id -> row,
  // roots = versions that are no child of another inventory edge.
  const invById = useMemo(() => {
    const m = new Map<string, ToolkitInventory>();
    (inventory ?? []).forEach((t) => m.set(t.id, t));
    return m;
  }, [inventory]);
  const invRoots = useMemo(() => {
    const childIds = new Set<string>();
    (inventory ?? []).forEach((t) =>
      (t.nested ?? []).forEach((n) => { if (invById.has(n.childToolkitId)) childIds.add(n.childToolkitId); }));
    const roots = (inventory ?? []).filter((t) => !childIds.has(t.id));
    // Cycle case: no root at all -> newest version per abbreviation as entry point
    if (roots.length === 0 && (inventory?.length ?? 0) > 0) {
      const latest = new Map<string, ToolkitInventory>();
      (inventory ?? []).forEach((t) => {
        const k = t.projectAcronym || t.projectName || t.id;
        const cur = latest.get(k);
        if (!cur || (t.version ?? "") > (cur.version ?? "")) latest.set(k, t);
      });
      return [...latest.values()];
    }
    return roots.sort((a, b) =>
      (a.projectAcronym ?? a.projectName ?? "").localeCompare(b.projectAcronym ?? b.projectName ?? ""));
  }, [inventory, invById]);

  return (
    <Stack>
      <Group justify="space-between">
        <Title order={2}>
          Toolkits
          <Badge size="md" color="violet" variant="light" ml={8}>
            {inventory ? tr(`${inventory.length} im Inventar`, `${inventory.length} in inventory`) : "…"}
          </Badge>
          {invOutdatedTotal > 0 && (
            <Badge size="md" color="red" variant="light" ml={4}
              title={tr("Unter-Toolkit-Kanten, die nicht die neueste im Inventar bekannte Version nutzen", "Sub-toolkit edges that do not use the newest version known in the inventory")}>
              {invOutdatedTotal} {invOutdatedTotal === 1 ? tr("veraltete Abhängigkeit", "outdated dependency") : tr("veraltete Abhängigkeiten", "outdated dependencies")}
            </Badge>
          )}
        </Title>
        <Button variant="light" leftSection={<IconCloudDownload size={16} />}
          onClick={() => setBpmOpen(true)}>
          {tr("Von BPM laden", "Load from BPM")}
        </Button>
      </Group>
      <BpmImportModal
        opened={bpmOpen}
        onClose={() => setBpmOpen(false)}
        toolkitOnly
      />
      <Dropzone
        onDrop={upload}
        accept={{ "application/zip": [".twx"] }}
        loading={uploading}
        maxFiles={1}
        mx="auto"
        maw={640}
      >
        <Dropzone.Idle>
          <Text size="sm" c="dimmed" ta="center">{tr("Toolkit-TWX hier ablegen", "Drop toolkit TWX here")}</Text>
        </Dropzone.Idle>
        <Dropzone.Accept>
          <Text size="sm" c="blue" fw={600} ta="center">{tr("Loslassen zum Hochladen", "Release to upload")}</Text>
        </Dropzone.Accept>
        <Group justify="center" p="xl">
          <IconFile size={36} />
          <Stack gap={0}>
            <Text fw={600}>{tr(".twx-Datei hochladen (Toolkit)", "Upload .twx file (toolkit)")}</Text>
            <Text size="sm" c="dimmed">
              {tr("Toolkit-Export wird geparst und analysiert — erkannt wird der Typ anhand des Manifests", "Toolkit export is parsed and analyzed — the type is detected from the manifest")}
            </Text>
            <Text size="xs" c="dimmed">
              {tr(
                "Erneuter Upload ersetzt die vorhandene Analyse automatisch (gleicher Dateiname bzw. Projekt + Version)",
                "Re-uploading automatically replaces the existing analysis (same file name or project + version)",
              )}
            </Text>
          </Stack>
        </Group>
      </Dropzone>

      {uploading && (
        <Group justify="center"><Loader size="sm" /> <Text c="dimmed">{tr("Analyse läuft…", "Analysis running…")}</Text></Group>
      )}
      {error && <Notification color="red" onClose={() => setError(null)}>{error}</Notification>}
      {notice && <Notification color="blue" onClose={() => setNotice(null)}>{notice}</Notification>}

      {/* Version conflicts (central): only apps whose graph actually has conflicts */}
      {(conflicts?.length ?? 0) > 0 && (
        <>
          <Title order={4}>
            <Group gap={6}>
              <IconAlertTriangle size={18} color="var(--mantine-color-red-6)" />
              {tr("Toolkit-Versionskonflikte", "Toolkit version conflicts")}
              <Badge size="md" color="red" variant="light">
                {conflicts!.reduce((n, a) => n + a.conflicts.length, 0)}
              </Badge>
            </Group>
          </Title>
          <Text size="xs" c="dimmed" mt={-8}>
            {tr("Dasselbe Toolkit-Kürzel erscheint im Abhängigkeitsgraph einer App in mehreren Versionen. Gezeigt werden nur Apps mit mindestens einem Konflikt — Details und Freigabe pro App in den Toolkit-Details.", "The same toolkit acronym appears in several versions within one app's dependency graph. Only apps with at least one conflict are shown — per-app details in the toolkit tab of the analysis.")}
          </Text>
          <Stack gap="xs">
            {conflicts!.map((a) => (
              <Paper withBorder p="sm" key={a.snapshotId}>
                <Group justify="space-between" wrap="wrap">
                  <Text
                    size="sm" fw={700} style={{ cursor: "pointer" }}
                    onClick={() => onOpen(a.snapshotId, "list")}
                  >
                    {a.app} <Text span size="xs" c="dimmed">{a.version}</Text>
                  </Text>
                  <Badge size="xs" color="red" variant="light">
                    {a.conflicts.length} {a.conflicts.length === 1 ? tr("Konflikt", "conflict") : tr("Konflikte", "conflicts")}
                  </Badge>
                </Group>
                <Stack gap={4} mt={6}>
                  {a.conflicts.map((c) => (
                    <div key={c.acronym}>
                      <Text size="xs" fw={600}>
                        {c.acronym}
                        {c.sameContent && (
                          <Text component="span" size="xs" c="dimmed">
                            {" "}{tr("(Varianten inhaltlich gleich — nur Versionsname weicht ab)", "(variants identical in content — only the version name differs)")}
                          </Text>
                        )}
                      </Text>
                      {c.uses.map((u) => (
                        <Group key={u.toolkitId} gap={6} wrap="nowrap">
                          <Text size="xs" c="dimmed" w={220}>
                            {tr("genutzt von", "used by")}: {u.usedBy.join(", ") || "?"}
                          </Text>
                          <Code style={{ fontSize: 11 }}>{u.version ?? "?"}</Code>
                          {!u.known && <Badge size="xs" color="gray" variant="light">{tr("ZIP fehlt", "ZIP missing")}</Badge>}
                        </Group>
                      ))}
                    </div>
                  ))}
                </Stack>
              </Paper>
            ))}
          </Stack>
        </>
      )}

      <Title order={4}>{tr("Analysierte Toolkit-Snapshots", "Analyzed toolkit snapshots")}</Title>
      <SnapshotList
        type="toolkit"
        refreshKey={refreshKey}
        onOpen={(id) => onOpen(id, "toolkits")}
        emptyText={tr("Noch keine Toolkit-Snapshots — oben eine Toolkit-.twx ablegen oder von BPM laden.", "No toolkit snapshots yet — drop a toolkit .twx above or load from BPM.")}
      />

      <Title order={4} mt="md">
        {tr("Globales Toolkit-Inventar", "Global toolkit inventory")}
      </Title>
      <Text size="xs" c="dimmed" mt={-8}>
        {tr("Alle jemals eingebetteten Toolkit-ZIPs, inhaltsbezogen dedupliziert (SHA-256); überlebt das Löschen von Snapshots und kann hier einzeln oder gesammelt („Alle ungenutzten löschen“) entfernt werden. Gruppiert pro Kürzel, darunter die Versionen. „Genutzt von“ = analysierte Snapshots, die diesen Toolkit einbinden. Unter-Toolkit-Kanten werden gegen die neueste im Inventar bekannte Version des Kürzels geprüft: „veraltet“ = eingebettetes ZIP ist älter, „evtl. veraltet“ = nur deklariert (ZIP nie eingebettet, Ist-Version unprüfbar).", "All toolkit ZIPs ever embedded, deduplicated by content (SHA-256); it survives snapshot deletion and can be removed here individually or in bulk (“Delete all unused”). Grouped per acronym, with the versions below. “Used by” = analyzed snapshots that embed this toolkit. Sub-toolkit edges are checked against the newest version known in the inventory for the acronym: “outdated” = the embedded ZIP is older, “possibly outdated” = declared only (ZIP never embedded, actual version unverifiable).")}
      </Text>
      <Group justify="space-between" wrap="wrap">
        <TextInput
          placeholder={tr("Inventar filtern (Kürzel, Name, Version, SHA)…", "Filter inventory (acronym, name, version, SHA)…")}
          value={invFilter}
          onChange={(e) => setInvFilter(e.currentTarget.value)}
          leftSection={<IconSearch size={16} />}
          maw={420}
          style={{ flex: 1 }}
        />
        <Switch
          label={tr("nur neuste App-Version", "newest app version only")}
          description={tr("„Genutzt von“ zählt dann je App nur deren neueste Analyse, nicht jede Version", "“Used by” then counts only each app's newest analysis, not every version")}
          checked={invLatestOnly}
          onChange={(e) => setInvLatestOnly(e.currentTarget.checked)}
        />
        <Switch
          label={tr("nur veraltete Abhängigkeiten", "outdated dependencies only")}
          description={tr("Zeigt Toolkits mit Unter-Toolkit-Kanten auf nicht-neueste Versionen – die Zeile eines jeden Unter-Toolkits zeigt dort wieder dessen eigene Kinder (rekursiv nach innen); Info-Badges zeigen weiter den vollständigen Stand", "Shows toolkits with sub-toolkit edges to non-newest versions – each sub-toolkit's row again shows its own children (recursively inward); info badges still show the complete state")}
          checked={invOutdatedOnly}
          onChange={(e) => setInvOutdatedOnly(e.currentTarget.checked)}
        />
        <Button size="xs" variant="light" color="red" leftSection={<IconTrash size={14} />}
          disabled={invUnused === 0}
          title={invLatestOnly
            ? tr("Zählung bitte ohne Filter „nur neuste App-Version“ ablesen – gelöscht wird serverseitig nur ohne jeglichen Nutzungsverweis", "Read the count without the “newest app version only” filter – the server deletes only entries without any usage reference")
            : tr(`${invUnused} Einträge ohne Nutzungsverweis`, `${invUnused} entries without usage reference`)}
          onClick={() => setPurgeOpen(true)}>
          {tr("Alle ungenutzten löschen", "Delete all unused")}{!invLatestOnly && invUnused > 0 ? ` (${invUnused})` : ""}
        </Button>
      </Group>
      <Table.ScrollContainer minWidth={800} type="native">
      <Table striped highlightOnHover withTableBorder>
        <Table.Thead>
          <Table.Tr>
            <Table.Th>{tr("Version", "Version")}</Table.Th>
            <Table.Th ta="right">{tr("Objekte", "Objects")}</Table.Th>
            <Table.Th ta="right" title={tr("Analysierte Snapshots, die diesen Toolkit einbinden", "Analyzed snapshots that embed this toolkit")}>{tr("Genutzt von", "Used by")}</Table.Th>
            <Table.Th>{tr("Inhalt (SHA-256)", "Content (SHA-256)")}</Table.Th>
            <Table.Th ta="right" w={56} title={tr("Aus dem Inventar löschen", "Remove from inventory")}>{tr("Aktionen", "Actions")}</Table.Th>
          </Table.Tr>
        </Table.Thead>
        <Table.Tbody>
          {invGroupList.map(({ key, items }) => (
            <Fragment key={key}>
              <Table.Tr>
                <Table.Td colSpan={5} fw={700} style={{ background: "light-dark(var(--mantine-color-gray-1), var(--mantine-color-dark-6))" }}>
                  <Group gap="xs" wrap="nowrap">
                    <Text fw={700} size="sm">{key}</Text>
                    {items[0].projectName && items[0].projectName !== key && (
                      <Text size="xs" c="dimmed" lineClamp={1}>{items[0].projectName}</Text>
                    )}
                    <Badge size="xs" variant="light" c="gray">{items.length} {items.length > 1 ? tr("Versionen", "versions") : tr("Version", "version")}</Badge>
                  </Group>
                </Table.Td>
              </Table.Tr>
              {items.map((t) => {
                const sha = t.sha256;
                const outdatedKids = (t.nested ?? []).filter((n) => n.childOutdated).length;
                return (
                  <Fragment key={t.id}>
                    <Table.Tr key={t.id}>
                      <Table.Td>
                        <Group gap={6} wrap="nowrap">
                          <Text size="sm">{t.version ?? "—"}</Text>
                          {t.version && t.version === items[0].version && (
                            <Badge size="xs" variant="light" color="violet">{tr("neueste", "latest")}</Badge>
                          )}
                          {t.inCycle && (
                            <Badge size="xs" variant="light" color="red" title={tr("Dieses Toolkit liegt in einem Abhängigkeitszyklus (bindet sich mittelbar selbst ein)", "This toolkit is in a dependency cycle (it indirectly embeds itself)")}>
                              {tr("Zyklus", "Cycle")}
                            </Badge>
                          )}
                          {(t.nested?.length ?? 0) > 0 && (
                            <Badge size="xs" variant="light" color="gray" title={tr("Enthält laut package.xml weitere Toolkits", "According to package.xml it contains further toolkits")}>
                              ▸ {t.nested!.length} {t.nested!.length === 1 ? tr("Unter-Toolkit", "sub-toolkit") : tr("Unter-Toolkits", "sub-toolkits")}
                            </Badge>
                          )}
                          {outdatedKids > 0 && (
                            <Badge size="xs" variant="light" color="red" title={tr("Mindestens ein Unter-Toolkit nutzt nicht die neueste im Inventar bekannte Version", "At least one sub-toolkit does not use the newest version known in the inventory")}>
                              {outdatedKids} {tr("veraltet", "outdated")}
                            </Badge>
                          )}
                        </Group>
                      </Table.Td>
                      <Table.Td ta="right">{t.objectCount}</Table.Td>
                      <Table.Td ta="right">{t.usedBy}</Table.Td>
                      <Table.Td>
                        {sha ? (
                          <CopyButton value={sha}>
                            {({ copied, copy }) => (
                              <Tooltip label={copied ? tr("kopiert ✔", "copied ✔") : sha}>
                                <Code onClick={copy} style={{ cursor: "pointer" }}>
                                  {sha.slice(0, 12)}
                                </Code>
                              </Tooltip>
                            )}
                          </CopyButton>
                        ) : "—"}
                      </Table.Td>
                      <Table.Td ta="right">
                        <Tooltip label={tr("Aus Inventar löschen", "Remove from inventory")}>
                          <Box component="span">
                            <ActionIcon size="sm" variant="subtle" color="red"
                              aria-label={tr(`Inventar-Eintrag ${t.projectAcronym ?? t.projectName ?? t.id} ${t.version ?? ""} löschen`, `Delete inventory entry ${t.projectAcronym ?? t.projectName ?? t.id} ${t.version ?? ""}`)}
                              onClick={() => setDeleteTarget(t)}>
                              <IconTrash size={14} />
                            </ActionIcon>
                          </Box>
                        </Tooltip>
                      </Table.Td>
                    </Table.Tr>
                    {(t.nested ?? []).filter((nd) => !invOutdatedOnly || nd.childOutdated).map((nd) => {
                      const mismatch = nd.known && nd.childVersionActual != null
                        && nd.childVersion != null && nd.childVersion !== nd.childVersionActual;
                      return (
                        <Table.Tr key={`${t.id}>${nd.childToolkitId}`} opacity={0.85}>
                          <Table.Td colSpan={5}>
                            <Group gap={6} wrap="nowrap" pl={22}>
                              <Text size="xs" c="dimmed">↳</Text>
                              <Text size="xs" fw={600}>{nd.childAcronym ?? nd.childName ?? nd.childToolkitId}</Text>
                              <Code style={{ fontSize: 11 }}>{nd.childVersion ?? "?"}</Code>
                              {!nd.known && (
                                <Badge size="xs" color="gray" variant="light" title={tr("ZIP dieses Toolkits war nie eingebettet – Version nur deklariert", "This toolkit's ZIP was never embedded – version only declared")}>
                                  {tr("nicht im Inventar", "not in inventory")}
                                </Badge>
                              )}
                              {nd.known && nd.childVersionActual != null && (
                                <Text size="xs" c="dimmed">{tr("ist:", "actual:")} {nd.childVersionActual}</Text>
                              )}
                              {mismatch && (
                                <Badge size="xs" color="red" variant="light" title={tr(`Dekariert ${nd.childVersion}, eingebettet ${nd.childVersionActual}`, `Declared ${nd.childVersion}, embedded ${nd.childVersionActual}`)}>
                                  {tr("falsche Version eingebettet", "wrong version embedded")}
                                </Badge>
                              )}
                              {nd.childOutdated && nd.known && (
                                <Badge size="xs" color="red" variant="filled" title={tr(`Im Inventar ist ${nd.childVersionLatest} die neueste Version dieses Toolkits`, `${nd.childVersionLatest} is the newest version of this toolkit in the inventory`)}>
                                  {tr("veraltet", "outdated")} – {tr("neuste", "latest")}: {nd.childVersionLatest}
                                </Badge>
                              )}
                              {nd.childOutdated && !nd.known && (
                                <Badge size="xs" color="orange" variant="light" title={tr(`Dekariert ${nd.childVersion}, neueste bekannte Version ${nd.childVersionLatest} – ZIP nie eingebettet, tatsächlicher Stand unprüfbar`, `Declared ${nd.childVersion}, newest known version ${nd.childVersionLatest} – ZIP never embedded, actual state unverifiable`)}>
                                  {tr("evtl. veraltet", "possibly outdated")} – {tr("neuste", "latest")}: {nd.childVersionLatest}
                                </Badge>
                              )}
                              {nd.childInCycle && (
                                <Badge size="xs" color="red" variant="outline" title={tr("Kind-Toolkit liegt in einem Abhängigkeitszyklus", "Child toolkit is in a dependency cycle")}>
                                  {tr("Zyklus", "Cycle")}
                                </Badge>
                              )}
                            </Group>
                          </Table.Td>
                        </Table.Tr>
                      );
                    })}
                  </Fragment>
                );
              })}
            </Fragment>
          ))}
          {inventory === null && (
            <Table.Tr><Table.Td colSpan={5}><Group gap="xs"><Loader size="sm" /><Text c="dimmed" size="sm">{tr("Inventar wird geladen…", "Loading inventory…")}</Text></Group></Table.Td></Table.Tr>
          )}
          {inventory?.length === 0 && (
            <Table.Tr>
              <Table.Td colSpan={5}>
                <Text c="dimmed" ta="center" py="md">{tr("Noch keine Toolkit-ZIPs gesehen.", "No toolkit ZIPs seen yet.")}</Text>
              </Table.Td>
            </Table.Tr>
          )}
        </Table.Tbody>
      </Table>
      </Table.ScrollContainer>

      <Title order={4} mt="md">
        <Group gap={6}>
          <IconSitemap size={18} /> {tr("Abhängigkeits-Explorer", "Dependency explorer")}
        </Group>
      </Title>
      <Text size="xs" c="dimmed" mt={-8}>
        {tr("Alle jemals eingebetteten ZIPs als Baum von den Wurzeln (von keinem anderen Toolkit eingebunden) nach innen — Versionen je Kürzel-Kante wie im Inventar. Zeigt die globale Lage über alle analysierten Snapshots hinweg; ↺ bricht Zyklen ab.", "All ZIPs ever embedded, as a tree from the roots (embedded by no other toolkit) inward — versions per acronym edge like in the inventory. Shows the global picture across all analyzed snapshots; ↺ cuts off cycles.")}
      </Text>
      {invRoots.length === 0 && inventory !== null && (
        <Text c="dimmed" size="sm">{tr("Keine einbindbaren Toolkits im Inventar.", "No embeddable toolkits in the inventory.")}</Text>
      )}
      {invRoots.map((root) => (
        <Tree key={root.id} item={root} byId={invById} seen={new Set()} depth={0} />
      ))}

      <Modal opened={deleteTarget !== null} onClose={() => setDeleteTarget(null)}
        title={tr("Inventar-Eintrag löschen?", "Delete inventory entry?")} size="md">
        {deleteTarget && (
          <Stack gap="sm">
            <Text size="sm">
              <b>{deleteTarget.projectAcronym || deleteTarget.projectName || deleteTarget.id}</b>{" "}
              <Code>{deleteTarget.version ?? "?"}</Code>{" "}
              {tr("wird aus dem globalen Inventar entfernt.", "is being removed from the global inventory.")}
            </Text>
            {deleteTarget.usedBy > 0 && (
              <Notification color="yellow" withCloseButton={false}>
                {tr(`Wird aktuell von ${deleteTarget.usedBy} analysierten Snapshot(s) eingebunden — deren Analysen bleiben unverändert, die Nutzungszählung für diesen Eintrag entfällt.`, `Currently embedded by ${deleteTarget.usedBy} analyzed snapshot(s) — their analyses remain unchanged; the usage count for this entry is dropped.`)}
              </Notification>
            )}
            <Text size="xs" c="dimmed">
              {tr("Eigene Unter-Toolkit-Kanten dieses ZIPs entfallen; Kind-Kanten anderer Toolkits bleiben als Deklaration („nicht im Inventar“) erhalten. Beim nächsten Einbetten (Upload/BPM-Import) wird der Eintrag regulär neu angelegt.", "This ZIP's own sub-toolkit edges are dropped; child edges of other toolkits remain as declarations (“not in inventory”). On the next embed (upload/BPM import) the entry is recreated normally.")}
            </Text>
            <Group justify="right">
              <Button variant="subtle" onClick={() => setDeleteTarget(null)}>{tr("Abbrechen", "Cancel")}</Button>
              <Button color="red" loading={deleteBusy} onClick={confirmDelete}>{tr("Löschen", "Delete")}</Button>
            </Group>
          </Stack>
        )}
      </Modal>

      <Modal opened={purgeOpen} onClose={() => setPurgeOpen(false)}
        title={tr("Alle ungenutzten Inventar-Einträge löschen?", "Delete all unused inventory entries?")} size="md">
        <Stack gap="sm">
          <Text size="sm">
            {tr("Entfernt alle Inventar-Einträge, die von", "Removes all inventory entries that")} <b>{tr("keinem", "no")}</b>{" "}
            {tr("analysierten Snapshot eingebunden sind", "analyzed snapshot embeds")}{" "}
            {tr(`(${invUnused} laut aktueller Anzeige`, `(${invUnused} per current display`)}
            {invLatestOnly ? tr(" — maßgeblich ist die serverseitige Zählung ohne Filter „nur neuste App-Version“", " — the authoritative figure is the server-side count without the “newest app version only” filter") : ""}
            ).
          </Text>
          <Text size="xs" c="dimmed">
            {tr("Typischer Aufräumen-Fall nach dem Löschen von Snapshots. Genutzte Einträge bleiben unberührt; ein erneut eingebettetes ZIP wird beim nächsten Ingest wieder angelegt.", "Typical cleanup case after deleting snapshots. Used entries remain untouched; a re-embedded ZIP is recreated on the next ingest.")}
          </Text>
          <Group justify="right">
            <Button variant="subtle" onClick={() => setPurgeOpen(false)}>{tr("Abbrechen", "Cancel")}</Button>
            <Button color="red" loading={deleteBusy} onClick={confirmPurge}>{tr("Löschen", "Delete")}</Button>
          </Group>
        </Stack>
      </Modal>
    </Stack>
  );
}

/** A node of the dependency tree; children from the global inventory (byId). */
function Tree({ item, byId, seen, depth }: {
  item: ToolkitInventory;
  byId: Map<string, ToolkitInventory>;
  seen: Set<string>;
  depth: number;
}) {
  const { tr } = useLang();
  const kids = (item.nested ?? [])
    .map((nd) => ({ nd, child: byId.get(nd.childToolkitId) }))
    .filter((k) => k.child != null)
    .sort((a, b) =>
      (a.nd.childAcronym ?? a.nd.childName ?? "").localeCompare(b.nd.childAcronym ?? b.nd.childName ?? ""));
  const outdatedKids = (item.nested ?? []).filter((n) => n.childOutdated).length;
  return (
    <>
      <Group gap={6} wrap="nowrap" pl={depth * 18} py={1}>
        <Text size="sm" fw={depth === 0 ? 700 : undefined}>
          {depth > 0 ? "↳ " : ""}{item.projectAcronym ?? item.projectName ?? item.id}
        </Text>
        <Code style={{ fontSize: 11 }}>{item.version ?? "?"}</Code>
        {item.inCycle && (
          <Badge size="xs" color="red" variant="light">{tr("Zyklus", "Cycle")}</Badge>
        )}
        {outdatedKids > 0 && (
          <Badge size="xs" color="red" variant="outline" title={tr(`${outdatedKids} Unter-Toolkit(s) nicht auf neuster Version`, `${outdatedKids} sub-toolkit(s) not on the newest version`)}>
            {outdatedKids} {tr("veraltet", "outdated")}
          </Badge>
        )}
      </Group>
      {kids.map(({ nd, child }) => {
        const c = child!;
        if (seen.has(c.id)) {
          return (
            <Group key={`${item.id}>${c.id}`} gap={6} wrap="nowrap" pl={(depth + 1) * 18} opacity={0.6}>
              <Text size="xs" c="dimmed">
                ↺ {nd.childAcronym ?? nd.childName ?? c.projectAcronym} ({c.version ?? "?"}) — {tr("Zyklus, hier nicht weiter ausgefaltet", "cycle, not expanded further here")}
              </Text>
            </Group>
          );
        }
        return <Tree key={`${item.id}>${c.id}`} item={c} byId={byId} seen={new Set([...seen, c.id])} depth={depth + 1} />;
      })}
    </>
  );
}
