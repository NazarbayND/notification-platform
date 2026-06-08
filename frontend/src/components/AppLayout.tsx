import { NavLink, Outlet } from "react-router-dom";

const navItems = [
  { to: "/", label: "Dashboard", end: true },
  { to: "/products", label: "Products", end: true },
  { to: "/templates", label: "Templates", end: true },
  { to: "/notifications", label: "Notifications", end: true },
  { to: "/notifications/test", label: "Send Test", end: true },
  { to: "/deliveries", label: "Deliveries", end: true }
];

export function AppLayout() {
  return (
    <div className="min-h-screen">
      <aside className="fixed inset-y-0 left-0 hidden w-64 border-r border-line bg-white md:block">
        <div className="border-b border-line px-6 py-5">
          <p className="text-xs font-semibold uppercase tracking-wide text-fern">Admin</p>
          <h1 className="mt-1 text-xl font-semibold">Notification Platform</h1>
        </div>
        <nav className="p-3">
          {navItems.map((item) => (
            <NavLink
              key={item.to}
              to={item.to}
              end={item.end}
              className={({ isActive }) =>
                [
                  "mb-1 block rounded-md px-3 py-2 text-sm font-medium transition",
                  isActive ? "bg-fern text-white" : "text-slate-700 hover:bg-mist"
                ].join(" ")
              }
            >
              {item.label}
            </NavLink>
          ))}
        </nav>
      </aside>

      <div className="md:pl-64">
        <header className="sticky top-0 z-10 border-b border-line bg-white/90 px-4 py-3 backdrop-blur md:hidden">
          <h1 className="text-base font-semibold">Notification Platform</h1>
          <nav className="mt-3 flex gap-2 overflow-x-auto">
            {navItems.map((item) => (
              <NavLink
                key={item.to}
                to={item.to}
                end={item.end}
                className={({ isActive }) =>
                  [
                    "whitespace-nowrap rounded-md px-3 py-2 text-sm font-medium",
                    isActive ? "bg-fern text-white" : "bg-mist text-slate-700"
                  ].join(" ")
                }
              >
                {item.label}
              </NavLink>
            ))}
          </nav>
        </header>

        <main className="mx-auto max-w-7xl px-4 py-6 sm:px-6 lg:px-8">
          <Outlet />
        </main>
      </div>
    </div>
  );
}
