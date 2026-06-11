import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { request } from "./http";
import type { Product, ProductStatus } from "../types/api";

export const productKeys = {
  all: ["products"] as const
};

export function useProducts() {
  return useQuery({
    queryKey: productKeys.all,
    queryFn: async () => {
      const templates = await request<Array<{ productId: string; createdAt?: string | null; updatedAt?: string | null }>>("/admin/templates");
      const products = new Map<string, Product>();
      for (const template of templates) {
        if (!template.productId || products.has(template.productId)) {
          continue;
        }
        products.set(template.productId, {
          id: template.productId,
          name: template.productId,
          status: "ACTIVE",
          createdAt: template.createdAt ?? null,
          updatedAt: template.updatedAt ?? null
        });
      }
      return [...products.values()];
    }
  });
}

export function useCreateProduct() {
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: async (payload: { name: string }) => ({
      id: payload.name.trim(),
      name: payload.name.trim(),
      status: "ACTIVE" as ProductStatus,
      createdAt: new Date().toISOString(),
      updatedAt: new Date().toISOString()
    }),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: productKeys.all })
  });
}

export async function updateProductStatus(productId: string, status: ProductStatus): Promise<Product> {
  void productId;
  void status;
  throw new Error("Product status updates are not exposed by the microservices BFF.");
}
