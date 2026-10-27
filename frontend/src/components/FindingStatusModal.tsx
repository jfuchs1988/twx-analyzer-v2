import { useEffect, useState } from "react";
import {
  Alert, Button, Group, Modal, SegmentedControl, Stack, Text, TextInput,
} from "@mantine/core";
import { api, FINDING_STATUSES, Finding, FindingStatus, STATUS_LABEL } from "../api";
import { useLang } from "../i18n";

const STATUS_EN: Record<FindingStatus, string> = {
  OPEN: "open", RESOLVED: "resolved", ACCEPTED: "accepted",
};

export interface BulkTarget {
  snapshotId: number;
  /** describes which findings are meant (e.g. "the 37 filtered findings"). */
  label: string;
  severity?: string;
  rule?: string;
  objectId?: string;
  needsReview?: boolean;
}

/**
 * B5: team status per finding — individually or as a bulk action on the filtered list.
 * "Resolved" = problem fixed; "Accepted" = deliberately tolerated (drops out of
 * health/gate/dashboard); "Open" = reset.
 */
export default function FindingStatusModal({ finding, bulk, onClose, onSaved }: {
  finding?: Finding | null;
  bulk?: BulkTarget | null;
  onClose: () => void;
  onSaved: (changed: number) => void;
}) {
  const { tr, pick } = useLang();
  const [status, setStatus] = useState<FindingStatus>("RESOLVED");
  const [comment, setComment] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (finding || bulk) {
      setStatus(finding?.status && finding.status !== "OPEN" ? finding.status : "RESOLVED");
      setComment(finding?.statusComment ?? "");
      setError(null);
    }
  }, [finding, bulk]);

  const save = async () => {
    setBusy(true);
    setError(null);
    try {
      if (finding) {
        const updated = await api.setFindingStatus(finding.id, status, comment || undefined);
        onSaved(1);
        void updated;
      } else if (bulk) {
        const r = await api.setBulkStatus(bulk.snapshotId, status, {
          severity: bulk.severity, rule: bulk.rule, objectId: bulk.objectId,
          needsReview: bulk.needsReview, comment: comment || undefined,
        });
        onSaved(r.changed);
      }
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setBusy(false);
    }
  };

  return (
    <Modal
      opened={!!finding || !!bulk}
      onClose={onClose}
      size="md"
      title={finding ? tr(`Status: ${finding.ruleId}`, `Status: ${finding.ruleId}`) : tr("Status ändern (Massenaktion)", "Change status (bulk action)")}
    >
      <Stack gap="sm">
        {finding && (
          <Text size="sm" c="dimmed" lineClamp={3}>
            {finding.objectName ?? ""}{finding.location ? ` — ${finding.location}` : ""}: {pick(finding.message, finding.messageEn)}
          </Text>
        )}
        {bulk && <Text size="sm" c="dimmed">{tr(`Betroffen: ${bulk.label}`, `Affected: ${bulk.label}`)}</Text>}
        <SegmentedControl
          fullWidth
          value={status}
          onChange={(v) => setStatus(v as FindingStatus)}
          data={FINDING_STATUSES.map((s) => ({ value: s, label: tr(STATUS_LABEL[s], STATUS_EN[s]) }))}
        />
        <TextInput
          label={tr("Kommentar (optional)", "Comment (optional)")}
          placeholder={tr("z. B. geprüft, unkritisch, Ticket #123", "e.g. reviewed, harmless, ticket #123")}
          value={comment}
          onChange={(e) => setComment(e.currentTarget.value)}
          maxLength={1000}
        />
        {status === "ACCEPTED" && (
          <Alert color="gray" withCloseButton={false}>
            {tr("Akzeptierte Befunde zählen nicht mehr in Health-Score, Gate, Dashboard und Export (Standard) hinein.", "Accepted findings no longer count toward the health score, gate, dashboard or (default) export.")}
          </Alert>
        )}
        {error && <Alert color="red">{error}</Alert>}
        <Group justify="right">
          <Button variant="default" onClick={onClose}>{tr("Abbrechen", "Cancel")}</Button>
          <Button onClick={save} loading={busy}>{tr("Speichern", "Save")}</Button>
        </Group>
      </Stack>
    </Modal>
  );
}
