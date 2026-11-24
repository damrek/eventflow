import { request } from "./client";
import type { PageResponse } from "./types";

export type NotificationType =
  "BOOKING_CONFIRMED" | "BOOKING_CANCELLED" | "BOOKING_EXPIRED" | "BOOKING_PAYMENT_FAILED";

export type NotificationState = "UNREAD" | "READ";

export interface NotificationMessage {
  id: string;
  userId: string;
  type: NotificationType;
  content: string;
  status: NotificationState;
  createdAt: string;
}

export function listNotifications(
  userId: string,
  page = 0,
  size = 20,
): Promise<PageResponse<NotificationMessage>> {
  const query = new URLSearchParams({ userId, page: String(page), size: String(size) });
  return request<PageResponse<NotificationMessage>>(`/api/notification/notifications?${query}`);
}
