type BadgeProps = {
  children: React.ReactNode;
  tone?: "neutral" | "positive" | "warning" | "info";
};

export function Badge({
  children,
  tone = "neutral",
}: BadgeProps): React.JSX.Element {
  return <span className={`badge badge--${tone}`}>{children}</span>;
}
