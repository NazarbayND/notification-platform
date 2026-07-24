import { useQuery } from "@tanstack/react-query";
import { request } from "./http";
import type { DashboardStats } from "../types/api";

export const dashboardKeys = {
  stats: ["dashboard", "stats"] as const
};

export function useDashboardStats() {
  return useQuery({
    queryKey: dashboardKeys.stats,
    queryFn: async (): Promise<DashboardStats> => {
      const stats = await request<Partial<DashboardStats>>("/admin/dashboard");
      return {
        totalNotificationsToday: stats.totalNotificationsToday ?? 0,
        deliveredCount: stats.deliveredCount ?? 0,
        failedCount: stats.failedCount ?? 0,
        retryAttemptCount: stats.retryAttemptCount ?? 0,
        providerErrorRate: stats.providerErrorRate ?? 0
      };
    }
  });
}
