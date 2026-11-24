import type { ButtonHTMLAttributes } from "react";

import { buttonClass } from "./ui";
import type { ButtonVariant } from "./ui";

export interface ButtonProps extends ButtonHTMLAttributes<HTMLButtonElement> {
  variant?: ButtonVariant;
}

export function Button({
  variant = "primary",
  className = "",
  type = "button",
  ...rest
}: ButtonProps) {
  return <button type={type} className={buttonClass(variant, className)} {...rest} />;
}
