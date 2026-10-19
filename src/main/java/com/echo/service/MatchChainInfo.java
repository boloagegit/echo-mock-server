package com.echo.service;

/**
 * Match chain destination shared interface
 * <p>
 * Both HTTP and MatchChainEntry of JMS implement this interface.
 * Allow {@link MatchDescriptionBuilder} to be processed uniformly.
 */
public interface MatchChainInfo {

    String getRuleId();

    String getReason();

    String getEndpoint();

    String getDescription();

    String getCondition();

    /** Matching score, such as "2/3"; return null when there is no analysis */
    String getScore();

    /** PASS/FAIL Detailed description; return null when there is no analysis */
    String getDetail();

    /** Is it near-miss (some conditions passed but not all) */
    boolean isNearMiss();

    /** JSON of the tree comparison description (see {@link MatchExplanationJson}); return null when there is no analysis */
    default String getDetailTree() {
        return null;
    }
}
