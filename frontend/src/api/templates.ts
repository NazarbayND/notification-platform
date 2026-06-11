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
    queryFn: async () => {
      const templates = await request<Array<{
        id: string;
        productId: string;
        key: string;
        channel: Channel;
        subject: string | null;
        body: string;
        status: TemplateStatus;
        createdAt: string | null;
        updatedAt: string | null;
      }>>(`/admin/templates?productId=${filters.productId}`);
      return templates
        .filter((template) => !filters.productId || template.productId === filters.productId)
        .map(toTemplate);
    }
  });
}

export function useCreateTemplate() {
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: async (payload: CreateTemplatePayload) => {
      const template = await request<{
        id: string;
        productId: string;
        key: string;
        channel: Channel;
        subject: string | null;
        body: string;
        status: TemplateStatus;
        createdAt: string | null;
        updatedAt: string | null;
      }>("/admin/templates", {
        method: "POST",
        body: JSON.stringify({
          productId: payload.productId,
          key: payload.templateKey,
          channel: payload.channel,
          subject: payload.subject?.trim() || payload.templateKey,
          body: payload.content,
          requiredVariables: []
        })
      });
      return toTemplate(template);
    },
    onSuccess: (_template, payload) =>
      queryClient.invalidateQueries({ queryKey: templateKeys.byProduct(payload.productId) })
  });
}

export async function updateTemplate(templateId: string, payload: Partial<CreateTemplatePayload>): Promise<Template> {
  const template = await request<{
    id: string;
    productId: string;
    key: string;
    channel: Channel;
    subject: string | null;
    body: string;
    status: TemplateStatus;
    createdAt: string | null;
    updatedAt: string | null;
  }>(`/admin/templates/${templateId}`, {
    method: "PUT",
    body: JSON.stringify({
      productId: payload.productId,
      key: payload.templateKey,
      channel: payload.channel,
      subject: payload.subject?.trim() || payload.templateKey,
      body: payload.content,
      requiredVariables: []
    })
  });
  return toTemplate(template);
}

function toTemplate(template: {
  id: string;
  productId: string;
  key: string;
  channel: Channel;
  subject: string | null;
  body: string;
  status: TemplateStatus;
  createdAt: string | null;
  updatedAt: string | null;
}): Template {
  return {
    id: template.id,
    productId: template.productId,
    templateKey: template.key,
    channel: template.channel,
    version: 1,
    subject: template.subject,
    content: template.body,
    status: template.status,
    createdAt: template.createdAt,
    updatedAt: template.updatedAt
  };
}
