import { isApiError, request } from "./client";

export interface TicketTypeAvailability {
  ticketTypeId: string;
  price: number;
  capacity: number;
  available: number;
  held: number;
  sold: number;
}

export interface Availability {
  eventId: string;
  ticketTypes: TicketTypeAvailability[];
}

export type WaitingRoomStatus = "NOT_JOINED" | "WAITING" | "ADMITTED";

export interface WaitingRoom {
  eventId: string;
  userId: string;
  status: WaitingRoomStatus;
  position: number | null;
  waitingCount: number;
  token: string | null;
  expiresAt: string | null;
}

export type ReservationStatus =
  "HELD" | "PENDING_PAYMENT" | "CONFIRMED" | "PAYMENT_FAILED" | "EXPIRED" | "CANCELLED";

export interface ReservationItem {
  ticketTypeId: string;
  quantity: number;
  unitPrice: number;
}

export interface Reservation {
  reservationId: string;
  eventId: string;
  userId: string;
  status: ReservationStatus;
  expiresAt: string;
  createdAt: string;
  items: ReservationItem[];
  totalAmount: number;
}

export interface ReservationItemInput {
  ticketTypeId: string;
  quantity: number;
}

export interface CreateReservationInput {
  eventId: string;
  userId: string;
  items: ReservationItemInput[];
  idempotencyKey: string;
  admissionToken?: string | null;
}

export function getAvailability(eventId: string): Promise<Availability> {
  return request<Availability>(`/api/booking/events/${encodeURIComponent(eventId)}/availability`);
}

export function joinWaitingRoom(eventId: string, userId: string): Promise<WaitingRoom> {
  return request<WaitingRoom>(`/api/booking/waiting-room/${encodeURIComponent(eventId)}/join`, {
    method: "POST",
    userId,
  });
}

export function getWaitingRoomStatus(eventId: string, userId: string): Promise<WaitingRoom> {
  return request<WaitingRoom>(`/api/booking/waiting-room/${encodeURIComponent(eventId)}/status`, {
    userId,
  });
}

export function createReservation(input: CreateReservationInput): Promise<Reservation> {
  return request<Reservation>("/api/booking/reservations", {
    method: "POST",
    userId: input.userId,
    idempotencyKey: input.idempotencyKey,
    admissionToken: input.admissionToken ?? null,
    body: {
      eventId: input.eventId,
      items: input.items.map((item) => ({
        ticketTypeId: item.ticketTypeId,
        quantity: item.quantity,
      })),
    },
  });
}

export function getReservation(reservationId: string, userId: string): Promise<Reservation> {
  return request<Reservation>(`/api/booking/reservations/${encodeURIComponent(reservationId)}`, {
    userId,
  });
}

export function checkoutReservation(reservationId: string, userId: string): Promise<Reservation> {
  return request<Reservation>(
    `/api/booking/reservations/${encodeURIComponent(reservationId)}/checkout`,
    { method: "POST", userId },
  );
}

export function cancelReservation(reservationId: string, userId: string): Promise<Reservation> {
  return request<Reservation>(
    `/api/booking/reservations/${encodeURIComponent(reservationId)}/cancel`,
    { method: "POST", userId },
  );
}

/** Redis is a throttle: while it is down the backend answers 503 and the gate fails open. */
export function isWaitingRoomUnavailable(error: unknown): boolean {
  return isApiError(error) && error.status === 503;
}

export function isTerminalReservationStatus(status: ReservationStatus): boolean {
  return (
    status === "CONFIRMED" ||
    status === "PAYMENT_FAILED" ||
    status === "EXPIRED" ||
    status === "CANCELLED"
  );
}
