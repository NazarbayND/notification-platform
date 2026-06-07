import { useMutation, useQuery } from "@tanstack/react-query";
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
    queryFn: async () => {
      // TODO: Connect when backend exposes GET /api/v1/admin/deliveries and GET /api/v1/notifications/{id}/deliveries.
      return [] as Delivery[];
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
