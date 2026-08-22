import Link from "next/link";

export function Brand(): React.JSX.Element {
  return (
    <Link className="brand" href="/" aria-label="OpenScholar home">
      <svg
        aria-hidden="true"
        className="brandMark"
        viewBox="0 0 42 42"
        width="42"
        height="42"
      >
        <path d="M8 9.5h16.5c5.25 0 9.5 4.25 9.5 9.5S29.75 28.5 24.5 28.5H18l-7 6v-6H8z" />
        <path d="M14 15h14M14 20h11" />
      </svg>
      <span>
        <strong>OpenScholar</strong>
        <small>Research, with sources</small>
      </span>
    </Link>
  );
}
