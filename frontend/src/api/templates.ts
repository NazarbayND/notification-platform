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
  subject: string | null;
  content: string;
  status: TemplateStatus;
}

export const templateKeys = {
  list: (filters: TemplateFilters) => ["templates", filters] as const
};

export function useTemplates(filters: TemplateFilters) {
  return useQuery({
    queryKey: templateKeys.list(filters),
    enabled: Boolean(filters.productId),
    queryFn: async () => {
      const params = new URLSearchParams();
      if (filters.productId) {
        params.set("productId", filters.productId);
      }
      if (filters.channel) {
        params.set("channel", filters.channel);
      }
      if (filters.status) {
        params.set("status", filters.status);
      }
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
      }>>(`/admin/templates?${params.toString()}`);
      return templates.map(toTemplate);
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
          status: payload.status,
          requiredVariables: []
        })
      });
      return toTemplate(template);
    },
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ["templates"] })
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
      status: payload.status,
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
    subject: template.subject,
    content: template.body,
    status: template.status,
    createdAt: template.createdAt,
    updatedAt: template.updatedAt
  };
}
