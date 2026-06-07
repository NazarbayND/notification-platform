import { useQuery } from "@tanstack/react-query";
import type { DashboardStats } from "../types/api";

export const dashboardKeys = {
  stats: ["dashboard", "stats"] as const
};

export function useDashboardStats() {
  return useQuery({
    queryKey: dashboardKeys.stats,
    queryFn: async (): Promise<DashboardStats> => {
      // TODO: Connect when backend exposes an admin summary endpoint or list endpoints for notifications/deliveries.
      return {
        totalNotifications: null,
        pendingDeliveries: null,
        failedDeliveries: null,
        deadLetteredDeliveries: null
      };
    }
  });
}
