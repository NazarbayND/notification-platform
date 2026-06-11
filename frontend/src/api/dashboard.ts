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
      const stats = await request<{
        totalNotificationsToday?: number;
        sentCount?: number;
        failedCount?: number;
        pendingOutboxCount?: number;
        dlqCount?: number;
      }>("/admin/dashboard");
      return {
        totalNotifications: stats.totalNotificationsToday ?? 0,
        pendingDeliveries: stats.pendingOutboxCount ?? 0,
        failedDeliveries: stats.failedCount ?? 0,
        deadLetteredDeliveries: stats.dlqCount ?? 0
      };
    }
  });
}
