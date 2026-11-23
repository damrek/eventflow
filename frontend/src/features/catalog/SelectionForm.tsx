import { zodResolver } from "@hookform/resolvers/zod";
import { useForm } from "react-hook-form";
import { useNavigate } from "react-router";

import { Button } from "../../components/Button";
import { formatPrice } from "../../components/format";
import { inputClass } from "../../components/ui";
import { MAX_QUANTITY, selectedItemsSearch } from "../reservation/items";
import type { SelectionValues, TicketOption } from "./selectionSchema";
import { buildSelectionSchema, quantityFieldName, selectionDefaults } from "./selectionSchema";

interface SelectionFormProps {
  eventId: string;
  options: TicketOption[];
}

export function SelectionForm({ eventId, options }: SelectionFormProps) {
  const navigate = useNavigate();
  const { register, handleSubmit, formState } = useForm<SelectionValues>({
    resolver: zodResolver(buildSelectionSchema(options)),
    defaultValues: selectionDefaults(options),
  });

  const errorFor = (option: TicketOption): string | undefined =>
    formState.errors[quantityFieldName(option.id)]?.message;

  return (
    <form
      noValidate
      className="space-y-4"
      onSubmit={handleSubmit((values) => {
        const items = options
          .map((option) => ({
            ticketTypeId: option.id,
            quantity: values[quantityFieldName(option.id)] ?? 0,
          }))
          .filter((item) => item.quantity > 0);
        if (items.length === 0) return;
        navigate(`/events/${eventId}/waiting-room?${selectedItemsSearch(items)}`);
      })}
    >
      <div className="space-y-3">
        {options.map((option) => {
          const field = quantityFieldName(option.id);
          const error = errorFor(option);
          const max = Math.min(MAX_QUANTITY, option.available);
          return (
            <div
              key={option.id}
              className="grid gap-3 rounded-md border border-slate-200 p-3 sm:grid-cols-[1fr_auto] sm:items-start"
            >
              <div>
                <label htmlFor={field} className="font-medium text-slate-900">
                  {option.name}
                </label>
                <p id={`${field}-help`} className="text-xs text-slate-600">
                  {formatPrice(option.price)} · {option.available} available of {option.capacity}
                </p>
              </div>
              <div>
                <input
                  id={field}
                  type="number"
                  inputMode="numeric"
                  min={0}
                  max={max}
                  step={1}
                  aria-describedby={error ? `${field}-help ${field}-error` : `${field}-help`}
                  aria-invalid={error ? true : undefined}
                  className={inputClass}
                  {...register(field, { valueAsNumber: true })}
                />
                {error ? (
                  <p id={`${field}-error`} className="mt-1 text-xs font-medium text-rose-700">
                    {error}
                  </p>
                ) : null}
              </div>
            </div>
          );
        })}
      </div>
      <Button type="submit">Continue to waiting room</Button>
    </form>
  );
}
