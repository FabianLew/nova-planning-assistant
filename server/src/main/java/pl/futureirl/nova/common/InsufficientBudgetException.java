package pl.futureirl.nova.common;

/** Thrown inside a transaction to roll it back when a reservation does not fit the remaining budget. */
public class InsufficientBudgetException extends RuntimeException {
    public InsufficientBudgetException(String message) {
        super(message);
    }
}
