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
        sentCount: stats.sentCount ?? 0,
        failedCount: stats.failedCount ?? 0,
        pendingOutboxCount: stats.pendingOutboxCount ?? 0,
        retryCount: stats.retryCount ?? 0,
        dlqCount: stats.dlqCount ?? 0,
        providerErrorRate: stats.providerErrorRate ?? 0,
        throughputPerMinute: stats.throughputPerMinute ?? 0
      };
    }
  });
}
