import type { ReactNode } from "react";

interface StateBlockProps {
  title: string;
  message?: string;
  action?: ReactNode;
}

export function StateBlock({ title, message, action }: StateBlockProps) {
  return (
    <div className="rounded-md border border-dashed border-line bg-white px-6 py-10 text-center">
      <h3 className="text-base font-semibold">{title}</h3>
      {message ? <p className="mx-auto mt-2 max-w-xl text-sm text-slate-600">{message}</p> : null}
      {action ? <div className="mt-4">{action}</div> : null}
    </div>
  );
}

export function LoadingBlock() {
  return <StateBlock title="Loading" message="Fetching the latest data from the platform." />;
}

export function ErrorBlock({ message }: { message: string }) {
  return <StateBlock title="Could not load data" message={message} />;
}
