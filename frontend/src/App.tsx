import { AppHeader } from "./components/AppHeader";
import { AppRoutes } from "./routes/AppRoutes";

export function App() {
  return (
    <div className="flex min-h-screen flex-col">
      <AppHeader />
      <main className="mx-auto w-full max-w-5xl flex-1 px-4 py-8">
        <AppRoutes />
      </main>
    </div>
  );
}
