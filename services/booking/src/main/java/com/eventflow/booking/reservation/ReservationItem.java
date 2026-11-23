package com.eventflow.booking.reservation;

import java.math.BigDecimal;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(
        name = "reservation_item",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "uk_reservation_item_type",
                        columnNames = {"reservation_id", "ticket_type_id"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ReservationItem {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "reservation_id", nullable = false)
    private Reservation reservation;

    @Column(name = "ticket_type_id", nullable = false)
    private UUID ticketTypeId;

    @Column(name = "quantity", nullable = false)
    private int quantity;

    @Column(name = "unit_price", nullable = false, precision = 12, scale = 2)
    private BigDecimal unitPrice;

    static ReservationItem create(Reservation reservation, ReservationLine line) {
        ReservationItem item = new ReservationItem();
        item.id = UUID.randomUUID();
        item.reservation = reservation;
        item.ticketTypeId = line.ticketTypeId();
        item.quantity = line.quantity();
        item.unitPrice = line.unitPrice();
        return item;
    }

    public BigDecimal amount() {
        return unitPrice.multiply(BigDecimal.valueOf(quantity));
    }
}
