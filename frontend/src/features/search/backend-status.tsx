import { getSystemStatus } from "@/shared/api/server";

export async function BackendStatus(): Promise<React.JSX.Element> {
  let status;
  try {
    status = await getSystemStatus();
  } catch {
    status = null;
  }

  if (status === null) {
    return (
      <span className="serviceStatus serviceStatus--down">
        <span aria-hidden="true" /> Start the Java backend to search
      </span>
    );
  }

  return (
    <span className="serviceStatus serviceStatus--up" title={status.timestamp}>
      <span aria-hidden="true" /> Backend connected
    </span>
  );
}
