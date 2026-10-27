import { useState } from "react";
import {
  Button, Group, Loader, Notification, Stack, Text, Title
} from "@mantine/core";
import { IconCloudDownload, IconFile } from "@tabler/icons-react";
import { Dropzone } from "@mantine/dropzone";
import type { DropzoneProps } from "@mantine/dropzone";
import { api } from "../api";
import { backendError, useLang } from "../i18n";
import BpmImportModal from "./BpmImportModal";
import SnapshotList from "../components/SnapshotList";

/** Process apps (toolkits have their own page). */
export default function SnapshotsPage({
  onOpen,
}: {
  onOpen: (id: number, from?: "list" | "toolkits") => void;
}) {
  const [uploading, setUploading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [bpmOpen, setBpmOpen] = useState(false);
  const { tr } = useLang();

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
      setNotice(tr(
        `${files.length} Datei${files.length > 1 ? "en" : ""} eingereiht – Fortschritt läuft im Banner oben, mehrere Analysen parallel.`,
        `${files.length} file${files.length > 1 ? "s" : ""} queued – progress shown in the banner above, analyses run in parallel.`,
      ));
    } catch (e) {
      setError(String(e));
    } finally {
      setUploading(false);
    }
  };

  return (
    <Stack>
      <Group justify="space-between">
        <Title order={2}>{tr("Prozess-Apps", "Process Apps")}</Title>
        <Button variant="light" leftSection={<IconCloudDownload size={16} />}
          onClick={() => setBpmOpen(true)}>
          {tr("Von BPM laden", "Load from BPM")}
        </Button>
      </Group>
      <BpmImportModal
        opened={bpmOpen}
        onClose={() => setBpmOpen(false)}
      />
      <Dropzone
        onDrop={upload}
        accept={{ "application/zip": [".twx"] }}
        loading={uploading}
        maxFiles={5}
        mx="auto"
        maw={640}
      >
        <Dropzone.Idle>
          <Text size="sm" c="dimmed" ta="center">{tr("TWX hier ablegen", "Drop TWX files here")}</Text>
        </Dropzone.Idle>
        <Dropzone.Accept>
          <Text size="sm" c="blue" fw={600} ta="center">{tr("Loslassen zum Hochladen", "Release to upload")}</Text>
        </Dropzone.Accept>
        <Group justify="center" p="xl">
          <IconFile size={36} />
          <Stack gap={0}>
            <Text fw={600}>{tr(".twx-Datei hochladen (Prozess-App)", "Upload .twx file (process app)")}</Text>
            <Text size="sm" c="dimmed">
              {tr(
                "Process-Designer-Export wird geparst und sofort analysiert (Regelkatalog R-KAT-nnn) — Toolkit-Exporte bitte auf der Toolkits-Seite ablegen",
                "Process Designer exports are parsed and analysed immediately (rule catalog R-KAT-nnn) — please drop toolkit exports on the Toolkits page",
              )}
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
        <Group justify="center"><Loader size="sm" /> <Text c="dimmed">{tr("Analyse läuft…", "Analysis in progress…")}</Text></Group>
      )}
      {error && <Notification color="red" onClose={() => setError(null)}>{error}</Notification>}
      {notice && <Notification color="violet" onClose={() => setNotice(null)}>{notice}</Notification>}

      <SnapshotList
        type="app"
        refreshKey={0}
        onOpen={(id) => onOpen(id, "list")}
        emptyText={tr("Noch keine Prozess-Apps — oben eine .twx ablegen oder von BPM laden.", "No process apps yet — drop a .twx above or load from BPM.")}
      />
    </Stack>
  );
}
