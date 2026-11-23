import { Link, NavLink } from "react-router";

import { useUserId } from "../hooks/useUserId";

const navItemClass = ({ isActive }: { isActive: boolean }): string =>
  `rounded-md px-2 py-1 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-sky-700 ${
    isActive ? "bg-slate-100 font-semibold text-slate-900" : "text-slate-700 hover:bg-slate-100"
  }`;

export function AppHeader() {
  const userId = useUserId();

  return (
    <header className="border-b border-slate-200 bg-white">
      <div className="mx-auto flex w-full max-w-5xl flex-wrap items-center justify-between gap-3 px-4 py-3">
        <Link to="/" className="text-lg font-bold text-slate-900">
          EventFlow
        </Link>
        <nav aria-label="Main" className="flex items-center gap-2 text-sm">
          <NavLink to="/" end className={navItemClass}>
            Catalog
          </NavLink>
          <NavLink to="/reservations" className={navItemClass}>
            My reservations
          </NavLink>
        </nav>
        <p className="text-xs text-slate-600">
          Demo identity <span className="font-mono">{userId}</span> · not authentication
        </p>
      </div>
    </header>
  );
}
