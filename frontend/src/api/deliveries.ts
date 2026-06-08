import { useMutation, useQuery } from "@tanstack/react-query";
import { request } from "./http";
import type { Channel, Delivery, DeliveryStatus } from "../types/api";

export interface DeliveryFilters {
  status?: DeliveryStatus | "";
  channel?: Channel | "";
  provider?: string;
  notificationRequestId?: string;
}

export const deliveryKeys = {
  list: (filters: DeliveryFilters) => ["deliveries", filters] as const
};

export function useDeliveries(filters: DeliveryFilters) {
  return useQuery({
    queryKey: deliveryKeys.list(filters),
    queryFn: () => {
      const params = new URLSearchParams();
      if (filters.status) {
        params.set("status", filters.status);
      }
      if (filters.channel) {
        params.set("channel", filters.channel);
      }
      if (filters.provider?.trim()) {
        params.set("provider", filters.provider.trim());
      }

      if (filters.notificationRequestId) {
        return request<Delivery[]>(`/notifications/${filters.notificationRequestId}/deliveries`);
      }

      const query = params.toString();
      return request<Delivery[]>(`/admin/deliveries${query ? `?${query}` : ""}`);
    }
  });
}

export function useRetryDelivery() {
  return useMutation({
    mutationFn: async (deliveryId: string) => {
      void deliveryId;
      // TODO: Connect when backend exposes POST /api/v1/admin/deliveries/{id}/retry.
      throw new Error("Delivery retry is not supported by the backend yet.");
    }
  });
}
