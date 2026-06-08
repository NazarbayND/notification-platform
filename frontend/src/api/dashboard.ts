import { useQuery } from "@tanstack/react-query";
import { request } from "./http";
import type { DashboardStats } from "../types/api";

export const dashboardKeys = {
  stats: ["dashboard", "stats"] as const
};

export function useDashboardStats() {
  return useQuery({
    queryKey: dashboardKeys.stats,
    queryFn: (): Promise<DashboardStats> => request<DashboardStats>("/admin/dashboard")
  });
}
