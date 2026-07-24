export type Channel = "EMAIL" | "SMS" | "PUSH" | "IN_APP" | "WEBHOOK";
export type ProductStatus = "ACTIVE" | "DISABLED";
export type TemplateStatus = "DRAFT" | "ACTIVE" | "ARCHIVED";
export type NotificationRequestStatus =
  | "ACCEPTED"
  | "PROCESSING"
  | "SCHEDULED"
  | "DELIVERED"
  | "PARTIALLY_DELIVERED"
  | "FAILED"
  | "REJECTED";
export type DeliveryStatus = "DELIVERED" | "FAILED";

export interface Product {
  id: string;
  name: string;
  status: ProductStatus;
  createdAt: string | null;
  updatedAt: string | null;
}

export interface Template {
  id: string;
  productId: string;
  templateKey: string;
  channel: Channel;
  subject: string | null;
  content: string;
  status: TemplateStatus;
  createdAt: string | null;
  updatedAt: string | null;
}

export interface NotificationRequest {
  id: string;
  productId: string;
  templateKey: string;
  userId: string;
  channel: Channel;
  status: NotificationRequestStatus;
  reasonCode: string | null;
  reasonMessage: string | null;
  requestedAt: string | null;
  updatedAt: string | null;
}

export interface Delivery {
  id: string;
  notificationRequestId: string;
  channel: Channel;
  status: DeliveryStatus;
  provider: string | null;
  destination: string | null;
  attemptCount: number;
  providerMessageId: string | null;
  errorCode: string | null;
  errorMessage: string | null;
  updatedAt: string | null;
}

export interface PageResult<T> {
  items: T[];
  total: number;
  page: number;
  size: number;
}

export interface DashboardStats {
  totalNotificationsToday: number | null;
  deliveredCount: number | null;
  failedCount: number | null;
  retryAttemptCount: number | null;
  providerErrorRate: number | null;
}

export interface ApiError {
  message: string;
  status?: number;
}
