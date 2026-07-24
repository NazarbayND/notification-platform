import { useQuery } from "@tanstack/react-query";
import { request } from "./http";
import type { Channel, Delivery, DeliveryStatus, PageResult } from "../types/api";

export interface DeliveryFilters {
  status?: DeliveryStatus | "";
  channel?: Channel | "";
  notificationRequestId?: string;
}

export const deliveryKeys = {
  list: (filters: DeliveryFilters) => ["deliveries", filters] as const
};

export function useDeliveries(filters: DeliveryFilters, page = 1, pageSize = 10) {
  return useQuery({
    queryKey: deliveryKeys.list({ ...filters, page: String(page), pageSize: String(pageSize) } as DeliveryFilters),
    queryFn: () => {
      const params = new URLSearchParams();
      params.set("page", String(Math.max(0, page - 1)));
      params.set("size", String(pageSize));
      if (filters.status) {
        params.set("status", filters.status);
      }
      if (filters.channel) {
        params.set("channel", filters.channel);
      }
      if (filters.notificationRequestId) {
        params.set("notificationRequestId", filters.notificationRequestId);
      }

      const query = params.toString();
      return request<PageResult<Delivery>>(`/admin/deliveries/page?${query}`);
    }
  });
}
