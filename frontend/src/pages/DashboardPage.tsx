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
          label="Sent"
          value={stats?.sentCount}
          note="Published outbox events or sent notifications."
        />
        <StatCard
          label="Pending outbox"
          value={stats?.pendingOutboxCount}
          note="Waiting to be published."
        />
        <StatCard
          label="Failed outbox"
          value={stats?.failedCount}
          note="Failed or dead-lettered events."
        />
        <StatCard
          label="Retry scheduled"
          value={stats?.retryCount}
          note="Failed events that can still retry."
        />
        <StatCard
          label="Dead-lettered"
          value={stats?.dlqCount}
          note="Max attempts exhausted."
        />
        <StatCard
          label="Error rate"
          value={formatPercent(stats?.providerErrorRate)}
          note="Failed and dead-lettered share of processed outbox."
        />
        <StatCard
          label="Throughput / min"
          value={formatDecimal(stats?.throughputPerMinute)}
          note="Notifications created today."
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

function formatDecimal(value: number | null | undefined) {
  if (value == null) {
    return "--";
  }
  return value.toFixed(2);
}
