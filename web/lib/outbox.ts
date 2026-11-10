/**
 * Keeps transcript and timeline writes in order and retries them after a network drop, so a short outage
 * on set does not lose the record. Messages are idempotent on the server (provider item id).
 */
export class Outbox {
  private queue: Array<() => Promise<unknown>> = [];
  private running = false;
  private retryTimer: ReturnType<typeof setTimeout> | null = null;

  constructor(private readonly onError: (e: unknown) => void = () => {}) {}

  push(task: () => Promise<unknown>) {
    this.queue.push(task);
    void this.drain();
  }

  get pending() {
    return this.queue.length;
  }

  async drain(): Promise<void> {
    if (this.running) return;
    this.running = true;
    try {
      while (this.queue.length) {
        try {
          await this.queue[0]();
          this.queue.shift();
        } catch (e) {
          if (e instanceof TypeError) {
            // network failure: keep the task and retry later
            this.scheduleRetry();
            return;
          }
          this.queue.shift(); // server rejected it; retrying will not help
          this.onError(e);
        }
      }
    } finally {
      this.running = false;
    }
  }

  private scheduleRetry() {
    if (this.retryTimer) return;
    this.retryTimer = setTimeout(() => {
      this.retryTimer = null;
      void this.drain();
    }, 3000);
  }
}
