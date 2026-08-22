import { getSystemStatus } from "@/shared/api/server";

export async function BackendStatus(): Promise<React.JSX.Element | null> {
  let status;
  try {
    status = await getSystemStatus();
  } catch {
    status = null;
  }

  if (status === null) {
    return (
      <span className="serviceStatus serviceStatus--down">
        <span aria-hidden="true" /> Search is temporarily unavailable
      </span>
    );
  }

  return null;
}
