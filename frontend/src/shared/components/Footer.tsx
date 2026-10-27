import { Group, Text } from "@mantine/core";
import { useDesign } from "@/shared/design";

/**
 * Footer: mirrors the header (design.headerBg) — with a thin brand accent line
 * on top (primary color -> accent) and copyright in the footer.
 */
export default function Footer() {
  const { design } = useDesign();
  return (
    <div style={{ height: "100%", background: design.headerBg }}>
      <div style={{
        height: 3,
        background: `linear-gradient(90deg, var(--mantine-color-brand-4), ${design.accent})`,
      }} />
      <Group h="calc(100% - 3px)" px="md" py={4} justify="space-between" wrap="wrap">
        <Group gap={8} wrap="nowrap">
          <img src="/logo.png" alt="Fuchs IT-Consulting" style={{ height: 22, borderRadius: 3 }} />
          <Text size="xs" c="rgba(255,255,255,0.75)">
            © 2026 Johannes Fuchs – IT Consulting
          </Text>
        </Group>
      </Group>
    </div>
  );
}
