import { useEffect, useState } from "react";
import {
  ActionIcon, AppShell, Burger, Title, Group, NavLink, SegmentedControl, Text, Tooltip, useMantineColorScheme
} from "@mantine/core";
import { useDisclosure } from "@mantine/hooks";
import {
  IconArrowsLeftRight, IconFileAnalytics, IconLayoutDashboard, IconMoon, IconSearch,
  IconSettings, IconBox, IconFilter, IconSun
} from "@tabler/icons-react";
import SnapshotsPage from "@/features/snapshots/SnapshotsPage";
import SnapshotDetail from "@/features/snapshots/SnapshotDetail";
import SearchPage from "@/features/search/SearchPage";
import SettingsPage from "@/features/settings/SettingsPage";
import RuleSettingsPage from "@/features/settings/RuleSettingsPage";
import ToolkitPage from "@/features/toolkits/ToolkitPage";
import DashboardPage from "@/features/dashboard/DashboardPage";
import ComparePage from "@/features/compare/ComparePage";
import IngestBanner from "@/shared/components/IngestBanner";
import Footer from "@/shared/components/Footer";
import { useDesign } from "@/shared/design";
import { useLang, type Lang } from "@/shared/i18n";
import { parseHash, type View } from "./navigation";

export default function App() {
  const [view, setView] = useState<View>(() => parseHash(window.location.hash) ?? { page: "list" });
  const [mobileOpened, { toggle: toggleMobile }] = useDisclosure();
  const { colorScheme, setColorScheme } = useMantineColorScheme();
  const { design } = useDesign();
  const { lang, setLang, tr } = useLang();

  // hashchange (manual address edits, Back) -> open comparison.
  useEffect(() => {
    const onHash = () => {
      const v = parseHash(window.location.hash);
      if (v) setView(v);
    };
    window.addEventListener("hashchange", onHash);
    return () => window.removeEventListener("hashchange", onHash);
  }, []);

  // Navigation: clear the comparison deep link only when leaving the page,
  // otherwise ComparePage owns hash maintenance (replaceState, no event).
  const navigate = (v: View) => {
    if (v.page !== "compare" && window.location.hash.startsWith("#/compare")) {
      window.history.replaceState(null, "", window.location.pathname + window.location.search);
    }
    setView(v);
  };

  const openDetail = (id: number, from: "list" | "toolkits" | "search" = "list") =>
    navigate({ page: "detail", id, from });

  return (
    <AppShell
      header={{ height: 56 }}
      footer={{ height: 46 }}
      navbar={{ width: 220, breakpoint: "sm", collapsed: { mobile: !mobileOpened } }}
      padding="md"
    >
      <AppShell.Header style={{ background: design.headerBg, borderColor: design.headerBg }}>
        <Group h="100%" px="md" justify="space-between">
          <Group>
            <Burger opened={mobileOpened} onClick={toggleMobile} hiddenFrom="sm" size="sm" color="white" />
            <img src="/logo.png" alt="Fuchs IT-Consulting" style={{ height: 28, borderRadius: 4 }} />
            <Title order={3} c="#fff">TWX Analyzer v2</Title>
          </Group>
          <Group gap="md">
            <Text size="xs" c="rgba(255,255,255,0.75)" visibleFrom="sm">{tr("TWX-Analyse für BAW-Exporte", "TWX analysis for BAW exports")}</Text>
            {/* i18n: language switch DE/EN, choice persists in the browser */}
            <SegmentedControl
              size="xs"
              value={lang}
              onChange={(v) => setLang(v as Lang)}
              data={[{ value: "de", label: "DE" }, { value: "en", label: "EN" }]}
              aria-label={tr("Sprache wechseln", "Switch language")}
              styles={{ root: { background: "rgba(255,255,255,0.15)" } }}
            />
            {/* B10: theme switcher (light/dark/system), choice persists in the browser */}
            <Tooltip label={colorScheme === "dark" ? tr("Helles Design", "Light theme") : tr("Dunkles Design", "Dark theme")}>
              <ActionIcon
                variant="subtle"
                color="white"
                onClick={() => setColorScheme(colorScheme === "dark" ? "light" : "dark")}
                aria-label={tr("Farbschema wechseln", "Toggle color scheme")}
              >
                {colorScheme === "dark" ? <IconSun size={18} /> : <IconMoon size={18} />}
              </ActionIcon>
            </Tooltip>
          </Group>
        </Group>
      </AppShell.Header>
      <AppShell.Navbar p="xs">
        <NavLink
          label="Dashboard"
          leftSection={<IconLayoutDashboard size={18} />}
          active={view.page === "dashboard"}
          onClick={() => { navigate({ page: "dashboard" }); toggleMobile(); }}
        />
        <NavLink
          label={tr("Prozess-Apps", "Process apps")}
          leftSection={<IconFileAnalytics size={18} />}
          active={view.page === "list" || (view.page === "detail" && view.from === "list")}
          onClick={() => { navigate({ page: "list" }); toggleMobile(); }}
        />
        <NavLink
          label="Toolkits"
          leftSection={<IconBox size={18} />}
          active={view.page === "toolkits" || (view.page === "detail" && view.from === "toolkits")}
          onClick={() => { navigate({ page: "toolkits" }); toggleMobile(); }}
        />
        <NavLink
          label={tr("Vergleich", "Compare")}
          leftSection={<IconArrowsLeftRight size={18} />}
          active={view.page === "compare"}
          onClick={() => { navigate({ page: "compare" }); toggleMobile(); }}
        />
        <NavLink
          label={tr("Suche", "Search")}
          leftSection={<IconSearch size={18} />}
          active={view.page === "search"}
          onClick={() => { navigate({ page: "search" }); toggleMobile(); }}
        />
        <NavLink
          label={tr("Regeleinstellungen", "Rule settings")}
          leftSection={<IconFilter size={18} />}
          active={view.page === "rules"}
          onClick={() => { navigate({ page: "rules" }); toggleMobile(); }}
          data-rules-nav
        />
        <NavLink
          label={tr("Einstellungen", "Settings")}
          leftSection={<IconSettings size={18} />}
          active={view.page === "settings"}
          onClick={() => { navigate({ page: "settings" }); toggleMobile(); }}
        />
      </AppShell.Navbar>
      <AppShell.Main>
        {/* Banner in flow: pushes content down instead of overlapping */}
        <IngestBanner onOpenSnapshot={openDetail} />
        {view.page === "list" && <SnapshotsPage onOpen={openDetail} />}
        {view.page === "toolkits" && <ToolkitPage onOpen={openDetail} />}
        {view.page === "dashboard" && <DashboardPage onOpenSnapshot={openDetail} />}
        {view.page === "compare" && (
          <ComparePage
            key={`${view.base ?? ""}-${view.target ?? ""}`}
            initialBase={view.base ?? null}
            initialTarget={view.target ?? null}
          />
        )}
        {view.page === "detail" && (
          <SnapshotDetail
            id={view.id}
            onBack={() => navigate({ page: view.from })}
            onOpenRules={(sid) => navigate({ page: "rules", snapshotId: sid })}
            onReplaced={(sid) => navigate({ page: "detail", id: sid, from: view.from })}
          />
        )}
        {view.page === "search" && (
          <SearchPage
            onOpenSnapshot={(id) => openDetail(id, "search")}
          />
        )}
        {view.page === "settings" && <SettingsPage />}
        {view.page === "rules" && <RuleSettingsPage key={view.snapshotId ?? "global"} initialSnapshotId={view.snapshotId} />}
      </AppShell.Main>
      <AppShell.Footer p={0} style={{ borderColor: design.headerBg }}>
        <Footer />
      </AppShell.Footer>
    </AppShell>
  );
}
