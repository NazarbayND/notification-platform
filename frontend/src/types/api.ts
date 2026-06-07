export type Channel = "EMAIL" | "SMS" | "PUSH" | "IN_APP";
export type ProductStatus = "ACTIVE" | "INACTIVE";
export type TemplateStatus = "DRAFT" | "ACTIVE" | "INACTIVE";
export type NotificationPriority = "LOW" | "NORMAL" | "HIGH" | "URGENT";
export type NotificationRequestStatus =
  | "ACCEPTED"
  | "DELIVERY_CREATED"
  | "COMPLETED"
  | "PARTIAL_FAILED"
  | "FAILED"
  | "SKIPPED";
export type DeliveryStatus =
  | "PENDING"
  | "PROCESSING"
  | "SENT"
  | "DELIVERED"
  | "FAILED"
  | "RETRY_SCHEDULED"
  | "DLQ"
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
  version: number;
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

export interface DashboardStats {
  totalNotifications: number | null;
  pendingDeliveries: number | null;
  failedDeliveries: number | null;
  deadLetteredDeliveries: number | null;
}

export interface ApiError {
  message: string;
  status?: number;
}
