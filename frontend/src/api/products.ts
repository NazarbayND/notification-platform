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
    mutationFn: (payload: { name: string }) =>
      request<Product>("/admin/products", {
        method: "POST",
        body: JSON.stringify(payload)
      }),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: productKeys.all })
  });
}

export async function updateProductStatus(productId: string, status: ProductStatus): Promise<Product> {
  void productId;
  void status;
  // TODO: Connect when backend exposes PATCH /api/v1/admin/products/{id}/status.
  throw new Error("Product status updates are not supported by the backend yet.");
}
