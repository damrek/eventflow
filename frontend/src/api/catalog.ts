import { request } from "./client";
import type { PageResponse } from "./types";

export type EventStatus = "DRAFT" | "PUBLISHED";

export interface EventSummary {
  id: string;
  name: string;
  venueId: string;
  venueName: string;
  startsAt: string;
  status: EventStatus;
}

export interface CatalogTicketType {
  id: string;
  name: string;
  price: number;
  capacity: number;
}

export interface EventDetail {
  id: string;
  name: string;
  venueId: string;
  venueName: string;
  startsAt: string;
  status: EventStatus;
  totalCapacity: number;
  ticketTypes: CatalogTicketType[];
}

export function listEvents(page = 0, size = 20): Promise<PageResponse<EventSummary>> {
  return request<PageResponse<EventSummary>>(`/api/catalog/events?page=${page}&size=${size}`);
}

export function getEvent(eventId: string): Promise<EventDetail> {
  return request<EventDetail>(`/api/catalog/events/${encodeURIComponent(eventId)}`);
}
