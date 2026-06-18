export type Channel = "EMAIL" | "SMS" | "PUSH" | "IN_APP";
export type ProductStatus = "ACTIVE" | "DISABLED";
export type TemplateStatus = "DRAFT" | "ACTIVE" | "ARCHIVED";
export type NotificationPriority = "LOW" | "NORMAL" | "HIGH";
export type NotificationRequestStatus =
  | "ACCEPTED"
  | "SENT"
  | "PARTIAL_FAILED"
  | "FAILED"
  | "SKIPPED";
export type DeliveryStatus =
  | "PENDING"
  | "PROCESSING"
  | "SENDING"
  | "SENT"
  | "DELIVERED"
  | "FAILED"
  | "RETRY_SCHEDULED"
  | "DLQ"
  | "DEAD_LETTERED"
  | "SKIPPED";

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
  batchId: string | null;
  templateKey: string;
  externalUserId: string;
  idempotencyKey: string;
  category: string;
  priority: NotificationPriority;
  requestedChannels: Channel[];
  status: NotificationRequestStatus;
  payload: Record<string, unknown>;
  recipient: Record<string, unknown>;
  expiresAt: string | null;
  createdAt: string | null;
}

export interface Delivery {
  id: string;
  notificationRequestId: string;
  templateId: string;
  channel: Channel;
  status: DeliveryStatus;
  provider: string | null;
  destination: string;
  attemptCount: number;
  maxAttempts: number;
  nextAttemptAt: string | null;
  lastErrorMessage: string | null;
  createdAt: string | null;
}

export interface PageResult<T> {
  items: T[];
  total: number;
  page: number;
  size: number;
}

export interface DashboardStats {
  totalNotificationsToday: number | null;
  sentCount: number | null;
  failedCount: number | null;
  pendingOutboxCount: number | null;
  retryCount: number | null;
  dlqCount: number | null;
  providerErrorRate: number | null;
  throughputPerMinute: number | null;
}

export interface ApiError {
  message: string;
  status?: number;
}
