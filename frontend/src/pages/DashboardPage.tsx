import { useDashboardStats } from "../api/dashboard";
import { ErrorBlock, LoadingBlock, StateBlock } from "../components/StateBlock";
import { PageHeader } from "../components/PageHeader";
import { StatCard } from "../components/StatCard";

export function DashboardPage() {
  const statsQuery = useDashboardStats();

  if (statsQuery.isLoading) {
    return <LoadingBlock />;
  }

  if (statsQuery.isError) {
    return <ErrorBlock message="Dashboard statistics could not be loaded." />;
  }

  const stats = statsQuery.data;

  return (
    <>
      <PageHeader
        title="Dashboard"
        description="Operational view for notification volume and delivery health."
      />

      <div className="grid gap-4 sm:grid-cols-2 xl:grid-cols-4">
        <StatCard
          label="Total notifications"
          value={stats?.totalNotifications}
          note="Waiting for backend summary endpoint."
        />
        <StatCard
          label="Pending deliveries"
          value={stats?.pendingDeliveries}
          note="Waiting for backend delivery list endpoint."
        />
        <StatCard
          label="Failed deliveries"
          value={stats?.failedDeliveries}
          note="Waiting for backend delivery list endpoint."
        />
        <StatCard
          label="Dead-lettered deliveries"
          value={stats?.deadLetteredDeliveries}
          note="Waiting for backend delivery list endpoint."
        />
      </div>

      <div className="mt-6">
        <StateBlock
          title="Live dashboard metrics are not available yet"
          message="The backend currently exposes management endpoints for products/templates and single-notification lookup, but not aggregate notification or delivery counters."
        />
      </div>
    </>
  );
}
