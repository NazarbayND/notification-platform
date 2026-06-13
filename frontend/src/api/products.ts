import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { request } from "./http";
import type { Product, ProductStatus } from "../types/api";

export const productKeys = {
  all: ["products"] as const
};

export function useProducts() {
  return useQuery({
    queryKey: productKeys.all,
    queryFn: () => request<Product[]>("/admin/products")
  });
}

export function useCreateProduct() {
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: (payload: { name: string; status?: ProductStatus }) =>
      request<Product>("/admin/products", {
        method: "POST",
        body: JSON.stringify({
          name: payload.name.trim(),
          status: payload.status ?? "ACTIVE"
        })
      }),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: productKeys.all })
  });
}
