/** Wait helper shared by run-polling loops and deferred UI work. */
export function sleep(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms))
}
