import { useDashboardStats } from "../api/dashboard";
import { ErrorBlock, LoadingBlock } from "../components/StateBlock";
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
          label="Notifications today"
          value={stats?.totalNotificationsToday}
          note="Requests created since UTC midnight."
        />
        <StatCard
          label="Delivered"
          value={stats?.deliveredCount}
          note="Fully or partially delivered notifications."
        />
        <StatCard
          label="Failed"
          value={stats?.failedCount}
          note="Notifications whose deliveries failed."
        />
        <StatCard
          label="Retry attempts"
          value={stats?.retryAttemptCount}
          note="Delivery attempts after the first try."
        />
        <StatCard
          label="Error rate"
          value={formatPercent(stats?.providerErrorRate)}
          note="Failed share of completed notifications."
        />
      </div>
    </>
  );
}

function formatPercent(value: number | null | undefined) {
  if (value == null) {
    return "--";
  }
  return `${(value * 100).toFixed(1)}%`;
}
