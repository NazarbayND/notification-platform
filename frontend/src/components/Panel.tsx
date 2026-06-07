import type { ReactNode } from "react";

interface PanelProps {
  title?: string;
  children: ReactNode;
}

export function Panel({ title, children }: PanelProps) {
  return (
    <section className="rounded-md border border-line bg-white p-5 shadow-sm">
      {title ? <h3 className="mb-4 text-base font-semibold">{title}</h3> : null}
      {children}
    </section>
  );
}
