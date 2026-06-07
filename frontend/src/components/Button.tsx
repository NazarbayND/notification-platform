import type { ButtonHTMLAttributes, ReactNode } from "react";

interface ButtonProps extends ButtonHTMLAttributes<HTMLButtonElement> {
  variant?: "primary" | "secondary" | "danger";
  children: ReactNode;
}

export function Button({ variant = "primary", className = "", children, ...props }: ButtonProps) {
  const variants = {
    primary: "bg-fern text-white hover:bg-fern/90 disabled:bg-slate-300",
    secondary: "border border-line bg-white text-slate-700 hover:bg-mist disabled:text-slate-400",
    danger: "bg-ruby text-white hover:bg-ruby/90 disabled:bg-slate-300"
  };

  return (
    <button
      className={[
        "inline-flex min-h-10 items-center justify-center rounded-md px-4 py-2 text-sm font-semibold shadow-sm transition disabled:cursor-not-allowed",
        variants[variant],
        className
      ].join(" ")}
      {...props}
    >
      {children}
    </button>
  );
}
