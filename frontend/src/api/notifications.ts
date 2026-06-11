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
    queryFn: async () => {
      const params = new URLSearchParams();
      if (filters.status) {
        params.set("status", filters.status);
      }

      const query = params.toString();
      const notifications = await request<MicroserviceNotification[]>(`/admin/notifications${query ? `?${query}` : ""}`);
      return notifications
        .filter((notification) => !filters.productId || notification.productId === filters.productId)
        .filter((notification) => !filters.priority || notification.priority === filters.priority)
        .map(toNotificationRequest);
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
