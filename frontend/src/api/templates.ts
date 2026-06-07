import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { request } from "./http";
import type { Channel, Template, TemplateStatus } from "../types/api";

export interface TemplateFilters {
  productId?: string;
  channel?: Channel | "";
  status?: TemplateStatus | "";
}

export interface CreateTemplatePayload {
  productId: string;
  templateKey: string;
  channel: Channel;
  version: number;
  subject: string | null;
  content: string;
  status: TemplateStatus;
}

export const templateKeys = {
  byProduct: (productId: string | undefined) => ["templates", productId] as const
};

export function useTemplates(filters: TemplateFilters) {
  return useQuery({
    queryKey: templateKeys.byProduct(filters.productId),
    enabled: Boolean(filters.productId),
    queryFn: () => request<Template[]>(`/admin/templates?productId=${filters.productId}`)
  });
}

export function useCreateTemplate() {
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: (payload: CreateTemplatePayload) =>
      request<Template>("/admin/templates", {
        method: "POST",
        body: JSON.stringify(payload)
      }),
    onSuccess: (_template, payload) =>
      queryClient.invalidateQueries({ queryKey: templateKeys.byProduct(payload.productId) })
  });
}

export async function updateTemplate(templateId: string, payload: Partial<CreateTemplatePayload>): Promise<Template> {
  void templateId;
  void payload;
  // TODO: Connect when backend exposes PUT/PATCH /api/v1/admin/templates/{id}.
  throw new Error("Template edits are not supported by the backend yet.");
}
