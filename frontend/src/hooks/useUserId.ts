import { useState } from "react";

import { readUserId } from "../api/identity";

/** The demo identity, read once per mount from `localStorage` (`eventflow.userId`). */
export function useUserId(): string {
  const [userId] = useState(readUserId);
  return userId;
}
