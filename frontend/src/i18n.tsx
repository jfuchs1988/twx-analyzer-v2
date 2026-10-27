/* eslint-disable react-refresh/only-export-components -- provider + hook +
   helpers in one module is the intended context pattern (rule is an HMR hint) */
import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from "react";

/**
 * Switchable UI language (header toggle + settings page): "en" (default)
 * or "de". The choice persists per browser in localStorage. Labels are maintained
 * inline bilingually: tr("German label", "English label"). Backend fields
 * (rule titles, finding messages) arrive bilingual from the server; pick()
 * chooses the language and falls back to German when EN is missing (legacy data).
 */
export type Lang = "de" | "en";

const STORAGE_KEY = "twxav2.lang";

export function loadLang(): Lang {
  try {
    return localStorage.getItem(STORAGE_KEY) === "de" ? "de" : "en";
  } catch {
    return "en";
  }
}

interface LangCtx {
  lang: Lang;
  setLang: (l: Lang) => void;
  /** Label in the active language (inline German/English pair). */
  tr: (de: string, en: string) => string;
  /** Bilingual backend field: EN when present and language is EN, otherwise DE. */
  pick: (de: string | null | undefined, en: string | null | undefined) => string;
}

const Ctx = createContext<LangCtx>({
  lang: "en",
  setLang: () => {},
  tr: (_de, en) => en,
  pick: (de, en) => (en ?? de ?? ""),
});

export const useLang = () => useContext(Ctx);

const DETAIL_TEXT_DE: Record<string, string> = {
  "Declarations (tw.local)": "Deklarationen (tw.local)",
  "Default value": "Vorgabewert",
  Steps: "Schritte",
  Component: "Komponente",
  Transitions: "Übergänge",
  Overview: "Übersicht",
  Property: "Eigenschaft",
  Value: "Wert",
  "Process type": "Prozess-Typ",
  "Pre/post scripts": "Pre/Post-Skripte",
  Declarations: "Deklarationen",
  "Flow objects": "Flow-Objekte",
  "Searchable Fields": "Durchsuchbare Felder",
  "Multi-instance": "Multi-Instanz",
  yes: "ja",
  on: "an",
  off: "aus",
  Type: "Typ",
  Hidden: "versteckt",
  "Type summary": "Typ-Art",
  Kind: "Art",
  "Wrapper (message)": "Wrapper (Nachricht)",
  "Import (service spec)": "Import (Service-Spec)",
  "Hand-authored": "handgemacht",
  "Root documentation": "Root-Doku",
  none: "keine",
  present: "vorhanden",
  "Attributes (flattened)": "Attribute (flatten)",
  Attributes: "Attribute",
  Documentation: "Doku",
  "Config options": "Config-Optionen",
  Note: "Hinweis",
  missing: "fehlt",
  "Binding types": "Binding-Typen",
  Members: "Mitglieder",
  "Individual user": "Einzelnutzer",
  Group: "Gruppe",
  "Script blocks": "Skriptblöcke",
  Location: "Ort",
  Lines: "Zeilen",
};

/** Localize static object-detail labels returned by the backend. */
export function localizeDetailText(text: string, lang: Lang): string {
  if (lang !== "de") return text;
  const exact = DETAIL_TEXT_DE[text];
  if (exact) return exact;
  return text
    .replace(/^Script block\b/, "Skriptblock")
    .replace(/^File$/, "Datei")
    .replace(/^BPMN-JSON Pre-script\b/, "BPMN-JSON Pre-Skript")
    .replace(/^BPMN-JSON Post-script\b/, "BPMN-JSON Post-Skript")
    .replace(/^BPMN-JSON Script\b/, "BPMN-JSON Skript")
    .replace(/^BPMN-JSON Condition\b/, "BPMN-JSON Bedingung")
    .replace(/^BPMN-JSON Transition script\b/, "BPMN-JSON Übergangs-Skript");
}

export function formatNumber(value: number, lang: Lang): string {
  return value.toLocaleString(lang === "de" ? "de-DE" : "en-US");
}

const DIFF_NOTE_DE: [RegExp, string][] = [
  [/^Contents are identical\.$/, "Inhalte sind identisch."],
  [/^Diff limited to the first (\d+) lines of the changed region\.$/, "Diff auf die ersten $1 Zeilen des Änderungsbereichs begrenzt."],
  [/^Too many changes for a line-by-line comparison; the changed region was replaced entirely\.$/, "Zu viele Änderungen für einen zeilenweisen Vergleich; der Änderungsbereich wurde vollständig ersetzt."],
  [/^Content truncated here \(maximum (\d+) lines\)$/, "Inhalt hier abgeschnitten (maximal $1 Zeilen)"],
  [/^(\d+) identical lines omitted$/, "$1 identische Zeilen ausgeblendet"],
  [/^Diff truncated here \(maximum (\d+) lines\)(?: \(plus (\d+) identical context lines omitted\))?$/, "Diff hier abgeschnitten (maximal $1 Zeilen)$2"],
  [/^No comparable content: both object versions exceed the 1 MB storage limit \(large object\)\.$/, "Kein Inhalt vergleichbar: Beide Objektversionen überschreiten das 1-MB-Speicherlimit (großes Objekt)."],
  [/^Object exists only in target \(new\)\.$/, "Objekt existiert nur im Ziel (neu)."],
  [/^Object exists only in base \(removed\)\.$/, "Objekt existiert nur in der Basis (entfernt)."],
  [/^Diff unavailable: at least one version exceeds the 1 MB storage limit \(large object\)\.$/, "Diff nicht verfügbar: Mindestens eine Version überschreitet das 1-MB-Speicherlimit (großes Objekt)."],
];

export function localizeDiffNote(note: string, lang: Lang): string {
  if (lang !== "de") return note;
  for (const [pattern, translation] of DIFF_NOTE_DE) {
    const match = note.match(pattern);
    if (match) return translation.replace(/\$(\d+)/g, (_, n: string) => match[Number(n)] ?? "");
  }
  return note;
}

/** Holds the language state; wraps the whole app (main.tsx, inside DesignProvider). */
export function LangProvider({ children }: { children: ReactNode }) {
  const [lang, setLangId] = useState<Lang>(loadLang);
  // keep <html lang> in sync always (including first load from localStorage —
  // a pure setLang-Sync only takes effect after the first toggle, not after reload).
  useEffect(() => {
    document.documentElement.lang = lang;
  }, [lang]);
  const setLang = useCallback((l: Lang) => {
    setLangId(l);
    try { localStorage.setItem(STORAGE_KEY, l); } catch { /* private mode */ }
    document.documentElement.lang = l;
  }, []);
  // tr/pick are referenced in effect dependency arrays across the app, so they
  // must keep a stable identity between renders (change only with lang).
  const tr = useCallback((de: string, en: string) => (lang === "en" ? en : de), [lang]);
  const pick = useCallback((de: string | null | undefined, en: string | null | undefined) =>
    lang === "en" && en != null && en !== "" ? en : (de ?? en ?? ""), [lang]);
  const value = useMemo(() => ({ lang, setLang, tr, pick }), [lang, setLang, tr, pick]);
  return (
    <Ctx.Provider value={value}>
      {children}
    </Ctx.Provider>
  );
}

/**
 * Translate German backend error messages (API errors, BpmException, ingest errors)
 * into English when language EN is active. Pure function without a hook —
 * works in api.ts too (reads the language via localStorage like at startup).
 * Dynamic parts are kept as $1..$3; unknown texts pass through.
 */
const BACKEND_ERRORS: [RegExp, string][] = [
  [/^API-Token fehlt oder ungueltig$/, "API token missing or invalid"],
  [/^Ungueltige Basis-URL \((.*)\)$/, "Invalid base URL ($1)"],
  [/^Ungueltige Basis-URL: (.*)$/, "Invalid base URL: $1"],
  [/^Loopback\/Link-Local ist nicht erlaubt$/, "loopback/link-local addresses are not allowed"],
  [/^Syntax: (.*)$/, "syntax: $1"],
  [/^Parameter acronym fehlt$/, "Parameter 'acronym' missing"],
  [/^Bitte Basis-URL, Benutzer und Passwort in den Settings hinterlegen$/,
    "Please configure base URL, user and password in Settings first"],
  [/^BPM-Zugang nicht konfiguriert – bitte zuerst die Settings ausfuellen$/,
    "BPM access not configured — please fill in the Settings first"],
  [/^Zwei verschiedene Snapshots als base und target noetig$/,
    "Two different snapshots are required as base and target"],
  [/^Snapshot #(\d+) nicht gefunden$/, "Snapshot #$1 not found"],
  [/^Snapshot nicht gefunden$/, "Snapshot not found"],
  [/^Snapshot #(\d+) ist nicht fertig analysiert \(Status .(.+).\)$/,
    "Snapshot #$1 has not finished analyzing (status '$2')"],
  [/^Gespeicherte Datei zu Snapshot #(\d+) fehlt — TWX erneut hochladen$/,
    "Stored file for snapshot #$1 is missing — please re-upload the TWX"],
  [/^Objekt in beiden Snapshots nicht gefunden$/, "Object not found in either snapshot"],
  [/^Objekt nicht gefunden(?:: (.*))?$/, "Object not found$1"],
  [/^Befund nicht gefunden$/, "Finding not found"],
  [/^Keine Datei gesendet \(Feld 'file'\)$/, "No file sent (field 'file')"],
  [/^Dateiname fehlt$/, "File name missing"],
  [/^Unbekannte Schwere: (.*)$/, "Unknown severity: $1"],
  [/^Unbekannter Status: (.*)$/, "Unknown status: $1"],
  [/^Ausschluss nicht gefunden$/, "Exclusion not found"],
  [/^Toolkit-Eintrag nicht gefunden$/, "Toolkit entry not found"],
  [/^Datei „(.*)“ wird gerade als Snapshot #(\d+) eingelesen$/, "File \"$1\" is currently being ingested as snapshot #$2"],
  [/^Datei „(.*)“ liegt bereits in der Warteschlange$/, "File \"$1\" is already queued for import"],
  [/^package\.xml ueber (\d+) kB$/, "package.xml exceeds $1 kB"],
  [/^Toolkit (.*): entpackt ueber (\d+) MB — Datei abgelehnen$/, "Toolkit $1: expanded size exceeds $2 MB — file rejected"],
  [/^Toolkit-ZIP nicht lesbar: (.*)$/, "Toolkit ZIP is unreadable: $1"],
  [/^XML nicht lesbar: (.*)$/, "XML is unreadable: $1"],
  [/^impact fuer (.*) muss 1\.\.3 sein$/, "Impact for $1 must be between 1 and 3"],
  [/^cap fuer (.*) darf nicht negativ sein$/, "Cap for $1 must not be negative"],
  [/^Unbekannter [Ss]tatus: (.*)$/, "Unknown status: $1"],
  [/^Unbekannter Scope\. Erlaubt: (.*)$/, "Unknown scope. Allowed: $1"],
  [/^Regex-Teilmenge: Lookaround .* ist nicht erlaubt\.$/, "Regex subset: lookaround constructs are not allowed."],
  [/^Regex-Teilmenge: Rueckwaertsreferenzen .* sind nicht erlaubt\.$/, "Regex subset: backreferences are not allowed."],
  [/^Regex-Teilmenge: wiederholte Gruppen .* sind nicht erlaubt\.$/, "Regex subset: nested/repeated groups of this form are not allowed."],
  [/^.* liegt bereits in der Warteschlange$/, "This import is already queued"],
  [/^Kein META-INF\/package.xml - keine gueltige TWX-Datei$/,
    "No META-INF/package.xml - not a valid TWX file"],
  [/^Toolkit (.*): entpackt ueber (.*)$/, "Toolkit $1: unpacked exceeds $2"],
  [/^Export fehlgeschlagen \(HTTP (\d+)\): (.*)$/, "Export failed (HTTP $1): $2"],
  [/^Export zu gross \(> 2 GB\) — Abbruch$/, "Export too large (> 2 GB) — aborted"],
  [/^Download-Schreibfehler: (.*)$/, "Download write error: $1"],
  [/^Unerwartete Antwort \(kein JSON\): (.*)$/, "Unexpected response (not JSON): $1"],
  [/^BPM nicht erreichbar: (.*)$/, "BPM not reachable: $1"],
  [/^Download unterbrochen$/, "Download interrupted"],
  [/^BPM-Login fehlgeschlagen \(HTTP (\d+)\)(.*)$/, "BPM login failed (HTTP $1)$2"],
  [/^BPM-Login ohne csrf_token: (.*)$/, "BPM login without csrf_token: $1"],
  [/^BPM-Login nicht moeglich: (.*)$/, "BPM login not possible: $1"],
  [/^BPM-Login unterbrochen$/, "BPM login interrupted"],
  [/^TLS-Aufbau fehlgeschlagen: (.*)$/, "TLS handshake failed: $1"],
  [/^Ungueltige Einstellungen: (.*)$/, "Invalid settings: $1"],
  [/^Verschluesseln fehlgeschlagen: (.*)$/, "Encryption failed: $1"],
];

export function backendError(msg: string | null | undefined): string | null {
  if (!msg) {
    return null;
  }
  if (loadLang() !== "en") {
    return msg;
  }
  for (const [re, en] of BACKEND_ERRORS) {
    const m = msg.match(re);
    if (m) {
      return en.replace(/\$(\d)/g, (_, i: string) => m[Number(i)] ?? "");
    }
  }
  return msg;
}
