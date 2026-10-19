package com.echo.core.match;

/**
 * Compiled immutable match trees can be shared among multiple threads. Created by {@link MatcherCompiler}.
 */
public interface CompiledMatcher {

    /** Quick judgment, can end early; hot path for rule selection. */
    boolean matches(EvalContext context);

    /** Evaluate all nodes and return instructions; used for matching chain analysis and rule testing. */
    MatchExplanation explain(EvalContext context);

    /** Normalized condition JSON, can be used to stage key as a result within the same request. */
    String cacheKey();

    /**
     * This condition requires body to determine how body should be prepared (for example, whether XML can only stream scan and not create DOM).
     * The default is considered to require complete parsing, and self-implemented conditions will not result in incorrect results.
     */
    default BodyRequirements bodyRequirements() {
        return BodyRequirements.FULL;
    }

    /** Without any conditions (empty {@code all}), rule selection is treated as fallback. */
    default boolean isUnconditional() {
        return false;
    }
}
