import { useMutation, useQueryClient } from "@tanstack/react-query";

import type { Reservation } from "../../api/booking";
import { cancelReservation, checkoutReservation } from "../../api/booking";
import { queryKeys } from "../../hooks/queryKeys";

/**
 * Checkout and cancel both answer the authoritative reservation, so the cache is rewritten with it
 * and the polling query continues from that state.
 */
export function useReservationActions(userId: string) {
  const queryClient = useQueryClient();

  const sync = (reservation: Reservation) => {
    queryClient.setQueryData(queryKeys.reservation(reservation.reservationId), reservation);
    void queryClient.invalidateQueries({
      queryKey: queryKeys.reservation(reservation.reservationId),
    });
  };

  const checkout = useMutation({
    mutationFn: (reservationId: string) => checkoutReservation(reservationId, userId),
    onSuccess: sync,
  });

  const cancel = useMutation({
    mutationFn: (reservationId: string) => cancelReservation(reservationId, userId),
    onSuccess: sync,
  });

  return { checkout, cancel };
}
