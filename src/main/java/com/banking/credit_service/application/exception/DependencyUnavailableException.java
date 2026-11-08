package com.banking.credit_service.application.exception;

/**
 * A service this one calls failed, timed out or has its circuit open. Different from an
 * unknown or inactive customer, which is a successful answer.
 */
public class DependencyUnavailableException extends RuntimeException {

    private final String dependency;

    public DependencyUnavailableException(String dependency, Throwable cause) {
        super(dependency + " is unavailable.", cause);
        this.dependency = dependency;
    }

    public String dependency() {
        return dependency;
    }
}
