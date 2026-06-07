import { FormEvent, useState } from "react";
import { Link, useNavigate } from "react-router-dom";
import { useNotifications } from "../api/notifications";
import { useProducts } from "../api/products";
import { Badge } from "../components/Badge";
import { Button } from "../components/Button";
import { DataTable } from "../components/DataTable";
import { SelectField, TextField } from "../components/Field";
import { PageHeader } from "../components/PageHeader";
import { Panel } from "../components/Panel";
import { ErrorBlock, LoadingBlock, StateBlock } from "../components/StateBlock";
import { formatDateTime } from "../lib/format";
import type { NotificationPriority, NotificationRequestStatus } from "../types/api";

const notificationStatuses: NotificationRequestStatus[] = [
  "ACCEPTED",
  "DELIVERY_CREATED",
  "COMPLETED",
  "PARTIAL_FAILED",
  "FAILED",
  "SKIPPED"
];
const priorities: NotificationPriority[] = ["LOW", "NORMAL", "HIGH", "URGENT"];

export function NotificationsPage() {
  const navigate = useNavigate();
  const productsQuery = useProducts();
  const [lookupId, setLookupId] = useState("");
  const [productId, setProductId] = useState("");
  const [status, setStatus] = useState<NotificationRequestStatus | "">("");
  const [priority, setPriority] = useState<NotificationPriority | "">("");
  const [dateFrom, setDateFrom] = useState("");
  const [dateTo, setDateTo] = useState("");
  const notificationsQuery = useNotifications({ productId, status, priority, dateFrom, dateTo });

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
          <div className="grid gap-3 md:grid-cols-5">
            <SelectField label="Product" value={productId} onChange={(event) => setProductId(event.target.value)}>
              <option value="">All products</option>
              {productsQuery.data?.map((product) => (
                <option key={product.id} value={product.id}>
                  {product.name}
                </option>
              ))}
            </SelectField>
            <SelectField label="Status" value={status} onChange={(event) => setStatus(event.target.value as NotificationRequestStatus | "")}>
              <option value="">All statuses</option>
              {notificationStatuses.map((item) => (
                <option key={item} value={item}>
                  {item}
                </option>
              ))}
            </SelectField>
            <SelectField label="Priority" value={priority} onChange={(event) => setPriority(event.target.value as NotificationPriority | "")}>
              <option value="">All priorities</option>
              {priorities.map((item) => (
                <option key={item} value={item}>
                  {item}
                </option>
              ))}
            </SelectField>
            <TextField label="From" type="date" value={dateFrom} onChange={(event) => setDateFrom(event.target.value)} />
            <TextField label="To" type="date" value={dateTo} onChange={(event) => setDateTo(event.target.value)} />
          </div>
        </Panel>
      </div>

      {notificationsQuery.isLoading ? <LoadingBlock /> : null}
      {notificationsQuery.isError ? <ErrorBlock message={notificationsQuery.error.message} /> : null}
      {notificationsQuery.data?.length === 0 ? (
        <StateBlock
          title="Notification list endpoint is not available yet"
          message="The current backend supports GET /api/v1/notifications/{id}, but not a filtered notification list. Use the detail lookup when you have an id."
        />
      ) : null}
      {notificationsQuery.data && notificationsQuery.data.length > 0 ? (
        <DataTable headers={["Template", "User", "Status", "Priority", "Created", "Actions"]}>
          {notificationsQuery.data.map((notification) => (
            <tr key={notification.id}>
              <td className="px-4 py-3 font-medium">{notification.templateKey}</td>
              <td className="px-4 py-3">{notification.externalUserId}</td>
              <td className="px-4 py-3">
                <Badge value={notification.status} tone={notification.status === "FAILED" ? "danger" : "neutral"} />
              </td>
              <td className="px-4 py-3">{notification.priority}</td>
              <td className="px-4 py-3 text-slate-600">{formatDateTime(notification.createdAt)}</td>
              <td className="px-4 py-3">
                <Link className="text-sm font-semibold text-fern hover:underline" to={`/notifications/${notification.id}`}>
                  Open
                </Link>
              </td>
            </tr>
          ))}
        </DataTable>
      ) : null}
    </>
  );
}
