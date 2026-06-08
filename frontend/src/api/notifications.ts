import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { request } from "./http";
import type { Channel, NotificationPriority, NotificationRequest, NotificationRequestStatus } from "../types/api";

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

export interface SendNotificationPayload {
  productId: string;
  templateKey: string;
  requestedChannels: Channel[];
  externalUserId: string;
  idempotencyKey: string;
  category: string;
  priority: NotificationPriority;
  payload: Record<string, unknown>;
  recipient: Record<string, unknown>;
  expiresAt: string | null;
}

export function useNotifications(filters: NotificationFilters) {
  return useQuery({
    queryKey: notificationKeys.list(filters),
    queryFn: () => {
      const params = new URLSearchParams();
      if (filters.productId) {
        params.set("productId", filters.productId);
      }
      if (filters.status) {
        params.set("status", filters.status);
      }
      if (filters.priority) {
        params.set("priority", filters.priority);
      }
      if (filters.dateFrom) {
        params.set("dateFrom", filters.dateFrom);
      }
      if (filters.dateTo) {
        params.set("dateTo", filters.dateTo);
      }

      const query = params.toString();
      return request<NotificationRequest[]>(`/notifications${query ? `?${query}` : ""}`);
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

export function useSendNotification() {
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: (payload: SendNotificationPayload) =>
      request<NotificationRequest>("/notifications", {
        method: "POST",
        body: JSON.stringify(payload)
      }),
    onSuccess: (notification) => {
      queryClient.setQueryData(notificationKeys.detail(notification.id), notification);
    }
  });
}
