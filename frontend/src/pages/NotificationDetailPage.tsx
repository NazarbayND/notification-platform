import type { ReactNode } from "react";
import { Link, useParams } from "react-router-dom";
import { useDeliveries } from "../api/deliveries";
import { useNotification } from "../api/notifications";
import { Badge } from "../components/Badge";
import { DataTable } from "../components/DataTable";
import { JsonPreview } from "../components/JsonPreview";
import { PageHeader } from "../components/PageHeader";
import { Panel } from "../components/Panel";
import { ErrorBlock, LoadingBlock, StateBlock } from "../components/StateBlock";
import { formatDateTime } from "../lib/format";

export function NotificationDetailPage() {
  const { id } = useParams();
  const notificationQuery = useNotification(id);
  const deliveriesQuery = useDeliveries({ notificationRequestId: id });

  if (notificationQuery.isLoading) {
    return <LoadingBlock />;
  }

  if (notificationQuery.isError) {
    return <ErrorBlock message={notificationQuery.error.message} />;
  }

  const notification = notificationQuery.data;

  if (!notification) {
    return <StateBlock title="Notification not found" />;
  }

  return (
    <>
      <PageHeader
        title="Notification detail"
        description={notification.id}
        actions={
          <Link className="inline-flex min-h-10 items-center rounded-md border border-line bg-white px-4 py-2 text-sm font-semibold text-slate-700 hover:bg-mist" to="/notifications">
            Back to notifications
          </Link>
        }
      />

      <div className="grid gap-6 xl:grid-cols-[1fr_420px]">
        <div className="grid gap-6">
          <Panel title="Request">
            <dl className="grid gap-4 text-sm sm:grid-cols-2">
              <Detail label="Template key" value={notification.templateKey} />
              <Detail label="External user" value={notification.externalUserId} />
              <Detail label="Category" value={notification.category} />
              <Detail label="Priority" value={notification.priority} />
              <Detail label="Status" value={<Badge value={notification.status} tone={notification.status === "FAILED" ? "danger" : "neutral"} />} />
              <Detail label="Created" value={formatDateTime(notification.createdAt)} />
              <Detail label="Expires" value={formatDateTime(notification.expiresAt)} />
              <Detail label="Requested channels" value={notification.requestedChannels.join(", ")} />
            </dl>
          </Panel>

          <Panel title="Related deliveries">
            {deliveriesQuery.isLoading ? <LoadingBlock /> : null}
            {deliveriesQuery.data?.length === 0 ? (
              <StateBlock
                title="Delivery detail endpoint is not available yet"
                message="The backend has delivery services internally, but no controller endpoint currently exposes deliveries for a notification."
              />
            ) : null}
            {deliveriesQuery.data && deliveriesQuery.data.length > 0 ? (
              <DataTable headers={["Channel", "Status", "Provider", "Attempts", "Last error"]}>
                {deliveriesQuery.data.map((delivery) => (
                  <tr key={delivery.id}>
                    <td className="px-4 py-3">{delivery.channel}</td>
                    <td className="px-4 py-3"><Badge value={delivery.status} /></td>
                    <td className="px-4 py-3">{delivery.provider ?? "Not set"}</td>
                    <td className="px-4 py-3">{delivery.attemptCount}/{delivery.maxAttempts}</td>
                    <td className="px-4 py-3 text-slate-600">{delivery.lastErrorMessage ?? "None"}</td>
                  </tr>
                ))}
              </DataTable>
            ) : null}
          </Panel>
        </div>

        <div className="grid gap-6">
          <Panel title="Recipient">
            <JsonPreview value={notification.recipient} />
          </Panel>
          <Panel title="Payload">
            <JsonPreview value={notification.payload} />
          </Panel>
        </div>
      </div>
    </>
  );
}

function Detail({ label, value }: { label: string; value: ReactNode }) {
  return (
    <div>
      <dt className="text-xs font-semibold uppercase tracking-wide text-slate-500">{label}</dt>
      <dd className="mt-1 font-medium text-ink">{value}</dd>
    </div>
  );
}
