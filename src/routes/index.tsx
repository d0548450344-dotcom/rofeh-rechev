import { createFileRoute } from "@tanstack/react-router";
import { GarageHome } from "@/components/garage-home";

export const Route = createFileRoute("/")({
  component: GarageHome,
});
