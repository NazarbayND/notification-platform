import { FormEvent, useMemo, useState } from "react";
import { useProducts } from "../api/products";
import { TemplateFilters, useCreateTemplate, useTemplates } from "../api/templates";
import { Badge } from "../components/Badge";
import { Button } from "../components/Button";
import { DataTable } from "../components/DataTable";
import { SelectField, TextAreaField, TextField } from "../components/Field";
import { PageHeader } from "../components/PageHeader";
import { Panel } from "../components/Panel";
import { ErrorBlock, LoadingBlock, StateBlock } from "../components/StateBlock";
import { formatDateTime } from "../lib/format";
import type { Channel, TemplateStatus } from "../types/api";

const channels: Channel[] = ["EMAIL", "SMS", "PUSH", "IN_APP"];
const templateStatuses: TemplateStatus[] = ["DRAFT", "ACTIVE", "INACTIVE"];

export function TemplatesPage() {
  const productsQuery = useProducts();
  const firstProductId = productsQuery.data?.[0]?.id ?? "";
  const [filters, setFilters] = useState<TemplateFilters>({ productId: "", channel: "", status: "" });
  const selectedProductId = filters.productId || firstProductId;
  const templatesQuery = useTemplates({ ...filters, productId: selectedProductId });

  const visibleTemplates = useMemo(() => {
    const templates = templatesQuery.data ?? [];
    return templates.filter((template) => {
      if (filters.channel && template.channel !== filters.channel) {
        return false;
      }
      return !(filters.status && template.status !== filters.status);
    });
  }, [filters.channel, filters.status, templatesQuery.data]);

  function updateFilter<K extends keyof TemplateFilters>(key: K, value: TemplateFilters[K]) {
    setFilters((current) => ({ ...current, [key]: value }));
  }

  return (
    <>
      <PageHeader title="Templates" description="Create and inspect channel-specific message templates." />

      <div className="mb-6 grid gap-3 rounded-md border border-line bg-white p-4 shadow-sm md:grid-cols-3">
        <SelectField label="Product" value={selectedProductId} onChange={(event) => updateFilter("productId", event.target.value)}>
          <option value="">Select product</option>
          {productsQuery.data?.map((product) => (
            <option key={product.id} value={product.id}>
              {product.name}
            </option>
          ))}
        </SelectField>
        <SelectField label="Channel" value={filters.channel} onChange={(event) => updateFilter("channel", event.target.value as Channel | "")}>
          <option value="">All channels</option>
          {channels.map((channel) => (
            <option key={channel} value={channel}>
              {channel}
            </option>
          ))}
        </SelectField>
        <SelectField label="Status" value={filters.status} onChange={(event) => updateFilter("status", event.target.value as TemplateStatus | "")}>
          <option value="">All statuses</option>
          {templateStatuses.map((status) => (
            <option key={status} value={status}>
              {status}
            </option>
          ))}
        </SelectField>
      </div>

      <div className="grid gap-6 xl:grid-cols-[420px_1fr]">
        <CreateTemplatePanel productId={selectedProductId} />

        <section>
          {!selectedProductId ? (
            <StateBlock title="Select a product" message="Templates are scoped to a product in the current backend API." />
          ) : null}
          {selectedProductId && templatesQuery.isLoading ? <LoadingBlock /> : null}
          {templatesQuery.isError ? <ErrorBlock message={templatesQuery.error.message} /> : null}
          {selectedProductId && !templatesQuery.isLoading && visibleTemplates.length === 0 ? (
            <StateBlock title="No templates found" message="Try changing filters or create a template for this product." />
          ) : null}
          {visibleTemplates.length > 0 ? (
            <DataTable headers={["Key", "Channel", "Version", "Status", "Updated", "Actions"]}>
              {visibleTemplates.map((template) => (
                <tr key={template.id}>
                  <td className="px-4 py-3 font-medium">{template.templateKey}</td>
                  <td className="px-4 py-3">{template.channel}</td>
                  <td className="px-4 py-3">{template.version}</td>
                  <td className="px-4 py-3">
                    <Badge value={template.status} tone={template.status === "ACTIVE" ? "success" : "neutral"} />
                  </td>
                  <td className="px-4 py-3 text-slate-600">{formatDateTime(template.updatedAt)}</td>
                  <td className="px-4 py-3">
                    <Button type="button" variant="secondary" disabled title="Backend endpoint not available yet">
                      Edit
                    </Button>
                  </td>
                </tr>
              ))}
            </DataTable>
          ) : null}
        </section>
      </div>
    </>
  );
}

function CreateTemplatePanel({ productId }: { productId: string }) {
  const createTemplate = useCreateTemplate();
  const [templateKey, setTemplateKey] = useState("");
  const [channel, setChannel] = useState<Channel>("EMAIL");
  const [version, setVersion] = useState(1);
  const [subject, setSubject] = useState("");
  const [content, setContent] = useState("");
  const [status, setStatus] = useState<TemplateStatus>("DRAFT");

  function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    createTemplate.mutate(
      {
        productId,
        templateKey,
        channel,
        version,
        subject: subject.trim() || null,
        content,
        status
      },
      {
        onSuccess: () => {
          setTemplateKey("");
          setSubject("");
          setContent("");
          setVersion(1);
          setStatus("DRAFT");
        }
      }
    );
  }

  return (
    <Panel title="Create template">
      <form className="grid gap-4" onSubmit={handleSubmit}>
        <TextField label="Template key" value={templateKey} onChange={(event) => setTemplateKey(event.target.value)} placeholder="invoice.created" required />
        <div className="grid gap-4 sm:grid-cols-2">
          <SelectField label="Channel" value={channel} onChange={(event) => setChannel(event.target.value as Channel)}>
            {channels.map((item) => (
              <option key={item} value={item}>
                {item}
              </option>
            ))}
          </SelectField>
          <TextField label="Version" type="number" min={1} value={version} onChange={(event) => setVersion(Number(event.target.value))} required />
        </div>
        <SelectField label="Status" value={status} onChange={(event) => setStatus(event.target.value as TemplateStatus)}>
          {templateStatuses.map((item) => (
            <option key={item} value={item}>
              {item}
            </option>
          ))}
        </SelectField>
        <TextField label="Subject" value={subject} onChange={(event) => setSubject(event.target.value)} placeholder="Optional for non-email channels" />
        <TextAreaField label="Content" value={content} onChange={(event) => setContent(event.target.value)} placeholder="Hello {{name}}" required />
        {createTemplate.isError ? <p className="rounded-md bg-red-50 px-3 py-2 text-sm text-ruby">{createTemplate.error.message}</p> : null}
        <Button type="submit" disabled={!productId || createTemplate.isPending}>
          {createTemplate.isPending ? "Creating" : "Create template"}
        </Button>
      </form>
    </Panel>
  );
}
