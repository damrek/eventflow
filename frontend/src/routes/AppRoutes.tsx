import { Route, Routes } from "react-router";

import { EventDetailPage } from "../features/catalog/EventDetailPage";
import { EventListPage } from "../features/catalog/EventListPage";
import { MyReservationsPage } from "../features/reservations/MyReservationsPage";
import { ReservationStatusPage } from "../features/reservation/ReservationStatusPage";
import { ReservePage } from "../features/reservation/ReservePage";
import { WaitingRoomPage } from "../features/waiting-room/WaitingRoomPage";
import { NotFoundPage } from "./NotFoundPage";

export function AppRoutes() {
  return (
    <Routes>
      <Route path="/" element={<EventListPage />} />
      <Route path="/events/:eventId" element={<EventDetailPage />} />
      <Route path="/events/:eventId/waiting-room" element={<WaitingRoomPage />} />
      <Route path="/events/:eventId/reserve" element={<ReservePage />} />
      <Route path="/reservations" element={<MyReservationsPage />} />
      <Route path="/reservations/:reservationId" element={<ReservationStatusPage />} />
      <Route path="*" element={<NotFoundPage />} />
    </Routes>
  );
}
