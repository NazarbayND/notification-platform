import { useQuery } from "@tanstack/react-query";
import { request } from "./http";
import type { NotificationPriority, NotificationRequest, NotificationRequestStatus } from "../types/api";

export interface NotificationFilters {
  productId?: string;
  status?: NotificationRequestStatus | "";
  priority?: NotificationPriority | "";
  dateFrom?: string;
  dateTo?: string;
}

export const notificationKeys = {
  list: (filters: NotificationFilters) => ["notifications", filters] as const,
  detail: (id: string | undefined) => ["notification", id] as const
};

export function useNotifications(filters: NotificationFilters) {
  return useQuery({
    queryKey: notificationKeys.list(filters),
    queryFn: async () => {
      // TODO: Connect when backend exposes GET /api/v1/notifications with filters.
      return [] as NotificationRequest[];
    }
  });
}

export function useNotification(id: string | undefined) {
  return useQuery({
    queryKey: notificationKeys.detail(id),
    enabled: Boolean(id),
    queryFn: () => request<NotificationRequest>(`/notifications/${id}`)
  });
}
