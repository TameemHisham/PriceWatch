/**
 * An error carrying the HTTP status that produced it.
 *
 * The status matters because not every failure is a failure: the backend answers 404 when
 * cross-store discovery simply found no match, which is an ordinary outcome deserving an
 * empty state, not an error banner. Matching on the message text instead would couple the
 * UI to backend wording and break silently the day it is reworded.
 */
export class ApiError extends Error {
    readonly status: number;

    constructor(message: string, status: number) {
        super(message);
        this.name = "ApiError";
        this.status = status;
    }
}
