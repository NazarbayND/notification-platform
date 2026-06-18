import { useEffect, useState } from "react";
import { useDeliveries, useRetryDelivery } from "../api/deliveries";
import { Badge } from "../components/Badge";
import { Button } from "../components/Button";
import { PaginatedDataTable } from "../components/DataTable";
import { SelectField, TextField } from "../components/Field";
import { PageHeader } from "../components/PageHeader";
import { ErrorBlock, LoadingBlock, StateBlock } from "../components/StateBlock";
import { formatDateTime } from "../lib/format";
import type { Channel, DeliveryStatus } from "../types/api";

const deliveryStatuses: DeliveryStatus[] = [
  "PENDING",
  "PROCESSING",
  "SENDING",
  "SENT",
  "DELIVERED",
  "FAILED",
  "RETRY_SCHEDULED",
  "DLQ",
  "DEAD_LETTERED",
  "SKIPPED"
];
const channels: Channel[] = ["EMAIL", "SMS", "PUSH", "IN_APP"];

export function DeliveriesPage() {
  const [status, setStatus] = useState<DeliveryStatus | "">("");
  const [channel, setChannel] = useState<Channel | "">("");
  const [provider, setProvider] = useState("");
  const [page, setPage] = useState(1);
  const [pageSize, setPageSize] = useState(10);
  const deliveriesQuery = useDeliveries({ status, channel, provider }, page, pageSize);
  const deliveries = deliveriesQuery.data?.items ?? [];
  const totalDeliveries = deliveriesQuery.data?.total ?? 0;
  const retryDelivery = useRetryDelivery();

  useEffect(() => {
    setPage(1);
  }, [status, channel, provider]);

  return (
    <>
      <PageHeader title="Deliveries" description="Inspect delivery attempts and provider results." />

      <div className="mb-6 grid gap-3 rounded-md border border-line bg-white p-4 shadow-sm md:grid-cols-3">
        <SelectField label="Status" value={status} onChange={(event) => setStatus(event.target.value as DeliveryStatus | "")}>
          <option value="">All statuses</option>
          {deliveryStatuses.map((item) => (
            <option key={item} value={item}>
              {item}
            </option>
          ))}
        </SelectField>
        <SelectField label="Channel" value={channel} onChange={(event) => setChannel(event.target.value as Channel | "")}>
          <option value="">All channels</option>
          {channels.map((item) => (
            <option key={item} value={item}>
              {item}
            </option>
          ))}
        </SelectField>
        <TextField label="Provider" value={provider} onChange={(event) => setProvider(event.target.value)} placeholder="sendgrid" />
      </div>

      {deliveriesQuery.isLoading ? <LoadingBlock /> : null}
      {deliveriesQuery.isError ? <ErrorBlock message={deliveriesQuery.error.message} /> : null}
      {retryDelivery.isError ? (
        <div className="mb-4 rounded-md bg-red-50 px-4 py-3 text-sm text-ruby">{retryDelivery.error.message}</div>
      ) : null}
      {deliveriesQuery.data && totalDeliveries === 0 ? (
        <StateBlock
          title="No deliveries found"
          message="Try changing the filters or send a test notification."
        />
      ) : null}
      {deliveriesQuery.data && totalDeliveries > 0 ? (
        <PaginatedDataTable
          headers={["Channel", "Status", "Provider", "Attempts", "Next attempt", "Last error", "Actions"]}
          rows={deliveries}
          totalRows={totalDeliveries}
          page={page}
          pageSize={pageSize}
          onPageChange={setPage}
          onPageSizeChange={(nextPageSize) => {
            setPageSize(nextPageSize);
            setPage(1);
          }}
          renderRow={(delivery) => (
            <tr key={delivery.id}>
              <td className="px-4 py-3">{delivery.channel}</td>
              <td className="px-4 py-3"><Badge value={delivery.status} tone={delivery.status === "DLQ" || delivery.status === "DEAD_LETTERED" || delivery.status === "FAILED" ? "danger" : "neutral"} /></td>
              <td className="px-4 py-3">{delivery.provider ?? "Not set"}</td>
              <td className="px-4 py-3">{delivery.attemptCount}/{delivery.maxAttempts}</td>
              <td className="px-4 py-3 text-slate-600">{formatDateTime(delivery.nextAttemptAt)}</td>
              <td className="max-w-xs truncate px-4 py-3 text-slate-600">{delivery.lastErrorMessage ?? "None"}</td>
              <td className="px-4 py-3">
                <Button
                  type="button"
                  variant="secondary"
                  disabled={!isRetryable(delivery.status) || retryDelivery.isPending}
                  title={isRetryable(delivery.status) ? "Retry delivery" : "Only failed deliveries can be retried"}
                  onClick={() => retryDelivery.mutate(delivery.id)}
                >
                  {retryDelivery.isPending ? "Retrying" : "Retry"}
                </Button>
              </td>
            </tr>
          )}
        />
      ) : null}
    </>
  );
}

function isRetryable(status: DeliveryStatus) {
  return status === "FAILED" || status === "RETRY_SCHEDULED" || status === "DLQ" || status === "DEAD_LETTERED";
}
