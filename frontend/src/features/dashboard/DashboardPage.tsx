import { useEffect, useState } from "react";
import { Badge, Card, Group, Loader, SimpleGrid, Stack, Text, Title, Tooltip } from "@mantine/core";
import { IconAlertTriangle, IconCalendar, IconCode, IconServer, IconSitemap, IconTrendingUp } from "@tabler/icons-react";
import { api, DashboardProject, healthColor } from "@/shared/api";
import { useLang } from "@/shared/i18n";

/**
 * B5.3 project dashboard: one tile per project (app or toolkit) with
 * health trend over the ingested snapshots and open critical findings
 * of the newest analysis. Accepted findings (B5) do not count as open.
 */
export default function DashboardPage({ onOpenSnapshot }: { onOpenSnapshot: (id: number) => void }) {
  const [projects, setProjects] = useState<DashboardProject[] | null>(null);
  const { tr } = useLang();

  useEffect(() => {
    api.dashboard().then(setProjects).catch(() => setProjects([]));
  }, []);

  if (projects === null) {
    return <Group justify="center" mt="xl"><Loader size="sm" /></Group>;
  }
  if (projects.length === 0) {
    return <Text c="dimmed">{tr("Noch keine Snapshots vorhanden.", "No snapshots yet.")}</Text>;
  }

  return (
    <Stack>
      <Group gap="xs">
        <IconTrendingUp size={22} />
        <Title order={3}>{tr("Projekt-Dashboard", "Project Dashboard")}</Title>
        <Text size="xs" c="dimmed">{tr("Health-Verlauf je Analyse (aelteste links) — Klick oeffnet den neuesten Stand", "Health trend per analysis (oldest first) — click opens the latest state")}</Text>
      </Group>
      {/* Sorting from the API: process apps first, then toolkits (alphabetical per group) */}
      <ProjectGroup title={tr("Prozess-Apps", "Process Apps")} projects={projects.filter((p) => !p.toolkit)} onOpenSnapshot={onOpenSnapshot} />
      <ProjectGroup title={tr("Toolkits", "Toolkits")} projects={projects.filter((p) => p.toolkit)} onOpenSnapshot={onOpenSnapshot} />
    </Stack>
  );
}

/** Tile group with heading (empty groups are not rendered) */
function ProjectGroup({ title, projects, onOpenSnapshot }: {
  title: string;
  projects: DashboardProject[];
  onOpenSnapshot: (id: number) => void;
}) {
  const { tr } = useLang();
  if (projects.length === 0) return null;
  return (
    <Stack gap="xs">
      <Group gap="xs">
        <Title order={5} c="dimmed">{title}</Title>
        <Badge size="sm" variant="light" color="gray">{projects.length}</Badge>
      </Group>
      <SimpleGrid cols={{ base: 1, sm: 2, lg: 3 }} spacing="md">
        {projects.map((p) => (
          <Card
            key={p.key}
            withBorder
            shadow="xs"
            p="md"
            style={{ cursor: p.latestSnapshotId ? "pointer" : undefined }}
            onClick={() => p.latestSnapshotId && onOpenSnapshot(p.latestSnapshotId)}
          >
            <Group justify="space-between" mb="xs">
              <div>
                <Group gap={6}>
                  <Text fw={700}>{p.acronym ?? p.name ?? p.key}</Text>
                  {p.toolkit && (
                    <Tooltip label={tr("Projekt-Typ laut Manifest (isToolkit)", "Project type per manifest (isToolkit)")}>
                      <Badge size="xs" variant="light" color="violet">{tr("Toolkit", "Toolkit")}</Badge>
                    </Tooltip>
                  )}
                </Group>
                <Text size="xs" c="dimmed">{p.name ?? ""}</Text>
              </div>
              {p.latestHealth != null && (
                <Badge size="lg" variant="light" color={healthColor(p.latestHealth)}>
                  {p.latestHealth}
                </Badge>
              )}
            </Group>
            <Sparkline project={p} />
            {/* Size metrics of the latest analysis (B5.3 addendum) */}
            <Tooltip label={tr("Größenangaben der neuesten Analyse: Services, BPDs und JavaScript-Zeilen (Services/Prozesse/Coach Views)", "Size metrics of the latest analysis: services, BPDs and JavaScript lines (services/processes/coach views)")}>
              <Group gap="md" mt="xs">
                <Group gap={4}>
                  <IconServer size={12} />
                  <Text size="xs" c="dimmed">{p.serviceCount} {tr("Services", "Services")}</Text>
                </Group>
                <Group gap={4}>
                  <IconSitemap size={12} />
                  <Text size="xs" c="dimmed">{p.bpdCount} {tr("BPDs", "BPDs")}</Text>
                </Group>
                <Group gap={4}>
                  <IconCode size={12} />
                  <Text size="xs" c="dimmed">{p.scriptLines.toLocaleString()} {tr("JS-Zeilen", "JS lines")}</Text>
                </Group>
              </Group>
            </Tooltip>
            <Group justify="space-between" mt="xs">
              <Tooltip label={tr("Critical-Befunde der neuesten Analyse (ohne akzeptierte)", "Critical findings of the latest analysis (excluding accepted)")}>
                <Group gap={4}>
                  <IconAlertTriangle size={14} color={p.openCritical > 0 ? "var(--twx-health-bad)" : "var(--mantine-color-dimmed)"} />
                  <Text size="xs" c={p.openCritical > 0 ? "red" : "dimmed"}>
                    {tr(`${p.openCritical} offen (Critical)`, `${p.openCritical} open (Critical)`)}
                  </Text>
                </Group>
              </Tooltip>
              <Group gap={4}>
                <IconCalendar size={12} />
                <Text size="xs" c="dimmed">{tr(`${p.trend.length} Analysen · ${p.lastImport?.slice(0, 10) ?? ""}`, `${p.trend.length} analyses · ${p.lastImport?.slice(0, 10) ?? ""}`)}</Text>
              </Group>
            </Group>
            {p.latestSnapshotName && (
              <Text size="xs" c="dimmed" mt={2}>{tr(`neuest: ${p.latestSnapshotName}`, `latest: ${p.latestSnapshotName}`)}</Text>
            )}
          </Card>
        ))}
      </SimpleGrid>
    </Stack>
  );
}

/** small health curve (SVG) per project */
function Sparkline({ project }: { project: DashboardProject }) {
  const { tr } = useLang();
  const pts = project.trend.filter((t) => t.health != null);
  if (pts.length === 0) return <Text size="xs" c="dimmed">{tr("Kein Health-Verlauf.", "No health history.")}</Text>;
  const w = 260, h = 56, pad = 4;
  const step = pts.length > 1 ? (w - 2 * pad) / (pts.length - 1) : 0;
  const xy = pts.map((t, i) => [
    pad + i * step,
    pad + (h - 2 * pad) * (1 - (t.health as number) / 100),
  ]);
  const path = xy.map(([x, y], i) => `${i === 0 ? "M" : "L"}${x.toFixed(1)},${y.toFixed(1)}`).join(" ");
  const last = xy[xy.length - 1];
  const color = (hc: number) =>
    hc >= 80 ? "var(--twx-health-good)"
      : hc >= 60 ? "var(--twx-health-warning)" : "var(--twx-health-bad)";
  return (
    <svg viewBox={`0 0 ${w} ${h}`} width="100%" height={h} role="img" aria-label={tr("Health-Verlauf", "Health trend")}>
      {[25, 50, 75].map((g) => (
        <line key={g} x1={pad} x2={w - pad}
          y1={pad + (h - 2 * pad) * (1 - g / 100)} y2={pad + (h - 2 * pad) * (1 - g / 100)}
          stroke="var(--mantine-color-default-border)" strokeDasharray="2 3" strokeWidth={1} />
      ))}
      <path d={path} fill="none" stroke={color(pts[pts.length - 1].health as number)} strokeWidth={2} />
      {xy.map(([x, y], i) => (
        <circle key={i} cx={x} cy={y} r={i === xy.length - 1 ? 3.5 : 2}
          fill={color(pts[i].health as number)}>
          <title>{pts[i].snapshotName}: {pts[i].health}</title>
        </circle>
      ))}
      {pts.length === 1 && (
        <text x={last[0] + 8} y={last[1] + 3} fontSize={10} fill="var(--mantine-color-dimmed)">
          {pts[0].health}
        </text>
      )}
    </svg>
  );
}
