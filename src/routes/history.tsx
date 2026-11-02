import { createFileRoute } from "@tanstack/react-router";
import { HistoryView } from "@/components/history";

export const Route = createFileRoute("/history")({
  component: HistoryView,
});
