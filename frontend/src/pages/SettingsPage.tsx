import { useEffect, useState } from "react";
import {
  Badge, Button, Code, Divider, Group, Loader, Notification, Paper, PasswordInput, Stack, Text, TextInput, Title
} from "@mantine/core";
import { api } from "../api";
import { useDesign } from "../design";
import { backendError, useLang } from "../i18n";

/** BPM access: base URL, user, password (never returned, ADR-010). */
export default function SettingsPage() {
  const { tr } = useLang();
  const [baseUrl, setBaseUrl] = useState("");
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [passwordSet, setPasswordSet] = useState(false);
  const [caFile, setCaFile] = useState("");
  const [busy, setBusy] = useState(false);
  const [msg, setMsg] = useState<{ color: string; text: string } | null>(null);

  useEffect(() => {
    api.bpmSettings()
      .then((s) => {
        setBaseUrl(s.baseUrl ?? "");
        setUsername(s.username ?? "");
        setPasswordSet(s.passwordSet);
        setCaFile(s.caFile ?? "");
      })
      .catch((e) => setMsg({ color: "red", text: String(e) }));
  }, []);

  const save = async (silent = false): Promise<boolean> => {
    setBusy(true);
    setMsg(null);
    try {
      const r = await api.bpmSaveSettings({
        baseUrl, username,
        password: password === "" ? undefined : password,
        caFile,
      });
      const body = await r.json();
      if (!r.ok) throw new Error(backendError(body?.error) ?? `HTTP ${r.status}`);
      setPassword("");
      setPasswordSet(body.passwordSet);
      setBaseUrl(body.baseUrl ?? "");
      setUsername(body.username ?? "");
      setCaFile(body.caFile ?? "");
      if (!silent) setMsg({ color: "green", text: tr("Gespeichert.", "Saved.") });
      return true;
    } catch (e) {
      setMsg({ color: "red", text: String(e) });
      return false;
    } finally {
      setBusy(false);
    }
  };

  const test = async () => {
    // save first so the test uses the currently visible values
    if (!(await save(true))) return;
    setBusy(true);
    setMsg(null);
    try {
      const r = await api.bpmTest();
      const body = await r.json();
      if (!r.ok || body.ok !== true) {
        throw new Error(backendError(body?.error) ?? backendError(body?.message) ?? `HTTP ${r.status}`);
      }
      setMsg({ color: "green", text: tr(`Verbindung OK – ${body.message}`, `Connection OK – ${body.message}`) });
    } catch (e) {
      setMsg({ color: "red", text: String(e) });
    } finally {
      setBusy(false);
    }
  };

  return (
    <Stack maw={560}>
      <Title order={2}>Settings</Title>
      <Text c="dimmed" size="sm">
        {tr("BPM-Direktimport: TWX Analyzer v2 nutzt ausschließlich lesende Endpunkte der BAW Operations-REST-Schnittstelle (",
            "BPM direct import: TWX Analyzer v2 uses read-only endpoints of the BAW Operations REST API (")}
        <Code>GET /ops/std/bpm/…</Code>
        {tr("). Das Passwort wird nur gespeichert (verschlüsselt, sofern ein Master-Secret konfiguriert ist) und nie über die API zurückgegeben.",
            "). The password is only stored (encrypted when a master secret is configured) and never returned via the API.")}
      </Text>

      {msg && (
        <Notification color={msg.color} onClose={() => setMsg(null)}>{msg.text}</Notification>
      )}

      <TextInput
        label={tr("Basis-URL", "Base URL")}
        description={tr("Kontextwurzel der BPM-Instanz, z. B. https://bpm.example.dir/BPM",
                        "Context root of the BPM instance, e.g. https://bpm.example.dir/BPM")}
        value={baseUrl}
        onChange={(e) => setBaseUrl(e.currentTarget.value)}
      />
      <TextInput
        label={tr("Benutzer", "Username")}
        value={username}
        onChange={(e) => setUsername(e.currentTarget.value)}
        autoComplete="username"
      />
      <PasswordInput
        label={tr("Passwort", "Password")}
        placeholder={passwordSet
          ? tr("gesetzt – zum Ändern neues eingeben", "set — enter a new password to change it")
          : tr("BPM-Passwort", "BPM password")}
        value={password}
        onChange={(e) => setPassword(e.currentTarget.value)}
        autoComplete="new-password"
      />
      <TextInput
        label={tr("CA-Bundle (PEM-Datei)", "CA bundle (PEM file)")}
        description={tr(
          "Pfad zu einer PEM-Datei mit den Unternehmens-Zertifikaten für die BPM-TLS-Verbindung; leer = nur System-Zertifizierungsstellen.",
          "Path to a PEM file with your organisation's certificates for the BPM TLS connection; empty = system CAs only."
        )}
        value={caFile}
        onChange={(e) => setCaFile(e.currentTarget.value)}
        placeholder="/app/certs/custom-ca.pem"
      />

      <Group>
        <Button onClick={() => save()} loading={busy}>{tr("Speichern", "Save")}</Button>
        <Button variant="outline" onClick={test} disabled={busy}>{tr("Speichern & testen", "Save & test")}</Button>
        {busy && <Loader size="sm" />}
      </Group>

      <Divider my="md" />
      <DesignSection />
      <Divider my="md" />
      <LanguageSection />
    </Stack>
  );
}

/** Shows the active built-in application palette. */
function DesignSection() {
  const { design } = useDesign();
  const { tr } = useLang();
  const swatches = [
    { label: tr("Papier", "Paper"), color: design.paper },
    { label: tr("Anthrazit", "Charcoal"), color: design.headerBg },
    { label: tr("Orange", "Orange"), color: design.accent },
  ];

  return (
    <Stack gap="xs">
      <Group justify="space-between">
        <Title order={3}>{tr("Design", "Design")}</Title>
        <Badge color="brand" variant="light">{design.name}</Badge>
      </Group>
      <Text c="dimmed" size="sm">
        {tr(
          "Farbschema inspiriert von jfuchs.de: warmes Papier, Anthrazit und Orange.",
          "Color palette inspired by jfuchs.de: warm paper, charcoal and orange."
        )}
      </Text>
      <Group gap="sm">
        {swatches.map((swatch) => (
          <Group key={swatch.label} gap={6}>
            <span
              aria-hidden="true"
              style={{ width: 16, height: 16, borderRadius: 4, background: swatch.color, border: "1px solid var(--mantine-color-default-border)" }}
            />
            <Text size="xs">{swatch.label}</Text>
          </Group>
        ))}
      </Group>
    </Stack>
  );
}

/** Switchable UI language (persisted per browser in localStorage). */
function LanguageSection() {
  const { lang, setLang, tr } = useLang();
  const langs = [
    { id: "de" as const, label: "Deutsch", desc: tr("Deutsche Beschriftungen und Meldungen", "German labels and messages") },
    { id: "en" as const, label: "English", desc: tr("Englische Beschriftungen; englische Regel-/Befundtexte, soweit hinterlegt", "English labels; English rule/finding texts where available") },
  ];
  return (
    <>
      <Title order={3}>{tr("Sprache", "Language")}</Title>
      <Text c="dimmed" size="sm">
        {tr("Sprache der Benutzeroberfläche — die Auswahl gilt nur für diesen Browser.",
            "User interface language — the selection applies to this browser only.")}
      </Text>
      <Group grow align="stretch">
        {langs.map((l) => {
          const active = l.id === lang;
          return (
            <Paper
              key={l.id}
              withBorder
              radius="sm"
              p="md"
              onClick={() => setLang(l.id)}
              style={{
                cursor: "pointer",
                borderColor: active ? "var(--mantine-primary-color-filled)" : undefined,
                borderWidth: active ? 2 : 1,
              }}
              role="button"
              aria-pressed={active}
            >
              <Group justify="space-between" wrap="nowrap">
                <Text fw={600} size="sm">{l.label}</Text>
                {active && <Badge color="brand" variant="light" size="sm">{tr("aktiv", "active")}</Badge>}
              </Group>
              <Text c="dimmed" size="xs" mt={4}>{l.desc}</Text>
            </Paper>
          );
        })}
      </Group>
    </>
  );
}
