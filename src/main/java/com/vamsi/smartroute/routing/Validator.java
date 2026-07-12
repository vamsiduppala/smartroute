package com.vamsi.smartroute.routing;

/**
 * Task-specific acceptance check. Returns true if the model's answer is good enough
 * to stop escalating. Keep these cheap and deterministic (substring/regex/format checks)
 * so validation cost stays near zero versus the model call it guards.
 */
@FunctionalInterface
public interface Validator {
    boolean accepts(String answer);

    /** Default: reject empty answers and obvious refusals; otherwise accept. */
    static Validator nonEmpty() {
        return answer -> answer != null
                && !answer.isBlank()
                && !answer.toLowerCase().contains("i cannot help");
    }
}
