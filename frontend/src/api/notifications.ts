import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { request } from "./http";
import type { Channel, NotificationRequest, NotificationRequestStatus, PageResult } from "../types/api";

export interface NotificationFilters {
  productId?: string;
  status?: NotificationRequestStatus | "";
  channel?: Channel | "";
}

export const notificationKeys = {
  list: (filters: NotificationFilters) => ["notifications", filters] as const,
  detail: (id: string | undefined) => ["notification", id] as const
};

export interface SendNotificationPayload {
  productId: string;
  templateKey: string;
  channel: Channel;
  userId: string;
  idempotencyKey: string;
  variables: Record<string, unknown>;
  destination: string;
}

export function useNotifications(filters: NotificationFilters, page = 1, pageSize = 10) {
  return useQuery({
    queryKey: notificationKeys.list({ ...filters, page: String(page), pageSize: String(pageSize) } as NotificationFilters),
    queryFn: async () => {
      const params = new URLSearchParams();
      params.set("page", String(Math.max(0, page - 1)));
      params.set("size", String(pageSize));
      if (filters.status) {
        params.set("status", filters.status);
      }
      if (filters.productId?.trim()) {
        params.set("productId", filters.productId.trim());
      }
      if (filters.channel) {
        params.set("channel", filters.channel);
      }

      const query = params.toString();
      const notifications = await request<PageResult<MicroserviceNotification>>(`/admin/notifications/page?${query}`);
      return {
        ...notifications,
        items: notifications.items.map(toNotificationRequest)
      };
    }
  });
}

export function useNotification(id: string | undefined) {
  return useQuery({
    queryKey: notificationKeys.detail(id),
    enabled: Boolean(id),
    queryFn: async () => toNotificationRequest(await request<MicroserviceNotification>(`/admin/notifications/${id}`))
  });
}

export function useSendNotification() {
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: async (payload: SendNotificationPayload) => {
      const accepted = await request<{
        notificationId: string;
        requestId: string;
        status: NotificationRequestStatus;
        acceptedAt: string;
        correlationId: string;
        channel: Channel;
      }>("/admin/notifications", {
        method: "POST",
        body: JSON.stringify({
          productId: payload.productId,
          userId: payload.userId,
          channel: payload.channel,
          templateKey: payload.templateKey,
          destination: payload.destination,
          idempotencyKey: payload.idempotencyKey,
          variables: payload.variables
        })
      });
      return {
        id: accepted.notificationId,
        productId: payload.productId,
        templateKey: payload.templateKey,
        userId: payload.userId,
        channel: accepted.channel,
        status: accepted.status,
        reasonCode: null,
        reasonMessage: null,
        requestedAt: accepted.acceptedAt,
        updatedAt: accepted.acceptedAt
      };
    },
    onSuccess: (notification) => {
      queryClient.setQueryData(notificationKeys.detail(notification.id), notification);
      queryClient.invalidateQueries({ queryKey: ["notifications"] });
    }
  });
}

interface MicroserviceNotification {
  id: string;
  productId: string;
  userId: string;
  channel: Channel;
  templateKey: string;
  status: NotificationRequestStatus;
  reasonCode: string | null;
  reasonMessage: string | null;
  requestedAt: string | null;
  updatedAt: string | null;
}

function toNotificationRequest(notification: MicroserviceNotification): NotificationRequest {
  return {
    id: notification.id,
    productId: notification.productId,
    templateKey: notification.templateKey,
    userId: notification.userId,
    channel: notification.channel,
    status: notification.status,
    reasonCode: notification.reasonCode,
    reasonMessage: notification.reasonMessage,
    requestedAt: notification.requestedAt,
    updatedAt: notification.updatedAt
  };
}
