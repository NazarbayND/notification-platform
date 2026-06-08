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
          label="Total notifications"
          value={stats?.totalNotifications}
          note="All notification requests."
        />
        <StatCard
          label="Pending deliveries"
          value={stats?.pendingDeliveries}
          note="Pending, sending, or retry scheduled."
        />
        <StatCard
          label="Failed deliveries"
          value={stats?.failedDeliveries}
          note="Terminal failed status."
        />
        <StatCard
          label="Dead-lettered deliveries"
          value={stats?.deadLetteredDeliveries}
          note="Max attempts exhausted."
        />
      </div>
    </>
  );
}
