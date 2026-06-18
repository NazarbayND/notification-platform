import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { request } from "./http";
import type { Channel, NotificationPriority, NotificationRequest, NotificationRequestStatus, PageResult } from "../types/api";

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
      const channel = payload.requestedChannels[0] ?? "EMAIL";
      const accepted = await request<{
        notificationId: string;
        status: NotificationRequestStatus;
        correlationId: string;
        channel: Channel;
        outboxEventId: string | null;
      }>("/admin/notifications", {
        method: "POST",
        body: JSON.stringify({
          productId: payload.productId,
          userId: payload.externalUserId,
          channel,
          templateKey: payload.templateKey,
          destination: destinationFor(channel, payload.recipient),
          priority: payload.priority,
          idempotencyKey: payload.idempotencyKey,
          variables: payload.payload
        })
      });
      return {
        id: accepted.notificationId,
        productId: payload.productId,
        batchId: null,
        templateKey: payload.templateKey,
        externalUserId: payload.externalUserId,
        idempotencyKey: payload.idempotencyKey,
        category: payload.category,
        priority: payload.priority,
        requestedChannels: [accepted.channel],
        status: accepted.status,
        payload: payload.payload,
        recipient: payload.recipient,
        expiresAt: payload.expiresAt,
        createdAt: new Date().toISOString()
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
  priority: NotificationPriority;
  status: NotificationRequestStatus;
  idempotencyKey: string;
  destination: string;
  variables: Record<string, unknown>;
  createdAt: string | null;
}

function toNotificationRequest(notification: MicroserviceNotification): NotificationRequest {
  return {
    id: notification.id,
    productId: notification.productId,
    batchId: null,
    templateKey: notification.templateKey,
    externalUserId: notification.userId,
    idempotencyKey: notification.idempotencyKey,
    category: "default",
    priority: notification.priority,
    requestedChannels: [notification.channel],
    status: notification.status,
    payload: notification.variables ?? {},
    recipient: { destination: notification.destination },
    expiresAt: null,
    createdAt: notification.createdAt
  };
}

function destinationFor(channel: Channel, recipient: Record<string, unknown>) {
  if (channel === "EMAIL") {
    return String(recipient.email ?? "");
  }
  if (channel === "SMS") {
    return String(recipient.phone ?? recipient.destination ?? "");
  }
  if (channel === "PUSH") {
    return String(recipient.token ?? recipient.destination ?? "");
  }
  return String(recipient.userId ?? recipient.destination ?? "");
}
