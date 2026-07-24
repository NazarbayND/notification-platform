import { useEffect, useState } from "react";
import { useDeliveries } from "../api/deliveries";
import { Badge } from "../components/Badge";
import { PaginatedDataTable } from "../components/DataTable";
import { SelectField } from "../components/Field";
import { PageHeader } from "../components/PageHeader";
import { ErrorBlock, LoadingBlock, StateBlock } from "../components/StateBlock";
import { formatDateTime } from "../lib/format";
import type { Channel, DeliveryStatus } from "../types/api";

const deliveryStatuses: DeliveryStatus[] = ["DELIVERED", "FAILED"];
const channels: Channel[] = ["EMAIL", "SMS", "PUSH", "IN_APP", "WEBHOOK"];

export function DeliveriesPage() {
  const [status, setStatus] = useState<DeliveryStatus | "">("");
  const [channel, setChannel] = useState<Channel | "">("");
  const [page, setPage] = useState(1);
  const [pageSize, setPageSize] = useState(10);
  const deliveriesQuery = useDeliveries({ status, channel }, page, pageSize);
  const deliveries = deliveriesQuery.data?.items ?? [];
  const totalDeliveries = deliveriesQuery.data?.total ?? 0;

  useEffect(() => {
    setPage(1);
  }, [status, channel]);

  return (
    <>
      <PageHeader title="Deliveries" description="Inspect delivery attempts and provider results." />

      <div className="mb-6 grid gap-3 rounded-md border border-line bg-white p-4 shadow-sm md:grid-cols-2">
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
      </div>

      {deliveriesQuery.isLoading ? <LoadingBlock /> : null}
      {deliveriesQuery.isError ? <ErrorBlock message={deliveriesQuery.error.message} /> : null}
      {deliveriesQuery.data && totalDeliveries === 0 ? (
        <StateBlock
          title="No deliveries found"
          message="Try changing the filters or send a test notification."
        />
      ) : null}
      {deliveriesQuery.data && totalDeliveries > 0 ? (
        <PaginatedDataTable
          headers={["Channel", "Status", "Provider", "Attempt", "Last error", "Updated"]}
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
              <td className="px-4 py-3"><Badge value={delivery.status} tone={delivery.status === "FAILED" ? "danger" : "neutral"} /></td>
              <td className="px-4 py-3">{delivery.provider ?? "Not set"}</td>
              <td className="px-4 py-3">{delivery.attemptCount}</td>
              <td className="max-w-xs truncate px-4 py-3 text-slate-600">{delivery.errorMessage ?? "None"}</td>
              <td className="px-4 py-3 text-slate-600">{formatDateTime(delivery.updatedAt)}</td>
            </tr>
          )}
        />
      ) : null}
    </>
  );
}
