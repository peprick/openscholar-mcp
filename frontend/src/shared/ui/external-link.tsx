type ExternalLinkProps = React.AnchorHTMLAttributes<HTMLAnchorElement> & {
  href: string;
};

export function ExternalLink({
  children,
  className,
  ...props
}: ExternalLinkProps): React.JSX.Element {
  return (
    <a
      {...props}
      className={className}
      rel="noopener noreferrer"
      target="_blank"
    >
      {children}
      <span className="srOnly"> (opens in a new tab)</span>
      <svg
        aria-hidden="true"
        className="externalIcon"
        viewBox="0 0 16 16"
        width="14"
        height="14"
      >
        <path d="M6 3H3.75A.75.75 0 0 0 3 3.75v8.5c0 .414.336.75.75.75h8.5a.75.75 0 0 0 .75-.75V10M9 3h4v4M13 3 7.5 8.5" />
      </svg>
    </a>
  );
}
