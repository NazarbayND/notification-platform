import { FormEvent, useEffect, useState } from "react";
import { Link, useNavigate } from "react-router-dom";
import { useNotifications } from "../api/notifications";
import { Badge } from "../components/Badge";
import { Button } from "../components/Button";
import { PaginatedDataTable } from "../components/DataTable";
import { SelectField, TextField } from "../components/Field";
import { PageHeader } from "../components/PageHeader";
import { Panel } from "../components/Panel";
import { ErrorBlock, LoadingBlock, StateBlock } from "../components/StateBlock";
import { formatDateTime } from "../lib/format";
import type { Channel, NotificationRequestStatus } from "../types/api";

const notificationStatuses: NotificationRequestStatus[] = [
  "ACCEPTED",
  "PROCESSING",
  "SCHEDULED",
  "DELIVERED",
  "PARTIALLY_DELIVERED",
  "FAILED",
  "REJECTED"
];
const channels: Channel[] = ["EMAIL", "SMS", "PUSH", "IN_APP", "WEBHOOK"];

export function NotificationsPage() {
  const navigate = useNavigate();
  const [lookupId, setLookupId] = useState("");
  const [productId, setProductId] = useState("");
  const [status, setStatus] = useState<NotificationRequestStatus | "">("");
  const [channel, setChannel] = useState<Channel | "">("");
  const [page, setPage] = useState(1);
  const [pageSize, setPageSize] = useState(10);
  const notificationsQuery = useNotifications({ productId, status, channel }, page, pageSize);
  const notifications = notificationsQuery.data?.items ?? [];
  const totalNotifications = notificationsQuery.data?.total ?? 0;

  useEffect(() => {
    setPage(1);
  }, [productId, status, channel]);

  function handleLookup(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (lookupId.trim()) {
      navigate(`/notifications/${lookupId.trim()}`);
    }
  }

  return (
    <>
      <PageHeader
        title="Notifications"
        description="Search notification requests and open details for a known notification id."
      />

      <div className="mb-6 grid gap-6 xl:grid-cols-[360px_1fr]">
        <Panel title="Open notification">
          <form className="grid gap-4" onSubmit={handleLookup}>
            <TextField
              label="Notification id"
              value={lookupId}
              onChange={(event) => setLookupId(event.target.value)}
              placeholder="UUID"
            />
            <Button type="submit" disabled={!lookupId.trim()}>
              Open detail
            </Button>
          </form>
        </Panel>

        <Panel title="Filters">
          <div className="grid gap-3 md:grid-cols-3">
            <TextField label="Product ID" value={productId} onChange={(event) => setProductId(event.target.value)} />
            <SelectField label="Status" value={status} onChange={(event) => setStatus(event.target.value as NotificationRequestStatus | "")}>
              <option value="">All statuses</option>
              {notificationStatuses.map((item) => (
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
        </Panel>
      </div>

      {notificationsQuery.isLoading ? <LoadingBlock /> : null}
      {notificationsQuery.isError ? <ErrorBlock message={notificationsQuery.error.message} /> : null}
      {notificationsQuery.data && totalNotifications === 0 ? (
        <StateBlock
          title="No notifications found"
          message="Adjust the filters or send a test notification."
        />
      ) : null}
      {notificationsQuery.data && totalNotifications > 0 ? (
        <PaginatedDataTable
          headers={["Template", "User", "Channel", "Status", "Requested", "Actions"]}
          rows={notifications}
          totalRows={totalNotifications}
          page={page}
          pageSize={pageSize}
          onPageChange={setPage}
          onPageSizeChange={(nextPageSize) => {
            setPageSize(nextPageSize);
            setPage(1);
          }}
          renderRow={(notification) => (
            <tr key={notification.id}>
              <td className="px-4 py-3 font-medium">{notification.templateKey}</td>
              <td className="px-4 py-3">{notification.userId}</td>
              <td className="px-4 py-3">{notification.channel}</td>
              <td className="px-4 py-3">
                <Badge value={notification.status} tone={notification.status === "FAILED" ? "danger" : "neutral"} />
              </td>
              <td className="px-4 py-3 text-slate-600">{formatDateTime(notification.requestedAt)}</td>
              <td className="px-4 py-3">
                <Link className="text-sm font-semibold text-fern hover:underline" to={`/notifications/${notification.id}`}>
                  Open
                </Link>
              </td>
            </tr>
          )}
        />
      ) : null}
    </>
  );
}
