import { FormEvent, useEffect, useMemo, useState } from "react";
import { Link } from "react-router-dom";
import { useSendNotification } from "../api/notifications";
import { useTemplates } from "../api/templates";
import { Button } from "../components/Button";
import { SelectField, TextAreaField, TextField } from "../components/Field";
import { JsonPreview } from "../components/JsonPreview";
import { PageHeader } from "../components/PageHeader";
import { Panel } from "../components/Panel";
import { ErrorBlock, LoadingBlock, StateBlock } from "../components/StateBlock";
import type { NotificationPriority, NotificationRequest } from "../types/api";

const priorities: NotificationPriority[] = ["LOW", "NORMAL", "HIGH"];
const defaultPayload = JSON.stringify(
  {
    name: "Ada",
    test: true
  },
  null,
  2
);

export function TestNotificationPage() {
  const [productId, setProductId] = useState("demo-product");
  const selectedProductId = productId;
  const templatesQuery = useTemplates({ productId: selectedProductId, channel: "EMAIL", status: "ACTIVE" });
  const emailTemplates = useMemo(
    () => (templatesQuery.data ?? []).filter((template) => template.channel === "EMAIL" && template.status === "ACTIVE"),
    [templatesQuery.data]
  );

  const [templateKey, setTemplateKey] = useState("");
  const [externalUserId, setExternalUserId] = useState("test-user");
  const [email, setEmail] = useState("test@example.com");
  const [category, setCategory] = useState("test");
  const [priority, setPriority] = useState<NotificationPriority>("NORMAL");
  const [idempotencyKey, setIdempotencyKey] = useState(generateIdempotencyKey);
  const [payloadText, setPayloadText] = useState(defaultPayload);
  const [payloadError, setPayloadError] = useState("");
  const [sentNotification, setSentNotification] = useState<NotificationRequest | null>(null);
  const sendNotification = useSendNotification();

  useEffect(() => {
    if (!templateKey && emailTemplates.length > 0) {
      setTemplateKey(emailTemplates[0].templateKey);
    }
  }, [emailTemplates, templateKey]);

  function handleProductChange(nextProductId: string) {
    setProductId(nextProductId);
    setTemplateKey("");
    setSentNotification(null);
  }

  function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setPayloadError("");

    let payload: Record<string, unknown>;
    try {
      const parsed = JSON.parse(payloadText) as unknown;
      if (!parsed || Array.isArray(parsed) || typeof parsed !== "object") {
        setPayloadError("Payload must be a JSON object.");
        return;
      }
      payload = parsed as Record<string, unknown>;
    } catch {
      setPayloadError("Payload is not valid JSON.");
      return;
    }

    sendNotification.mutate(
      {
        productId: selectedProductId,
        templateKey,
        requestedChannels: ["EMAIL"],
        externalUserId,
        idempotencyKey,
        category,
        priority,
        payload,
        recipient: { email },
        expiresAt: null
      },
      {
        onSuccess: (notification) => {
          setSentNotification(notification);
          setIdempotencyKey(generateIdempotencyKey());
        }
      }
    );
  }

  return (
    <>
      <PageHeader title="Send Test Notification" description="Create an EMAIL notification request and queue it for local delivery." />

      <div className="grid gap-6 xl:grid-cols-[460px_1fr]">
        <Panel title="Test notification">
          <form className="grid gap-4" onSubmit={handleSubmit}>
            <TextField label="Product ID" value={selectedProductId} onChange={(event) => handleProductChange(event.target.value)} required />

            <SelectField label="Email template" value={templateKey} onChange={(event) => setTemplateKey(event.target.value)} required>
              <option value="">Select template</option>
              {emailTemplates.map((template) => (
                <option key={template.id} value={template.templateKey}>
                  {template.templateKey} v{template.version}
                </option>
              ))}
            </SelectField>

            <div className="grid gap-4 sm:grid-cols-2">
              <TextField label="External user id" value={externalUserId} onChange={(event) => setExternalUserId(event.target.value)} required />
              <TextField label="Recipient email" type="email" value={email} onChange={(event) => setEmail(event.target.value)} required />
            </div>

            <div className="grid gap-4 sm:grid-cols-2">
              <TextField label="Category" value={category} onChange={(event) => setCategory(event.target.value)} required />
              <SelectField label="Priority" value={priority} onChange={(event) => setPriority(event.target.value as NotificationPriority)}>
                {priorities.map((item) => (
                  <option key={item} value={item}>
                    {item}
                  </option>
                ))}
              </SelectField>
            </div>

            <TextField
              label="Idempotency key"
              value={idempotencyKey}
              onChange={(event) => setIdempotencyKey(event.target.value)}
              required
            />
            <TextAreaField label="Payload JSON" value={payloadText} onChange={(event) => setPayloadText(event.target.value)} required />

            {payloadError ? <p className="rounded-md bg-red-50 px-3 py-2 text-sm text-ruby">{payloadError}</p> : null}
            {sendNotification.isError ? <p className="rounded-md bg-red-50 px-3 py-2 text-sm text-ruby">{sendNotification.error.message}</p> : null}
            <Button
              type="submit"
              disabled={!selectedProductId || !templateKey || !email || !externalUserId || sendNotification.isPending}
            >
              {sendNotification.isPending ? "Sending" : "Send test notification"}
            </Button>
          </form>
        </Panel>

        <section className="grid content-start gap-6">
          {selectedProductId && templatesQuery.isLoading ? <LoadingBlock /> : null}
          {selectedProductId && !templatesQuery.isLoading && emailTemplates.length === 0 ? (
            <StateBlock title="No active EMAIL templates" message="Create an active EMAIL template before sending a test notification." />
          ) : null}

          {sentNotification ? (
            <Panel title="Created notification">
              <div className="grid gap-4">
                <div className="rounded-md border border-emerald-200 bg-emerald-50 px-4 py-3 text-sm text-emerald-900">
                  Notification request created with status {sentNotification.status}.
                </div>
                <div className="grid gap-2 text-sm">
                  <p>
                    <span className="font-semibold">Request id:</span> {sentNotification.id}
                  </p>
                  <p>
                    <span className="font-semibold">Template:</span> {sentNotification.templateKey}
                  </p>
                  <p>
                    <span className="font-semibold">Recipient:</span> {String(sentNotification.recipient.email ?? "")}
                  </p>
                </div>
                <div className="flex flex-wrap gap-3">
                  <Link className="inline-flex min-h-10 items-center rounded-md bg-fern px-4 py-2 text-sm font-semibold text-white shadow-sm" to={`/notifications/${sentNotification.id}`}>
                    Open detail
                  </Link>
                  <Link className="inline-flex min-h-10 items-center rounded-md border border-line bg-white px-4 py-2 text-sm font-semibold text-slate-700 shadow-sm" to="/deliveries">
                    View deliveries
                  </Link>
                </div>
              </div>
            </Panel>
          ) : (
            <Panel title="Request preview">
              <JsonPreview
                value={{
                  productId: selectedProductId,
                  templateKey,
                  requestedChannels: ["EMAIL"],
                  externalUserId,
                  idempotencyKey,
                  category,
                  priority,
                  recipient: { email }
                }}
              />
            </Panel>
          )}
        </section>
      </div>
    </>
  );
}

function generateIdempotencyKey() {
  if (typeof crypto !== "undefined" && "randomUUID" in crypto) {
    return `test-${crypto.randomUUID()}`;
  }
  return `test-${Date.now()}`;
}
