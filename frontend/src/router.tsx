import { createBrowserRouter } from "react-router-dom";
import { AppLayout } from "./components/AppLayout";
import { DashboardPage } from "./pages/DashboardPage";
import { DeliveriesPage } from "./pages/DeliveriesPage";
import { NotificationDetailPage } from "./pages/NotificationDetailPage";
import { NotificationsPage } from "./pages/NotificationsPage";
import { ProductsPage } from "./pages/ProductsPage";
import { TestNotificationPage } from "./pages/TestNotificationPage";
import { TemplatesPage } from "./pages/TemplatesPage";

export const router = createBrowserRouter([
  {
    path: "/",
    element: <AppLayout />,
    children: [
      { index: true, element: <DashboardPage /> },
      { path: "products", element: <ProductsPage /> },
      { path: "templates", element: <TemplatesPage /> },
      { path: "notifications", element: <NotificationsPage /> },
      { path: "notifications/test", element: <TestNotificationPage /> },
      { path: "notifications/:id", element: <NotificationDetailPage /> },
      { path: "deliveries", element: <DeliveriesPage /> }
    ]
  }
]);
